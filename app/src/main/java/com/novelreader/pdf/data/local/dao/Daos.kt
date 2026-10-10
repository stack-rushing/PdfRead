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

    /**
     * 书架顺序：这里只给一个**稳定的基础顺序**（按书名、再按加入时间）。
     *
     * 真正的展示顺序由 [com.novelreader.pdf.data.model.NaturalOrder] 在仓库层做**自然排序**——
     * 因为 SQL 的 `ORDER BY` 是逐字节比较，`2-10` 会排到 `2-2` 前面（'1' < '2'），那不是人想要的顺序。
     */
    @Query("SELECT * FROM books ORDER BY title ASC, added_at ASC")
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
