package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterKind
import com.novelreader.pdf.data.model.ChapterResolver
import com.novelreader.pdf.data.ocr.OcrEngine
import com.novelreader.pdf.data.pdf.BilingualTitle
import com.novelreader.pdf.data.pdf.OutlineNode
import com.novelreader.pdf.data.pdf.PdfRasterSession
import com.novelreader.pdf.data.pdf.PdfTextBoxReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** 生成进度回调：阶段名 + 已完成 + 总数 */
typealias TocProgress = (phase: String, done: Int, total: Int) -> Unit

/**
 * 目录生成器：把「PDF 结构」与「页面图像」两条信息合成一份可用的章节表。
 *
 * 三类书对应三条路径：
 * 1. **有内置书签且页码可信**（大多数正规电子书）→ 直接用书签，秒级完成；
 * 2. **有内置书签但页码漂移**（calibre 从 EPUB 转的 PDF，实测书虫合集偏移 +4~+61）
 *    → 保留书签的标题与顺序，用 OCR 每页页眉把页码**重新锚定**；
 * 3. **没有书签的扫描件**（剑桥《真爱无界》整页位图、无文字层）→ OCR 每页页眉，
 *    用标题模式挑出章节起始页，从零构建章节表。
 *
 * 为什么第 2 类必须重锚定而不能"整体加个偏移"：书虫这本的偏移量从 +4 一路涨到 +61，
 * 不是常数——因为 EPUB 的一个"页"与 PDF 重排后的一页字数完全不同。
 */
