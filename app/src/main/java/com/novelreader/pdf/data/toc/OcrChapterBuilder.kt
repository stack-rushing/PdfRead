package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterKind
import com.novelreader.pdf.data.model.ChapterResolver

/**
 * 「OCR 全页眉扫描」的状态机（纯逻辑，可单元测试）。
 *
 * 逐页喂入顶部条带的行，产出章节表。六条规则都是用真实扫描件调出来的
 * （剑桥小说馆这一套书里就有三种不同的章节页排版）：
 *
 * 1. **编号取序号，不取 OCR 数字**：OCR 把 `Chapter 2` 读成 `Chapter 乥`、`Chapter 3` 读成 `Chapter 5`，
 *    但页码和顺序全对。所以第 N 个章节标题就是 Chapter N —— 这样连"数字完全认不出来"的页面也能救回来。
 * 2. **编号独占一行时，标题在下一行**：2级16/17/19 都是这个版式（`Chapter 1` 换行 `It's not fair!`），
 *    而 2级08 是 `Chapter 1 A hand on a guitar` 挤在一行。两种都要认。
 * 3. **整页页眉只有一行、且不像附文标题 → 这一行就是章节名**：2级07《圆圈故事集》和
 *    2级18《派对与礼物》干脆没有 `Chapter N`（`Quick Man Tan`、`he Carden Party`）。
 * 4. **只在"终止性"后置附文处停止收章节**：`Translation`/`Learning guide`/`Words & expressions`
 *    这些永远在书末，见到就停；但 `ACTIVITIES`/`After reading` 在短篇合集里会夹在两篇之间，
 *    在那里停会把后两个故事整段丢掉（2级18 实测）。
 * 5. **同名去重**：书里有两个 `目录` 页，但阅读器只需要一个跳转目标，重复的只保留第一次。
 * 6. **跨行拼接标题**：扫描件的标题常被折成两行（`Chapter 1 A hand on a` + `guitar`），
 *    以及"英文标题一行 + 中文译名一行"的双语形态，都要合并成一个条目。
 */
class OcrChapterBuilder(private val totalPages: Int) {

    private val chapters = ArrayList<Chapter>()
    private val seenTitles = HashSet<String>()
    private var ordinal = 0
    private var backStarted = false

    /** 合集里已经切出几本（0 表示还没切过，第一本在遇到 `作者简介` 时才建组） */
    private var groupCount = 0

    /** 当前条目该挂到哪个分组下（单本文件时一直是 ROOT） */
    private var currentGroupIndex: Int = Chapter.ROOT

    /** 上一张有内容的页的行，用来尽力猜书名（扉页在 `作者简介` 之前） */
    private var lastNonEmptyLines: List<String> = emptyList()

    /**
     * 喂入一页的顶部条带文本（按行）。返回该页新产生的条目，没有则返回 null。
     */
    fun accept(pageIndex: Int, rawLines: List<String>): Chapter? {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val head = lines[0]
        val previousLines = lastNonEmptyLines
        lastNonEmptyLines = lines

        // ---------------------------------------------------------------- 多本合集
        // 实测：剑桥《赃款》是一个 368 页的装订本，里面装了 5 本小说，每本都以
        // `作者简介` → `故事导读` → `Contents 目录` → … → `Chapter 1` 开头。
        // 不切分会出现两个致命错误：
        //   ① 章节号靠序号累加 → 第二本的 Chapter 1 被写成 Chapter 11；
        //   ② 第一本的《参考译文》会封口 → 后面四本的章节整段丢失
        //      （实测还吐出一条跨 300 页的"读前思考"垃圾条目）。
        if (HeadingDetector.isBookStart(head) && (groupCount == 0 || ordinal > 0)) {
            groupCount++
            ordinal = 0
            backStarted = false
            val group = add(pageIndex, ChapterKind.GROUP, bookTitle(groupCount, previousLines), null)
            // 后面这本书里的所有条目都挂到这个分组下
            currentGroupIndex = group?.index ?: currentGroupIndex
            return group
        }

        if (!backStarted) {
            val body = HeadingDetector.chapterBody(head)
            if (body != null) {
                ordinal++
                // 空串 = 编号独占一行（版式 2），标题去下面几行取
                val full = if (body.isEmpty()) titleFromFollowing(lines).orEmpty() else joinContinuation(body, lines)
                return add(pageIndex, ChapterKind.CHAPTER, "Chapter $ordinal $full".trim(), null)
            }
        }

        val hit = HeadingDetector.sectionHit(head)
        if (hit == null) {
            // 版式 3：不是附文标题，而且整页页眉只有一行 → 这一行就是章节名。
            // **必须放在 sectionHit 之后**：`Contents 目 录` 也满足"只有一行"，
            // 先判版式 3 的话，两个目录页会变成 `Chapter 1 Contents 目 录`（实测踩过）。
            if (!backStarted) {
                HeadingDetector.bareTitle(lines)?.let { bare ->
                    ordinal++
                    return add(pageIndex, ChapterKind.CHAPTER, "Chapter $ordinal $bare", null)
                }
            }
            return null
        }
        // 只有终止性附文才封口；ACTIVITIES / After reading 允许后面还有正文（短篇合集）
        if (HeadingDetector.isTerminalBack(hit)) backStarted = true

        // 双语形态：英文标题在首行、中文译名在下一行 → 中文当主标题、规范英文当副标题。
        // 但只有 ≥3 个汉字的中文行才可信：实测 "Preface" 下面那行被识别成 "刖口"（前言 的误识），
        // 2 个汉字时反而是关键词表里的规范名更可靠。
        //
        // 再加一道"必须包含规范名"的检查：实测某本的《参考译文》页眉被识别成 "第章空房子"
        // （把上一页的章节名糊上来了），这种就直接用规范名"参考译文"。
        val cjkLine = lines.drop(1).firstOrNull { HeadingDetector.isShortCjkLine(it) }
            ?.let { HeadingDetector.cjkOf(it) }
        val cleanCjk = cjkLine?.takeIf { it.contains(hit.zhKey) }
        val zh = when {
            cleanCjk != null && cleanCjk.length >= 3 -> cleanCjk
            hit.zhKey.isNotEmpty() -> hit.zhKey
            cleanCjk != null -> cleanCjk
            else -> HeadingDetector.cjkOf(head).takeIf { it.isNotEmpty() }
        }
        val title = zh ?: hit.canonicalEn
        val titleEn = if (zh == null) null else hit.canonicalEn
        return add(pageIndex, kindOf(hit.kind), title, titleEn)
    }

