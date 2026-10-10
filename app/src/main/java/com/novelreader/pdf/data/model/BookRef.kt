package com.novelreader.pdf.data.model

import kotlinx.serialization.Serializable

/**
 * 打开一本书所需的最小上下文。Activity 之间传递用的数据结构。
 *
 * [localPath] 为应用私有目录下的本地副本路径；PDFView 与 PdfRenderer 都优先使用它，
 * 既避开各家 DocumentsProvider 的 fd 差异，也让「文件路径」成为稳定标识。
 */
@Serializable
data class BookRef(
    val bookId: String,
    val uri: String,
    val title: String,
    val localPath: String? = null,
)
