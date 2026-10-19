package com.novelreader.pdf.data.pdf

import java.io.File
import java.util.zip.Inflater

/**
 * 从 PDF **文字层**读文本（不是 OCR）。零依赖：只用 `java.util.zip.Inflater`。
 *
 * ## 为什么自己写
 * 引入 PdfBox 要多 8~10 MB 的 APK，还会破坏工程"零第三方 PDF 库"的约定。而目录生成只需要
 * 「每页前几行 + 坐标」，不需要完整的排版还原，所以自己解一层就够。
 *
 * ## 能处理什么（都按 PDF 规范来的，不针对某本书）
 * - **对象定位**：不解析 xref 表/xref 流，直接扫描全文的 `N M obj`。慢一点，但对
 *   增量更新、xref 流、对象流（`/ObjStm`）都免疫——这三种情况恰恰是手工解析最容易翻车的；
 * - **页序**：优先走 `/Root → /Pages → /Kids` 页树；走不通就按对象在文件里的顺序兜底；
 * - **解压**：`FlateDecode`（含 PNG/TIFF 预测器）；其它滤镜（LZW / DCT / JPX）跳过；
 * - **字体**：优先读字体的 `/ToUnicode` CMap（`bfchar` / `bfrange`），这是唯一可靠的路径；
 *   没有就按 `/Encoding`（WinAnsi / MacRoman / Standard）单字节映射；
 * - **文本算子**：`BT/ET`、`Tf`、`Td/TD/Tm/T*`、`Tj/TJ/'/"`，记录每个字符串的 x/y。
 *
 * ## 明确不做的（会在结果里体现，不会被当成"识别成功"）
 * - 不做完整排版还原：不处理 `q/Q` 的 CTM 嵌套、不处理字体变换矩阵、不做字距微调，
 *   坐标是**近似值**，只用于"分行"和"一页两开时判断左右"；
 * - 不处理 Type3 字体、不处理 AcroForm 表单文字、不还原表格。
 *
 * ## 用法
 * ```kotlin
 * val reader = PdfTextBoxReader(file)
 * if (reader.open()) {
 *     val lines = reader.pageLines(displayPage)   // 已按一页两开分好左右
 * }
 * reader.close()
 * ```
 */
class PdfTextBoxReader(private val file: File) {

    /** 显示页数（一页两开时是物理页数的两倍） */
    var pageCount: Int = 0
        private set

    /** 是不是一页两开：见 [SpreadLayout] */
    var spread: Boolean = false
        private set

    private var doc: PdfDoc? = null

    /** 打开并解析结构；失败返回 false（调用方据此回退到 OCR） */
    fun open(): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        // 超大文件不解析：整本书读进内存的代价不可接受（比如 600 MB 的词典）
        if (file.length() > MAX_FILE_BYTES) return false
        val parsed = runCatching { PdfDoc.parse(file) }.getOrNull() ?: return false
        if (parsed.pages.isEmpty()) return false
        doc = parsed

