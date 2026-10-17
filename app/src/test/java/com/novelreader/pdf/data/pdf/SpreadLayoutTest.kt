package com.novelreader.pdf.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一页两开版式的换算。
 *
 * 现实样本：`3Just Good Friends.pdf` 是 34 张扫描纸、每张印两个书页（宽高比 1.26），
 * 也就是 68 个显示页；页眉在**半页**顶部，所以 OCR 条带必须按半页取，否则左右两页的
 * 页眉会黏成一行，章节判定直接失效。
 */
class SpreadLayoutTest {

    @Test
    fun `按宽高比判定是否两开`() {
        // 竖版书页（0.63）——不是两开
        assertFalse(SpreadLayout.isSpread(630, 1000))
        // 两页并排（1.26）——是两开
        assertTrue(SpreadLayout.isSpread(1260, 1000))
        // 真实样本
        assertTrue(SpreadLayout.isSpread(2905, 2295))
        // 阈值本身算两开；再窄一点就不算
        assertTrue(SpreadLayout.isSpread(1050, 1000))
        assertFalse(SpreadLayout.isSpread(1040, 1000))
        // 非法尺寸不崩
        assertFalse(SpreadLayout.isSpread(0, 1000))
        assertFalse(SpreadLayout.isSpread(1000, 0))
    }

    @Test
    fun `显示页是物理页的两倍`() {
        assertEquals(68, SpreadLayout.displayPageCount(34, spread = true))
        assertEquals(34, SpreadLayout.displayPageCount(34, spread = false))
        assertEquals(0, SpreadLayout.displayPageCount(0, spread = true))
    }

    @Test
    fun `显示页换算成第几张纸与左右半`() {
        // 两开：0,1 → 第 0 张的左右；2,3 → 第 1 张
        assertEquals(0, SpreadLayout.sheetOf(0, true))
        assertEquals(0, SpreadLayout.sheetOf(1, true))
        assertEquals(1, SpreadLayout.sheetOf(2, true))
        assertEquals(17, SpreadLayout.sheetOf(35, true))
        assertEquals(0, SpreadLayout.halfOf(0, true))
        assertEquals(1, SpreadLayout.halfOf(1, true))
        assertEquals(0, SpreadLayout.halfOf(2, true))
        assertEquals(1, SpreadLayout.halfOf(35, true))
        // 非两开：一一对应
        assertEquals(7, SpreadLayout.sheetOf(7, false))
        assertEquals(0, SpreadLayout.halfOf(7, false))
    }

    @Test
    fun `整张纸按两倍宽渲染才能裁出目标宽度`() {
        assertEquals(2160, SpreadLayout.sheetRenderWidth(1080, spread = true))
        assertEquals(1080, SpreadLayout.sheetRenderWidth(1080, spread = false))
        // 宽度非法时兜底为 1，不产生 0 宽位图
        assertEquals(2, SpreadLayout.sheetRenderWidth(0, spread = true))
        assertEquals(1, SpreadLayout.sheetRenderWidth(0, spread = false))
    }
}
