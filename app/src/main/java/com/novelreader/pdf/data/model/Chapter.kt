package com.novelreader.pdf.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 节点类型：书虫那种 16 本打包的合集，必须区分"书/组"与"章"才能正确显示与折叠 */
enum class ChapterKind {
    /** 分组节点（一本书、一个 Part），自身不是可读章节，但有页码范围 */
    GROUP,

    /** 正文章节 */
    CHAPTER,

    /** 前置内容：封面 / 扉页 / 版权页 / 目录 / 简介 / 前言 */
    FRONT,

    /** 后置内容：习题 / 词汇表 / 参考译文 / 封底 */
    BACK,

    UNKNOWN,
}

/**
 * 章节实体（层级化）。
 *
 * 为什么必须层级化：书虫 4 级合集是 **16 本书塞进一个 2278 页 PDF**，506 条大纲分 3 层。
 * 扁平列表在这种书上既不能折叠、也没法告诉用户"你现在在第 3 本书的第 5 章"。
 *
 * [index] 是**大纲遍历顺序**，直接决定抽屉里的显示顺序；
 * [startPage] 的顺序**不保证**与 [index] 一致——calibre 从 EPUB 生成的 PDF 大纲，
 * 书签锚点是 EPUB 的逻辑页，与 PDF 重排后的实际页系统性错位（实测这本书偏移 +4 到 +61）。
 * 因此"按页码找当前章节"绝不能假设列表有序，必须走 [ChapterResolver.PageIndex] 的二分索引。
 */
@Serializable
data class Chapter(
    val index: Int,
    val parentIndex: Int = -1,
    val level: Int = 0,
    val kind: ChapterKind = ChapterKind.CHAPTER,
    val title: String,
    val titleEn: String? = null,
    /** 0 基起始页 */
    val startPage: Int,
    /** 0 基结束页（含）；[UNRESOLVED] 表示待计算 */
    val endPage: Int = UNRESOLVED,
    /** 页码可信度 0..100：内置大纲 100，OCR 重锚定按匹配质量给分 */
    val confidence: Int = 100,
) {
    val isGroup: Boolean get() = kind == ChapterKind.GROUP

    val isResolved: Boolean get() = endPage >= startPage

    val pageCount: Int get() = if (isResolved) endPage - startPage + 1 else 0

    fun containsPage(page: Int): Boolean = isResolved && page in startPage..endPage

    companion object {
        const val UNRESOLVED = -1
        const val ROOT = -1
    }
}

/**
 * 预设 / 导出的 JSON 目录文件结构。
 *
 * ```json
 * {
 *   "bookId": "3f2a…",
 *   "pageNumberBase": 1,
 *   "chapters": [
 *     { "titleZh": "巴斯克维尔猎犬", "level": 0, "kind": "GROUP", "startPage": 14 },
 *     { "titleZh": "1 这宗案件开始了", "titleEn": "1 The Case Begins", "level": 1, "startPage": 19 }
 *   ]
 * }
 * ```
 *
 * [pageNumberBase] 是刻意加的显式约定：1 表示与阅读器显示的页码一致，0 表示 0 基。
 * 少了这个字段，整本目录会整体偏移一页，而且极难排查。
 */
@Serializable
data class ChapterConfig(
    val bookId: String? = null,
    val title: String? = null,
    val pageNumberBase: Int = 1,
    val source: String? = null,
    val chapters: List<ChapterEntry> = emptyList(),
)

@Serializable
data class ChapterEntry(
    val title: String? = null,
    val titleZh: String? = null,
    val titleEn: String? = null,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val level: Int = 0,
    /** 可选：显式指定父节点在 chapters 数组中的下标 */
    val parentIndex: Int? = null,
    /** GROUP / CHAPTER / FRONT / BACK，缺省按"有没有子节点"推断 */
    val kind: String? = null,
    val confidence: Int = 100,
) {
    fun resolvedTitle(): String =
        titleZh?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: titleEn?.takeIf { it.isNotBlank() }
            ?: "未命名章节"

    fun resolvedKind(hasChildren: Boolean): ChapterKind =
        kind?.let { runCatching { ChapterKind.valueOf(it.uppercase()) }.getOrNull() }
            ?: if (hasChildren) ChapterKind.GROUP else ChapterKind.CHAPTER
}

