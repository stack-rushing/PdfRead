package com.novelreader.pdf.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novelreader.pdf.data.local.entity.BookEntity
import com.novelreader.pdf.data.local.entity.ChapterEntity
import com.novelreader.pdf.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY last_read_at DESC")
    fun observeShelf(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun find(bookId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    /**
     * 只更新"会随翻页变化"的字段。
     * 用一条 UPDATE 而不是读-改-写整行，避免与目录生成等并发写互相覆盖。
     */
    @Query(
        """
        UPDATE books SET page_index = :pageIndex,
                         total_pages = :totalPages,
                         chapter_index = :chapterIndex,
                         last_read_at = :lastReadAt
        WHERE bookId = :bookId
        """,
    )
    suspend fun saveProgress(
        bookId: String,
        pageIndex: Int,
        totalPages: Int,
        chapterIndex: Int,
        lastReadAt: Long,
    ): Int

    @Query("UPDATE books SET local_path = :localPath WHERE bookId = :bookId")
    suspend fun updateLocalPath(bookId: String, localPath: String)

    @Query("UPDATE books SET toc_source = :source, toc_generated_at = :at WHERE bookId = :bookId")
    suspend fun updateTocInfo(bookId: String, source: String, at: Long)

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
abstract class ChapterDao {

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index")
    abstract suspend fun get(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index")
    abstract fun observe(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapters WHERE book_id = :bookId")
    abstract suspend fun count(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    abstract suspend fun clear(bookId: String)

    /** 整表替换：目录要么整体生效，要么完全不生效，不能出现半新半旧 */
    @Transaction
    open suspend fun replace(bookId: String, chapters: List<ChapterEntity>) {
        clear(bookId)
        insertAll(chapters)
    }
}

@Dao
interface SettingDao {

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)
}
