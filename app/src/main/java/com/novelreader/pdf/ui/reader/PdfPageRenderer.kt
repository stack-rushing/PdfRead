package com.novelreader.pdf.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统 PdfRenderer 的薄封装。
 *
 * 两个必须处理的点：
 * 1. **线程安全**：`PdfRenderer` 与 `PdfRenderer.Page` 不是线程安全的，
 *    同一实例的 open/render/close 必须串行，因此内部用 [Mutex] 串行化；
 *    渲染放在 `Dispatchers.IO`，不阻塞主线程。
 * 2. **背景铺白**：PDF 页面本身没有背景色，`PdfRenderer` 渲染出的 Bitmap 未绘制区域是透明的，
 *    若不先 `eraseColor(WHITE)`，在深色主题下会出现"文字浮在黑色上"的诡异效果。
 *
 * 局限（这也是保留 Pdfium 引擎的原因）：PdfRenderer 不支持加密 PDF，
 * 对部分扫描件的渲染质量与速度也不如 Pdfium。
 */
class PdfPageRenderer(private val file: File) {

    private val mutex = Mutex()

    private var descriptor: ParcelFileDescriptor? = null

    private companion object {
        /** 判定两开时抽样的页数 */
        const val SAMPLE_PAGES = 8
    }
    private var renderer: PdfRenderer? = null

    /** 显示页数（一页两开时是物理页数的两倍）。阅读进度、目录页码全用这个编号 */
    var pageCount: Int = 0
        private set

    /** 一页两开：一张纸印了两个书页，显示时要拆开 */
    var spread: Boolean = false
        private set

    /** 打开文档并返回页数；失败会抛异常，由调用方转成 UI 错误提示 */
    suspend fun open(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val newRenderer = PdfRenderer(pfd)
            descriptor = pfd
            renderer = newRenderer
            // 抽样多页取多数：只看第一页会被封面骗过去
            val sample = (0 until minOf(newRenderer.pageCount, SAMPLE_PAGES)).mapNotNull { idx ->
                runCatching {
                    newRenderer.openPage(idx).use { it.width.toFloat() / it.height.toFloat() }
                }.getOrNull()
            }
            spread = com.novelreader.pdf.data.pdf.SpreadLayout.decide(sample)
            pageCount = com.novelreader.pdf.data.pdf.SpreadLayout.displayPageCount(
                newRenderer.pageCount,
                spread,
            )
            pageCount
        }
    }

    /**
     * 渲染单页。
     * @param targetWidth 目标像素宽度，通常传 ViewPager2 的宽度，做到"一次渲染恰好铺满，无需二次缩放"
     */
    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pdfRenderer = renderer ?: return@withLock null
            // 边界按**物理页**检查（显示页可能比物理页多一倍）
            val sheetForBounds = com.novelreader.pdf.data.pdf.SpreadLayout.sheetOf(index, spread)
            if (sheetForBounds < 0 || sheetForBounds >= pdfRenderer.pageCount) return@withLock null
            runCatching {
                // index 是**显示页**：一页两开时换算成"第几张纸 + 左/右半"，整张按两倍宽渲染再裁
                val sheetIndex = com.novelreader.pdf.data.pdf.SpreadLayout.sheetOf(index, spread)
                val sheetWidth = com.novelreader.pdf.data.pdf.SpreadLayout.sheetRenderWidth(
                    targetWidth,
                    spread,
                )
                pdfRenderer.openPage(sheetIndex).use { page ->
                    val scale = sheetWidth.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val sheet = Bitmap.createBitmap(sheetWidth, height, Bitmap.Config.ARGB_8888)
                    sheet.eraseColor(Color.WHITE)
                    page.render(sheet, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (!spread) {
                        sheet
                    } else {
                        val halfX = com.novelreader.pdf.data.pdf.SpreadLayout.halfOf(index, spread) *
                            (sheet.width / 2)
                        val cropped = Bitmap.createBitmap(
                            sheet,
                            halfX,
                            0,
                            sheet.width / 2,
                            sheet.height,
                        )
                        sheet.recycle()
                        cropped
                    }
                }
            }.getOrNull()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}