/** 章节列表的通用计算与索引工具 */
object ChapterResolver {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(Chapter.serializer())

    private const val MAX_DEPTH = 32

    // ---------------------------------------------------------------- 区间计算

    /**
     * 计算每章区间。**对乱序大纲安全**，且正确处理层级：
     * 1. 先只看**叶子**节点，按 startPage 升序推进：
     *    endPage = 下一个叶子的 startPage - 1（同页则收敛为 startPage，避免负区间）；
     * 2. 再由下往上把范围回推给分组节点（start = 子节点最小 start，end = 子节点最大 end）。
     */
    fun resolveRanges(chapters: List<Chapter>, totalPages: Int): List<Chapter> {
        if (chapters.isEmpty()) return chapters
        val n = chapters.size
        val lastPage = (totalPages - 1).coerceAtLeast(0)

        val hasChild = BooleanArray(n)
        chapters.forEach { c ->
            if (c.parentIndex in 0 until n) hasChild[c.parentIndex] = true
        }

        val startOf = IntArray(n) { chapters[it].startPage.coerceIn(0, lastPage) }
        val endOf = IntArray(n) { startOf[it] }

        val leaves = (0 until n).filter { !hasChild[it] }.sortedBy { startOf[it] }
        leaves.forEachIndexed { k, idx ->
            val next = leaves.getOrNull(k + 1)
            endOf[idx] = if (next != null) {
                (startOf[next] - 1).coerceAtLeast(startOf[idx])
            } else {
                lastPage.coerceAtLeast(startOf[idx])
            }
        }

        // 分组节点：层级深的先算，保证子节点范围已就绪
        (0 until n).sortedByDescending { chapters[it].level }.forEach { idx ->
            if (!hasChild[idx]) return@forEach
            val kids = (0 until n).filter { chapters[it].parentIndex == idx }
            if (kids.isEmpty()) return@forEach
            startOf[idx] = kids.minOf { startOf[it] }
            endOf[idx] = kids.maxOf { endOf[it] }
        }

        return chapters.mapIndexed { i, c -> c.copy(startPage = startOf[i], endPage = endOf[i]) }
    }

    fun childrenOf(chapters: List<Chapter>, parentIndex: Int): List<Chapter> =
        chapters.filter { it.parentIndex == parentIndex }

    /** 祖先链（不含自身），由外到内 */
    fun ancestorsOf(chapters: List<Chapter>, index: Int): List<Chapter> {
        val out = ArrayList<Chapter>()
        var cur = chapters.getOrNull(index)?.parentIndex ?: Chapter.ROOT
        var guard = 0
        while (cur in chapters.indices && guard++ < MAX_DEPTH) {
            out.add(chapters[cur])
            cur = chapters[cur].parentIndex
        }
        return out.asReversed()
    }

    // ---------------------------------------------------------------- 页码索引

    /**
     * 页码 → 章节 的索引器。
     *
     * 内部维护"叶子节点按 startPage 升序"的副本做二分，因此**大纲乱序也不会算错**。
     * 这是修掉旧实现（直接在原始列表上二分）缺陷的关键。
     */
    class PageIndex(private val chapters: List<Chapter>) {

        private val leafIndices: IntArray
        private val leafStarts: IntArray

        init {
            val n = chapters.size
            val hasChild = BooleanArray(n)
            chapters.forEach { c -> if (c.parentIndex in 0 until n) hasChild[c.parentIndex] = true }
            val leaves = (0 until n).filter { !hasChild[it] }.sortedBy { chapters[it].startPage }
            leafIndices = leaves.toIntArray()
            leafStarts = IntArray(leaves.size) { chapters[leaves[it]].startPage }
        }