class TocGenerator(
    private val ocr: OcrEngine,
) {

    // ------------------------------------------------------------------ 1) 内置书签

    /** 把大纲节点转成层级章节表；[markLowConfidence] 用于"页码不可信"时给低分 */
    fun outlineToChapters(
        nodes: List<OutlineNode>,
        totalPages: Int,
        markLowConfidence: Boolean,
    ): List<Chapter> {
        if (nodes.isEmpty()) return emptyList()

        // 用层级栈把扁平的 level 序列还原成父子关系（PDF 大纲本身是树，解析时已带 level）
        val parentOf = IntArray(nodes.size) { Chapter.ROOT }
        val stack = HashMap<Int, Int>()
        nodes.forEachIndexed { i, node ->
            val level = node.level.coerceAtLeast(0)
            parentOf[i] = if (level == 0) Chapter.ROOT else (stack[level - 1] ?: Chapter.ROOT)
            stack[level] = i
            stack.keys.filter { it > level }.forEach { stack.remove(it) }
        }
        val hasChild = BooleanArray(nodes.size)
        parentOf.forEach { p -> if (p >= 0) hasChild[p] = true }

        val lastPage = (totalPages - 1).coerceAtLeast(0)
        val raw = nodes.mapIndexed { i, node ->
            val split = BilingualTitle.split(node.title)
            Chapter(
                index = i,
                parentIndex = parentOf[i],
                level = node.level.coerceAtLeast(0),
                kind = classify(split.title, hasChild[i]),
                title = split.title,
                titleEn = split.titleEn,
                startPage = node.pageIndex.coerceIn(0, lastPage),
                endPage = Chapter.UNRESOLVED,
                confidence = if (markLowConfidence && !hasChild[i]) LOW_CONFIDENCE else 100,
            )
        }
        return ChapterResolver.resolveRanges(raw, totalPages)
    }

    /**
     * 判断内置书签的页码是否可信。
     *
     * 判据：叶子章节按大纲顺序排列时，startPage 出现逆序的比例。
     * 正常 PDF 大纲天然单调递增（逆序 0%）；calibre 转出来的那本 506 条里 396 条逆序（78%）。
     */
    fun pagesLookUnreliable(chapters: List<Chapter>): Boolean {
        val leaves = chapters.filter { !it.isGroup }
        if (leaves.size < 4) return false
        var inversions = 0
        var prev = -1
        leaves.forEach { c ->
            if (c.startPage < prev) inversions++
            prev = maxOf(prev, c.startPage)
        }
        return inversions.toFloat() / leaves.size > 0.05f
    }

    private fun classify(title: String, hasChildren: Boolean): ChapterKind {
        if (hasChildren) return ChapterKind.GROUP
        val t = title.lowercase()
        return when {
            FRONT_WORDS.any { t.contains(it) } -> ChapterKind.FRONT
            BACK_WORDS.any { t.contains(it) } -> ChapterKind.BACK
            else -> ChapterKind.CHAPTER
        }
    }

    // ------------------------------------------------------------------ 2) OCR 重锚定

    /**
     * 用 OCR 页眉把已有章节的真实页码找回来。
     *
     * 算法：按**大纲顺序**扫描章节（顺序是可信的，页码才是漂移的），
     * 从第 0 页起逐页 OCR 顶部条带；某页页眉命中"指针附近若干章节"的标题时，
     * 就把该章节锚定到这一页，并把指针推进。未被命中的章节保持原位，
     * 最后统一夹在相邻已锚定页之间，保证整体单调。
     */
    suspend fun reanchorByOcr(
        file: File,
        chapters: List<Chapter>,
        totalPages: Int,
        progress: TocProgress,
    ): List<Chapter> {
        val leafPositions = chapters.indices.filter { !chapters[it].isGroup }
        if (leafPositions.isEmpty() || totalPages <= 0) return chapters

        // 标题预先归一化：整轮扫描要反复比对，不能每页重算
        val needles = leafPositions.map { normalize(chapters[it].title) }
        val needlesEn = leafPositions.map { normalize(chapters[it].titleEn.orEmpty()) }

        // 每个章节标题"出现在哪些页"。用出现位置表而不是"指针 + 固定窗口"，
        // 是因为开头的封面/扉页/版权页标题太短、根本匹配不上，
        // 指针会被它们卡在第 0 章，导致后面所有章节都不再被检验（实测命中率 0%）。
        val appearances = Array(leafPositions.size) { mutableListOf<Int>() }

        PdfRasterSession(file).use { session ->
            session.open()
            for (page in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                progress(PHASE_OCR, page + 1, totalPages)
                val bitmap = session.renderTopStrip(page, OCR_WIDTH, TOP_FRACTION) ?: continue
                val text = normalize(runCatching { ocr.recognize(bitmap) }.getOrDefault(""))
                bitmap.recycle()
                if (text.length < MIN_NEEDLE) continue

                val hits = ArrayList<Int>(4)
                for (k in needles.indices) {
                    val zh = needles[k]
                    val en = needlesEn[k]
                    if ((zh.length >= MIN_NEEDLE && text.contains(zh)) ||
                        (en.length >= MIN_NEEDLE && text.contains(en))
                    ) {
                        hits += k
                    }
                }
                // 一页命中 3 个以上标题 → 这是目录页，绝不能当成章节起始页，
                // 否则整本目录会被锚定成某一章。
                if (hits.size >= TOC_PAGE_HITS) continue
                hits.forEach { appearances[it] += page }
            }
        }

        // 第二遍：严格按大纲顺序贪心，取"不早于上一章起始页的最早出现页"。
        // 大纲的标题顺序是可信的（只有页码漂移），所以这个约束能把整条序列钉住。
        val anchored = HashMap<Int, Int>(appearances.size)
        var lastAssigned = -1
        for (k in appearances.indices) {
            val hit = appearances[k].firstOrNull { it >= lastAssigned }
            if (hit != null) {
                anchored[k] = hit
                lastAssigned = hit
            }
        }

        val lastPage = totalPages - 1
        val pages = IntArray(appearances.size) {
            chapters[leafPositions[it]].startPage.coerceIn(0, lastPage)
        }
        anchored.forEach { (k, page) -> pages[k] = page }

        // 未命中的章节：在相邻锚点之间做线性插值。
        // 早期实现用"上一页 + 1"依次夹取，结果是把 488 章摊成了每章一页的假目录。
        val anchorKeys = anchored.keys.sorted()
        anchorKeys.zipWithNext().forEach { (a, b) ->
            if (b - a <= 1) return@forEach
            val p0 = anchored.getValue(a)
            val p1 = anchored.getValue(b)
            for (j in a + 1 until b) {
                pages[j] = p0 + Math.round((p1 - p0).toFloat() * (j - a) / (b - a))
            }
        }
        anchorKeys.firstOrNull()?.let { first ->
            val p = anchored.getValue(first)
            for (j in 0 until first) pages[j] = minOf(pages[j], p)
        }
        anchorKeys.lastOrNull()?.let { lastKey ->
            val p = anchored.getValue(lastKey)
            for (j in lastKey + 1 until pages.size) pages[j] = maxOf(pages[j], p)
        }
        for (k in 1 until pages.size) pages[k] = maxOf(pages[k], pages[k - 1])

        val anchoredChapters = HashSet<Int>(anchored.size)
        anchored.keys.forEach { anchoredChapters += leafPositions[it] }

        val out = chapters.mapIndexed { i, c ->
            if (c.isGroup) {
                c
            } else {
                c.copy(
                    startPage = pages[leafPositions.indexOf(i)].coerceIn(0, lastPage),
                    confidence = if (anchoredChapters.contains(i)) ANCHORED_CONFIDENCE else LOW_CONFIDENCE,
                )
            }
        }
        return ChapterResolver.resolveRanges(out, totalPages)
    }
    // ------------------------------------------------------------------ 3) OCR 全页眉扫描

    /**
     * 完全没有书签时的兜底：逐页 OCR 页眉，靠标题模式找章首页。
     *
     * 关键细节：
     * - 目录页（一页里出现多个 "Chapter N"）会被识别并跳过，否则整本目录会被当成一个章节；
     * - 页眉在整章内会重复出现，用 normalize 后去重，只保留第一次出现（= 章首页）；
     * - 置信度统一给 60：这是启发式结果，用户应当核对，但比"没有目录"强得多。
     */
    suspend fun scanByOcr(
        file: File,
        totalPages: Int,
        progress: TocProgress,
    ): List<Chapter> {
        if (totalPages <= 0) return emptyList()
        val builder = OcrChapterBuilder(totalPages)

        PdfRasterSession(file).use { session ->
            session.open()
            for (page in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                progress(PHASE_SCAN, page + 1, totalPages)
                val bitmap = session.renderTopStrip(page, OCR_WIDTH, TOP_FRACTION) ?: continue
                val raw = runCatching { ocr.recognize(bitmap) }.getOrDefault("")
                bitmap.recycle()
                if (raw.isBlank()) continue
                // 规则全部收敛在 OcrChapterBuilder 里（纯逻辑、有单元测试用真实 OCR 文本兜底）
                builder.accept(page, raw.split('\n'))
            }
        }
        return builder.finish()
    }
    /**
     * 用 PDF **文字层**生成目录（不是 OCR）。
     *
     * 为什么优先于 OCR：文字层是出版方切好的文本，字符精确、没有识别误差，
     * 而且不用渲染整本书的图片，速度是 OCR 的数倍。纯扫描件没有文字层，
     * 这里会返回空表，由调用方回退到 [scanByOcr]。
     *
     * 规则本身不重复实现：逐页取前几行喂给 [OcrChapterBuilder]，
     * 章节判定、多本合集的切分、附文识别全部复用同一套逻辑（已有单元测试兜底）。
     */
    suspend fun scanByTextLayer(
        file: File,
        totalPages: Int,
        progress: TocProgress,
    ): List<Chapter> {
        if (totalPages <= 0) return emptyList()
        val reader = PdfTextBoxReader(file)
        val builder = OcrChapterBuilder(totalPages)
        try {
            if (!reader.open()) return emptyList()
            // 没有可用文字层（纯扫描件）就别浪费时间了，直接让调用方走 OCR
            if (!reader.hasUsableTextLayer()) return emptyList()

            // 一页两开时显示页数是物理页数的两倍，两个数都取自 SpreadLayout，天然一致；
            // 这里取较小值只是防止越界
            val pages = minOf(totalPages, reader.pageCount)
            for (page in 0 until pages) {
                currentCoroutineContext().ensureActive()
                progress(PHASE_TEXT, page + 1, pages)
                val lines = reader.pageLines(page, TEXT_LAYER_LINES)
                if (lines.isEmpty()) continue
                builder.accept(page, lines)
            }
        } finally {
            reader.close()
        }
        return builder.finish()
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(WHITESPACE, "")
            .replace(PUNCT, "")

    companion object {
        const val LOW_CONFIDENCE = 30
        const val ANCHORED_CONFIDENCE = 90
        const val SCAN_CONFIDENCE = 60

        const val PHASE_OUTLINE = "解析内置书签"
        const val PHASE_OCR = "OCR 识别页眉"
        const val PHASE_SCAN = "OCR 扫描章节标题"
        const val PHASE_TEXT = "读取 PDF 文字层"
        /** 从文字层每页取前几行喂给章节判定（页眉通常在前两行，留点余量） */
        private const val TEXT_LAYER_LINES = 6

        /** OCR 渲染宽度：太低中文识别率掉得快，太高整本书要多花好几分钟 */
        private const val OCR_WIDTH = 720
        private const val TOP_FRACTION = 0.2f
        private const val TOC_PAGE_HITS = 3
        private const val MIN_NEEDLE = 4

        private val WHITESPACE = Regex("\\s+")
        private val PUNCT = Regex("[\\p{Punct}。，、；：？！“”‘’（）《》【】…—·]")

        private val FRONT_WORDS = listOf(
            "封面", "扉页", "版权", "目录", "简介", "前言", "序言", "出版说明", "编者的话",
            "cover", "title page", "copyright", "contents", "introduction", "preface", "foreword",
            "before reading", "about the author", "人物", "地点",
        )
        private val BACK_WORDS = listOf(
            "习题", "练习", "词汇表", "参考译文", "译文", "读后活动", "学习指导", "答案", "封底",
            "exercises", "glossary", "appendix", "after reading", "answer", "learning guide",
            "translation", "notes",
        )
    }
}
