package com.novelreader.pdf.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架的自然排序。
 *
 * 起因是一个真实反馈：按纯字符串排序时 **`2-10` 排在 `2-2` 前面**（'1' < '2'），
 * 而编号是"数字"语义，人期望的是 `2-2 → 2-10 → 2-13`。
 */
class NaturalOrderTest {

    @Test
    fun `数字段按数值大小比`() {
        assertTrue("2-2 应该在 2-10 前面", NaturalOrder.compare("2-2", "2-10") < 0)
        assertTrue(NaturalOrder.compare("2-10", "2-13") < 0)
        assertTrue(NaturalOrder.compare("book9", "book10") < 0)
        assertTrue(NaturalOrder.compare("第9章", "第10章") < 0)
        assertTrue(NaturalOrder.compare("v1", "v2") < 0)
    }

    @Test
    fun `剑桥系列按级数自然递增`() {
        assertTrue(
            NaturalOrder.compare("剑桥小说馆2级07 圆圈故事集", "剑桥小说馆2级16 阿姆斯特丹风波") < 0,
        )
        assertTrue(
            NaturalOrder.compare("剑桥小说馆2级16 阿姆斯特丹风波", "剑桥小说馆2级17 魔幻手机") < 0,
        )
    }

    @Test
    fun `字母忽略大小写，前缀短的在前`() {
        assertEquals("大小写不敏感", 0, NaturalOrder.compare("Apple", "apple"))
        assertTrue("前缀更短的在前", NaturalOrder.compare("a", "ab") < 0)
        assertTrue(NaturalOrder.compare("Cover", "cover page") < 0)
    }

    @Test
    fun `数值相等时前导零多的在前`() {
        assertTrue(NaturalOrder.compare("07", "7") < 0)
        assertTrue(NaturalOrder.compare("第07章", "第7章") < 0)
        // 顺序必须稳定：同一个比较做两次结果一致（排序才不会是随机的）
        assertEquals(NaturalOrder.compare("07", "7"), NaturalOrder.compare("07", "7"))
    }

    @Test
    fun `超长数字不会溢出`() {
        assertTrue(
            NaturalOrder.compare("x99999999999999999998", "x99999999999999999999") < 0,
        )
    }

    @Test
    fun `整张书架的排序结果`() {
        val titles = listOf(
            "2-13 漠客疑云",
            "剑桥小说馆2级16 阿姆斯特丹风波",
            "2-2 圆圈故事集",
            "2-10 某某",
            "剑桥小说馆2级07 圆圈故事集",
        )
        assertEquals(
            listOf(
                "2-2 圆圈故事集",
                "2-10 某某",
                "2-13 漠客疑云",
                "剑桥小说馆2级07 圆圈故事集",
                "剑桥小说馆2级16 阿姆斯特丹风波",
            ),
            titles.sortedWith(NaturalOrder),
        )
    }
}
