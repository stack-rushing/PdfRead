package com.novelreader.pdf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩放的边界计算。
 *
 * 这块最容易出的错不是"算错倍数"，而是**忘了夹住边界**：
 * 少一个 clamp，页面就能被拖出屏幕、留下一片空白再也拉不回来；
 * 不夹倍数，双指一捏可以缩到看不见或者放大到把内存吃光。
 * 全是纯函数，测起来很便宜，就没必要留到真机上试。
 */
class ZoomMathTest {

    @Test
    fun `基准倍数让整页刚好放进视口`() {
        // 竖版页面放进竖屏：宽度是限制项（页宽 1080 → 视口 1080，页高更高）
        assertEquals(1f, ZoomMath.fitScale(1080, 1920, 1080, 1920), 0.0001f)
        // 页面比视口高得多 → 按高度受限
        assertEquals(0.5f, ZoomMath.fitScale(1000, 4000, 1000, 2000), 0.0001f)
        // 尺寸非法时退回 1，不产生除零或 NaN
        assertEquals(1f, ZoomMath.fitScale(0, 100, 100, 100), 0.0001f)
        assertEquals(1f, ZoomMath.fitScale(100, 100, 0, 0), 0.0001f)
    }

    @Test
    fun `缩放倍数被夹在上下限内`() {
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.clampScale(0.01f), 0.0001f)
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.clampScale(-3f), 0.0001f)
        assertEquals(ZoomMath.MAX_SCALE, ZoomMath.clampScale(99f), 0.0001f)
        assertEquals(2.5f, ZoomMath.clampScale(2.5f), 0.0001f)
    }

    @Test
    fun `内容比视口小时一律居中`() {
        // 宽 500 的内容放进 1000 的视口 → 左右各留 250
        assertEquals(250f, ZoomMath.clampTranslate(0f, 500f, 1000f), 0.0001f)
        // 就算传进来一个离谱的偏移，也会被拉回居中
        assertEquals(250f, ZoomMath.clampTranslate(-9999f, 500f, 1000f), 0.0001f)
    }

    @Test
    fun `内容比视口大时不允许拖出空白`() {
        // 内容 2000 宽、视口 1000：可平移范围是 -1000 … 0
        assertEquals(0f, ZoomMath.clampTranslate(300f, 2000f, 1000f), 0.0001f)
        assertEquals(-1000f, ZoomMath.clampTranslate(-3000f, 2000f, 1000f), 0.0001f)
        assertEquals(-400f, ZoomMath.clampTranslate(-400f, 2000f, 1000f), 0.0001f)
    }

    @Test
    fun `双击在适应与放大之间切换`() {
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, ZoomMath.doubleTapTarget(ZoomMath.MIN_SCALE), 0.0001f)
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.doubleTapTarget(ZoomMath.DOUBLE_TAP_SCALE), 0.0001f)
        // 任何放大状态双击都回到适应
        assertEquals(ZoomMath.MIN_SCALE, ZoomMath.doubleTapTarget(5f), 0.0001f)
    }

    @Test
    fun `放大到一定程度才值得重渲染高清页`() {
        // 适应状态下不需要：宽度原样返回，调用方据此直接跳过
        assertEquals(1080, ZoomMath.renderWidthFor(1f, 1080))
        assertEquals(1080, ZoomMath.renderWidthFor(1.2f, 1080))
        // 超过阈值才提高分辨率
        assertEquals(2160, ZoomMath.renderWidthFor(2f, 1080))
        assertEquals(2700.coerceAtMost(ZoomMath.MAX_RENDER_WIDTH), ZoomMath.renderWidthFor(2.5f, 1080))
    }

    @Test
    fun `重渲染宽度有上限，免得一次分配几十 MB`() {
        assertEquals(ZoomMath.MAX_RENDER_WIDTH, ZoomMath.renderWidthFor(ZoomMath.MAX_SCALE, 1080))
        // 基准宽度为 0（还没测量出视口宽度）时不崩，返回 1
        assertEquals(1, ZoomMath.renderWidthFor(3f, 0))
    }

    @Test
    fun `是否算放大留有容差`() {
        assertFalse(ZoomMath.isZoomed(1f))
        assertFalse("浮点误差不该被当成放大", ZoomMath.isZoomed(1.0000001f))
        assertTrue(ZoomMath.isZoomed(1.5f))
    }
}
