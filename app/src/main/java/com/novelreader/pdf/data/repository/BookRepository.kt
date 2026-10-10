package com.novelreader.pdf.data.repository

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.novelreader.pdf.data.model.BookRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书籍仓库：负责「拿到一本书」这件事。
 *
 * 设计取舍——为什么要把 content:// 复制成应用私有副本？
 * 1. PdfRenderer / Pdfium 都要求 **可 seek** 的 fd，部分 DocumentsProvider 给不了或行为不一致；
 * 2. SAF 的持久化授权在用户清理「最近文件」/卸载网盘 App 后会失效，本地副本是最终兜底；
 * 3. 有了确定的本地路径，bookId 可以退化为「路径 + 元数据」的 MD5，避免每次打开都做全文件哈希；
 * 4. 复制是流式的，内存占用恒定，且用 `.tmp` + rename 保证不会读到半截文件。
 *
 * 代价是额外磁盘占用，所以提供了 [deleteLocalCopy] 与 [copyToLocal] 开关。
 */
class BookRepository(private val context: Context) {

    /** 解析一本书：生成 bookId、猜书名、可选地准备本地副本 */
    suspend fun resolve(
        uri: Uri,
        displayName: String? = null,
        copyToLocal: Boolean = true,
        strategy: String = BookIdFactory.DEFAULT_STRATEGY,
    ): BookRef = withContext(Dispatchers.IO) {
        val title = displayName?.substringBeforeLast('.')?.ifBlank { null }
            ?: BookIdFactory.guessTitle(context, uri)
        val bookId = BookIdFactory.create(context, uri, strategy)
        val local = if (copyToLocal) runCatching { ensureLocalCopy(uri, bookId) }.getOrNull() else null
        BookRef(
            bookId = bookId,
            uri = uri.toString(),
            title = title,
            localPath = local?.absolutePath,
        )
    }

    /**
     * 确保本地副本存在。已存在且非空直接复用，否则流式复制。
     * 返回的 File 一定可读、可 seek、长度 > 0。
     */
    suspend fun ensureLocalCopy(uri: Uri, bookId: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, BOOKS_DIR).apply { if (!exists()) mkdirs() }
        val dest = File(dir, "$bookId.pdf")
        if (dest.exists() && dest.length() > 0L) return@withContext dest

        val tmp = File(dir, "$bookId.pdf.tmp")
        if (tmp.exists()) tmp.delete()

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
                if (total <= 0L) throw IllegalStateException("PDF 内容为空或无法读取：$uri")
            }
        } ?: throw IllegalStateException("无法打开输入流：$uri")

        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        dest
    }

    fun localFile(bookId: String): File = File(File(context.filesDir, BOOKS_DIR), "$bookId.pdf")

    fun findLocalFile(bookId: String): File? = localFile(bookId).takeIf { it.exists() && it.length() > 0L }

    /** 本地副本缺失时按 uri 重建，失败返回 null（阅读器会退回直接使用 uri） */
    suspend fun rehydrate(bookId: String, uri: Uri): File? = withContext(Dispatchers.IO) {
        findLocalFile(bookId) ?: runCatching { ensureLocalCopy(uri, bookId) }.getOrNull()
    }

    suspend fun deleteLocalCopy(bookId: String) = withContext(Dispatchers.IO) {
        runCatching { localFile(bookId).delete() }
        Unit
    }

    /** 查询已持久化的 SAF 授权（用于诊断"重新打开后无权访问"的问题） */
    fun hasPersistedPermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

    @Suppress("unused")
    private fun permissions(): List<UriPermission> = context.contentResolver.persistedUriPermissions

    companion object {
        const val BOOKS_DIR = "books"

        /** 在 Activity 里收到 SAF 回调后调用，避免下次冷启动丢失读权限 */
        fun takePersistablePermission(context: Context, uri: Uri, flags: Int) {
            val takeFlags = flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        }
    }
}
