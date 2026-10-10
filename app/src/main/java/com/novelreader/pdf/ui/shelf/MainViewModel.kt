package com.novelreader.pdf.ui.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.BookSummary
import com.novelreader.pdf.data.model.ChapterResolver
import com.novelreader.pdf.data.toc.TocImportMatcher
import com.novelreader.pdf.data.toc.TocMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelfUiState(
    val loading: Boolean = true,
    val books: List<BookSummary> = emptyList(),
    val importing: Boolean = false,
)

/** 批量任务的进度（货架中央的浮层用它显示"第 3 / 7 本 · 扫描页眉 45/102"） */
data class BatchState(
    val running: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val title: String = "",
    val phase: String = "",
    val done: Int = 0,
    val pages: Int = 0,
) {
    val label: String get() = if (total <= 0) "" else "第 $index / $total 本"
    val percent: Int get() = if (pages <= 0) 0 else (done * 100 / pages).coerceIn(0, 100)
}

/** 批量导入目录的结果，逐条回报，不吞错误 */
data class TocImportSummary(
    val imported: List<String> = emptyList(),
    val unmatched: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val handled: Int get() = imported.size + unmatched.size + failed.size
    val allOk: Boolean get() = unmatched.isEmpty() && failed.isEmpty()
}

sealed interface ShelfEvent {
    data class OpenBook(val book: BookRef) : ShelfEvent
    data class Message(val text: String) : ShelfEvent
    data class TocImported(val summary: TocImportSummary) : ShelfEvent
}

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    private val _events = Channel<ShelfEvent>(Channel.BUFFERED)
    val events: Flow<ShelfEvent> = _events.receiveAsFlow()

    private val _batch = MutableStateFlow(BatchState())
    val batch: StateFlow<BatchState> = _batch.asStateFlow()
    private var batchJob: Job? = null

    init {
        // 书架直接订阅 Room：从阅读器返回后进度/目录状态自动刷新，无需手动 reload
        viewModelScope.launch {
            container.libraryRepository.observeShelf().collect { books ->
                _state.update { it.copy(loading = false, books = books) }
            }
        }
    }

    /**
     * 导入：SAF uri → bookId（元数据/内容 MD5）→ 应用私有副本 → Room 登记。
     * 拷贝大文件期间用 importing 状态挡住重复点击。
     */
    fun import(uri: Uri) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            val result = runCatching { container.bookRepository.resolve(uri) }
            result
                .onSuccess { book ->
                    runCatching { container.libraryRepository.registerBook(book) }
                    _state.update { it.copy(importing = false) }
                    _events.trySend(ShelfEvent.OpenBook(book))
                }
                .onFailure { error ->
                    _state.update { it.copy(importing = false) }
                    _events.trySend(
                        ShelfEvent.Message("导入失败：${error.message ?: error.javaClass.simpleName}"),
                    )
                }
        }
    }

    fun open(summary: BookSummary) {
        val book = BookRef(
            bookId = summary.bookId,
            uri = summary.uri,
            title = summary.title,
            localPath = summary.localPath
                ?: container.bookRepository.findLocalFile(summary.bookId)?.absolutePath,
        )
        _events.trySend(ShelfEvent.OpenBook(book))
    }

    // ------------------------------------------------------------------ 批量

    /**
     * 批量导入 PDF，并对每一本**自动跑一次「OCR 全页眉扫描」**生成目录。
     *
     * 几个刻意的选择：
     * - **逐本串行**：OCR 是重活，并行只会互相抢 CPU 还把进度搞乱；串行还能让用户看清在第几本；
     * - **导入后立刻读页数**：目录生成需要总页数，而数据库里的 `totalPages` 要等书被打开过才有值，
     *   所以这里直接用 [TocRepository.pageCount] 打开一次 PdfRenderer 拿页数并顺手登记；
     * - **一本失败不影响下一本**：任何一本出错都只记进失败列表，批量继续。
     */
    fun importPdfs(uris: List<Uri>) {
        if (uris.isEmpty() || batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            val imported = mutableListOf<String>()
            val failed = mutableListOf<String>()

            uris.forEachIndexed { i, uri ->
                val n = i + 1
                _batch.value = BatchState(true, n, uris.size, labelOf(uri), "导入中…")

                val book = runCatching { container.bookRepository.resolve(uri) }.getOrElse { error ->
                    failed += "${labelOf(uri)}：导入失败（${error.message ?: "未知错误"}）"
                    return@forEachIndexed
                }

                val pages = runCatching { container.tocRepository.pageCount(book) }.getOrDefault(0)
                runCatching { container.libraryRepository.registerBook(book, pages) }
                if (pages <= 0) {
                    failed += "${book.title}：读不出页数，文件可能有问题"
                    return@forEachIndexed
                }

                _batch.value = BatchState(true, n, uris.size, book.title, "扫描页眉…", 0, pages)
                val ok = runCatching {
                    container.tocRepository.generateNow(book, pages, TocMode.OCR_SCAN) { phase, done, total ->
                        _batch.value = BatchState(true, n, uris.size, book.title, phase, done, total)
                    }
                }.getOrElse { error ->
                    failed += "${book.title}：生成失败（${error.message ?: "未知错误"}）"
                    false
                }

                if (ok) {
                    imported += "${book.title.take(22)}（$pages 页）"
                } else {
                    failed += "${book.title}：没能识别出章节"
                }
            }

            _batch.value = BatchState()
            _events.trySend(ShelfEvent.TocImported(TocImportSummary(imported, emptyList(), failed)))
        }
    }

    /**
     * 批量导入目录 JSON：按书名匹配书架上已有的书，逐本**整表替换**。
     * 替换前会把旧目录备份成 `<bookId>.json.bak`（见 [TocRepository.applyImportedToc]）。
     */
    fun importTocs(uris: List<Uri>) {
        if (uris.isEmpty() || batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            val imported = mutableListOf<String>()
            val unmatched = mutableListOf<String>()
            val failed = mutableListOf<String>()

            uris.forEachIndexed { i, uri ->
                _batch.value = BatchState(true, i + 1, uris.size, labelOf(uri), "读取 JSON…")

                val candidate = runCatching { container.tocRepository.readImportConfig(uri) }.getOrNull()
                if (candidate == null) {
                    failed += "${labelOf(uri)}：不是合法的目录 JSON"
                    return@forEachIndexed
                }

                val book = TocImportMatcher.match(candidate.config, _state.value.books)
                if (book == null) {
                    unmatched += "${candidate.fileName} → ${candidate.config.title ?: "（文件里没有书名）"}"
                    return@forEachIndexed
                }

                // 页数优先用书架上已知的；没有就用 JSON 里最大的页码兜底
                val pages = if (book.totalPages > 0) {
                    book.totalPages
                } else {
                    candidate.config.chapters.mapNotNull { it.endPage ?: it.startPage }.maxOrNull() ?: 1
                }
                val chapters = ChapterResolver.toChapters(candidate.config, pages.coerceAtLeast(1))
                if (chapters.isEmpty()) {
                    failed += "${candidate.fileName}：解析后没有章节"
                    return@forEachIndexed
                }

                runCatching { container.tocRepository.applyImportedToc(book, chapters) }
                    .onSuccess { imported += "${book.title.take(22)}（${chapters.size} 条）" }
                    .onFailure { failed += "${candidate.fileName}：写入失败（${it.message ?: "未知错误"}）" }
            }

            _batch.value = BatchState()
            _events.trySend(ShelfEvent.TocImported(TocImportSummary(imported, unmatched, failed)))
        }
    }

    /** 取消批量任务（当前这一本就地放弃，已完成的保留） */
    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        _batch.value = BatchState()
    }

    private fun labelOf(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.take(28) ?: "未命名文件"

    /** 只移除书架记录（进度 + 目录），本地副本保留：重新导入时可秒开 */
    fun remove(summary: BookSummary) {
        viewModelScope.launch {
            container.libraryRepository.deleteBook(summary.bookId)
            _events.trySend(ShelfEvent.Message("已从书架移除：${summary.title}"))
        }
    }

    /** 连本地副本一起删（释放几十 MB） */
    fun removeWithLocalCopy(summary: BookSummary) {
        viewModelScope.launch {
            container.libraryRepository.deleteBook(summary.bookId)
            container.bookRepository.deleteLocalCopy(summary.bookId)
            _events.trySend(ShelfEvent.Message("已删除书籍与本地副本：${summary.title}"))
        }
    }
}

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return MainViewModel(container) as T
    }
}
