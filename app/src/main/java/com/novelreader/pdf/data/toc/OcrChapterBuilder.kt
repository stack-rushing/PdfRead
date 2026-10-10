package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterKind
import com.novelreader.pdf.data.model.ChapterResolver

/**
 * 「OCR 全页眉扫描」的状态机（纯逻辑，可单元测试）。
 *
 * 逐页喂入顶部条带的行，产出章节表。四条规则都是用真实扫描件调出来的：
 *
 * 1. **编号取序号，不取 OCR 数字**：OCR 把 `Chapter 2` 读成 `Chapter 乥`、`Chapter 3` 读成 `Chapter 5`，
 *    但页码和顺序全对。所以第 N 个章节标题就是 Chapter N —— 这样连"数字完全认不出来"的页面也能救回来。
 * 2. **遇到第一个后置附文就停止收章节**：这类分级读物正文到 `After reading` 就结束了，
 *    后面的《学习指导》里每页都带 `Chapter 2`、`Chapter 3` 这样的页眉（其实是习题分组），
 *    不截断就会凭空多出 Chapter 12/13/14。
 * 3. **同名去重**：书里有两个 `目录` 页，但阅读器只需要一个跳转目标，重复的只保留第一次。
 * 4. **跨行拼接标题**：扫描件的标题常被折成两行（`Chapter 1 A hand on a` + `guitar`），
 *    以及"英文标题一行 + 中文译名一行"的双语形态，都要合并成一个条目。
 */
class OcrChapterBuilder(private val totalPages: Int) {

    private val chapters = ArrayList<Chapter>()
    private val seenTitles = HashSet<String>()
    private var ordinal = 0
    private var backStarted = false

    /**
     * 喂入一页的顶部条带文本（按行）。返回该页新产生的条目，没有则返回 null。
     */
    fun accept(pageIndex: Int, rawLines: List<String>): Chapter? {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val head = lines[0]

        if (!backStarted) {
            val body = HeadingDetector.chapterBody(head)
            if (body != null) {
                ordinal++
                val full = joinContinuation(body, lines)
                return add(pageIndex, ChapterKind.CHAPTER, "Chapter $ordinal $full".trim(), null)
            }
        }

        val hit = HeadingDetector.sectionHit(head) ?: return null
        if (hit.kind == HeadingDetector.Kind.BACK) backStarted = true

        // 双语形态：英文标题在首行、中文译名在下一行 → 中文当主标题、规范英文当副标题。
        // 但只有 ≥3 个汉字的中文行才可信：实测 "Preface" 下面那行被识别成 "刖口"（前言 的误识），
        // 2 个汉字时反而是关键词表里的规范名更可靠。
        val cjkLine = lines.drop(1).firstOrNull { HeadingDetector.isShortCjkLine(it) }
            ?.let { HeadingDetector.cjkOf(it) }
        val zh = when {
            cjkLine != null && cjkLine.length >= 3 -> cjkLine
            hit.zhKey.isNotEmpty() -> hit.zhKey
            cjkLine != null -> cjkLine
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

    private fun add(page: Int, kind: ChapterKind, title: String, titleEn: String?): Chapter? {
        val key = Regex("\\s+").replace(title.lowercase(), "")
        if (key.isEmpty() || !seenTitles.add(key)) return null
        val chapter = Chapter(
            index = chapters.size,
            parentIndex = Chapter.ROOT,
            level = 0,
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
