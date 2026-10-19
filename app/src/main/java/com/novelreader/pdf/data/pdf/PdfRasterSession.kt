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

    /** 显示页数（一页两开时是物理页数的两倍），全工程统一用这个编号 */
    var pageCount: Int = 0
        private set

    /** 一页两开：一张纸印了两个书页 */
    var spread: Boolean = false
        private set

    suspend fun open(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd)
            descriptor = pfd
            renderer = r
            // 抽样若干页的宽高比，过半数是宽的就判为两开。
            // 只看第一页会踩坑：Two Lives 的第 0 页是封面（单页），其余 34 页全是两开。
            val sample = (0 until minOf(r.pageCount, SAMPLE_PAGES)).mapNotNull { idx ->
                runCatching {
                    r.openPage(idx).use { it.width.toFloat() / it.height.toFloat() }
                }.getOrNull()
            }
            spread = SpreadLayout.decide(sample)
            pageCount = SpreadLayout.displayPageCount(r.pageCount, spread)
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

    /**
     * [displayIndex] 是**显示页**编号。一页两开时：物理页 = displayIndex / 2，
     * 半页 = displayIndex % 2；整张纸按两倍宽度渲染再裁出那一半。
     */
    private fun renderLocked(displayIndex: Int, targetWidth: Int): Bitmap? {
        val r = renderer ?: return null
        val sheetIndex = SpreadLayout.sheetOf(displayIndex, spread)
        if (sheetIndex < 0 || sheetIndex >= r.pageCount) return null
        val sheetWidth = SpreadLayout.sheetRenderWidth(targetWidth, spread)
        return runCatching {
            r.openPage(sheetIndex).use { page ->
                val scale = sheetWidth.toFloat() / page.width.toFloat()
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val sheet = Bitmap.createBitmap(sheetWidth, height, Bitmap.Config.ARGB_8888)
                // PDF 页面本身没有背景色，不铺白会在深色主题下出现"文字浮在黑色上"
                sheet.eraseColor(Color.WHITE)
                page.render(sheet, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (!spread) {
                    sheet
                } else {
                    val half = SpreadLayout.halfOf(displayIndex, spread)
                    val halfWidth = sheet.width / 2
                    val x = if (half == 0) 0 else halfWidth
                    val cropped = Bitmap.createBitmap(sheet, x, 0, halfWidth, sheet.height)
                    sheet.recycle()
                    cropped
                }
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private companion object {
        /** 判定两开时抽样的页数：够躲开封面/版权页这类异常页，又不至于太慢 */
        const val SAMPLE_PAGES = 8
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}
