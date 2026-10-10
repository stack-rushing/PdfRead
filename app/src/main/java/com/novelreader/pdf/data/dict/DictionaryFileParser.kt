package com.novelreader.pdf.data.dict

import java.io.BufferedReader

/** 解析出来的一条词条 */
data class ParsedEntry(
    val word: String,
    val phonetic: String? = null,
    val translation: String? = null,
    val definition: String? = null,
    val pos: String? = null,
    val exchange: String? = null,
    val collins: Int = 0,
    val oxford: Int = 0,
    val tag: String? = null,
    val bnc: Int = 0,
    val frq: Int = 0,
)

enum class DictFormat { CSV, TSV, JSON }

/**
 * 查询键的归一化。**查询侧和入库侧必须用同一个函数**，否则永远查不到，
 * 所以它放在这里而不是散落在仓库里。
 */
object DictionaryNormalizer {

    /** 小写、只保留字母数字与汉字（丢掉空格、连字符、撇号、标点） */
    fun normalize(word: String): String =
        word.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 解析 ECDICT 的 `exchange` 列，取出所有词形变体。
     *
     * 格式：`类型:单词/类型:单词`，类型含义：
     * `p` 过去式、`d` 过去分词、`i` 现在分词、`3` 第三人称单数、
     * `r` 比较级、`t` 最高级、`s` 复数、`0` 本词的原形（Lemma）。
     *
     * **带 `0:` 的是"变形词条"，它本身不是原形**——这种条目不建映射，
     * 否则会把 `running → running` 这种自环写进去。原形由它自己的条目承担。
     */
    fun formsOf(exchange: String?): List<String> {
        if (exchange.isNullOrBlank()) return emptyList()
        if (exchange.split('/').any { it.startsWith("0:") }) return emptyList()
        val out = LinkedHashSet<String>()
        exchange.split('/').forEach { seg ->
            val idx = seg.indexOf(':')
            if (idx <= 0 || idx == seg.length - 1) return@forEach
            val type = seg.substring(0, idx)
            if (type == "0" || type == "1") return@forEach
            val form = normalize(seg.substring(idx + 1))
            if (form.isNotEmpty()) out += form
        }
        return out.toList()
    }

    /** 展示用：把 `n:64/v:36` 变成 `n. 64% / v. 36%` */
    fun prettyPos(pos: String?): String? {
        if (pos.isNullOrBlank()) return null
        return pos.split('/').mapNotNull { seg ->
            val idx = seg.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = seg.substring(0, idx)
            val value = seg.substring(idx + 1)
            val label = when (key) {
                "n" -> "n."
                "v" -> "v."
                "adj" -> "adj."
                "adv" -> "adv."
                "prep" -> "prep."
                "conj" -> "conj."
                "pron" -> "pron."
                "num" -> "num."
                "art" -> "art."
                "int" -> "int."
                "u" -> "不可数"
                "j" -> "adj."
                "r" -> "adv."
                else -> key
            }
            "$label $value%"
        }.joinToString(" · ").ifEmpty { null }
    }

    /** 带截断的编辑距离：超过 cap 直接返回 cap+1。OCR 误识别的模糊兜底要用 */
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

/**
 * 把一条词条排成给人看的文本（纯逻辑，可单元测试）。
 *
 * 之所以不直接在 UI 里拼字符串：这一段的取舍（哪些字段为空就整行不显示、
 * 柯林斯星级怎么画、词频怎么标）值得单独测，而且点读卡片和查词页要共用同一套排版。
 */
object DictCardFormatter {

    fun stars(collins: Int): String =
        if (collins <= 0) "" else "★".repeat(collins.coerceAtMost(5))

