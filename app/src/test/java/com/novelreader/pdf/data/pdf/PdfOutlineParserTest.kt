package com.novelreader.pdf.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

/**
 * `PdfOutlineParser` 的回归测试。
 *
 * 两本真实书都是**经典 xref 表**，覆盖不到 PDF 1.5+ 的主流写法，所以这里在测试内
 * 现场合成一个「xref 流 + 对象流(ObjStm) + PNG Predictor 12」的 PDF：
 * 9 个对象全部塞进对象流，三层书签，指向第 1 / 第 3 页。
 *
 * 这条用例曾一次抓出"对象流里下标为 0 的对象被 `if (f2 > 0)` 挡掉"的缺陷——
 * 而现代 PDF 的第一个对象常常就是 Catalog，会直接导致整本读不出目录。
 */
class PdfOutlineParserTest {

    @Test
    fun `能解析 xref 流加对象流加 PNG predictor 的现代 PDF`() {
        val file = SyntheticModernPdf.write(File.createTempFile("synthetic-modern", ".pdf"))
        val result = PdfOutlineParser().parse(file)

        assertEquals("页数", 3, result.pageCount)
        assertEquals("书签条数", 3, result.nodes.size)

        val (n0, n1, n2) = Triple(result.nodes[0], result.nodes[1], result.nodes[2])
        assertEquals("Chapter 1 The Beginning", n0.title)
        assertEquals(0, n0.level)
        assertEquals("第一个书签应指向第 1 页", 0, n0.pageIndex)
        assertEquals("Chapter 2 The Middle", n1.title)
        assertEquals(0, n1.level)
        // 该条目故意不写 /Dest：必须返回 -1，而不是伪造一个页码
        assertEquals("没有目标的条目应返回 -1", -1, n1.pageIndex)
        assertEquals("Section 2.1 Nested", n2.title)
        assertEquals("二级书签层级", 1, n2.level)
        assertEquals("二级书签应指向第 3 页", 2, n2.pageIndex)

        file.delete()
    }

    @Test
    fun `没有大纲的 PDF 返回空且不抛异常`() {
        val file = SyntheticModernPdf.write(File.createTempFile("synthetic-nooutline", ".pdf"), withOutline = false)
        val result = PdfOutlineParser().parse(file)
        assertEquals(3, result.pageCount)
        assertTrue("应返回空列表而不是异常", result.nodes.isEmpty())
        file.delete()
    }

    @Test
    fun `不存在的文件安全返回空`() {
        val result = PdfOutlineParser().parse(File("/definitely/not/here.pdf"))
        assertTrue(result.nodes.isEmpty())
        assertEquals(0, result.pageCount)
    }

    /**
     * 真机/开发机上如果放着那两本书，就顺手做一次真实文件回归。
     * 文件不存在时整条用例直接跳过（不会因为别人的机器没这些书而失败）。
     */
    @Test
    fun `真实书籍回归（文件存在时才跑）`() {
        val cases = listOf(
            "剑桥小说馆2级08 真爱无界" to intArrayOf(92, 0),
            "书虫·牛津英汉双语读物" to intArrayOf(2278, 506),
        )
        val desktop = File(System.getProperty("user.home"), "Desktop")
        var ran = 0
        cases.forEach { (prefix, expect) ->
            val pdf = desktop.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".pdf") }
                ?: return@forEach
            val result = PdfOutlineParser().parse(pdf)
            assertEquals("$prefix 页数", expect[0], result.pageCount)
            assertEquals("$prefix 书签数", expect[1], result.nodes.size)
            ran++
        }
        println("真实书籍回归：跑了 $ran 个文件（缺失则跳过）")
    }
}

/** 现场合成一个「xref 流 + 对象流 + PNG Predictor(Up)」的 PDF 1.5 */
private object SyntheticModernPdf {

