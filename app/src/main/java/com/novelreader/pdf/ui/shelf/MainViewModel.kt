package com.novelreader.pdf.ui.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.BookSummary
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

sealed interface ShelfEvent {
    data class OpenBook(val book: BookRef) : ShelfEvent
    data class Message(val text: String) : ShelfEvent
}

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    private val _events = Channel<ShelfEvent>(Channel.BUFFERED)
    val events: Flow<ShelfEvent> = _events.receiveAsFlow()

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
