package com.novelreader.pdf.data.toc

import com.novelreader.pdf.data.model.ChapterKind
import com.novelreader.pdf.data.model.ChapterResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实的 OCR 结果**回归「扫描版自动生成目录」。
 *
 * 夹具 `src/test/resources/cambridge_ocr_strips.tsv` 是剑桥《真爱无界》（92 页扫描件、
 * 无内置书签）用 Windows OCR 对每页顶部 20% 条带、720px 宽识别出来的原始文本——
 * 与 App 里 ML Kit 的输入形态、渲染参数完全一致。
 *
 * 这份数据里包含了一批真实的识别错误，正是这些错误逼出了现在的规则：
 *   `Chapter 乥 Shopping`（2 被认成汉字）、`Chapter 5 My friend`（3 被认成 5）、
 *   `Afier reading`（t→i）、`TransIation`（l→I）、`PeopIe`（l→I）、`刖口`（前言）。
 */
class OcrChapterBuilderTest {

    private fun loadFixture(): Map<Int, List<String>> = loadFixture("cambridge_ocr_strips.tsv")

    private fun loadFixture(name: String): Map<Int, List<String>> {
        val stream = javaClass.getResourceAsStream("/$name")
            ?: error("缺少 OCR 测试夹具 $name")
        return stream.bufferedReader(Charsets.UTF_8).useLines { seq ->
            seq.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val index = line.substring(0, tab).removePrefix("p").toIntOrNull()
                    ?: return@mapNotNull null
                index to line.substring(tab + 1).split('\u0001')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }.toMap()
        }
    }

    private fun replayFixture(name: String, totalPages: Int): List<com.novelreader.pdf.data.model.Chapter> {
        val pages = loadFixture(name)
        val builder = OcrChapterBuilder(totalPages)
        pages.toSortedMap().forEach { (page, lines) -> builder.accept(page, lines) }
        return builder.finish()
    }

    private fun replay(): List<com.novelreader.pdf.data.model.Chapter> {
        val pages = loadFixture()
        val builder = OcrChapterBuilder(totalPages = 92)
        pages.toSortedMap().forEach { (page, lines) -> builder.accept(page, lines) }
        return builder.finish()
    }

    @Test
    fun `夹具完整性`() {
        val pages = loadFixture()
        assertEquals("扫描件有 91 页带图（第 92 页是纯文字版权页）", 91, pages.size)
        assertTrue("第 14 页应含 Chapter 1 标题", pages.getValue(13).any { it.startsWith("Chapter 1") })
    }

    @Test
    fun `真实 OCR 结果能还原出完整目录`() {
        val chapters = replay()
        chapters.forEach { println("  P${it.startPage + 1} ${it.kind} ${it.title} / ${it.titleEn}") }

        assertEquals("条目总数", 19, chapters.size)

        // ---- 11 个正文章节：页码全部正确 ----
        val body = chapters.filter { it.kind == ChapterKind.CHAPTER }
        assertEquals(
            "正文章节页码",
            listOf(13, 17, 22, 25, 29, 33, 37, 42, 48, 52, 57),
            body.map { it.startPage },
        )
        // ---- 编号来自序号，而不是被误识别的数字 ----
        body.forEachIndexed { i, c ->
            assertTrue("第 ${i + 1} 章应以 'Chapter ${i + 1} ' 开头，实为 ${c.title}", c.title.startsWith("Chapter ${i + 1} "))
        }
        assertEquals("OCR 把 2 认成汉字也要救回来", "Chapter 2 Shopping", chapters.first { it.startPage == 17 }.title)
        assertEquals("OCR 把 3 认成 5，编号必须按序号纠正", "Chapter 3 My friend", chapters.first { it.startPage == 22 }.title)
        assertEquals("OCR 把 11 拆成两个 1", "Chapter 11 Happy ever after", chapters.first { it.startPage == 57 }.title)
        assertEquals("折行标题要拼接", "Chapter 1 A hand on a guitar", chapters.first { it.startPage == 13 }.title)
        assertEquals("尾部符号要清掉", "Chapter 5 snow", chapters.first { it.startPage == 29 }.title)

        // ---- 前后附文：中文主标题 + 规范化英文副标题 ----
        val byPage = chapters.associateBy { it.startPage }
        assertEquals("目录", byPage[4]?.title)
        assertEquals("Contents", byPage[4]?.titleEn)
        assertEquals("刖口 是 前言 的误识，2 个汉字时用规范名", "前言", byPage[7]?.title)
        assertEquals("Preface", byPage[7]?.titleEn)
        assertEquals("故事中的人物", byPage[10]?.title)
        assertEquals("People in the story", byPage[10]?.titleEn)
        assertEquals("故事中的地点", byPage[11]?.title)
        assertEquals("读前思考", byPage[12]?.title)
        assertEquals("Before reading", byPage[12]?.titleEn)
        assertEquals("读后活动", byPage[59]?.title)
        assertEquals("Afier reading 也要规范成 After reading", "After reading", byPage[59]?.titleEn)
        assertEquals("学习指导", byPage[60]?.title)
        assertEquals("参考译文", byPage[69]?.title)
        assertEquals("TransIation 也要规范成 Translation", "Translation", byPage[69]?.titleEn)

        // ---- 关键回归 1：附文开始后不得再冒出新章节 ----
        assertTrue(
            "《学习指导》里的 Chapter 2u/Chapter 3u 页眉不应产生章节",
            body.none { it.startPage > 57 },
        )
        // ---- 关键回归 2：两个"目录"页只保留一个跳转目标 ----
        assertEquals(1, chapters.count { it.title == "目录" })
        // ---- 区间可解析 ----
        chapters.forEach { assertTrue("${it.title} 区间未解析", it.isResolved) }
    }

    @Test
    fun `正文行里的关键词不会误判成附文`() {
        val builder = OcrChapterBuilder(100)
        // 真书第 2 页封底介绍里出现"练习答案"，锚定行首后不应命中
        assertNull(builder.accept(1, listOf("故 事 类 别 ：", "情 感", "配 套 录 音 练 习 答 案")))
        // 真书第 36 页顶部是正文，OCR 里带 cover 之类的碎片
        assertNull(builder.accept(35, listOf("standing near the bottles of wine with a", "young man")))
        assertTrue(builder.finish().isEmpty())
    }

    @Test
    fun `没有标题的页不产生垃圾条目`() {
        val builder = OcrChapterBuilder(100)
        builder.accept(0, listOf(""))
        builder.accept(1, listOf("2", "3"))
        builder.accept(2, listOf("A".repeat(80)))
        assertTrue(builder.finish().isEmpty())
    }

    @Test
    fun `逐词编辑距离按预期工作`() {
        assertEquals(0, HeadingDetector.levenshtein("chapter", "chapter"))
        assertEquals(1, HeadingDetector.levenshtein("afier", "after"))
        assertEquals(1, HeadingDetector.levenshtein("transiation", "translation"))
        // 真实误识：PeopIe 里的 l 被认成大写 I，lowercase 后与 people 距离正好 1
        assertEquals(1, HeadingDetector.levenshtein("peopIe".lowercase(), "people"))
        assertTrue(HeadingDetector.levenshtein("xyz", "chapter") > 1)
    }
    @Test
    fun `导出的成品 JSON 能被 App 正确导入`() {
        val stream = javaClass.getResourceAsStream("/chapters-cambridge.json")
            ?: error("缺少成品 JSON")
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val config = ChapterResolver.decodeConfig(raw) ?: error("成品 JSON 解析失败")
        assertEquals("导出统一用 1 基页码", 1, config.pageNumberBase)

        val chapters = ChapterResolver.toChapters(config, totalPages = 92)
        assertEquals(19, chapters.size)
        assertEquals("目录 起于第 5 页、止于第 7 页", 5, chapters.first().startPage + 1)
        assertEquals(7, chapters.first().endPage + 1)
        val body = chapters.filter { it.kind == ChapterKind.CHAPTER }
        assertEquals(11, body.size)
        assertEquals("第 1 章应落在 PDF 第 14 页", 14, body.first().startPage + 1)
        assertEquals("第 11 章应落在 PDF 第 58 页", 58, body.last().startPage + 1)
        assertEquals("标题里的 Chapter 编号来自序号", "Chapter 1 A hand on a guitar", body.first().title)
    }

    // ==================================================================================
    //  剑桥小说馆这一套书里同时存在**三种**章节页排版。只用《真爱无界》一本调规则，
    //  另外两种版式会整段漏掉——下面三个夹具就是按这个补的，全部来自真实扫描件。
    // ==================================================================================

    @Test
    fun `版式二 编号独占一行时标题在下一行`() {
        // 2级17《魔幻手机》：页眉是 `Chapter 1` 换行 `It's not fair!`
        // 与印刷目录逐页一致（PDF 页 = 印刷页 + 8）
        val chapters = replayFixture("cambridge_2_17_strips.tsv", 102)
        chapters.forEach { println("  P${it.startPage + 1} ${it.kind} ${it.title}") }

        val body = chapters.filter { it.kind == ChapterKind.CHAPTER }
        assertEquals("10 章一条都不能少", 10, body.size)
        assertEquals(
            "章节页码（0 基页索引，对应夹具里的 p015/p020/…）",
            listOf(15, 20, 26, 31, 40, 44, 49, 54, 58, 66),
            body.map { it.startPage },
        )
        // 编号一律来自序号：这一页的 `Chapter` 后面那个字 OCR 认成了汉字"乥"
        assertEquals("Chapter 2 lt's amazing ！", body[1].title)
        // 这一页 OCR 把 3 认成了 5，按序号纠正回 3
        assertEquals("Chapter 3 lhat's strange ！", body[2].title)
        // 标题独占下一行时也要取到
        assertEquals("Chapter 6 We'll be famous", body[5].title)
        // 收尾附文用规范名，不被页眉上糊过来的章节名带偏
        assertEquals("参考译文", chapters.first { it.title == "参考译文" }.title)
        assertEquals("词汇与表达", chapters.last().title)
    }

    @Test
    fun `版式三 整页页眉只有一行标题`() {
        // 2级07《圆圈故事集》：短篇集，压根没有 `Chapter N`，页眉就是标题本身
        val chapters = replayFixture("cambridge_2_07_strips.tsv", 94)
        chapters.forEach { println("  P${it.startPage + 1} ${it.kind} ${it.title}") }

        val body = chapters.filter { it.kind == ChapterKind.CHAPTER }
        assertEquals("5 个故事", 5, body.size)
        assertEquals(
            "章节页码（0 基页索引，对应夹具里的 p010/p020/…）",
            listOf(10, 20, 26, 38, 45),
            body.map { it.startPage },
        )
        assertEquals("Chapter 1 Quick Man Tan", body[0].title)
        assertEquals("Chapter 5 Beautiful Thing", body[4].title)
    }

    @Test
    fun `短篇合集里夹在中间的 ACTIVITIES 不能截断后面的故事`() {
        // 2级18《派对与礼物》：三个短篇，每篇后面都夹着 ACTIVITIES 活动页。
        // 早期规则"见到第一个后置附文就停止"会把第 2、3 个故事整段丢掉。
        val chapters = replayFixture("cambridge_2_18_strips.tsv", 102)
        chapters.forEach { println("  P${it.startPage + 1} ${it.kind} ${it.title}") }

        val body = chapters.filter { it.kind == ChapterKind.CHAPTER }
        assertEquals("3 个故事都要", 3, body.size)
        assertEquals(
            "章节页码（0 基页索引）",
            listOf(14, 42, 58),
            body.map { it.startPage },
        )
        assertTrue("活动页本身仍应作为一个附文条目", chapters.any { it.title == "活动" })
    }

    @Test
    fun `无编号版式的噪声行不会被当成章节`() {
        // 下面这些全是真实 OCR 里出现过的"只有一行"的条带，但都不是章节标题
        assertNull("纯数字", HeadingDetector.bareTitle(listOf("0")))
        assertNull("汉字噪声", HeadingDetector.bareTitle(listOf("孬 ． 疒 艹 、")))
        assertNull("全大写版面标记", HeadingDetector.bareTitle(listOf("LOOKING BACK")))
        assertNull("中文书名号", HeadingDetector.bareTitle(listOf("北 语 社 “ 剑 桥 国 际 英 《 系 列 经 典 教 材 推 荐")))
        assertNull("正文残句带句末标点", HeadingDetector.bareTitle(listOf("Henry was the cook.")))
        assertNull("两行就不是这个版式", HeadingDetector.bareTitle(listOf("Quick Man Tan", "CaIeb had more")))
        assertEquals("真的标题要认出来", "The Wheel on the Wall", HeadingDetector.bareTitle(listOf("The Wheel on the Wall")))

        // 编号独占一行时 chapterBody 返回空串（而不是 null）——这是版式二的信号
        assertEquals("", HeadingDetector.chapterBody("Chapter 1"))
        assertEquals("", HeadingDetector.chapterBody("hapter 乥"))
        assertEquals("A hand on a guitar", HeadingDetector.chapterBody("Chapter 1 A hand on a guitar"))

        // 终止性附文的判定：Translation 之后不再收章节，Activities 之后还要继续收
        assertEquals(true, HeadingDetector.isTerminalBack(HeadingDetector.sectionHit("Translation")!!))
        assertEquals(true, HeadingDetector.isTerminalBack(HeadingDetector.sectionHit("Learning guide")!!))
        assertEquals(false, HeadingDetector.isTerminalBack(HeadingDetector.sectionHit("After reading")!!))
        assertEquals(false, HeadingDetector.isTerminalBack(HeadingDetector.sectionHit("ACTIVITIES PAGES 6 一 14")!!))
    }
}