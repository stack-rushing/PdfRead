package com.novelreader.pdf.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.data.model.BookRef

/** 手写 ViewModel 工厂；依赖图很小，不值得为 Hilt 付注解处理器的构建成本 */
class ReaderViewModelFactory(
    private val container: AppContainer,
    private val book: BookRef,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return ReaderViewModel(
            library = container.libraryRepository,
            tocRepository = container.tocRepository,
            book = book,
        ) as T
    }
}