        val isEmpty: Boolean get() = leafIndices.isEmpty()

        /** 返回章节在列表中的下标；空目录返回 -1。页码在第一章之前时归第一章 */
        fun indexAtPage(page: Int): Int {
            if (leafIndices.isEmpty()) return -1
            var lo = 0
            var hi = leafIndices.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (leafStarts[mid] <= page) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return leafIndices[if (ans < 0) 0 else ans]
        }

        fun chapterAt(page: Int): Chapter? = chapters.getOrNull(indexAtPage(page))

        /** 从根到当前章节的路径，用于顶栏面包屑与"所在书目"显示 */
        fun pathAt(page: Int): List<Chapter> {
            val idx = indexAtPage(page)
            if (idx < 0) return emptyList()
            return ancestorsOf(chapters, idx) + chapters[idx]
        }
    }

    // ---------------------------------------------------------------- JSON

    fun encodeChapters(chapters: List<Chapter>): String = json.encodeToString(listSerializer, chapters)

    fun decodeChapters(raw: String): List<Chapter> =
        runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())

    fun encodeConfig(config: ChapterConfig): String =
        json.encodeToString(ChapterConfig.serializer(), config)

    fun decodeConfig(raw: String): ChapterConfig? =
        runCatching { json.decodeFromString(ChapterConfig.serializer(), raw) }.getOrNull()

    /**
     * 配置 → 章节列表。`parentIndex` 显式给出就用它；否则用 `level` 栈推出来
     * （手写 JSON 时写 level 比写下标友好得多）。
     */
    fun toChapters(config: ChapterConfig, totalPages: Int): List<Chapter> {
        val entries = config.chapters
        if (entries.isEmpty()) return emptyList()
        val base = if (config.pageNumberBase == 0) 0 else 1
        val lastPage = (totalPages - 1).coerceAtLeast(0)

        val parentOf = IntArray(entries.size) { Chapter.ROOT }
        val stack = HashMap<Int, Int>()
        entries.forEachIndexed { i, e ->
            val level = e.level.coerceAtLeast(0)
            parentOf[i] = e.parentIndex?.takeIf { it in entries.indices }
                ?: if (level == 0) Chapter.ROOT else (stack[level - 1] ?: Chapter.ROOT)
            stack[level] = i
            stack.keys.filter { it > level }.forEach { stack.remove(it) }
        }

        val hasChild = BooleanArray(entries.size)
        parentOf.forEach { p -> if (p >= 0) hasChild[p] = true }

        val raw = entries.mapIndexed { i, e ->
            val start = ((e.startPage ?: (base + i)) - base).coerceIn(0, lastPage)
            Chapter(
                index = i,
                parentIndex = parentOf[i],
                level = e.level.coerceAtLeast(0),
                kind = e.resolvedKind(hasChild[i]),
                title = e.resolvedTitle(),
                titleEn = e.titleEn?.takeIf { it.isNotBlank() && it != e.resolvedTitle() },
                startPage = start,
                endPage = e.endPage?.let { (it - base).coerceIn(start, lastPage) } ?: Chapter.UNRESOLVED,
                confidence = e.confidence,
            )
        }
        return resolveRanges(raw, totalPages)
    }

    /** 导出为可人工校对的 JSON（页码转回 1 基） */
    fun toConfig(
        bookId: String,
        title: String,
        chapters: List<Chapter>,
        source: TocSource = TocSource.LOCAL_JSON,
    ): ChapterConfig = ChapterConfig(
        bookId = bookId,
        title = title,
        pageNumberBase = 1,
        source = source.name,
        chapters = chapters.map {
            ChapterEntry(
                titleZh = it.title,
                titleEn = it.titleEn,
                startPage = it.startPage + 1,
                endPage = if (it.isResolved) it.endPage + 1 else null,
                level = it.level,
                parentIndex = it.parentIndex,
                kind = it.kind.name,
                confidence = it.confidence,
            )
        },
    )
}
