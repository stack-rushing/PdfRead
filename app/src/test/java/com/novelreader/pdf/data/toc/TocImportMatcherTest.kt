package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.BookSummary
import com.novelreader.pdf.data.model.ChapterConfig
import com.novelreader.pdf.data.model.ChapterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批量导入目录时"这份 JSON 是哪本书的"这个判断。
 *
 * 真实场景：导出的 JSON 里书名是干净的（`剑桥小说馆2级17 魔幻手机`），
 * 而书架上的书名带着来源后缀（`… (未知) (z-library.sk, 1lib.sk, z-lib.sk)`），
 * 两边字符串**并不相等**——只做精确匹配的话，我生成的那批 JSON 一个都导入不进去。
 */
class TocImportMatcherTest {

    private fun book(id: String, title: String) = BookSummary(bookId = id, uri = "content://x/$id", title = title)

    private val shelf = listOf(
        book("a1", "剑桥小说馆2级07 圆圈故事集 (未知) (z-library.sk, 1lib.sk, z-lib.sk)"),
        book("a2", "剑桥小说馆2级16 阿姆斯特丹风波 (未知) (z-library.sk, 1lib.sk, z-lib.sk)"),
        book("a3", "剑桥小说馆2级17 魔幻手机 (未知) (z-library.sk, 1lib.sk, z-lib.sk)"),
        book("a4", "书虫·牛津英汉双语读物(第4级全)(套装共16本)"),
    )

    private fun config(title: String?, bookId: String? = null) = ChapterConfig(
        bookId = bookId,
        title = title,
        chapters = listOf(ChapterEntry(title = "Chapter 1", startPage = 1)),
    )

    @Test
    fun `书名带来源后缀也能匹配上`() {
        assertEquals("a3", TocImportMatcher.match(config("剑桥小说馆2级17 魔幻手机"), shelf)?.bookId)
        assertEquals("a1", TocImportMatcher.match(config("剑桥小说馆2级07 圆圈故事集"), shelf)?.bookId)
        assertEquals("a4", TocImportMatcher.match(config("书虫·牛津英汉双语读物（第4级全）"), shelf)?.bookId)
    }

    @Test
    fun `有 bookId 时以它为准`() {
        // 书名故意写错，但 bookId 对得上 → 认 bookId
        assertEquals("a2", TocImportMatcher.match(config("随便写的书名", bookId = "a2"), shelf)?.bookId)
    }

    @Test
    fun `完全相等的书名走精确分支`() {
        assertEquals("a4", TocImportMatcher.match(config(shelf[3].title), shelf)?.bookId)
    }

    @Test
    fun `不会把这本书的目录配到另一本上`() {
        // "魔幻手机" 与 "阿姆斯特丹风波" 毫无交集，绝不该互相配上
        val only = listOf(book("b1", "剑桥小说馆2级16 阿姆斯特丹风波 (未知)"))
        assertNull(TocImportMatcher.match(config("剑桥小说馆2级17 魔幻手机"), only))
    }

    @Test
    fun `书名为空或过短时不猜`() {
        assertNull(TocImportMatcher.match(config(null), shelf))
        assertNull(TocImportMatcher.match(config(""), shelf))
        // 归一化后只有 2 个字符，低于下限，宁可报"未匹配"也不乱配
        assertNull(TocImportMatcher.match(config("上册"), shelf))
    }

    @Test
    fun `归一化会丢掉空格标点与全角括号`() {
        assertEquals("剑桥小说馆2级17魔幻手机", TocImportMatcher.normalize("剑桥小说馆2级17 魔幻手机"))
        assertEquals("abc123", TocImportMatcher.normalize("A B C 1-2-3"))
        assertEquals("书虫牛津英汉双语读物第4级全套装共16本", TocImportMatcher.normalize("书虫·牛津英汉双语读物(第4级全)(套装共16本)"))
    }
}
