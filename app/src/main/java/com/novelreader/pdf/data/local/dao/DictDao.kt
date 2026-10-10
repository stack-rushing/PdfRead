package com.novelreader.pdf.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novelreader.pdf.data.local.entity.DictEntryEntity
import com.novelreader.pdf.data.local.entity.DictFormEntity
import com.novelreader.pdf.data.local.entity.DictionaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 词书查询。
 *
 * 查询语句一律 `INNER JOIN dictionaries ... AND d.enabled = 1`：
 * 用户可以把一本词书留着但不启用（比如同时装了英汉和英英两本，只想用其中一本）。
 */
@Dao
interface DictDao {

    @Query("SELECT * FROM dictionaries ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionaries ORDER BY priority ASC, name ASC")
    suspend fun all(): List<DictionaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDict(dict: DictionaryEntity)

    @Query("UPDATE dictionaries SET enabled = :enabled WHERE dict_id = :dictId")
    suspend fun setEnabled(dictId: String, enabled: Boolean)

    @Query("UPDATE dictionaries SET entry_count = :count WHERE dict_id = :dictId")
    suspend fun setEntryCount(dictId: String, count: Int)

    @Query("DELETE FROM dictionaries WHERE dict_id = :dictId")
    suspend fun deleteDict(dictId: String)

    @Query("DELETE FROM dict_entries WHERE dict_id = :dictId")
    suspend fun deleteEntries(dictId: String)

    @Query("DELETE FROM dict_forms WHERE dict_id = :dictId")
    suspend fun deleteForms(dictId: String)

    /** 删一本词书：条目、词形映射、元信息一起走，不留孤儿行 */
    @Transaction
    suspend fun remove(dictId: String) {
        deleteForms(dictId)
        deleteEntries(dictId)
        deleteDict(dictId)
    }

    @Insert
    suspend fun insertEntries(entries: List<DictEntryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForms(forms: List<DictFormEntity>)

    @Query("SELECT COUNT(*) FROM dict_entries WHERE dict_id = :dictId")
    suspend fun countEntries(dictId: String): Int

    /** 精确命中 */
    @Query(
        """
        SELECT e.* FROM dict_entries e
        INNER JOIN dictionaries d ON d.dict_id = e.dict_id
        WHERE e.normalized = :key AND d.enabled = 1
        ORDER BY d.priority ASC, e.id ASC
        LIMIT :limit
        """,
    )
    suspend fun lookup(key: String, limit: Int = 5): List<DictEntryEntity>

    /** 词形还原命中（running → run） */
    @Query(
        """
        SELECT e.* FROM dict_entries e
        INNER JOIN dict_forms f ON f.entry_id = e.id
        INNER JOIN dictionaries d ON d.dict_id = e.dict_id
        WHERE f.form = :key AND d.enabled = 1
        ORDER BY d.priority ASC, e.id ASC
        LIMIT :limit
        """,
    )
    suspend fun lookupForm(key: String, limit: Int = 5): List<DictEntryEntity>

    /**
     * 前缀联想。`(e.frq = 0)` 参与排序是为了让"常见词"排在"没词频数据的词"前面
     * —— frq=0 在 ECDICT 里表示没有词频记录，直接按 frq 升序会把它们顶到最前。
     */
    @Query(
        """
        SELECT DISTINCT e.word FROM dict_entries e
        INNER JOIN dictionaries d ON d.dict_id = e.dict_id
        WHERE e.normalized LIKE :prefix || '%' AND d.enabled = 1
        ORDER BY (e.frq = 0) ASC, e.frq ASC, e.word ASC
        LIMIT :limit
        """,
    )
    suspend fun suggest(prefix: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM dict_entries")
    suspend fun totalEntries(): Int
}
