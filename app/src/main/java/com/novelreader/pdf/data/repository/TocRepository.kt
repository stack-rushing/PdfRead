package com.novelreader.pdf.data.repository

import android.content.Context
import android.net.Uri
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterResolver
import com.novelreader.pdf.data.model.ChapterConfig
import com.novelreader.pdf.data.model.TocSource
import com.novelreader.pdf.data.pdf.PdfOutlineParser
import com.novelreader.pdf.data.toc.TocGenerator
import com.novelreader.pdf.data.toc.TocMode
import com.novelreader.pdf.data.toc.TocProgress
import com.novelreader.pdf.data.toc.TocTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 目录仓库：**编排**四条目录来源，并把结果落进 Room。
 *
 * 来源优先级（[TocSource] 的顺序就是优先级）：
 * `LOCAL_JSON` ＞ `PDF_OUTLINE`（必要时先经 OCR 重锚定）＞ `OCR` ＞ `ASSET_JSON`
 *
 * 两个刻意的设计：
 * 1. **首屏只跑"便宜路径"**（[ensureChapters]：本地 JSON / 预置 / 内置书签），
 *    OCR 一定要用户显式触发——2278 页的书扫一遍要一两分钟，不能偷偷干；
 * 2. **生成任务跑在 Application 作用域**，用户退出阅读器也让它跑完，
 *    结果直接落 Room，下次打开就能用；进度通过 [observeTask] 回传给 UI。
 */
