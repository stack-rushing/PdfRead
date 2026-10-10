package com.novelreader.pdf.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书架 + 阅读进度。**合并成一张表**：断点续读的页码本质上是"这本书的一条状态"，
 * 拆两张表只会让每次翻页都要跨表事务，收益为零。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val bookId: String,
    /** 原始 content:// 或 file://，用于恢复时重建打开入口 */
    val uri: String,
    val title: String,
    /** 应用私有目录里的本地副本路径（PdfRenderer 需要可 seek 的文件） */
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    /** 0 基当前页 */
    @ColumnInfo(name = "page_index") val pageIndex: Int = 0,
    /** 当前章节在 chapters 表里的 chapterIndex，-1 表示没有目录 */
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int = -1,
    @ColumnInfo(name = "added_at") val addedAt: Long = 0L,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long = 0L,
    /** 目录来源：NONE / PDF_OUTLINE / OCR / LOCAL_JSON / ASSET_JSON / MANUAL */
    @ColumnInfo(name = "toc_source") val tocSource: String = "NONE",
    /** 目录生成时间；0 表示还没生成过 */
    @ColumnInfo(name = "toc_generated_at") val tocGeneratedAt: Long = 0L,
)

/**
 * 章节表。**层级化**是这次改造的重点：
 * 书虫 4 级合集是 16 本书打包在一个 PDF 里，506 条大纲分 3 层，
 * 扁平的章节列表在这种书上根本没法用（既没法折叠，也没法把"当前在哪本书"显示出来）。
 *
 * 主键 (bookId, chapterIndex)：chapterIndex 就是**大纲遍历顺序**，直接决定抽屉里的显示顺序。
 * 注意它与 startPage 的顺序**不保证一致**——calibre 生成的 PDF 大纲页码会漂移，
 * 所以按页查找当前章节必须走"按 startPage 排序的副本 + 二分"，不能假设表是有序的。
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["book_id", "chapter_index"],
    indices = [Index(value = ["book_id", "start_page"])],
)
data class ChapterEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    /** 父节点在同一个 bookId 下的 chapterIndex；-1 为顶层 */
    @ColumnInfo(name = "parent_index") val parentIndex: Int,
    val level: Int,
    /** GROUP / CHAPTER / FRONT / BACK */
    val kind: String,
    val title: String,
    @ColumnInfo(name = "title_en") val titleEn: String? = null,
    @ColumnInfo(name = "start_page") val startPage: Int,
    /** 含端点；-1 待计算 */
    @ColumnInfo(name = "end_page") val endPage: Int = -1,
    /** 页码可信度 0..100：内置大纲直接取 100，OCR 重锚定后按匹配质量给分 */
    val confidence: Int = 100,
)

/** 全局设置（夜间模式等），避免为了一个布尔值再引入 DataStore */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
