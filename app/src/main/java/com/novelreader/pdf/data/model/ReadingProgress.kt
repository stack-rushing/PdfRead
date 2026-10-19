package com.novelreader.pdf.data.model

import kotlinx.serialization.Serializable

/**
 * 阅读进度 —— 断点续读的载体。
 *
 * 页码约定：**全程 0 基**，只在 UI 展示时 +1。页码进制混用是断点续读最常见的 bug 源。
 */
@Serializable
data class ReadingProgress(
    val bookId: String,
    val bookUri: String,
    val title: String,
    val pageIndex: Int = 0,
    val totalPages: Int = 0,
    val chapterIndex: Int = INDEX_NONE,
    val updatedAt: Long = 0L,
) {
    val percent: Float
        get() = if (totalPages <= 0) 0f else (pageIndex + 1).toFloat() / totalPages.toFloat()

    val displayPage: Int get() = pageIndex + 1

    val isFinished: Boolean get() = totalPages > 0 && pageIndex >= totalPages - 1

    companion object {
        const val INDEX_NONE = -1
    }
}

/** 目录来源：决定 UI 上给用户什么交代，也决定要不要重跑生成 */
enum class TocSource {
    /** 还没生成过 */
    NONE,

    /** 纯 Kotlin 自研解析器读出的 PDF 内置书签 */
    PDF_OUTLINE,

    /** OCR 扫描页眉后重锚定过页码的目录 */
    OCR,

    /** 从 PDF **文字层**读出的目录：有文字层的书又快又准，不依赖扫描质量 */
    TEXT_LAYER,

    /** 用户手改 / 导入的 JSON（优先级最高，永远不被自动结果覆盖） */
    LOCAL_JSON,

    /** 随 APK 预置的 assets 配置 */
    ASSET_JSON,

    /** 人为按固定页数切分 */
    MANUAL,
    ;

    companion object {
        fun from(name: String?): TocSource = entries.firstOrNull { it.name == name } ?: NONE

        fun label(source: TocSource): String = when (source) {
            NONE -> "未生成"
            PDF_OUTLINE -> "PDF 内置书签"
            OCR -> "OCR 识别"
            TEXT_LAYER -> "PDF 文字层"
            LOCAL_JSON -> "本地 JSON"
            ASSET_JSON -> "预置配置"
            MANUAL -> "手动指定"
        }
    }
}

/** 书架条目：books 表在 UI 层的投影 */
@Serializable
data class BookSummary(
    val bookId: String,
    val uri: String,
    val title: String,
    val localPath: String? = null,
    val pageIndex: Int = 0,
    val totalPages: Int = 0,
    val chapterIndex: Int = ReadingProgress.INDEX_NONE,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tocSource: TocSource = TocSource.NONE,
    val tocGeneratedAt: Long = 0L,
) {
    val percent: Float
        get() = if (totalPages <= 0) 0f else (pageIndex + 1).toFloat() / totalPages.toFloat()

    val displayPage: Int get() = pageIndex + 1
}
