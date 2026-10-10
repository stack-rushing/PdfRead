package com.novelreader.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * 一次"批量读取页面"的渲染会话（供目录生成使用）。
 *
 * 为什么要有会话：目录生成要连续渲染成百上千页，每页都 `PdfRenderer(file)` 开关一次
 * 会慢得无法接受；而 `PdfRenderer` 不是线程安全的，所以整个会话用 [Mutex] 串行化。
 *
 * 与阅读器用的 [com.novelreader.pdf.ui.reader.PdfPageRenderer] 分开，是因为两者寿命不同：
 * 阅读器的渲染器跟着 Activity 走，这里的会话跟着一次目录生成任务走，且可以随时取消。
 */
class PdfRasterSession(private val file: File) : Closeable {

    private val mutex = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    var pageCount: Int = 0
        private set

    suspend fun open(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd)
            descriptor = pfd
            renderer = r
            pageCount = r.pageCount
            pageCount
        }
    }

    /** 渲染整页到指定宽度（白底） */
    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock { renderLocked(index, targetWidth) }
    }

    /**
     * 只取页面顶部一条，用于 OCR 页眉。
     *
     * `PdfRenderer` 没有"只渲染一块区域"的可靠 API（`transform` 与自动缩放叠加后语义含糊），
     * 所以老老实实整页渲染再裁剪：内存是瞬时的，裁剪后立刻回收原图。
     */
    suspend fun renderTopStrip(index: Int, targetWidth: Int, topFraction: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val full = renderLocked(index, targetWidth) ?: return@withLock null
                val keep = (full.height * topFraction.coerceIn(0.05f, 1f)).toInt().coerceAtLeast(1)
                if (keep >= full.height) return@withLock full
                val strip = runCatching {
                    Bitmap.createBitmap(full, 0, 0, full.width, keep)
                }.getOrNull()
                full.recycle()
                strip
            }
        }

    private fun renderLocked(index: Int, targetWidth: Int): Bitmap? {
        val r = renderer ?: return null
        if (index < 0 || index >= r.pageCount) return null
        return runCatching {
            r.openPage(index).use { page ->
                val width = targetWidth.coerceAtLeast(1)
                val scale = width.toFloat() / page.width.toFloat()
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PDF 页面本身没有背景色，不铺白会在深色主题下出现"文字浮在黑色上"
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}
