package com.novelreader.pdf.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * 从 PDF **文字层**读文本（不是 OCR）。用 PdfBox 实现，零渲染依赖。
 *
 * ## 为什么不用自己写
 * 先写过一版纯手解析（约 700 行），撞出三个缺口后放弃：`/Name` 被当算子丢掉、
 * 正文藏在 Form XObject 里、对象流 `/ObjStm` 里的对象看不见。前两个修掉了，
 * 但**覆盖率仍只有 18%**（Two Lives 抽样 40 页仅 7 页有字，pypdf 同期每页 2200+ 字符），
 * 而且没有字体度量（`/Widths`、`/W`）时词间距必然错。
 * PdfBox 把这些都处理好了，代价是 APK 增大约 8~10 MB。
 *
 * ## 坐标与分栏
 * 用 [TextPosition.xDirAdj] / [TextPosition.yDirAdj]（阅读方向坐标，y 从上往下增长）。
 * **一页两开**时按 `x < 页宽/2` 把文本分到左半或右半——
 * 不做这一步，左右两页的文字会混在一起，目录判定必然错。
 *
 * ## 边界
 * - 超过 [MAX_FILE_BYTES] 不解析：整本读进内存的代价不可接受（例如 600 MB 的词典）；
 * - 没有文字层的扫描件会返回空列表，调用方据此回退到 OCR。
 */
class PdfTextBoxReader(private val file: File) {

    /** 显示页数（一页两开时是物理页数的两倍） */
    var pageCount: Int = 0
        private set

    /** 是不是一页两开：见 [SpreadLayout] */
    var spread: Boolean = false
        private set

    private var document: PDDocument? = null

    /** 打开并加载；失败返回 false（调用方据此回退到 OCR） */
    fun open(): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (file.length() > MAX_FILE_BYTES) return false
        val doc = runCatching { PDDocument.load(file) }.getOrNull() ?: return false
        if (doc.numberOfPages <= 0) {
            runCatching { doc.close() }
            return false
        }
        document = doc
        // 抽样取多数判定一页两开，避免被封面/版权页这类单页骗到（见 SpreadLayout.decide）
        val ratios = (0 until minOf(doc.numberOfPages, SAMPLE_PAGES)).map { i ->
            runCatching {
                val box = doc.getPage(i).mediaBox
                box.width / box.height.coerceAtLeast(1f)
            }.getOrDefault(0f)
        }
        spread = SpreadLayout.decide(ratios)
        pageCount = SpreadLayout.displayPageCount(doc.numberOfPages, spread)
        return true
    }

    /**
     * 某个**显示页**的文本行（从上到下）。
     * 一页两开时只返回该半页的行。
     */
    fun pageLines(displayIndex: Int, maxLines: Int = 8): List<String> {
        val doc = document ?: return emptyList()
        val sheet = SpreadLayout.sheetOf(displayIndex, spread)
        if (sheet < 0 || sheet >= doc.numberOfPages) return emptyList()
        val half = SpreadLayout.halfOf(displayIndex, spread)
        val pageWidth = runCatching { doc.getPage(sheet).mediaBox.width }.getOrDefault(0f)

        // (y, x, 文本)；PdfBox 每"行"回调一次 writeString，所以按 y 排序即可
        val collected = ArrayList<Triple<Float, Float, String>>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                val raw = text?.trim().orEmpty()
                if (raw.isEmpty()) return
                val first = textPositions?.firstOrNull() ?: return
                if (spread) {
                    val isLeft = first.xDirAdj < pageWidth / 2f
                    if ((half == 0) != isLeft) return
                }
                collected += Triple(first.yDirAdj, first.xDirAdj, raw)
            }
        }
        runCatching {
            stripper.startPage = sheet + 1
            stripper.endPage = sheet + 1
            stripper.sortByPosition = true
            stripper.getText(doc)
        }
        return collected
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(maxLines)
            .map { it.third }
    }

    /**
     * 这本书有没有**可用的**文字层（用于决定"文字层优先还是直接 OCR"）。
     * 抽查若干页，平均每页字符数达到阈值才算可用。
     */
    fun hasUsableTextLayer(): Boolean {
        if (document == null) return false
        val probes = minOf(pageCount, PROBE_PAGES).coerceAtLeast(1)
        var chars = 0
        for (i in 0 until probes) {
            chars += pageLines(i, maxLines = 40).sumOf { it.length }
        }
        return chars / probes >= MIN_CHARS_PER_PAGE
    }

    fun close() {
        runCatching { document?.close() }
        document = null
    }

    private companion object {
        /** 判定一页两开时抽样的页数 */
        const val SAMPLE_PAGES = 8

        /** 判断"有没有文字层"时抽查的页数 */
        const val PROBE_PAGES = 6

        /** 平均每页至少这么多字符才算"文字层可用"（低于此多半只有页眉页脚） */
        const val MIN_CHARS_PER_PAGE = 200

        /** 超过这个大小不解析：整本读进内存的代价不可接受 */
        const val MAX_FILE_BYTES = 150L * 1024 * 1024
    }
}
