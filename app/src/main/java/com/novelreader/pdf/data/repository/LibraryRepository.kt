package com.novelreader.pdf.data.repository

import androidx.room.withTransaction
import com.novelreader.pdf.data.local.AppDatabase
import com.novelreader.pdf.data.local.entity.BookEntity
import com.novelreader.pdf.data.local.entity.ChapterEntity
import com.novelreader.pdf.data.local.entity.SettingEntity
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.model.BookSummary
import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.data.model.ChapterKind
import com.novelreader.pdf.data.model.ReadingProgress
import com.novelreader.pdf.data.model.TocSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 书架 / 进度 / 章节 / 设置 的统一仓库（Room 实现，替代原来的 DataStore 方案）。
 *
 * 为什么换 Room：
 * - 章节缓存是这次改造的刚需。2278 页的书虫合集每打开一次都重新解析 506 条大纲纯属浪费，
 *   OCR 生成的目录更是**必须**持久化（跑一次要一两分钟）。
 * - 按 bookId 建索引查进度、按 (bookId,startPage) 建索引查章节，是关系型数据库该干的活；
 *   DataStore 的 KV + 手写 JSON 已经在"目录 + 书架 + 设置"三份数据上到了极限。
 *
 * 落盘保证：与之前一致——[saveProgressNow] 投递到 Application 作用域，
 * 因此 onPause/onStop/onCleared 触发的写入不会被 Activity 生命周期取消。
 */
class LibraryRepository(
    private val db: AppDatabase,
    private val appScope: CoroutineScope,
) {

    private val bookDao get() = db.bookDao()
    private val chapterDao get() = db.chapterDao()
    private val settingDao get() = db.settingDao()

    // ------------------------------------------------------------------ 书架

    fun observeShelf(): Flow<List<BookSummary>> =
        bookDao.observeShelf().map { list -> list.map(BookEntity::toSummary) }

    fun observeBook(bookId: String): Flow<BookSummary?> =
        bookDao.observe(bookId).map { it?.toSummary() }

    suspend fun findBook(bookId: String): BookSummary? = bookDao.find(bookId)?.toSummary()

    /** 导入或重新打开时登记书籍；已存在则只补齐元信息，不动进度 */
    suspend fun registerBook(book: BookRef, totalPages: Int = 0) {
        val existing = bookDao.find(book.bookId)
        val now = System.currentTimeMillis()
        bookDao.upsert(
            BookEntity(
                bookId = book.bookId,
                uri = book.uri,
                title = book.title,
                localPath = book.localPath ?: existing?.localPath,
                totalPages = if (totalPages > 0) totalPages else (existing?.totalPages ?: 0),
                pageIndex = existing?.pageIndex ?: 0,
                chapterIndex = existing?.chapterIndex ?: ReadingProgress.INDEX_NONE,
                addedAt = existing?.addedAt ?: now,
                lastReadAt = existing?.lastReadAt ?: now,
                tocSource = existing?.tocSource ?: TocSource.NONE.name,
                tocGeneratedAt = existing?.tocGeneratedAt ?: 0L,
            ),
        )
    }

    suspend fun updateLocalPath(bookId: String, localPath: String) {
        bookDao.updateLocalPath(bookId, localPath)
    }

    suspend fun deleteBook(bookId: String) {
        db.withTransaction {
            chapterDao.clear(bookId)
            bookDao.delete(bookId)
        }
    }

    // ------------------------------------------------------------------ 进度

    /** 挂起写入，供防抖流水线与确定操作使用 */
    suspend fun saveProgress(progress: ReadingProgress) {
        val updated = bookDao.saveProgress(
            bookId = progress.bookId,
            pageIndex = progress.pageIndex,
            totalPages = progress.totalPages,
            chapterIndex = progress.chapterIndex,
            lastReadAt = progress.updatedAt,
        )
        if (updated == 0) {
            // 表里没有这本书（例如进度恢复早于导入登记）→ 补一条完整记录，别把这次翻页丢掉
            val now = System.currentTimeMillis()
            bookDao.upsert(
                BookEntity(
                    bookId = progress.bookId,
                    uri = progress.bookUri,
                    title = progress.title,
                    localPath = null,
                    totalPages = progress.totalPages,
                    pageIndex = progress.pageIndex,
                    chapterIndex = progress.chapterIndex,
                    addedAt = now,
                    lastReadAt = progress.updatedAt,
                ),
            )
        }
    }

    /** 非挂起落盘：投递到 Application 作用域，生命周期回调里用它 */
    fun saveProgressNow(progress: ReadingProgress) {
        appScope.launch { runCatching { saveProgress(progress) } }
    }

    // ------------------------------------------------------------------ 章节

    fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapterDao.observe(bookId).map { list -> list.map(ChapterEntity::toChapter) }

    suspend fun loadChapters(bookId: String): List<Chapter> =
        chapterDao.get(bookId).map(ChapterEntity::toChapter)

    /**
     * 整表替换章节并记录来源。目录要么整体生效要么完全不生效——
     * 半新半旧的目录会让"当前章节"显示错乱，比没有目录更糟。
     */
    suspend fun replaceChapters(bookId: String, chapters: List<Chapter>, source: TocSource) {
        db.withTransaction {
            chapterDao.replace(bookId, chapters.map { it.toEntity(bookId) })
            bookDao.updateTocInfo(bookId, source.name, System.currentTimeMillis())
        }
    }

    suspend fun clearChapters(bookId: String) {
        db.withTransaction {
            chapterDao.clear(bookId)
            bookDao.updateTocInfo(bookId, TocSource.NONE.name, 0L)
        }
    }

    // ------------------------------------------------------------------ 设置

    fun observeNightMode(): Flow<Boolean> =
        settingDao.observe(KEY_NIGHT_MODE).map { it == "1" }

    suspend fun setNightMode(enabled: Boolean) {
        settingDao.put(SettingEntity(KEY_NIGHT_MODE, if (enabled) "1" else "0"))
    }

    private companion object {
        const val KEY_NIGHT_MODE = "night_mode"
    }
}

// ---------------------------------------------------------------------- 映射

internal fun BookEntity.toSummary() = BookSummary(
    bookId = bookId,
    uri = uri,
    title = title,
    localPath = localPath,
    pageIndex = pageIndex,
    totalPages = totalPages,
    chapterIndex = chapterIndex,
    addedAt = addedAt,
    updatedAt = lastReadAt,
    tocSource = TocSource.from(tocSource),
    tocGeneratedAt = tocGeneratedAt,
)

internal fun ChapterEntity.toChapter() = Chapter(
    index = chapterIndex,
    parentIndex = parentIndex,
    level = level,
    kind = runCatching { ChapterKind.valueOf(kind) }.getOrDefault(ChapterKind.UNKNOWN),
    title = title,
    titleEn = titleEn,
    startPage = startPage,
    endPage = endPage,
    confidence = confidence,
)

internal fun Chapter.toEntity(bookId: String) = ChapterEntity(
    bookId = bookId,
    chapterIndex = index,
    parentIndex = parentIndex,
    level = level,
    kind = kind.name,
    title = title,
    titleEn = titleEn,
    startPage = startPage,
    endPage = endPage,
    confidence = confidence,
)