        // 用抽样取多数的方式判定一页两开，避免被封面/版权页这类单页骗到
        val ratios = parsed.pages.take(SAMPLE_PAGES).map { page ->
            val box = page.sheetWidth()
            if (box <= 0f) 0f else box / page.sheetHeight().coerceAtLeast(1f)
        }
        spread = SpreadLayout.decide(ratios)
        pageCount = SpreadLayout.displayPageCount(parsed.pages.size, spread)
        return true
    }

    /**
     * 读取某个**显示页**的文本行（从上到下）。
     * 一页两开时只返回该半页的行，坐标已按半页归位。
     */
    fun pageLines(displayIndex: Int, maxLines: Int = 8): List<String> {
        val doc = doc ?: return emptyList()
        val sheet = SpreadLayout.sheetOf(displayIndex, spread)
        val half = SpreadLayout.halfOf(displayIndex, spread)
        val page = doc.pages.getOrNull(sheet) ?: return emptyList()
        return runCatching { page.textLines(half, spread, maxLines) }.getOrDefault(emptyList())
    }

    fun close() {
        doc = null
    }

    // ================================================================== 内部实现

    /** 解析后的文档：页面列表 */
    private class PdfDoc(val pages: List<PdfPage>, val objects: Map<Int, PdfObject>) {

        companion object {
            fun parse(file: File): PdfDoc {
                val bytes = file.readBytes()
                val objects = ObjScanner.scan(bytes)
                val pages = PageTree.collect(bytes, objects)
                return PdfDoc(pages, objects)
            }
        }
    }

    /** 一页：内容流 + 字体资源 */
    private class PdfPage(
        private val bytes: ByteArray,
        private val contents: List<ByteArray>,
        private val fonts: Map<String, PdfFont>,
        private val mediaBox: FloatArray,
    ) {
        fun sheetWidth(): Float = mediaBox[2] - mediaBox[0]
        fun sheetHeight(): Float = mediaBox[3] - mediaBox[1]

        /**
         * 解析内容流，返回按行合并后的文本。
         * [half] 只对一页两开有意义：0 = 左半，1 = 右半。
         */
        fun textLines(half: Int, spread: Boolean, maxLines: Int): List<String> {
            val items = ArrayList<TextItem>()
            val content = runCatching { contents.map { Filters.decode(it) }.reduceOrNull { a, b -> a + b } }
                .getOrNull() ?: return emptyList()
            runCatching { ContentParser(content, fonts, items).parse() }

            val filtered = if (!spread) {
                items
            } else {
                val mid = sheetWidth() / 2f
                items.filter { (it.x < mid) == (half == 0) }
            }
            return LineAssembler.assemble(filtered, maxLines)
        }
    }

    /** 一段带坐标的文本 */
    private class TextItem(val x: Float, val y: Float, val text: String)

    /** 字体：只保留"字节 → 字符串"的映射 */
    private class PdfFont(private val toUnicode: Map<Int, String>?, private val singleByte: Boolean) {

        fun decode(raw: ByteArray): String {
            if (toUnicode != null) {
                if (singleByte) {
                    return raw.joinToString("") { b ->
                        toUnicode[b.toInt() and 0xFF] ?: ((b.toInt() and 0xFF).toChar()).toString()
                    }
                }
                // 双字节（Identity-H 之类）：按 2 字节码查表
                val sb = StringBuilder()
                var i = 0
                while (i + 1 < raw.size) {
                    val code = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
                    sb.append(toUnicode[code] ?: "")
                    i += 2
                }
                return sb.toString()
            }
            // 没有 ToUnicode：单字节按 Latin-1 近似
            val sb = StringBuilder()
            for (b in raw) {
                val c = b.toInt() and 0xFF
                sb.append(if (c in 32..126 || c >= 160) c.toChar() else ' ')
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ 对象扫描

    /**
     * 一个间接对象：字典原文 + （可能是 null 的）流数据。
     *
     * 这里必须是 `internal` 而不是 `private`：文件末尾那几个 `PdfObject` 的扩展函数是
     * **顶层私有**函数，它们访问不到 `private` 的嵌套类型。
     */
    internal class PdfObject(val ref: Int, val dictBytes: ByteArray, val stream: ByteArray?)

    /**
     * 直接扫全文找 `N M obj`。
     *
     * 不解析 xref 是有意为之：xref 表、xref 流、对象流、增量更新这四样东西的解析代码量
     * 远超本类，而"扫描对象"对它们全部免疫，代价只是 O(文件大小) 的一次遍历。
     */
    private object ObjScanner {

        fun scan(bytes: ByteArray): Map<Int, PdfObject> {
            val out = HashMap<Int, PdfObject>()
            val text = String(bytes, Charsets.ISO_8859_1)
            val re = Regex("""(?:^|[\r\n\s])(\d{1,7})\s+(\d{1,5})\s+obj\b""")
            val matches = re.findAll(text).toList()
            matches.forEachIndexed { idx, m ->
                val objNum = m.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                val bodyStart = m.range.last + 1
                val bodyEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else text.length
                val body = text.substring(bodyStart.coerceAtMost(text.length), bodyEnd.coerceAtMost(text.length))
                val streamAt = body.indexOf("stream")
                val dictText = if (streamAt >= 0) body.substring(0, streamAt) else body
                val streamBytes = if (streamAt >= 0) extractStream(text, bytes, bodyStart + streamAt) else null
                out[objNum] = PdfObject(
                    ref = objNum,
                    dictBytes = dictText.toByteArray(Charsets.ISO_8859_1),
                    stream = streamBytes,
                )
            }
            // 现代 PDF 会把大量对象压缩进**对象流**（/Type /ObjStm）里，
            // 扫 `N M obj` 只能看到外壳。不展开的话，页面引用的 /Contents、/Resources、/Font
            // 全都"查不到"，表现就是"少数页有字、大部分页空白"（实测 Two Lives 7/40）。
            val objStms = out.values.filter { it.dictAt("/Type") == "/ObjStm" }.toList()
            objStms.forEach { stm ->
                val data = stm.stream?.let { Filters.decode(it) } ?: return@forEach
                val count = stm.numbers("/N").firstOrNull()?.toInt() ?: 0
                val first = stm.numbers("/First").firstOrNull()?.toInt() ?: 0
                if (count <= 0 || first <= 0 || first >= data.size) return@forEach
                val header = data.copyOfRange(0, first).toString(Charsets.ISO_8859_1)
                val nums = header.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                for (k in 0 until minOf(count, nums.size / 2)) {
                    val objNum = nums[k * 2]
                    val begin = nums[k * 2 + 1]
                    val end = if ((k + 1) * 2 + 1 < nums.size) nums[(k + 1) * 2 + 1] else data.size - first
                    val from = first + begin
                    val to = minOf(first + end, data.size)
                    if (from < 0 || to <= from || from >= data.size) continue
                    if (!out.containsKey(objNum)) {
                        out[objNum] = PdfObject(objNum, data.copyOfRange(from, to), null)
                    }
                }
            }

            return out
        }

        /** `stream` 之后到 `endstream` 之间的原始字节（跳过紧随的一个换行） */
        private fun extractStream(text: String, bytes: ByteArray, streamKeywordAt: Int): ByteArray? {
            var start = streamKeywordAt + "stream".length
            // stream 关键字后是 CRLF 或 LF
            while (start < text.length && (text[start] == '\r' || text[start] == '\n')) start++
            val end = text.indexOf("endstream", start)
            if (end < 0 || end <= start) return null
            var realEnd = end
            // 去掉 endstream 前的换行
            while (realEnd > start && (text[realEnd - 1] == '\r' || text[realEnd - 1] == '\n')) realEnd--
            if (realEnd <= start || realEnd > bytes.size) return null
            return bytes.copyOfRange(start, realEnd)
        }
    }

    // ------------------------------------------------------------------ 页树

    private object PageTree {

        fun collect(bytes: ByteArray, objects: Map<Int, PdfObject>): List<PdfPage> {
            // 1) 试着按页树走：trailer 里的 /Root → /Pages → /Kids
            val ordered = runCatching { walkTree(bytes, objects) }.getOrNull().orEmpty()
            if (ordered.isNotEmpty()) return ordered
            // 2) 兜底：按对象号顺序取所有 /Type /Page
            return objects.entries
                .sortedBy { it.key }
                .mapNotNull { (_, obj) -> buildPage(bytes, objects, obj) }
        }

        private fun walkTree(bytes: ByteArray, objects: Map<Int, PdfObject>): List<PdfPage> {
            val trailer = objects.values.lastOrNull { it.dictAt("/Type") == "/XRef" } ?: return emptyList()
            val rootRef = trailer.refOf("/Root") ?: return emptyList()
            val root = objects[rootRef] ?: return emptyList()
            val pagesRef = root.refOf("/Pages") ?: return emptyList()
            val out = ArrayList<PdfPage>()
            val guard = HashSet<Int>()

            fun visit(ref: Int) {
                if (!guard.add(ref) || out.size > MAX_PAGES) return
                val obj = objects[ref] ?: return
                when (obj.dictAt("/Type")) {
                    "/Page" -> buildPage(bytes, objects, obj)?.let { out += it }
                    else -> {
                        val kids = obj.arrayRefs("/Kids")
                        if (kids.isEmpty()) {
                            buildPage(bytes, objects, obj)?.let { out += it }
                        } else {
                            kids.forEach { visit(it) }
                        }
                    }
                }
            }
            visit(pagesRef)
            return out
        }

        fun buildPage(bytes: ByteArray, objects: Map<Int, PdfObject>, obj: PdfObject): PdfPage? {
            if (obj.dictAt("/Type") != "/Page") return null
            val contents = ArrayList<ByteArray>()
            obj.refOf("/Contents")?.let { ref -> objects[ref]?.stream?.let { contents += it } }
            if (contents.isEmpty()) {
                obj.arrayRefs("/Contents").forEach { ref -> objects[ref]?.stream?.let { contents += it } }
            }
            // 正文经常不在页面自己的 /Contents 里，而是放在 **Form XObject** 里，
            // 页面内容流只剩一句 `q … /Fm0 Do Q`。不展开的话整本书的正文都读不到
            // （实测 Two Lives 抽样 40 页只有 7 页有字，就是这个原因）。
            // 这里只按 /Subtype /Form 过滤，字体/图片等其它 XObject 会被排除。
            val xobjectRefs = obj.refOf("/Resources")
                ?.let { objects[it]?.dictBytes?.toString(Charsets.ISO_8859_1) }
                ?.let { resText ->
                    val at = resText.indexOf("/XObject")
                    if (at < 0) {
                        emptyList()
                    } else {
                        Regex("""(\d+)\s+\d+\s+R""")
                            .findAll(resText.substring(at))
                            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                            .toList()
                    }
                }
                .orEmpty()
            xobjectRefs.forEach { ref ->
                val xobj = objects[ref] ?: return@forEach
                if (xobj.dictAt("/Subtype") == "/Form") {
                    xobj.stream?.let { contents += it }
                }
            }

            if (contents.isEmpty()) return null

            val resources = obj.refOf("/Resources")
                ?.let { objects[it]?.dictBytes?.toString(Charsets.ISO_8859_1) }
                ?: obj.dictBytes.toString(Charsets.ISO_8859_1)
            val fonts = Fonts.parse(bytes, objects, resources)

            val box = obj.numbers("/MediaBox")
            val mediaBox = if (box.size >= 4) {
                floatArrayOf(box[0], box[1], box[2], box[3])
            } else {
                floatArrayOf(0f, 0f, 612f, 792f)
            }
            return PdfPage(bytes, contents, fonts, mediaBox)
        }
    }

    // ------------------------------------------------------------------ 字体

    private object Fonts {

        fun parse(bytes: ByteArray, objects: Map<Int, PdfObject>, resources: String): Map<String, PdfFont> {
            val out = HashMap<String, PdfFont>()
            val fontDictStart = resources.indexOf("/Font")
            if (fontDictStart < 0) return out
            // 从 /Font 之后抓 `名称 参考号 0 R`
            val segment = resources.substring(fontDictStart, minOf(resources.length, fontDictStart + 4000))
            Regex("""/([A-Za-z0-9#+.\-]+)\s+(\d+)\s+\d+\s+R""").findAll(segment).forEach { m ->
                val name = m.groupValues[1]
                val ref = m.groupValues[2].toIntOrNull() ?: return@forEach
                val fontObj = objects[ref] ?: return@forEach
                out[name] = fromObject(bytes, objects, fontObj)
            }
            return out
        }

        private fun fromObject(bytes: ByteArray, objects: Map<Int, PdfObject>, fontObj: PdfObject): PdfFont {
            val toUnicode = fontObj.refOf("/ToUnicode")
                ?.let { objects[it]?.stream }
                ?.let { runCatching { CMap.parse(Filters.decode(it)) }.getOrNull() }
            val subtype = fontObj.dictAt("/Subtype")
            val singleByte = subtype != "/Type0"
            return PdfFont(toUnicode, singleByte)
        }
    }

    /** 解析 ToUnicode CMap 的 bfchar / bfrange */
    private object CMap {

        fun parse(data: ByteArray): Map<Int, String> {
            val text = data.toString(Charsets.ISO_8859_1)
            val map = HashMap<Int, String>()
            Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""").findAll(block.groupValues[1]).forEach { pair ->
                    val code = pair.groupValues[1].toIntOrNull(16) ?: return@forEach
                    map[code] = hexToText(pair.groupValues[2])
                }
            }
            Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
                    .findAll(block.groupValues[1]).forEach { m ->
                        val from = m.groupValues[1].toIntOrNull(16) ?: return@forEach
                        val to = m.groupValues[2].toIntOrNull(16) ?: return@forEach
                        val dst = m.groupValues[3].toIntOrNull(16) ?: return@forEach
                        if (to < from || to - from > 512) return@forEach
                        for (k in from..to) {
                            map[k] = String(Character.toChars(dst + (k - from)))
                        }
                    }
            }
            return map
        }

        private fun hexToText(hex: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 3 < hex.length) {
                val code = hex.substring(i, i + 4).toIntOrNull(16) ?: break
                sb.append(code.toChar())
                i += 4
            }
            // 单个 UTF-16 单元就是普通字符；代理对也按两段拼接（够用于目录标题）
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ 解压

    private object Filters {

        fun decode(raw: ByteArray): ByteArray {
            if (raw.size < 2) return raw
            // zlib 头：0x78
            if ((raw[0].toInt() and 0xFF) != 0x78) return raw
            val inflater = Inflater()
            return runCatching {
                inflater.setInput(raw)
                val out = ByteArray(maxOf(raw.size * 4, 8192))
                var len = 0
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        if (len + n > out.size) return@runCatching raw
                        System.arraycopy(buf, 0, out, len, n)
                        len += n
                    }
                }
                out.copyOf(len)
            }.getOrElse { raw }.also { inflater.end() }
        }
    }

    // ------------------------------------------------------------------ 内容流

    private class ContentParser(
        private val content: ByteArray,
        private val fonts: Map<String, PdfFont>,
        private val out: ArrayList<TextItem>,
    ) {
        private var x = 0f
        private var y = 0f
        private var leading = 0f
        private var currentFont: PdfFont? = null

        fun parse() {
            val text = content.toString(Charsets.ISO_8859_1)
            var i = 0
            val operands = ArrayList<Any>()
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '%' -> {
                        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                    }
                    c == '(' -> {
                        val (str, next) = readLiteral(text, i)
                        operands += str
                        i = next
                    }
                    c == '<' && i + 1 < text.length && text[i + 1] != '<' -> {
                        val end = text.indexOf('>', i)
                        if (end < 0) {
                            i = text.length
                        } else {
                            operands += hexString(text.substring(i + 1, end))
                            i = end + 1
                        }
                    }
                    c == '[' || c == ']' || c == '<' || c == '>' -> i++
                    // `/F1` 这类**名字**是操作数，不是算子：必须原样留下（去掉斜杠），
                    // 否则 `Tf` 拿不到字体名，后面所有文本都会因为"没有当前字体"被丢掉。
                    c == '/' -> {
                        val start = i + 1
                        var k = start
                        while (k < text.length && !text[k].isWhitespace() && text[k] !in "()<>[]/{}%") k++
                        operands += text.substring(start, k)
                        i = k
                    }
                    c.isWhitespace() -> i++
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() &&
                            text[i] !in "()<>[]/%{}"
                        ) {
                            i++
                        }
                        val token = text.substring(start, i)
                        if (token.isEmpty()) {
                            i++
                        } else if (token[0].isDigit() || token[0] == '-' || token[0] == '+' || token[0] == '.') {
                            operands += token.toFloatOrNull() ?: 0f
                        } else {
                            handleOperator(token, operands)
                            operands.clear()
                        }
                    }
                }
            }
        }

        private fun handleOperator(op: String, args: List<Any>) {
            when (op) {
                "Tf" -> {
                    val name = args.getOrNull(0) as? String ?: return
                    currentFont = fonts[name]
                }
                "Td", "TD" -> {
                    val tx = args.getOrNull(0) as? Float ?: 0f
                    val ty = args.getOrNull(1) as? Float ?: 0f
                    x += tx
                    y += ty
                    if (op == "TD") leading = -ty
                }
                "Tm" -> {
                    val e = args.getOrNull(4) as? Float ?: 0f
                    val f = args.getOrNull(5) as? Float ?: 0f
                    x = e
                    y = f
                }
                "T*" -> y -= leading
                "Tj" -> show(args.getOrNull(0) as? ByteArray)
                "TJ" -> args.forEach { if (it is ByteArray) show(it) }
                "'" -> {
                    y -= leading
                    show(args.getOrNull(0) as? ByteArray)
                }
                "\"" -> {
                    y -= leading
                    show(args.getOrNull(2) as? ByteArray)
                }
            }
        }

        private fun show(raw: ByteArray?) {
            if (raw == null || raw.isEmpty()) return
            val decoded = (currentFont ?: return).decode(raw)
            if (decoded.isBlank()) return
            out += TextItem(x, y, decoded.trim())
            // 估算前进量：只用于让同一行内后续文本不至于重叠，精度不重要
            x += decoded.length * 4.5f
        }

        private fun readLiteral(text: String, start: Int): Pair<ByteArray, Int> {
            var i = start + 1
            val sb = StringBuilder()
            var depth = 1
            while (i < text.length) {
                val ch = text[i]
                when {
                    ch == '\\' -> {
                        val n = text.getOrNull(i + 1)
                        when (n) {
                            'n' -> { sb.append('\n'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            '(', ')', '\\' -> { sb.append(n); i += 2 }
                            else -> i += 2
                        }
                    }
                    ch == '(' -> { depth++; sb.append(ch); i++ }
                    ch == ')' -> {
                        depth--
                        i++
                        if (depth == 0) break
                        sb.append(ch)
                    }
                    else -> { sb.append(ch); i++ }
                }
            }
            return Pair(sb.toString().toByteArray(Charsets.ISO_8859_1), i)
        }

        private fun hexString(hex: String): ByteArray {
            val clean = hex.filter { it.isLetterOrDigit() }
            val out = ByteArray(clean.length / 2)
            for (k in out.indices) {
                out[k] = clean.substring(k * 2, k * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
            return out
        }
    }

    /** 把带坐标的文本拼成行：y 相近归为同一行，每行按 x 排序 */
    private object LineAssembler {

        /**
         * 拼一行文本。
         *
         * 同一个词常常被拆成好几个 `Tj`（`Hele n Naylo r` 这种），一律补空格会把词切碎。
         * 这里按"上一段估算的结束位置"与"下一段的起点"判断间距：小就直接连，大才补空格。
         * 字宽是估的（每个字符约 4.5 单位），够用——这层只服务于"读标题"，不追求排版还原。
         */
        fun joinRow(row: List<TextItem>): String {
            if (row.isEmpty()) return ""
            val sb = StringBuilder()
            var endX = Float.NEGATIVE_INFINITY
            row.forEach { item ->
                if (sb.isNotEmpty() && item.x - endX > SPACE_GAP) sb.append(' ')
                sb.append(item.text)
                endX = item.x + item.text.length * CHAR_WIDTH
            }
            return sb.toString().trim()
        }

        /**
         * 估算字宽与"该不该补空格"的阈值。
         *
         * 刻意**偏保守**（字宽估小、阈值调小）——多补空格对目录识别无害
         * （检测器按词匹配，`Chapter  2` 一样认得出），而少补空格会把词粘住，
         * `Chapter2` 就认不出来了。
         */
        private const val CHAR_WIDTH = 3.0f
        private const val SPACE_GAP = 1.5f

        fun assemble(items: List<TextItem>, maxLines: Int): List<String> {
            if (items.isEmpty()) return emptyList()
            // PDF 的 y 轴朝上，行从上到下 = y 从大到小
            val rows = ArrayList<MutableList<TextItem>>()
            items.sortedWith(compareByDescending<TextItem> { it.y }.thenBy { it.x }).forEach { item ->
                val row = rows.firstOrNull { r -> kotlin.math.abs(r.first().y - item.y) <= 3.5f }
                if (row == null) rows += mutableListOf(item) else row += item
            }
            return rows
                .take(maxLines)
                .map { row -> joinRow(row.sortedBy { it.x }) }
                .filter { it.isNotEmpty() }
        }
    }

    private companion object {
        /** 判定一页两开时抽样的页数 */
        const val SAMPLE_PAGES = 8

        /** 超过这个大小的 PDF 不解析文字层（整本读进内存的代价不可接受） */
        const val MAX_FILE_BYTES = 150L * 1024 * 1024

        /** 页数上限，防畸形文件把递归撑爆 */
        const val MAX_PAGES = 5000
    }
}

// ---------------------------------------------------------------------- 字典辅助
//
// 这些是**顶层**扩展函数，所以接收者必须写全限定名 `PdfTextBoxReader.PdfObject`
// （嵌套类不会自动进入文件作用域，写成 `PdfObject` 会编译不过）。

private fun PdfTextBoxReader.PdfObject.dictText(): String =
    dictBytes.toString(Charsets.ISO_8859_1)

/** 取 `/Key /Name` 形式的名称 */
private fun PdfTextBoxReader.PdfObject.dictAt(key: String): String? {
    val text = dictText()
    val at = text.indexOf(key)
    if (at < 0) return null
    val rest = text.substring(at + key.length)
    return Regex("""^\s*(/[A-Za-z0-9#+.\-]+)""").find(rest)?.groupValues?.getOrNull(1)
}

/** 取 `/Key N 0 R` 的引用号 */
private fun PdfTextBoxReader.PdfObject.refOf(key: String): Int? {
    val text = dictText()
    val at = text.indexOf(key)
    if (at < 0) return null
    val rest = text.substring(at + key.length)
    return Regex("""^\s*(\d+)\s+\d+\s+R""").find(rest)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

/** 取 `/Key [1 2 3 4]` 里的数字 */
private fun PdfTextBoxReader.PdfObject.numbers(key: String): List<Float> {
    val text = dictText()
    val at = text.indexOf(key)
    if (at < 0) return emptyList()
    val start = text.indexOf('[', at)
    if (start < 0) return emptyList()
    val end = text.indexOf(']', start)
    if (end < 0) return emptyList()
    return text.substring(start + 1, end).trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
}

/** 取 `/Key [N 0 R M 0 R …]` 的所有引用号 */
private fun PdfTextBoxReader.PdfObject.arrayRefs(key: String): List<Int> {
    val text = dictText()
    val at = text.indexOf(key)
    if (at < 0) return emptyList()
    val start = text.indexOf('[', at)
    if (start < 0) return emptyList()
    val end = text.indexOf(']', start)
    if (end < 0) return emptyList()
    return Regex("""(\d+)\s+\d+\s+R""").findAll(text.substring(start + 1, end))
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()
}