    fun format(
        word: String,
        phonetic: String?,
        prettyPos: String?,
        translation: String?,
        definition: String?,
        exchange: String?,
        collins: Int,
        oxford: Int,
        tag: String?,
        bnc: Int,
        frq: Int,
        matchedForm: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append(word)
        if (!phonetic.isNullOrBlank()) sb.append("  /").append(phonetic.trim().trim('/')).append('/')
        sb.append('\n')

        if (matchedForm != null) {
            sb.append("（由 ").append(matchedForm).append(" 还原到原形）\n")
        }

        val badges = mutableListOf<String>()
        stars(collins).takeIf { it.isNotEmpty() }?.let { badges += "柯林斯 $it" }
        if (oxford == 1) badges += "牛津3000"
        if (!tag.isNullOrBlank()) badges += tag.trim().split(' ').filter { it.isNotBlank() }.joinToString("·")
        if (bnc > 0) badges += "BNC $bnc"
        if (frq > 0) badges += "当代 $frq"
        if (badges.isNotEmpty()) sb.append(badges.joinToString("  |  ")).append('\n')

        if (!prettyPos.isNullOrBlank()) sb.append("【词性】").append(prettyPos).append('\n')
        if (!translation.isNullOrBlank()) sb.append("【释义】\n").append(translation.trim()).append('\n')
        if (!definition.isNullOrBlank()) sb.append("【英文释义】\n").append(definition.trim()).append('\n')
        if (!exchange.isNullOrBlank()) sb.append("【词形】").append(prettyExchange(exchange)).append('\n')
        return sb.toString().trimEnd()
    }

    /** `d:perceived/p:perceived/3:perceives` → `过去式 perceived · 过去分词 perceived · 三单 perceives` */
    fun prettyExchange(exchange: String?): String {
        if (exchange.isNullOrBlank()) return ""
        return exchange.split('/').mapNotNull { seg ->
            val idx = seg.indexOf(':')
            if (idx <= 0 || idx == seg.length - 1) return@mapNotNull null
            val label = when (seg.substring(0, idx)) {
                "p" -> "过去式"
                "d" -> "过去分词"
                "i" -> "现在分词"
                "3" -> "三单"
                "r" -> "比较级"
                "t" -> "最高级"
                "s" -> "复数"
                "0" -> "原形"
                "1" -> "原形变换"
                else -> seg.substring(0, idx)
            }
            "$label ${seg.substring(idx + 1)}"
        }.joinToString(" · ")
    }
}

/**
 * 词书文件解析器（纯逻辑，可单元测试）。
 *
 * 支持三种形态：
 * 1. **ECDICT 风格的 CSV**：`word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,...`
 *    —— 列顺序靠表头识别，不写死位置；
 * 2. **通用两列 TSV/CSV**：`单词<TAB>释义`（很多人自己整理的词表就是这个形状）；
 * 3. **JSON 数组**：`[{"word":"...","translation":"..."}, ...]`，字段名与上面同名即可。
 *
 * CSV 解析是完整的有限状态机：ECDICT 的中文释义字段里**既有逗号也有换行**，
 * 直接 `split(',')` 会把一条词条切碎——这是这个解析器存在的主要理由。
 */
class DictionaryFileParser {

    /** 根据文件名与首行猜格式 */
    fun detectFormat(fileName: String, firstLine: String): DictFormat {
        val lower = fileName.lowercase()
        if (lower.endsWith(".json")) return DictFormat.JSON
        if (lower.endsWith(".tsv")) return DictFormat.TSV
        if (lower.endsWith(".csv")) return DictFormat.CSV
        // 没有扩展名时看首行：有制表符且没有逗号 → TSV；以 [ 或 { 开头 → JSON
        val trimmed = firstLine.trimStart()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return DictFormat.JSON
        return if (firstLine.contains('\t') && !firstLine.contains(',')) DictFormat.TSV else DictFormat.CSV
    }

