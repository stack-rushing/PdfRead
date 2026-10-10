package com.novelreader.pdf.data.toc

/**
 * 页眉标题识别（纯逻辑，无 Android 依赖，可直接单元测试）。
 *
 * 规则是用剑桥《真爱无界》的真实扫描件 OCR 结果调出来的，三个关键点：
 *
 * 1. **锚定行首匹配**：附文短语必须出现在行首。最初用"整行包含"，
 *    结果正文行里出现"练习答案"四个字就被判成了章节（真书第 2 页的封底介绍）。
 * 2. **逐词编辑距离 ≤ 1**：扫描件的 OCR 会把 `After` 读成 `Afier`、`Translation` 读成 `TransIation`、
 *    `People` 读成 `PeopIe`（t→i、l→I 是这套字体的高频混淆）。精确匹配会全军覆没，距离 1 刚好覆盖。
 *    但**长度 ≤ 3 的词要求精确匹配**，否则 `the`/`for` 这类词会到处乱配。
 * 3. **章节号不采信 OCR 数字**：实测 OCR 把 `Chapter 2` 读成 `Chapter 乥`、把 `Chapter 3` 读成 `Chapter 5`，
 *    但**页码全部正确、顺序稳定**。所以编号交给 [OcrChapterBuilder] 的序号，而不是识别出来的数字。
 */
object HeadingDetector {

    enum class Kind { FRONT, BACK }

    /** [canonicalEn] 是规范化的英文名（顺手修掉 OCR 拼写），[zhKey] 是中文关键词 */
    data class Hit(val kind: Kind, val canonicalEn: String, val zhKey: String)

    private val CJK = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF]+")
    private val TOKEN = Regex("[A-Za-z]+|\\d+|[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF]+")
    private val WHITESPACE = Regex("\\s+")

    private val CHAPTER_WORDS = listOf("chapter", "part", "unit", "section", "book", "lesson")

    private val FRONT_PHRASES = listOf(
        "contents" to "目录",
        "people in the story" to "人物",
        "places in the story" to "地点",
        "before reading" to "读前思考",
        "introduction" to "简介",
        "preface" to "前言",
        "foreword" to "序言",
        "about the author" to "作者",
        "cover" to "封面",
        "title page" to "扉页",
        "copyright" to "版权",
    )

    private val BACK_PHRASES = listOf(
        "after reading" to "读后活动",
        "learning guide" to "学习指导",
        "translation" to "参考译文",
        "exercises" to "习题",
        "glossary" to "词汇表",
        "answer key" to "答案",
        "activities" to "活动",
        "appendix" to "附录",
    )

    /** 短语之后允许的尾巴长度（通常只是中文译名），超出就不算标题行 */
    private const val MAX_TAIL_CHARS = 16

    /** 纯中文标题行的长度上限 */
    const val MAX_CJK_ONLY_LINE = 12

    /** 章节标题：行首是 chapter/part/... → 返回去掉编号后的正文；不是则返回 null */
    fun chapterBody(line: String): String? {
        val tokens = TOKEN.findAll(line).map { it.value }.toList()
        val first = tokens.firstOrNull() ?: return null
        if (first.isEmpty() || !first.all { it.code < 128 && it.isLetter() }) return null
        if (CHAPTER_WORDS.none { wordMatches(first, it) }) return null
        var rest = tokens.drop(1)
        // 编号 token：数字，或被整体误识别的单字符（如 "乥"）。
        // 注意 "Chapter 1 1 Happy ever after" —— OCR 把 11 拆成了两个 1，所以要连吃最多两个数字段。
        var guard = 0
        while (rest.isNotEmpty() && guard++ < 2) {
            val token = rest[0]
            val numeric = token.all { it.isDigit() } && token.length <= 2
            // 只把"非 ASCII 的单字符"当成被误识别的编号：实测 OCR 把 2 认成形近汉字"乥"。
            // 不能把 ASCII 单字母也算进来 —— "Chapter 1 A hand on a guitar" 的 "A" 是冠词，
            // 早期版本把它吃掉，标题就变成了 "Chapter 1 hand on a guitar"。
            val misread = token.length == 1 && !token[0].isDigit() && token[0].code > 127
            if (numeric || misread) rest = rest.drop(1) else break
        }
        // 去掉粘在末尾的纯符号 token（实测有 "Chapter 5 snow №"）
        while (rest.isNotEmpty() && rest.last().none { it.isLetterOrDigit() }) {
            rest = rest.dropLast(1)
        }
        return rest.joinToString(" ").trim().ifEmpty { null }
    }

    /** 附文标题：行首锚定匹配短语，或整行是短中文且含中文关键词 */
    fun sectionHit(line: String): Hit? {
        val tokens = TOKEN.findAll(line).map { it.value }.toList()
        val asciiTokens = tokens.filter { t -> t.all { it.code < 128 } }

        for ((table, kind) in listOf(FRONT_PHRASES to Kind.FRONT, BACK_PHRASES to Kind.BACK)) {
            for ((phrase, zhKey) in table) {
                val words = phrase.split(" ")
                if (asciiTokens.size < words.size) continue
                if (words.indices.any { !wordMatches(asciiTokens[it], words[it]) }) continue
                val tail = tokens.drop(words.size).joinToString(" ")
                if (tail.length <= MAX_TAIL_CHARS) {
                    return Hit(kind, phrase.replaceFirstChar { it.uppercaseChar() }, zhKey)
                }
            }
        }

        val zh = cjkOf(line)
        if (zh.isNotEmpty() && WHITESPACE.replace(line, "").length <= MAX_CJK_ONLY_LINE) {
            for ((table, kind) in listOf(FRONT_PHRASES to Kind.FRONT, BACK_PHRASES to Kind.BACK)) {
                table.firstOrNull { it.second.isNotEmpty() && zh.contains(it.second) }
                    ?.let { return Hit(kind, it.first.replaceFirstChar { c -> c.uppercaseChar() }, it.second) }
            }
        }
        return null
    }

    /** 粗判：这一行像不像标题（供不关心类型的调用点使用） */
    fun looksLikeHeading(line: String): Boolean =
        chapterBody(line) != null || sectionHit(line) != null

    fun cjkOf(line: String): String = CJK.findAll(line).joinToString("") { it.value }

    /** 这一行是不是"纯中文且足够短"——中文译名行的典型形态 */
    fun isShortCjkLine(line: String): Boolean {
        val zh = cjkOf(line)
        if (zh.isEmpty()) return false
        val stripped = WHITESPACE.replace(line, "")
        return stripped.length <= MAX_CJK_ONLY_LINE && stripped.all { it.code > 127 || it.isDigit() }
    }

    // ------------------------------------------------------------------ 逐词模糊匹配

    private fun wordMatches(token: String, word: String): Boolean =
        levenshtein(token.lowercase(), word) <= if (word.length <= 3) 0 else 1

    /** 带截断的编辑距离：超过 cap 直接返回 cap+1，省掉大量无谓计算 */
    fun levenshtein(a: String, b: String, cap: Int = 2): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
