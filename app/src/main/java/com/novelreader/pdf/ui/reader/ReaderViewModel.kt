package com.novelreader.pdf.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterResolver
import com.novelreader.pdf.data.model.ReadingProgress
import com.novelreader.pdf.data.model.TocSource
import com.novelreader.pdf.data.repository.LibraryRepository
import com.novelreader.pdf.data.repository.TocRepository
import com.novelreader.pdf.data.toc.TocMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读器 ViewModel：断点续读 + 翻页防抖 + 章节状态计算 + 目录生成调度。
 *
 * 数据流是单向的：Room 是唯一真相源，章节列表从 [LibraryRepository.observeChapters] 流入，
 * 目录生成完成后不需要任何"回传"，抽屉会自动刷新。
 *
 * 落盘策略（三层，缺一不可）：
 * 1. **翻页防抖**：连续翻 20 页只产生 1 次写库；
 * 2. **确定性操作立即写**：跳章节、拖完进度条、文档加载完成；
 * 3. **生命周期兜底**：onPause / onStop / onCleared 调 [flush]，
 *    内部走 [LibraryRepository.saveProgressNow]（Application 作用域），
 *    因此 Activity 正在销毁、viewModelScope 即将取消时这次写入也一定写得完。
 */
@OptIn(FlowPreview::class)
class ReaderViewModel(
    private val library: LibraryRepository,
    private val tocRepository: TocRepository,
    private val book: BookRef,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState(book = book))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events: Flow<ReaderEvent> = _events.receiveAsFlow()

    private val pageChanges = MutableSharedFlow<ReadingProgress>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 按页码二分定位当前章节；由 chapters 变化时重建，避免每次翻页都排序 */
    private var pageIndex: ChapterResolver.PageIndex? = null

    private var lastPersisted: ReadingProgress? = null

    /**
     * 首帧同步守卫：PDF 渲染完成后 ViewPager2 可能先回调一次 page 0，
     * 而此时我们已用 setCurrentItem 定位到断点页。不丢弃这次回调，断点会被覆盖成第 1 页。
     */
    private var awaitingInitialSync = true

    init {
        observeDebouncedPersistence()
        observeChapters()
        observeTocTask()
        restoreProgress()
    }

    // ------------------------------------------------------------------ 初始化

    private fun observeTocTask() {
        viewModelScope.launch {
            tocRepository.observeTask(book.bookId).collect { task ->
                _uiState.update { it.copy(tocTask = task) }
            }
        }
    }

    fun setControlsVisible(visible: Boolean) = _uiState.update { it.copy(controlsVisible = visible) }

    private fun observeDebouncedPersistence() {
        viewModelScope.launch {
            pageChanges
                .buffer(Channel.UNLIMITED)
                .debounce(PERSIST_DEBOUNCE_MS)
                .collect { progress ->
                    // 乱序保护：flush() 可能已写入更新的快照，排队中的旧快照必须丢弃，
                    // 否则会把进度"倒回去"（例如先翻到第 10 页又立刻跳到第 50 页）。
                    val previous = lastPersisted
                    if (previous != null && progress.updatedAt < previous.updatedAt) return@collect
                    lastPersisted = progress
                    library.saveProgress(progress)
                }
        }
    }

    private fun observeChapters() {
        viewModelScope.launch {
            library.observeChapters(book.bookId).collect { list ->
                applyChapters(list)
            }
        }
    }
    private fun restoreProgress() {
        viewModelScope.launch {
            val saved = library.findBook(book.bookId)
            val start = (saved?.pageIndex ?: 0).coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    progressLoaded = true,
                    initialPage = start,
                    currentPage = start,
                    totalPages = saved?.totalPages ?: 0,
                    tocSource = saved?.tocSource ?: TocSource.NONE,
                )
            }
        }
    }

    private fun applyChapters(list: List<Chapter>) {
        val totalPages = _uiState.value.totalPages
        val resolved = ChapterResolver.resolveRanges(list, totalPages)
        val index = ChapterResolver.PageIndex(resolved)
        pageIndex = index
        _uiState.update { state ->
            state.copy(
                chapters = resolved,
                chaptersLoading = false,
                currentChapterIndex = index.indexAtPage(state.currentPage),
            )
        }
    }

    // ------------------------------------------------------------------ PDF 生命周期

    fun onDocumentReady(pageCount: Int) {
        if (pageCount <= 0) {
            _uiState.update { it.copy(error = "PDF 页数异常（$pageCount），文件可能已损坏") }
            return
        }
        val state = _uiState.value
        val start = state.initialPage.coerceIn(0, pageCount - 1)
        _uiState.update {
            it.copy(
                documentReady = true,
                totalPages = pageCount,
                currentPage = start,
                initialPage = start,
                error = null,
            )
        }
        emit(ReaderEvent.JumpToPage(start, smooth = false))

        // 页数确定后：重算章节区间 + 落一次盘（让书架显示总页数）
        applyChapters(_uiState.value.chapters)
        flush()

        // 首次打开且数据库里还没有目录 → 先跑"便宜"的那条路（本地 JSON / 预置 / 内置书签），
        // 不自动跑 OCR：那可能要一两分钟，必须由用户显式触发。
        viewModelScope.launch {
            if (_uiState.value.chapters.isEmpty()) {
                _uiState.update { it.copy(chaptersLoading = true) }
                val source = tocRepository.ensureChapters(book, pageCount)
                if (source == TocSource.NONE) {
                    _uiState.update { it.copy(chaptersLoading = false) }
                    emit(ReaderEvent.Message("未找到目录：可在目录抽屉里「自动生成目录」"))
                }
            }
        }
    }

    fun onDocumentError(message: String?) {
        _uiState.update { it.copy(error = message ?: "PDF 打开失败") }
    }

    /** 页面变化回调：只更新内存状态 + 投递防抖事件，绝不在这里直接写库 */
    fun onPageChanged(page: Int) {
        val state = _uiState.value
        if (!state.documentReady || page < 0) return
        if (state.seekBarDragging) return

        if (awaitingInitialSync) {
            awaitingInitialSync = false
            if (page == 0 && state.initialPage > 0) return
        }
        if (page == state.currentPage && state.totalPages > 0) return

        _uiState.update {
            it.copy(
                currentPage = page,
                currentChapterIndex = pageIndex?.indexAtPage(page) ?: -1,
            )
        }
        schedulePersist()
    }

    // ------------------------------------------------------------------ 翻页 / 章节导航

    fun nextPage() = jumpToPage(_uiState.value.currentPage + 1, smooth = true, persistNow = true)

    fun prevPage() = jumpToPage(_uiState.value.currentPage - 1, smooth = true, persistNow = true)

    fun nextChapter() {
        val state = _uiState.value
        val next = state.chapters.getOrNull(state.currentChapterIndex + 1) ?: return
        jumpToPage(next.startPage, smooth = true, persistNow = true)
    }

    /**
     * 上一章：已经翻到本章中段时先回到本章开头，已在章首才真正跳到上一章。
     * 这是小说阅读器的通用手感。
     */
    fun prevChapter() {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        val idx = state.currentChapterIndex
        val targetIndex = when {
            idx < 0 -> 0
            state.currentPage > state.chapters[idx].startPage -> idx
            else -> (idx - 1).coerceAtLeast(0)
        }
        jumpToPage(state.chapters[targetIndex].startPage, smooth = true, persistNow = true)
    }

    fun selectChapter(chapterIndex: Int) {
        val chapter = _uiState.value.chapters.getOrNull(chapterIndex) ?: return
        jumpToPage(chapter.startPage, smooth = false, persistNow = true)
    }

    fun jumpToPage(page: Int, smooth: Boolean = true, persistNow: Boolean = false) {
        val state = _uiState.value
        val target = page.coerceIn(0, (state.totalPages - 1).coerceAtLeast(0))
        _uiState.update {
            it.copy(
                currentPage = target,
                currentChapterIndex = pageIndex?.indexAtPage(target) ?: -1,
            )
        }
        emit(ReaderEvent.JumpToPage(target, smooth))
        if (persistNow) flush()
    }

    // ------------------------------------------------------------------ 进度条

    fun onSeekBarDragStart() = _uiState.update { it.copy(seekBarDragging = true) }

    fun onSeekBarPreview(percent: Int) {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val page = ((percent.coerceIn(0, 100) / 100f) * (state.totalPages - 1)).toInt()
        _uiState.update {
            it.copy(
                currentPage = page,
                currentChapterIndex = pageIndex?.indexAtPage(page) ?: -1,
            )
        }
    }

    fun onSeekBarCommit(percent: Int) {
        val state = _uiState.value
        _uiState.update { it.copy(seekBarDragging = false) }
        if (state.totalPages <= 0) return
        val page = ((percent.coerceIn(0, 100) / 100f) * (state.totalPages - 1)).toInt()
        jumpToPage(page, smooth = false, persistNow = true)
    }

    // ------------------------------------------------------------------ 控件 / 夜间模式

    fun toggleControls() = _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }
    // ------------------------------------------------------------------ 目录

    fun openChapterList() = emit(ReaderEvent.OpenChapterList)

    /** 触发目录生成；任务跑在 Application 作用域，离开页面也不会中断，结果由 Room 回流 */
    fun generateToc(mode: TocMode) {
        val state = _uiState.value
        if (state.totalPages <= 0) {
            emit(ReaderEvent.Message("PDF 还没加载完，稍后再试"))
            return
        }
        tocRepository.startGenerate(book, state.totalPages, mode)
    }

    fun cancelToc() = tocRepository.cancel()

    /** 清掉自动目录（用于回到"未生成"状态，重新走一遍） */
    fun clearToc() {
        viewModelScope.launch {
            library.clearChapters(book.bookId)
            emit(ReaderEvent.Message("已清除目录"))
        }
    }

    fun exportChapters() {
        val state = _uiState.value
        if (state.chapters.isEmpty()) {
            emit(ReaderEvent.Message("当前没有可导出的章节"))
            return
        }
        viewModelScope.launch {
            val file = tocRepository.exportToJson(book, state.chapters, state.tocSource)
            emit(ReaderEvent.ChaptersExported(file.absolutePath))
        }
    }

    fun localChapterConfigPath(): String = tocRepository.localOverridePath(book.bookId)

    fun importJsonFromDisk(): Boolean = tocRepository.hasLocalOverride(book.bookId)

    // ------------------------------------------------------------------ 落盘

    private fun snapshot(): ReadingProgress? {
        val state = _uiState.value
        if (state.totalPages <= 0 && state.currentPage <= 0) return null
        return ReadingProgress(
            bookId = book.bookId,
            bookUri = book.uri,
            title = book.title,
            pageIndex = state.currentPage,
            totalPages = state.totalPages,
            chapterIndex = state.currentChapterIndex,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun schedulePersist() {
        snapshot()?.let { pageChanges.tryEmit(it) }
    }

    /**
     * 立即落盘。onPause / onStop / onCleared 都会调用；走 Application 作用域，
     * 不受 viewModelScope 取消影响。
     */
    fun flush() {
        val snapshot = snapshot() ?: return
        val previous = lastPersisted
        if (previous != null &&
            previous.pageIndex == snapshot.pageIndex &&
            previous.totalPages == snapshot.totalPages &&
            previous.chapterIndex == snapshot.chapterIndex
        ) {
            return
        }
        lastPersisted = snapshot
        library.saveProgressNow(snapshot)
        // 同时把最新快照补进防抖流水线：重置待触发的计时器，后续那次延迟写入与这里一致（幂等）
        pageChanges.tryEmit(snapshot)
    }

    override fun onCleared() {
        flush()
        // 目录生成故意**不**在这里取消：它跑在 Application 作用域，退出阅读器也让它跑完，
        // 结果会落进 Room，下次打开直接可用。
        super.onCleared()
    }

    private fun emit(event: ReaderEvent) {
        _events.trySend(event)
    }

    companion object {
        const val PERSIST_DEBOUNCE_MS = 800L
    }
}