    /**
     * 打开一个词书文件并按批读取。
     *
     * **为什么按批而不是逐条回调**：把条目写进 Room 是挂起操作，而解析回调不是 suspend 函数，
     * 在回调里没法挂起。按批读 → 批与批之间可以 `ensureActive()`（于是可取消）也能报进度，
     * 而且内存占用被一批的大小封顶——ECDICT 全量 CSV 有几十万到上百万条，
     * 一次性读进内存会直接把手机打爆。
     */
    inner class Stream(
        private val reader: BufferedReader,
        private val format: DictFormat,
        /** 调用方为了探测格式已经读掉的首行；按原样交回来，避免丢掉表头或 JSON 的开头 */
        private val pushedBackLine: String? = null,
    ) {

        private val delimiter: Char = if (format == DictFormat.CSV) ',' else '\t'

        private val delimited: DelimitedReader? =
            if (format == DictFormat.JSON) null else DelimitedReader(reader, delimiter)

        /**
         * 首行如果不像表头，它就是**数据**——不能当表头吃掉。
         * （很多人自己整理的词表就是 `apple<TAB>苹果` 这种没有表头的两列，
         * 早期版本会把第一个词直接吞掉。）
         *
         * 注意声明顺序：Kotlin 按声明顺序初始化属性，这个字段必须先于 [mapping]，
         * 否则会被 mapping 的初始化过程写入后再被自己的初始化清成 null。
         */
        private var pendingRow: List<String>? = null

        private val mapping: ColumnMapping? = delimited?.let { rows ->
            val first = pushedBackLine?.let { line ->
                DelimitedReader(java.io.BufferedReader(java.io.StringReader(line)), delimiter).readRow()
            } ?: rows.readRow()
            if (first != null && ColumnMapping.looksLikeHeader(first)) {
                ColumnMapping.of(first)
            } else {
                pendingRow = first
                ColumnMapping.generic()
            }
        }

        private var jsonItems: List<kotlinx.serialization.json.JsonObject>? = null
        private var jsonIndex = 0

        /** 读下一批；返回空列表表示读完了 */
        fun nextBatch(max: Int): List<ParsedEntry> =
            if (format == DictFormat.JSON) nextJsonBatch(max) else nextDelimitedBatch(max)

        private fun nextDelimitedBatch(max: Int): List<ParsedEntry> {
            val out = ArrayList<ParsedEntry>(max)
            val rows = delimited ?: return out
            val map = mapping ?: return out
            while (out.size < max) {
                val row = takePending() ?: rows.readRow() ?: break
                if (row.all { it.isBlank() }) continue
                map.toEntry(row)?.let { out += it }
            }
            return out
        }

        /** 首行被判定成数据时，它会在这里第一次被消费掉，只消费一次 */
        private fun takePending(): List<String>? {
            val p = pendingRow
            pendingRow = null
            return p
        }

        private fun nextJsonBatch(max: Int): List<ParsedEntry> {
            if (jsonItems == null) {
                // 词书 JSON 通常不大（几千到几万条），整体读进来最简单可靠
                val text = (pushedBackLine?.plus("\n") ?: "") + reader.readText()
                jsonItems = when (val element = kotlinx.serialization.json.Json.parseToJsonElement(text)) {
                    is kotlinx.serialization.json.JsonArray ->
                        element.mapNotNull { it as? kotlinx.serialization.json.JsonObject }

                    is kotlinx.serialization.json.JsonObject ->
                        (element["entries"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                            ?: emptyList()

                    else -> emptyList()
                }
            }
            val items = jsonItems ?: return emptyList()
            val out = ArrayList<ParsedEntry>(max)
            while (jsonIndex < items.size && out.size < max) {
                ColumnMapping.fromJson(items[jsonIndex])?.let { out += it }
                jsonIndex++
            }
            return out
        }
    }

    // ------------------------------------------------------------------ 列映射

    /** 表头 → 列号；认不出来就退回"第一列是词、第二列是释义" */
    private class ColumnMapping(private val index: Map<String, Int>, private val generic: Boolean) {

        fun toEntry(row: List<String>): ParsedEntry? {
            if (generic) {
                val word = row.getOrNull(0)?.trim().orEmpty()
                if (word.isEmpty()) return null
                val meaning = row.drop(1).joinToString("\n").trim().ifEmpty { null }
                return ParsedEntry(word = word, translation = meaning)
            }
            val word = get(row, "word") ?: return null
            if (word.isBlank()) return null
            return ParsedEntry(
                word = word,
                phonetic = get(row, "phonetic"),
                translation = get(row, "translation"),
                definition = get(row, "definition"),
                pos = get(row, "pos"),
                exchange = get(row, "exchange"),
                collins = get(row, "collins")?.toIntOrNull() ?: 0,
                oxford = get(row, "oxford")?.toIntOrNull() ?: 0,
                tag = get(row, "tag"),
                bnc = get(row, "bnc")?.toIntOrNull() ?: 0,
                frq = get(row, "frq")?.toIntOrNull() ?: 0,
            )
        }

        private fun get(row: List<String>, name: String): String? {
            val i = index[name] ?: return null
            return row.getOrNull(i)?.trim()?.ifEmpty { null }
        }

        companion object {
            private val KNOWN = setOf(
                "word", "phonetic", "definition", "translation", "pos",
                "collins", "oxford", "tag", "bnc", "frq", "exchange",
            )

            private val HEADER_ALIASES = setOf("单词", "词条", "headword", "word", "entry")

            /** 首行像不像表头。不像就当数据，不能让第一个词白白丢掉 */
            fun looksLikeHeader(row: List<String>): Boolean {
                if (row.isEmpty()) return false
                val cells = row.map { it.trim().lowercase().removePrefix("\uFEFF") }
                if (cells.any { it in KNOWN }) return true
                return cells.firstOrNull() in HEADER_ALIASES
            }

            /** 通用两列形态：第 0 列是词，其余列拼成释义 */
            fun generic(): ColumnMapping = ColumnMapping(emptyMap(), generic = true)

            fun of(header: List<String>): ColumnMapping {
                val normalized = header.map { it.trim().lowercase().removePrefix("\uFEFF") }
                val index = mutableMapOf<String, Int>()
                normalized.forEachIndexed { i, name ->
                    if (name in KNOWN && name !in index) index[name] = i
                }
                // 表头里必须有 word 列才当成结构化词书，否则按通用两列处理
                val structured = index.containsKey("word") &&
                    (index.containsKey("translation") || index.containsKey("definition"))
                return ColumnMapping(index, generic = !structured)
            }

            fun fromJson(obj: kotlinx.serialization.json.JsonObject): ParsedEntry? {
                fun str(key: String): String? =
                    (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString || it.content != "null" }?.content?.trim()?.ifEmpty { null }

                fun int(key: String): Int = str(key)?.toIntOrNull() ?: 0

                val word = str("word") ?: str("headword") ?: return null
                return ParsedEntry(
                    word = word,
                    phonetic = str("phonetic"),
                    translation = str("translation"),
                    definition = str("definition"),
                    pos = str("pos"),
                    exchange = str("exchange"),
                    collins = int("collins"),
                    oxford = int("oxford"),
                    tag = str("tag"),
                    bnc = int("bnc"),
                    frq = int("frq"),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 带引号的 CSV

    /**
     * 支持 RFC4180 引号规则的流式读取器：
     * `"` 包裹的字段里可以有分隔符和换行，`""` 表示一个字面引号。
     */
    private class DelimitedReader(private val reader: BufferedReader, private val delimiter: Char) {

        fun readRow(): List<String>? {
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var sawAny = false

            while (true) {
                val ch = reader.read()
                if (ch == -1) {
                    if (!sawAny && fields.isEmpty() && field.isEmpty()) return null
                    fields += field.toString()
                    return fields
                }
                sawAny = true
                val c = ch.toChar()
                when {
                    inQuotes -> when (c) {
                        '"' -> {
                            // 可能是转义的双引号，也可能是引号段结束
                            reader.mark(1)
                            val next = reader.read()
                            if (next == '"'.code) {
                                field.append('"')
                            } else {
                                if (next != -1) reader.reset()
                                inQuotes = false
                            }
                        }

                        else -> field.append(c)
                    }

                    c == '"' && field.isEmpty() -> inQuotes = true
                    c == delimiter -> {
                        fields += field.toString()
                        field.setLength(0)
                    }

                    c == '\n' -> {
                        fields += field.toString()
                        return fields
                    }

                    c == '\r' -> {
                        reader.mark(1)
                        val next = reader.read()
                        if (next != '\n'.code && next != -1) reader.reset()
                        fields += field.toString()
                        return fields
                    }

                    else -> field.append(c)
                }
            }
        }
    }
}
