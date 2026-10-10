package com.novelreader.pdf.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节层级 / 乱序区间 / 页码定位 的回归测试。
 *
 * 这些不变式是用真实的书虫合集（506 条大纲、396 条页码逆序）验出来的，
 * 换成扁平列表二分的老实现会全部失败——所以它们值得固化成测试。
 */
class ChapterResolverTest {

    private fun chapter(
        index: Int,
        title: String,
        start: Int,
        parent: Int = -1,
        level: Int = 0,
        kind: ChapterKind = ChapterKind.CHAPTER,
    ) = Chapter(index = index, parentIndex = parent, level = level, kind = kind, title = title, startPage = start)

    /** 刻意打乱的大纲顺序：大纲里的第 3 条，实际页码最小 */
    private val shuffled = listOf(
        chapter(0, "组A", start = 50, kind = ChapterKind.GROUP),
        chapter(1, "大纲第3条/实际最后", start = 80, parent = 0, level = 1),
        chapter(2, "大纲第1条/实际最先", start = 10, parent = 0, level = 1),
        chapter(3, "大纲第2条", start = 30, parent = 0, level = 1),
    )

    @Test
    fun `乱序大纲也能算出单调区间`() {
        val resolved = ChapterResolver.resolveRanges(shuffled, totalPages = 100)
        val leaves = resolved.filter { !it.isGroup }.sortedBy { it.startPage }

        assertEquals(listOf("大纲第1条/实际最先", "大纲第2条", "大纲第3条/实际最后"), leaves.map { it.title })
        assertEquals(10, leaves[0].startPage)
        assertEquals(29, leaves[0].endPage)
        assertEquals(30, leaves[1].startPage)
        assertEquals(79, leaves[1].endPage)
        assertEquals(80, leaves[2].startPage)
        assertEquals(99, leaves[2].endPage)
        leaves.forEach { assertTrue("${it.title} 区间不合法", it.endPage >= it.startPage) }
    }

    @Test
    fun `分组区间由子节点回推并完整包住它们`() {
        val resolved = ChapterResolver.resolveRanges(shuffled, totalPages = 100)
        val group = resolved.first { it.isGroup }
        assertEquals(10, group.startPage)
        assertEquals(99, group.endPage)
        resolved.filter { it.parentIndex == group.index }.forEach {
            assertTrue("${group.title} 未包住 ${it.title}", it.startPage >= group.startPage && it.endPage <= group.endPage)
        }
    }

    @Test
    fun `同页并列时取最后一个且不产生非法区间`() {
        val same = listOf(
            chapter(0, "封面", start = 13),
            chapter(1, "扉页", start = 13),
            chapter(2, "第一章", start = 14),
        )
        val resolved = ChapterResolver.resolveRanges(same, totalPages = 50)
        resolved.forEach { assertTrue("${it.title} 区间非法", it.endPage >= it.startPage) }
        assertEquals(13, resolved[0].startPage)
        assertEquals(13, resolved[0].endPage)
        // 同页并列的规约：页码定位取最后一个（更靠后的条目信息量更大）
        val index = ChapterResolver.PageIndex(resolved)
        assertEquals(1, index.indexAtPage(13))
        assertEquals(2, index.indexAtPage(14))
    }

    @Test
    fun `页码定位覆盖全书且边界安全`() {
        val resolved = ChapterResolver.resolveRanges(shuffled, totalPages = 100)
        val index = ChapterResolver.PageIndex(resolved)
        val leaves = resolved.indices.filter { !resolved[it].isGroup }.sortedBy { resolved[it].startPage }

        assertEquals(leaves.first(), index.indexAtPage(0))
        assertEquals(leaves.first(), index.indexAtPage(-100))
        assertEquals(leaves.last(), index.indexAtPage(99))
        assertEquals(leaves.last(), index.indexAtPage(1_000_000))
        for (page in 0 until 100) {
            val hit = index.indexAtPage(page)
            assertTrue("页 ${page + 1} 未被任何章节认领", hit in resolved.indices)
        }
    }

    @Test
    fun `面包屑路径按层级由外到内`() {
        val nested = listOf(
            chapter(0, "第4级上", start = 3, kind = ChapterKind.GROUP),
            chapter(1, "巴斯克维尔猎犬", start = 14, parent = 0, level = 1, kind = ChapterKind.GROUP),
            chapter(2, "1 The Case Begins", start = 19, parent = 1, level = 2),
        )
        val resolved = ChapterResolver.resolveRanges(nested, totalPages = 100)
        val path = ChapterResolver.PageIndex(resolved).pathAt(20).map { it.title }
        assertEquals(listOf("第4级上", "巴斯克维尔猎犬", "1 The Case Begins"), path)
    }

    @Test
    fun `JSON 往返保留层级与进制约定`() {
        val resolved = ChapterResolver.resolveRanges(shuffled, totalPages = 100)
        val config = ChapterResolver.toConfig("book-1", "测试书", resolved)
        assertEquals(1, config.pageNumberBase)  // 导出统一用 1 基，避免整本偏移一页
        val back = ChapterResolver.toChapters(config, totalPages = 100)
        assertEquals(resolved.size, back.size)
        assertEquals(resolved.map { it.title }, back.map { it.title })
        assertEquals(resolved.map { it.startPage }, back.map { it.startPage })
        assertEquals(resolved.map { it.parentIndex }, back.map { it.parentIndex })
        assertEquals(resolved.map { it.kind }, back.map { it.kind })
    }

    @Test
    fun `层级栈能从 level 推出父子关系`() {
        // 注意：push 父子关系是 toChapters(config) 的职责，resolveRanges 只负责算区间。
        // 这里就是通过 JSON 配置（手写时只写 level 最省事）来验证那个层级栈。
        val config = ChapterConfig(
            pageNumberBase = 1,
            chapters = listOf(
                ChapterEntry(titleZh = "卷", startPage = 1, level = 0, kind = "GROUP"),
                ChapterEntry(titleZh = "书", startPage = 2, level = 1, kind = "GROUP"),
                ChapterEntry(titleZh = "章", startPage = 3, level = 2),
                ChapterEntry(titleZh = "另一本", startPage = 9, level = 1, kind = "GROUP"),
                ChapterEntry(titleZh = "另一章", startPage = 10, level = 2),
            ),
        )
        val resolved = ChapterResolver.toChapters(config, totalPages = 20)
        assertEquals(-1, resolved[0].parentIndex)
        assertEquals(0, resolved[1].parentIndex)
        assertEquals(1, resolved[2].parentIndex)
        assertEquals(0, resolved[3].parentIndex)
        assertEquals(3, resolved[4].parentIndex)
        // 有子节点的自动判为 GROUP
        assertEquals(ChapterKind.GROUP, resolved[1].kind)
        assertEquals(ChapterKind.GROUP, resolved[3].kind)
    }
}
