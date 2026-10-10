package com.novelreader.pdf.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户导入的「词书」。
 *
 * 设计前提：**App 自己不附带任何词典数据**。词书是用户自己提供的文件
 * （CSV / TSV / JSON），导入后解析进本地数据库，App 只负责"读你给的文件、查给你看"。
 * 所以这里存的只是元信息与解析结果，不含任何随包分发的版权内容。
 */
@Entity(tableName = "dictionaries")
data class DictionaryEntity(
    @PrimaryKey @ColumnInfo(name = "dict_id") val dictId: String,
    /** 展示名，默认取文件名 */
    val name: String,
    /** 来源格式：csv / tsv / json */
    val format: String,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "entry_count") val entryCount: Int = 0,
    /** 查询时是否参与（可以留着但不启用，比如同时装了英汉和英英两本） */
    val enabled: Boolean = true,
    /** 结果排序用：数字小的优先 */
    val priority: Int = 100,
    @ColumnInfo(name = "added_at") val addedAt: Long = 0L,
)

/**
 * 一条词条。[normalized] 是查询键（小写、去掉标点）。
 *
 * [collins] / [oxford] / [bnc] / [frq] / [tag] 这些字段来自 ECDICT 的列；
 * 用别的词书时留空即可，卡片上会自动少显示几行。
 */
@Entity(
    tableName = "dict_entries",
    indices = [
        Index(value = ["dict_id", "normalized"]),
        Index(value = ["normalized"]),
    ],
)
data class DictEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "dict_id") val dictId: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    /** 原始词形（保留大小写，卡片上照原样显示） */
    val word: String,
    val phonetic: String? = null,
    /** 中文释义（多行） */
    val translation: String? = null,
    /** 英文释义（多行） */
    val definition: String? = null,
    /** 词性，用 / 分隔，如 `n:64/v:36` */
    val pos: String? = null,
    /** 词形变化，如 `d:perceived/p:perceived/3:perceives` */
    val exchange: String? = null,
    val collins: Int = 0,
    val oxford: Int = 0,
    val tag: String? = null,
    val bnc: Int = 0,
    val frq: Int = 0,
)

/**
 * 词形 → 词条的映射，用来做原形还原：
 * `running`、`ran` 都应该能查到 `run`。
 *
 * 数据来源是词书自身的 `exchange` 列（ECDICT 每个词条都带自己的变体表），
 * 所以不需要额外的词法分析器。
 */
@Entity(
    tableName = "dict_forms",
    primaryKeys = ["dict_id", "form", "entry_id"],
    indices = [Index(value = ["form"])],
)
data class DictFormEntity(
    @ColumnInfo(name = "dict_id") val dictId: String,
    /** 已归一化的小写变体 */
    val form: String,
    @ColumnInfo(name = "entry_id") val entryId: Long,
)
