package com.novelreader.pdf.ui.reader

/**
 * 缩放与平移的纯计算（无 Android 依赖，可直接单元测试）。
 *
 * 单独抽出来的理由：这块看着简单，但**边界钳制**最容易出错——
 * 少一个 clamp，页面就能被拖出屏幕外、留下一片空白再也拉不回来；
 * 缩放倍数不夹，双指一捏就能缩到 0.01 倍或者放大到 100 倍把内存吃光。
 * 这些都能用纯函数测掉，不必等到真机上试。
 */
object ZoomMath {

    /** 最小倍数 = 1 = "适应屏幕"，不允许缩得比整页还小 */
    const val MIN_SCALE = 1f

    /** 上限：扫描件放到 6 倍已经远超原始像素，再大只是糊 */
    const val MAX_SCALE = 6f

    /** 双击放大到的倍数 */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 超过这个倍数就值得按更高分辨率重渲染（1.3 以下肉眼几乎看不出差别，白费内存） */
    const val HIGH_RES_THRESHOLD = 1.3f

    /**
     * 重渲染的像素宽度上限。
     * 一页 ARGB_8888 的 2600×3500 大约 36 MB，再往上放几张就会 OOM。
     */
    const val MAX_RENDER_WIDTH = 2600

    /** 让整页刚好放进视口的基准倍数（fitCenter 语义） */
    fun fitScale(contentWidth: Int, contentHeight: Int, viewWidth: Int, viewHeight: Int): Float {
        if (contentWidth <= 0 || contentHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f
        return minOf(
            viewWidth.toFloat() / contentWidth.toFloat(),
            viewHeight.toFloat() / contentHeight.toFloat(),
        )
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * 夹住平移量：
     * - 内容比视口**小** → 直接居中（此时拖动没有意义）；
     * - 内容比视口**大** → 不允许拖出空白，范围是 `viewSize - scaledSize … 0`。
     */
    fun clampTranslate(offset: Float, scaledSize: Float, viewSize: Float): Float {
        if (scaledSize <= viewSize) return (viewSize - scaledSize) / 2f
        return offset.coerceIn(viewSize - scaledSize, 0f)
    }

    /** 双击行为：已放大 → 回到适应；否则放大到 [DOUBLE_TAP_SCALE] */
    fun doubleTapTarget(currentScale: Float): Float =
        if (currentScale > MIN_SCALE * 1.01f) MIN_SCALE else DOUBLE_TAP_SCALE

    /**
     * 该按多宽重新渲染。
     * 不需要更高分辨率（或没有基准宽度）时原样返回 [baseWidth]，调用方据此直接跳过。
     */
    fun renderWidthFor(scale: Float, baseWidth: Int): Int {
        if (baseWidth <= 0 || scale < HIGH_RES_THRESHOLD) return baseWidth.coerceAtLeast(1)
        return (baseWidth * scale).toInt().coerceIn(baseWidth, MAX_RENDER_WIDTH)
    }

    /** 是否算"已放大"（留 1% 容差，避免浮点误差把适应状态判成放大） */
    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE * 1.01f
}
