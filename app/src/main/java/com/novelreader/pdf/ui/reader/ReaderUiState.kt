package com.novelreader.pdf.ui.reader

import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterResolver
import com.novelreader.pdf.data.model.TocSource
import com.novelreader.pdf.data.toc.TocTaskState

/**
 * 阅读器单一状态源。所有交互先落到这里，Activity 只负责把状态渲染到 ViewPager2 与控件上。
 */
data class ReaderUiState(
    val book: BookRef,
    /** 断点进度是否已读出（读出前不建引擎，避免先闪第 1 页） */
    val progressLoaded: Boolean = false,
    val initialPage: Int = 0,
    val documentReady: Boolean = false,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    val chaptersLoading: Boolean = false,
    val tocSource: TocSource = TocSource.NONE,
    val currentChapterIndex: Int = -1,
    /** 顶/底控件是否可见（沉浸式：默认隐藏） */
    val controlsVisible: Boolean = false,
    /** 用户正在拖进度条：期间忽略页面回调，避免进度条抖动 */
    val seekBarDragging: Boolean = false,
    val error: String? = null,
    val tocTask: TocTaskState = TocTaskState.IDLE,
) {
    val currentChapter: Chapter? get() = chapters.getOrNull(currentChapterIndex)

    /** 从根到当前章节的路径，用于顶栏面包屑（书虫合集里能显示"在哪本书里"） */
    val currentPath: List<Chapter>
        get() = if (currentChapterIndex < 0) {
            emptyList()
        } else {
            ChapterResolver.ancestorsOf(chapters, currentChapterIndex) + chapters[currentChapterIndex]
        }

    val headerTitle: String
        get() = currentChapter?.title?.takeIf { it.isNotBlank() } ?: book.title

    /** 面包屑：祖先分组 › 英文副标题 */
    val headerSubtitle: String?
        get() {
            val parts = mutableListOf<String>()
            val path = currentPath
            if (path.size > 1) parts += path.dropLast(1).map { it.title }
            currentChapter?.titleEn?.takeIf { it.isNotBlank() }?.let { parts += it }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" › ")
        }

    /** 没有面包屑也没有英文名时的兜底：书名 · 所在书目 */
    val headerFallback: String
        get() {
            val group = currentPath.dropLast(1).lastOrNull()
            return if (group != null) "${book.title} · ${group.title}" else book.title
        }

    val displayPage: Int get() = currentPage + 1

    val progressPercent: Int
        get() = if (totalPages <= 0) 0 else (((currentPage + 1) * 100f) / totalPages).toInt()

    val hasPrevChapter: Boolean get() = chapters.isNotEmpty() && currentChapterIndex > 0

    val hasNextChapter: Boolean
        get() = chapters.isNotEmpty() && currentChapterIndex in 0 until chapters.lastIndex

    val tocSourceLabel: String get() = TocSource.label(tocSource)
}

/** 一次性事件（导航 / 提示），用 Channel 投递，配置变更时不重放 */
sealed interface ReaderEvent {
    data class JumpToPage(val page: Int, val smooth: Boolean = true) : ReaderEvent
    data class Message(val text: String) : ReaderEvent
    data class ChaptersExported(val path: String) : ReaderEvent
    data object OpenChapterList : ReaderEvent
    data object Finish : ReaderEvent
}
