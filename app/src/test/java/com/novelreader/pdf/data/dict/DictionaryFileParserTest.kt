package com.novelreader.pdf.data.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * 词书文件解析。
 *
 * 最关键的一条：**ECDICT 风格 CSV 的中文释义字段里既有逗号也有换行**
 * （形如 `"n. 苹果\nn. 苹果树"`），直接 `split(',')` 会把一条词条切成好几行垃圾。
 * 下面第一个用例就是照着这个真实形态写的。
 */
class DictionaryFileParserTest {

    private val parser = DictionaryFileParser()

    private fun batches(text: String, format: DictFormat, size: Int = 10): List<ParsedEntry> {
        val stream = parser.Stream(BufferedReader(StringReader(text)), format)
        val out = mutableListOf<ParsedEntry>()
        while (true) {
            val batch = stream.nextBatch(size)
            if (batch.isEmpty()) break
            out += batch
        }
        return out
    }

    @Test
    fun `带引号的字段里可以同时有逗号和换行`() {
        val csv = buildString {
            append("word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange\n")
            append("apple,ˈæpl,\"a round fruit\nany tree\",\"n. 苹果\nn. 苹果树\",n:100,5,1,cet4 zk,1200,900,s:apples\n")
            append("run,rʌn,to move fast,跑；经营,v:80/n:20,5,1,cet4,245,189,\"p:ran/d:run/i:running/3:runs\"\n")
        }
        val entries = batches(csv, DictFormat.CSV)
        assertEquals(2, entries.size)
        assertEquals("apple", entries[0].word)
        assertEquals("ˈæpl", entries[0].phonetic)
        assertEquals("a round fruit\nany tree", entries[0].definition)
        assertEquals("n. 苹果\nn. 苹果树", entries[0].translation)
        assertEquals(5, entries[0].collins)
        assertEquals(1, entries[0].oxford)
        assertEquals("cet4 zk", entries[0].tag)
        assertEquals(1200, entries[0].bnc)
        assertEquals("s:apples", entries[0].exchange)
        assertEquals("run", entries[1].word)
    }

    @Test
    fun `列顺序打乱也能靠表头对上`() {
        val csv = "frq,word,translation,bnc\n900,apple,苹果,1200\n"
        val entries = batches(csv, DictFormat.CSV)
        assertEquals(1, entries.size)
        assertEquals("apple", entries[0].word)
        assertEquals("苹果", entries[0].translation)
        assertEquals(1200, entries[0].bnc)
        assertEquals(900, entries[0].frq)
    }

    @Test
    fun `没有表头的两列词表按通用形态处理`() {
        val tsv = "apple\t苹果\nrun\t跑；经营\n\nbanana\t香蕉\n"
        val entries = batches(tsv, DictFormat.TSV)
        assertEquals(3, entries.size)
        assertEquals("apple", entries[0].word)
        assertEquals("苹果", entries[0].translation)
        assertEquals("香蕉", entries[2].translation)
        // 通用形态没有音标/词频，不该编造
        assertNull(entries[0].phonetic)
        assertEquals(0, entries[0].frq)
    }

    @Test
    fun `JSON 词书`() {
        val json = """[{"word":"apple","translation":"苹果","phonetic":"ˈæpl","collins":5},
                       {"headword":"run","translation":"跑"}]"""
        val entries = batches(json, DictFormat.JSON)
        assertEquals(2, entries.size)
        assertEquals("apple", entries[0].word)
        assertEquals(5, entries[0].collins)
        assertEquals("run", entries[1].word)
    }

    @Test
    fun `按批读取不会漏条也不会重复`() {
        val tsv = (1..25).joinToString("\n") { "word$it\t释义$it" }
        val entries = batches(tsv, DictFormat.TSV, size = 7)
        assertEquals(25, entries.size)
        assertEquals("word1", entries.first().word)
        assertEquals("word25", entries.last().word)
        assertEquals(25, entries.map { it.word }.distinct().size)
    }

    @Test
    fun `格式探测`() {
        assertEquals(DictFormat.JSON, parser.detectFormat("dict.json", "["))
        assertEquals(DictFormat.TSV, parser.detectFormat("dict.tsv", "word\t释义"))
        assertEquals(DictFormat.CSV, parser.detectFormat("ecdict.csv", "word,translation"))
        // 没有扩展名：看首行
        assertEquals(DictFormat.TSV, parser.detectFormat("mydict", "apple\t苹果"))
        assertEquals(DictFormat.JSON, parser.detectFormat("mydict", "  [{"))
    }

    @Test
    fun `查询键归一化在两侧一致`() {
        assertEquals("running", DictionaryNormalizer.normalize("Running"))
        assertEquals("running", DictionaryNormalizer.normalize("  running, "))
        assertEquals("dont", DictionaryNormalizer.normalize("don't"))
        assertEquals("苹果", DictionaryNormalizer.normalize("苹果。"))
        assertEquals("", DictionaryNormalizer.normalize("  ---  "))
    }

    @Test
    fun `词形变化只在原形条目上建映射`() {
        // 原形条目：给出各种变形
        val forms = DictionaryNormalizer.formsOf("d:perceived/p:perceived/3:perceives")
        assertEquals(listOf("perceived", "perceives"), forms)

        // 变形条目（带 0: 指向原形）不建映射，否则会出现 running → running 这种自环
        assertTrue(DictionaryNormalizer.formsOf("0:run/1:i").isEmpty())
        assertTrue(DictionaryNormalizer.formsOf(null).isEmpty())
        assertTrue(DictionaryNormalizer.formsOf("").isEmpty())
    }

    @Test
    fun `词卡排版`() {
        assertEquals("★★★", DictCardFormatter.stars(3))
        assertEquals("", DictCardFormatter.stars(0))

        val card = DictCardFormatter.format(
            word = "run",
            phonetic = "rʌn",
            prettyPos = DictionaryNormalizer.prettyPos("v:80/n:20"),
            translation = "跑；经营",
            definition = "to move fast",
            exchange = "p:ran/d:run/i:running/3:runs",
            collins = 5,
            oxford = 1,
            tag = "cet4 zk",
            bnc = 245,
            frq = 189,
            matchedForm = null,
        )
        assertTrue(card.startsWith("run  /rʌn/"))
        assertTrue("星级要画出来", card.contains("柯林斯 ★★★★★"))
        assertTrue("牛津与考试标签要显示", card.contains("牛津3000") && card.contains("cet4·zk"))
        assertTrue(card.contains("【释义】\n跑；经营"))
        assertTrue(card.contains("过去式 ran"))
        assertTrue(card.contains("过去分词 run"))
        assertTrue(card.contains("三单 runs"))
    }

    @Test
    fun `词卡不显示空字段`() {
        val card = DictCardFormatter.format(
            word = "apple", phonetic = null, prettyPos = null, translation = "苹果",
            definition = null, exchange = null, collins = 0, oxford = 0,
            tag = null, bnc = 0, frq = 0,
        )
        assertEquals("apple\n【释义】\n苹果", card)
    }

    @Test
    fun `模糊匹配的编辑距离`() {
        assertEquals(0, DictionaryNormalizer.levenshtein("apple", "apple"))
        assertEquals(1, DictionaryNormalizer.levenshtein("appIe".lowercase(), "apple"))
        assertEquals(1, DictionaryNormalizer.levenshtein("runing", "running"))
        assertTrue(DictionaryNormalizer.levenshtein("banana", "apple") > 1)
    }
}
