package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.BookSummary
import com.novelreader.pdf.data.model.ChapterConfig

/**
 * 批量导入目录时，把一份 JSON 对应到书架上的一本书。
 *
 * 为什么需要它：导出的 JSON 里 `bookId` 是空的（那是**导出时**才填的字段），
 * 而书架上书名带着来源后缀，两边的字符串并不相等：
 *
 * ```
 * JSON 里的书名   ：剑桥小说馆2级17 魔幻手机
 * 书架上的书名   ：剑桥小说馆2级17 魔幻手机 (未知) (z-library.sk, 1lib.sk, z-lib.sk)
 * ```
 *
 * 所以匹配按三级降级：bookId → 书名精确相等 → 归一化后互相包含。
 * 纯逻辑、无 Android 依赖，可直接单元测试。
 */
object TocImportMatcher {

    /** 归一化：只留字母、数字与汉字，丢掉空格与标点（含全角括号、书名号等） */
    fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 找这本书。找不到返回 null（调用方会把它列进"未匹配"里让用户看到）。
     *
     * [minContainLength] 是"包含匹配"要求的最短归一化长度：
     * 太短的字符串（比如书名只有"上册"两个字）容易误配到别的书上。
     */
    fun match(config: ChapterConfig, books: List<BookSummary>, minContainLength: Int = 4): BookSummary? {
        // 1) 导出时填过 bookId 的（App 自己导出的文件）——精确且无歧义
        config.bookId?.takeIf { it.isNotBlank() }?.let { id ->
            books.firstOrNull { it.bookId == id }?.let { return it }
        }

        val title = config.title?.trim().orEmpty()
        if (title.isEmpty()) return null

        // 2) 书名完全相等
        books.firstOrNull { it.title.trim() == title }?.let { return it }

        // 3) 归一化后互相包含
        val key = normalize(title)
        if (key.length < minContainLength) return null
        return books.firstOrNull { normalize(it.title).contains(key) }
    }
}
