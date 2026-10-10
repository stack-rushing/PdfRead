package com.novelreader.pdf

import android.content.Context
import com.novelreader.pdf.data.local.AppDatabase
import com.novelreader.pdf.data.ocr.OcrEngine
import com.novelreader.pdf.data.pdf.PdfOutlineParser
import com.novelreader.pdf.data.repository.BookRepository
import com.novelreader.pdf.data.repository.DictionaryRepository
import com.novelreader.pdf.data.repository.LibraryRepository
import com.novelreader.pdf.data.repository.TocRepository
import com.novelreader.pdf.data.toc.TocGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简手写依赖容器。
 *
 * MVP 阶段刻意不引入 Hilt：依赖图只有 7 个对象，手写容器可读性更好，
 * 也不会因为注解处理器拖慢构建（Room 已经用掉了一次 KSP）。
 * 需要升级时把这些 `val` 换成 `@Provides` 即可。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Application 级作用域：只用于「不能被 UI 生命周期取消」的长任务与写入——
     * onPause 的进度落盘、以及可能跑几分钟的目录生成（OCR）。
     * 直接绑定 Dispatchers.IO，避免在主线程做 IO。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: AppDatabase = AppDatabase.get(appContext)

    val libraryRepository: LibraryRepository = LibraryRepository(database, appScope)

    val bookRepository: BookRepository = BookRepository(appContext)

    /** 词书：导入用户自己的词典文件并提供查询（App 不附带任何词典数据） */
    val dictionaryRepository: DictionaryRepository = DictionaryRepository(appContext, database)

    private val outlineParser = PdfOutlineParser()

    private val ocrEngine = OcrEngine()

    private val tocGenerator = TocGenerator(ocrEngine)

    val tocRepository: TocRepository = TocRepository(
        context = appContext,
        appScope = appScope,
        library = libraryRepository,
        bookRepository = bookRepository,
        outlineParser = outlineParser,
        generator = tocGenerator,
    )
}