    fun finish(): List<Chapter> = ChapterResolver.resolveRanges(chapters, totalPages)

    // ------------------------------------------------------------------ 内部

    /** 标题被折行时把下一行接上（仅在下一行像"标题的续行"时才接，避免把正文吞进来） */
    private fun joinContinuation(body: String, lines: List<String>): String {
        val next = lines.getOrNull(1) ?: return body
        if (HeadingDetector.looksLikeHeading(next)) return body
        val words = next.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return body
        if (next.length > 24 || next.any { it.isDigit() }) return body
        if (body.length + next.length > 40) return body
        if (!body.last().isLetter()) return body
        return "$body $next"
    }

    /**
     * 编号独占一行时（版式 2），从下面几行里把标题捞出来。
     *
     * 实测形态：
     *   `Chapter 1` / `It's not fair!`                     → 两行
     *   `hapter 6` / `Kompier doesn't want` / `to know`    → 标题换行，第三行才是结尾
     *   `hapter 7` / `Meeting Peter van` / `、 VVIC`        → 第三行是噪声，不能接
     *
     * 捞不到就返回 null：这一章照样登记，标题退化成 "Chapter N"，但**页码是对的**。
     */
    private fun titleFromFollowing(lines: List<String>): String? {
        val first = lines.getOrNull(1) ?: return null
        if (HeadingDetector.looksLikeHeading(first)) return null
        val words = first.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 8 || first.length > 60) return null

        var title = first
        val third = lines.getOrNull(2)
        if (third != null && !HeadingDetector.looksLikeHeading(third)) {
            val w3 = third.split(Regex("\\s+")).filter { it.isNotEmpty() }
            // 续行必须是"小写开头的短尾巴"：这样 `to know` 会被接上，
            // 而 `、 VVIC`、`ACTIVITIES` 这类噪声会被挡掉。
            if (w3.isNotEmpty() && w3.size <= 3 && third.length <= 24 &&
                third.none { it.isDigit() } && third.first().isLowerCase() &&
                title.length + third.length <= 60
            ) {
                title = "$title $third"
            }
        }
        return title
    }

    /**
     * 合集里每一本的组名。
     *
     * 先把话说清楚：**扫描件里拿不到可靠书名**。每本书的扉页（`CAMBRIDGE` + 书名）OCR 得半斤八两——
     * 第一本读出过 `Dirty M on`，第二本只剩 `CAM BRIDGE`。所以这里只做尽力而为：
     * 上一张有内容的页里若有一行像英文书名就带上，否则退化成「第 N 本」，由使用者在导出的 JSON 里改名。
     */
    private fun bookTitle(index: Int, previousLines: List<String>): String {
        val candidate = previousLines.firstOrNull { line ->
            val letters = line.filter { it.isLetter() }
            // "CAMBRIDGE" 会被 OCR 认成 CAMBRTDGE / CAM BRIDGE 之类，所以用编辑距离而不是相等
            val lower = letters.lowercase()
            val isPublisher = lower.isNotEmpty() &&
                (lower == "cambridge" || HeadingDetector.levenshtein(lower, "cambridge", 2) <= 2)
            letters.length >= 4 &&
                line.any { it.code < 128 && it.isLetter() } &&
                !isPublisher &&
                line.length <= 40 &&
                line.none { it.isDigit() }
        }
        return candidate?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "第 $index 本 · $it" }
            ?: "第 $index 本"
    }

    /**
     * 登记一条目录。多本合集里，一本书内的条目要把 [parentIndex] 指向那本书的分组，
     * 否则 [ChapterResolver.resolveRanges] 无从知道哪些章节属于哪本书，
     * 分组范围就只剩它自己那一页（实测：第 1 本只占 P10-12，抽屉里折不起来）。
     */
    private fun add(
        page: Int,
        kind: ChapterKind,
        title: String,
        titleEn: String?,
        parentIndex: Int = if (kind == ChapterKind.GROUP) Chapter.ROOT else currentGroupIndex,
    ): Chapter? {
        val key = Regex("\\s+").replace(title.lowercase(), "")
        if (key.isEmpty() || !seenTitles.add(key)) return null
        val chapter = Chapter(
            index = chapters.size,
            parentIndex = parentIndex,
            level = if (parentIndex == Chapter.ROOT) 0 else 1,
            kind = kind,
            title = title,
            titleEn = titleEn,
            startPage = page,
            endPage = Chapter.UNRESOLVED,
            confidence = SCAN_CONFIDENCE,
        )
        chapters += chapter
        return chapter
    }

    private fun kindOf(kind: HeadingDetector.Kind) = when (kind) {
        HeadingDetector.Kind.FRONT -> ChapterKind.FRONT
        HeadingDetector.Kind.BACK -> ChapterKind.BACK
    }

    companion object {
        /** 启发式结果：比"没有目录"强得多，但用户应当核对 */
        const val SCAN_CONFIDENCE = 60
    }
}
