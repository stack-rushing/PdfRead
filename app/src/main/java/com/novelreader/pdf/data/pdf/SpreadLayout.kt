package com.novelreader.pdf.data.pdf

/**
 * 「一页两开」（spread）版式：**一张扫描纸上印了两个书页**（摊开的书拍照/扫描）。
 *
 * 这类 PDF 有三个连带影响，必须一起处理，否则会错得很自然：
 * 1. **显示**：一张纸应该拆成两页显示，阅读器只呈现单页（`minSdk 34` 的横屏平板也照样单页）；
 * 2. **页码**：用户看到的第 N 页 ≠ PDF 的第 N 页。全工程统一用**显示页**编号
 *    （章节页码、阅读进度、跳转），换算只发生在渲染层；
 * 3. **OCR**：页眉在**半页**的顶部，而条带只取整张纸的顶部 —— 会同时读到左右两页的页眉，
 *    识别结果黏成一行，章节判定随之失效。所以条带也必须按半页取。
 *
 * 判定只看宽高比：竖版书页约 0.6，两页并排约 1.2。阈值取 1.05，留出裁边与倾斜的余量。
 */
object SpreadLayout {

    /** 宽高比 ≥ 这个值就当作一页两开 */
    const val SPREAD_ASPECT = 1.05f

    fun isSpread(pageWidth: Int, pageHeight: Int): Boolean =
        pageHeight > 0 && pageWidth > 0 && pageWidth.toFloat() / pageHeight >= SPREAD_ASPECT

    /** 显示页总数：一页两开时是物理页数的两倍 */
    fun displayPageCount(sheetCount: Int, spread: Boolean): Int =
        if (spread) sheetCount * 2 else sheetCount

    /** 显示页 → 物理页（第几张纸） */
    fun sheetOf(displayIndex: Int, spread: Boolean): Int =
        if (spread) displayIndex / 2 else displayIndex

    /** 显示页 → 半页：0 = 左半，1 = 右半；非两开版式恒为 0 */
    fun halfOf(displayIndex: Int, spread: Boolean): Int =
        if (spread) displayIndex % 2 else 0

    /**
     * 为了裁出宽 `targetWidth` 的半页，整张纸要按多宽渲染。
     * 非两开版式就是 `targetWidth` 本身。
     */
    fun sheetRenderWidth(targetWidth: Int, spread: Boolean): Int =
        if (spread) targetWidth.coerceAtLeast(1) * 2 else targetWidth.coerceAtLeast(1)
}