class TocRepository(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val library: LibraryRepository,
    private val bookRepository: BookRepository,
    private val outlineParser: PdfOutlineParser,
    private val generator: TocGenerator,
) {

    private data class Snapshot(val bookId: String, val state: TocTaskState)

    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private var job: Job? = null

    fun observeTask(bookId: String): Flow<TocTaskState> = snapshot
        .map { if (it?.bookId == bookId) it.state else TocTaskState.IDLE }
        .distinctUntilChanged()

    fun cancel() {
        job?.cancel()
        job = null
        snapshot.value = snapshot.value?.let { it.copy(state = TocTaskState.IDLE) }
    }

    private fun setTask(bookId: String, state: TocTaskState) {
        snapshot.value = Snapshot(bookId, state)
    }

    // ------------------------------------------------------------------ 首屏便宜路径

    /**
     * 打开书时调用：数据库里有目录就直接用；没有才依次尝试本地 JSON → 预置 → 内置书签。
     * 返回本次实际生效的来源。
     */
    suspend fun ensureChapters(book: BookRef, totalPages: Int): TocSource {
        val cached = library.loadChapters(book.bookId)
        val summary = library.findBook(book.bookId)

        // 本地 JSON 是"人工校正"的最高优先级：只要文件比上次生成时间新，就重新读它
        val localFile = findLocalOverride(book.bookId)
        if (localFile != null && cached.isNotEmpty() &&
            localFile.lastModified() > (summary?.tocGeneratedAt ?: 0L)
        ) {
            parseConfigFile(localFile, totalPages)?.let { chapters ->
                library.replaceChapters(book.bookId, chapters, TocSource.LOCAL_JSON)
                return TocSource.LOCAL_JSON
            }
        }
        if (cached.isNotEmpty()) return summary?.tocSource ?: TocSource.NONE

        parseConfigFile(localFile, totalPages)?.let { chapters ->
            library.replaceChapters(book.bookId, chapters, TocSource.LOCAL_JSON)
            return TocSource.LOCAL_JSON
        }
        loadAssetConfig(book.bookId, totalPages)?.let { chapters ->
            library.replaceChapters(book.bookId, chapters, TocSource.ASSET_JSON)
            return TocSource.ASSET_JSON
        }

        val outline = readOutline(book, totalPages) ?: return TocSource.NONE
        library.replaceChapters(book.bookId, outline.chapters, TocSource.PDF_OUTLINE)
        return TocSource.PDF_OUTLINE
    }

    // ------------------------------------------------------------------ 生成任务

    fun startGenerate(book: BookRef, totalPages: Int, mode: TocMode) {
        if (job?.isActive == true) return
        job = appScope.launch {
            try {
                setTask(book.bookId, TocTaskState(running = true, phase = "准备中"))
                val file = resolveFile(book)
                if (file == null) {
                    setTask(book.bookId, TocTaskState(false, "找不到可读的 PDF 文件"))
                    return@launch
                }
                if (totalPages <= 0) {
                    setTask(book.bookId, TocTaskState(false, "页数未知，先把书打开一次"))
                    return@launch
                }

                val progress: TocProgress = { phase, done, total ->
                    setTask(book.bookId, TocTaskState(running = true, phase = phase, done = done, total = total))
                }

                var chapters: List<Chapter> = emptyList()
                var source = TocSource.NONE

                when (mode) {
                    TocMode.OUTLINE_ONLY -> {
                        val outline = readOutline(book, totalPages)
                        if (outline != null) {
                            chapters = outline.chapters
                            source = TocSource.PDF_OUTLINE
                        }
                    }

                    TocMode.OCR_REANCHOR -> {
                        val base = library.loadChapters(book.bookId)
                            .ifEmpty { readOutline(book, totalPages)?.chapters.orEmpty() }
                        if (base.isEmpty()) {
                            setTask(book.bookId, TocTaskState(false, "没有可重锚定的章节，请改用全页眉扫描"))
                            return@launch
                        }
                        chapters = generator.reanchorByOcr(file, base, totalPages, progress)
                        source = TocSource.OCR
                    }

                    TocMode.OCR_SCAN -> {
                        chapters = generator.scanByOcr(file, totalPages, progress)
                        source = TocSource.OCR
                    }

                    TocMode.AUTO -> {
                        // 1) 人工校正过的 JSON 永远第一优先
                        parseConfigFile(findLocalOverride(book.bookId), totalPages)?.let {
                            chapters = it
                            source = TocSource.LOCAL_JSON
                        }
                        // 2) 内置书签：页码可信就直接用
                        if (chapters.isEmpty()) {
                            val outline = readOutline(book, totalPages)
                            when {
                                outline == null -> Unit
                                !outline.unreliable -> {
                                    chapters = outline.chapters
                                    source = TocSource.PDF_OUTLINE
                                }
                                // 3) 页码漂移 → OCR 重锚定
                                else -> {
                                    chapters = generator.reanchorByOcr(file, outline.chapters, totalPages, progress)
                                    source = TocSource.OCR
                                }
                            }
                        }
                        // 4) 连书签都没有（扫描件）→ 全页眉扫描
                        if (chapters.isEmpty()) {
                            chapters = generator.scanByOcr(file, totalPages, progress)
                            source = TocSource.OCR
                        }
                    }
                }

                if (chapters.isEmpty()) {
                    setTask(book.bookId, TocTaskState(false, "没能识别出章节"))
                    return@launch
                }
                library.replaceChapters(book.bookId, chapters, source)
                setTask(book.bookId, TocTaskState(false, "完成：${chapters.count { !it.isGroup }} 章"))
            } catch (cancelled: CancellationException) {
                setTask(book.bookId, TocTaskState(false, "已取消"))
                throw cancelled
            } catch (t: Throwable) {
                setTask(book.bookId, TocTaskState(false, "失败：${t.message ?: t.javaClass.simpleName}"))
            }
        }
    }

    // ------------------------------------------------------------------ 内置书签

    private data class OutlineOutcome(val chapters: List<Chapter>, val unreliable: Boolean, val note: String)

    private suspend fun readOutline(book: BookRef, totalPages: Int): OutlineOutcome? {
        val file = resolveFile(book) ?: return null
        val result = withContext(Dispatchers.IO) { outlineParser.parse(file) }
        if (result.nodes.isEmpty()) return null
        val pages = if (totalPages > 0) totalPages else result.pageCount
        if (pages <= 0) return null
        val chapters = generator.outlineToChapters(result.nodes, pages, markLowConfidence = false)
        val unreliable = generator.pagesLookUnreliable(chapters)
        val finalChapters = if (unreliable) {
            generator.outlineToChapters(result.nodes, pages, markLowConfidence = true)
        } else {
            chapters
        }
        return OutlineOutcome(finalChapters, unreliable, result.note)
    }

    // ------------------------------------------------------------------ JSON 读写

    private fun parseConfigFile(file: File?, totalPages: Int): List<Chapter>? {
        if (file == null || !file.exists() || file.length() <= 0L) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val config: ChapterConfig = ChapterResolver.decodeConfig(raw) ?: return null
        return ChapterResolver.toChapters(config, totalPages).takeIf { it.isNotEmpty() }
    }

    private fun loadAssetConfig(bookId: String, totalPages: Int): List<Chapter>? = runCatching {
        context.assets.open("chapters/$bookId.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()?.let { raw ->
        ChapterResolver.decodeConfig(raw)?.let { ChapterResolver.toChapters(it, totalPages) }
    }?.takeIf { it.isNotEmpty() }

    /**
     * 导出为可人工校对的 JSON。默认写到应用外部私有目录
     * （`/sdcard/Android/data/<pkg>/files/chapters/<bookId>.json`），
     * 文件管理器或 adb 可直接改；改完重开阅读器即生效（LOCAL_JSON 优先级最高）。
     */
    suspend fun exportToJson(
        book: BookRef,
        chapters: List<Chapter>,
        source: TocSource,
    ): File = withContext(Dispatchers.IO) {
        val target = externalJsonFile(book.bookId) ?: internalJsonFile(book.bookId)
        target.parentFile?.mkdirs()
        val config = ChapterResolver.toConfig(book.bookId, book.title, chapters, source)
        target.writeText(ChapterResolver.encodeConfig(config), Charsets.UTF_8)
        target
    }

    fun localOverridePath(bookId: String): String =
        (externalJsonFile(bookId) ?: internalJsonFile(bookId)).absolutePath

    fun hasLocalOverride(bookId: String): Boolean = findLocalOverride(bookId) != null

    private fun findLocalOverride(bookId: String): File? =
        listOfNotNull(externalJsonFile(bookId), internalJsonFile(bookId))
            .firstOrNull { it.exists() && it.length() > 0L }

    private fun externalJsonFile(bookId: String): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, CHAPTER_DIR), "$bookId.json") }

    private fun internalJsonFile(bookId: String): File =
        File(File(context.filesDir, CHAPTER_DIR), "$bookId.json")

    // ------------------------------------------------------------------ 文件定位

    private suspend fun resolveFile(book: BookRef): File? {
        bookRepository.findLocalFile(book.bookId)?.let { return it }
        val uri = book.uri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        return bookRepository.rehydrate(book.bookId, uri)
    }

    private companion object {
        const val CHAPTER_DIR = "chapters"
    }
}