    fun write(target: File, withOutline: Boolean = true): File {
        val bodies = LinkedHashMap<Int, ByteArray>()
        bodies[1] = "<< /Type /Catalog /Pages 2 0 R${if (withOutline) " /Outlines 6 0 R" else ""} >>".toByteArray()
        bodies[2] = "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>".toByteArray()
        bodies[3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".toByteArray()
        bodies[4] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".toByteArray()
        bodies[5] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".toByteArray()
        if (withOutline) {
            bodies[6] = "<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 3 >>".toByteArray()
            bodies[7] = "<< /Title (Chapter 1 The Beginning) /Parent 6 0 R /Next 8 0 R /Dest [3 0 R /Fit] >>".toByteArray()
            bodies[8] = "<< /Title (Chapter 2 The Middle) /Parent 6 0 R /Prev 7 0 R /First 9 0 R /Last 9 0 R /Count 1 >>".toByteArray()
            bodies[9] = "<< /Title (Section 2.1 Nested) /Parent 8 0 R /Dest [5 0 R /XYZ null null null] >>".toByteArray()
        }

        // ---- 对象流：头部是 n 组 "对象号 相对偏移" ----
        val header = StringBuilder()
        var rel = 0
        bodies.keys.sorted().forEach { num ->
            header.append(num).append(' ').append(rel).append(' ')
            rel += bodies.getValue(num).size + 1
        }
        val headerBytes = header.toString().toByteArray(Charsets.ISO_8859_1)
        val payload = ByteArrayOutputStream().apply {
            write(headerBytes)
            bodies.keys.sorted().forEach { write(bodies.getValue(it)); write(' '.code) }
        }.toByteArray()
        val objStmData = deflate(payload)

        // 动态算对象号，保证 withOutline=false 时也是一个结构自洽的 PDF
        val objStmNum = bodies.size + 1
        val xrefNum = bodies.size + 2
        val size = bodies.size + 3

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        out.write("%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))

        val offObjStm = out.size()
        out.write(
            (
                "$objStmNum 0 obj\n<< /Type /ObjStm /N ${bodies.size} /First ${headerBytes.size} " +
                    "/Filter /FlateDecode /Length ${objStmData.size} >>\nstream\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        out.write(objStmData)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))

        val offXref = out.size()

        // ---- xref 流：每行 W = [1 4 2] ----
        val stmIndex = bodies.keys.sorted().withIndex().associate { (i, n) -> n to i }
        val rows = ArrayList<ByteArray>(size)
        rows += ByteArray(1) + intToBytes(0, 4) + intToBytes(65535, 2)          // 0: 空闲
        bodies.keys.sorted().forEach { n ->                                     // 1..9: 在对象流里
            rows += byteArrayOf(2) + intToBytes(objStmNum, 4) + intToBytes(stmIndex.getValue(n), 2)
        }
        rows += byteArrayOf(1) + intToBytes(offObjStm, 4) + intToBytes(0, 2)     // 10: ObjStm
        rows += byteArrayOf(1) + intToBytes(offXref, 4) + intToBytes(0, 2)       // 11: 本 xref 流

        // PNG Predictor 12 (Up)：先写过滤类型 2，再写与上一行的差值
        val raw = ByteArrayOutputStream()
        var prev = ByteArray(7)
        rows.forEach { row ->
            raw.write(2)
            row.forEachIndexed { i, b -> raw.write((b.toInt() - prev[i].toInt()) and 0xFF) }
            prev = row
        }
        val xrefData = deflate(raw.toByteArray())

        out.write(
            (
                "$xrefNum 0 obj\n<< /Type /XRef /Size $size /W [1 4 2] /Index [0 $size] /Root 1 0 R " +
                    "/Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 7 /Colors 1 " +
                    "/BitsPerComponent 8 >> /Length ${xrefData.size} >>\nstream\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        out.write(xrefData)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        out.write("startxref\n$offXref\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))

        target.writeBytes(out.toByteArray())
        return target
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos, Deflater(9)).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun intToBytes(value: Int, len: Int): ByteArray =
        ByteArray(len) { i -> ((value shr (8 * (len - 1 - i))) and 0xFF).toByte() }
}
