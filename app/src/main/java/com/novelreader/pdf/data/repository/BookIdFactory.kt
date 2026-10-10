package com.novelreader.pdf.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 书籍唯一标识（bookId）工厂。
 *
 * 需求要求「文件路径或 MD5」，这里提供两种可切换策略：
 *
 * 1. [STRATEGY_METADATA]（默认，推荐）：对 `uri + size + lastModified` 做 MD5。
 *    稳定（同一本书每次结果一致）、廉价（不读文件内容，大文件也是毫秒级），
 *    且天然规避了"同名不同书"与"同书不同路径"两类冲突。
 * 2. [STRATEGY_CONTENT]：对 PDF 全文做流式 MD5。语义最严格，但首次打开需要完整读一遍文件，
 *    几十 MB 的扫描版 PDF 会有明显耗时。适合配合「本地副本」在后台线程预热。
 *
 * 两者产出的都是 32 位小写 hex MD5，可直接作为 DataStore 的 key 与本地缓存文件名。
 */
object BookIdFactory {

    const val STRATEGY_METADATA = "metadata"
    const val STRATEGY_CONTENT = "content"

    /** 默认策略，想换成全文 MD5 只改这一处（或调用方显式传参） */
    const val DEFAULT_STRATEGY = STRATEGY_METADATA

    suspend fun create(
        context: Context,
        uri: Uri,
        strategy: String = DEFAULT_STRATEGY,
    ): String = withContext(Dispatchers.IO) {
        when (strategy) {
            STRATEGY_CONTENT -> md5OfContent(context, uri)
            else -> md5(fingerprint(context, uri))
        }
    }

    /** 元数据指纹：uri|size|lastModified|displayName */
    private fun fingerprint(context: Context, uri: Uri): String {
        val meta = queryMetadata(context, uri)
        return buildString {
            append(uri.toString())
            append('|').append(meta.size)
            append('|').append(meta.lastModified)
            append('|').append(meta.displayName.orEmpty())
        }
    }

    /** 流式全文 MD5，8KB 缓冲，内存占用恒定 */
    suspend fun md5OfContent(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@withContext md5(uri.toString())
        digest.digest().toHex()
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    data class Metadata(
        val size: Long = -1L,
        val lastModified: Long = 0L,
        val displayName: String? = null,
    )

    fun queryMetadata(context: Context, uri: Uri): Metadata {
        var size = -1L
        var displayName: String? = null
        var lastModified = 0L

        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file != null && file.exists()) {
                return Metadata(file.length(), file.lastModified(), file.name)
            }
        }

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                }
            }
        }

        // 部分 provider 不返回 SIZE，退回 DocumentFile 元数据
        runCatching {
            DocumentFile.fromSingleUri(context, uri)?.let { doc ->
                if (size < 0) size = doc.length()
                lastModified = doc.lastModified()
                if (displayName.isNullOrBlank()) displayName = doc.name
            }
        }

        return Metadata(size, lastModified, displayName)
    }

    /** 从 uri 猜一个书名（去掉 .pdf 后缀），用于书架与顶栏展示 */
    fun guessTitle(context: Context, uri: Uri, fallback: String = "未命名书籍"): String {
        val meta = queryMetadata(context, uri)
        val raw = meta.displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: fallback
        return raw.substringBeforeLast('.', raw).ifBlank { fallback }
    }
}
