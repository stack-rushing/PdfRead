package com.novelreader.pdf.data.pdf

import java.io.File
import java.util.zip.Inflater

/** 解析出的原始大纲节点（扁平化后的中间态） */
data class OutlineNode(
    val title: String,
    /** 0 基页码；-1 表示目标无法解析到具体页 */
    val pageIndex: Int,
    val level: Int,
)

data class OutlineParseResult(
    val nodes: List<OutlineNode>,
    val pageCount: Int,
    /** 诊断信息，方便在 UI 上解释"为什么没读到目录" */
    val note: String = "",
)

/**
 * 纯 Kotlin 实现的 PDF 大纲（书签）解析器。
 *
 * 为什么要自己写：`android.graphics.pdf.PdfRenderer` **只负责画页面**，
 * 既没有大纲 API 也没有文字层 API；而 pdfium / android-pdf-viewer 那套已经停止维护、
 * 且带 native so（Android 15 的 16KB page size 隐患）。大纲是纯结构信息，
 * 用 `Inflater` + 手写解析完全够用，还能顺带把"页码为什么不准"这件事说清楚。
 *
 * 覆盖范围：
 * - 交叉引用表（`xref` + trailer）与交叉引用流（`/Type /XRef`，含 PNG Predictor）；
 * - `/Prev` 链回溯与 `/XRefStm` 混合引用；
 * - 对象流 `/Type /ObjStm`（PDF 1.5+ 把大量对象塞进流里）；
 * - xref 损坏时回退到全文暴力扫描 `N G obj`；
 * - 大纲树 `/First` `/Next` `/FirstChild`，带环检测；
 * - 目标页解析：`/Dest` 数组、命名目标（`/Root/Dests` 与 `/Names → /Dests` 名字树）、`/A → /D`；
 * - 标题编码：UTF-16BE BOM / UTF-8 BOM / PDFDocEncoding(按 Latin-1 兜底)。
 *
 * 不支持加密文档（返回空结果并给出说明），因为个人使用场景里加密 PDF 极其罕见。
 */
class PdfOutlineParser {

    fun parse(file: File): OutlineParseResult {
        if (!file.exists() || file.length() <= 0L) return OutlineParseResult(emptyList(), 0, "文件不存在")
        val size = file.length()
        if (size > MAX_FILE_BYTES) {
            return OutlineParseResult(emptyList(), 0, "文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB，跳过内置书签解析")
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return OutlineParseResult(emptyList(), 0, "文件读取失败")
        return runCatching { Doc(bytes).run() }
            .getOrElse { OutlineParseResult(emptyList(), 0, "解析失败：${it.javaClass.simpleName} ${it.message}") }
    }

    private fun Doc.run(): OutlineParseResult {
        readXref()
        val tableObjects = xref.size
        if (!hasRoot()) bruteForceXref()
        // 诊断串同时服务于两件事：排错，以及告诉用户"为什么没读到目录"
        val diag = "xref=$xrefKind 表内对象=$tableObjects 最终对象=${xref.size} " +
            "对象流=${inObjStm.size} trailer键=${trailer.keys.sorted()}" +
            if (streamDiag.isEmpty()) "" else "；$streamDiag"

        val catalog = resolveDict(trailer["Root"])
            ?: return OutlineParseResult(emptyList(), 0, "找不到 /Root；$diag")
        if (trailer["Encrypt"] != null && trailer["Encrypt"] !is PdfObject.Null) {
            return OutlineParseResult(emptyList(), 0, "文档已加密；$diag")
        }

        buildPageIndex(catalog)

        val outlines = resolveDict(catalog["Outlines"])
            ?: return OutlineParseResult(emptyList(), pageCount, "该 PDF 没有内置书签；页数=$pageCount；$diag")

        val nodes = ArrayList<OutlineNode>(256)
        walkItems(outlines["First"], 0, nodes, HashSet())
        val head = if (nodes.isEmpty()) "内置书签为空" else "内置书签 ${nodes.size} 条"
        return OutlineParseResult(nodes, pageCount, "$head；页数=$pageCount；$diag")
    }
    private companion object {
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        const val MAX_OUTLINE_DEPTH = 16
        const val MAX_PAGE_TREE_DEPTH = 64
    }

    // ==================================================================== PDF 对象模型

    private sealed interface PdfObject {
        data class Ref(val num: Int, val gen: Int) : PdfObject
        data class Num(val value: Double) : PdfObject
        data class Str(val bytes: ByteArray) : PdfObject {
            override fun equals(other: Any?) = other is Str && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }

        data class Name(val value: String) : PdfObject
        data class Bool(val value: Boolean) : PdfObject
        data object Null : PdfObject
        data class Arr(val items: List<PdfObject>) : PdfObject
        data class Dict(val map: Map<String, PdfObject>) : PdfObject
        data class Stream(val map: Map<String, PdfObject>, val raw: ByteArray) : PdfObject
    }

    // ==================================================================== 解析上下文

    private inner class Doc(private val buf: ByteArray) {

        val xref = HashMap<Int, Int>()
        val inObjStm = HashMap<Int, Pair<Int, Int>>()
        var trailer: Map<String, PdfObject> = emptyMap()
        var xrefKind = "unknown"
        /** 记录 xref 流的解码细节，排错与"为什么没读到目录"都用得上 */
        var streamDiag = ""
        var pageCount = 0

        /** 页码对象号 → 0 基页序号 */
        private val pageIndexOf = HashMap<Int, Int>()
        private val cache = HashMap<Int, PdfObject?>()
        private val objStmCache = HashMap<Int, List<PdfObject?>>()

        fun hasRoot(): Boolean = trailer["Root"] != null

        // ------------------------------------------------------------ 交叉引用

        fun readXref() {
            // findLastAscii 返回 Int（找不到时是 -1），所以必须显式判 -1：
            // 之前写成 `?: return`，编译器会指出那是死代码，而且 -1 会被当成偏移量继续往下算。
            val start = findLastAscii("startxref", buf.size, 4096)
            if (start < 0) return
            var pos = start + "startxref".length
            val offset = readLongAt(pos) ?: return
            var cur = offset.toInt()
            val visited = HashSet<Int>()
            var kind = ""
            while (cur in 0 until buf.size && visited.add(cur)) {
                val next = if (startsWithAscii(cur, "xref")) {
                    kind = "table"
                    readXrefTable(cur)
                } else {
                    kind = "stream"
                    readXrefStream(cur)
                } ?: break
                cur = next
            }
            if (kind.isNotEmpty()) xrefKind = kind
        }

        /** 返回 /Prev 偏移（没有则返回 null，同时不结束整条链） */
        private fun readXrefTable(offset: Int): Int? {
            var p = offset + 4
            while (true) {
                p = skipWhitespace(p)
                if (startsWithAscii(p, "trailer")) {
                    p += 7
                    val dict = runCatching { Parser(buf, p).parseObject() }.getOrNull()
                    if (dict is PdfObject.Dict) mergeTrailer(dict.map)
                    return prevOffset(dict)
                }
                if (p >= buf.size) return null
                // 子段头：起始对象号 + 条目数。必须用"读完后位置"的版本，
                // 否则 p 永远不动 → count 恒为 0 → while(true) 死循环。
                val firstRead = readNumberAt(p) ?: return null
                val first = firstRead.first.toInt()
                p = skipWhitespace(firstRead.second)
                val countRead = readNumberAt(p) ?: return null
                val count = countRead.first.toInt()
                p = skipWhitespace(countRead.second)
                var i = 0
                while (i < count && p + 20 <= buf.size) {
                    val off = asciiAt(p, 10).trim().toIntOrNull()
                    val type = byteAt(p + 17).toInt().toChar()
                    val field = first + i
                    if (type == 'n' && off != null && off > 0 && off < buf.size && !xref.containsKey(field)) {
                        xref[field] = off
                    }
                    p += 20
                    i++
                }
            }
        }

        private fun readXrefStream(offset: Int): Int? {
            val obj = parseObjectAt(offset) as? PdfObject.Stream ?: return null
            if (!xref.containsKey(0)) xref[0] = 0
            val data = decodeStream(obj.map, obj.raw) ?: return null
            val w = (resolve(obj.map["W"]) as? PdfObject.Arr)?.items?.map { numOf(it)?.toInt() ?: 0 }
                ?: return null
            val size = numOf(obj.map["Size"])?.toInt() ?: 0
            val index = (resolve(obj.map["Index"]) as? PdfObject.Arr)
                ?.items?.mapNotNull { numOf(it)?.toInt() }
                ?: listOf(0, size)

            val rowLen = w.sum()
            if (rowLen <= 0) return null
            streamDiag = "流解码=${data.size}B W=${w} Index=$index 行宽=$rowLen 行数=${data.size / rowLen}"
            var p = 0
            var k = 0
            while (k + 1 < index.size) {
                val first = index[k]
                val count = index[k + 1]
                var i = 0
                while (i < count && p + rowLen <= data.size) {
                    var type = 1
                    var f1 = 0L
                    var f2 = 0L
                    var shift = 0
                    for (wi in w.indices) {
                        var v = 0L
                        for (b in 0 until w[wi]) {
                            v = (v shl 8) or (data[p + shift + b].toLong() and 0xFF)
                        }
                        when (wi) {
                            0 -> type = v.toInt()
                            1 -> f1 = v
                            2 -> f2 = v
                        }
                        shift += w[wi]
                    }
                    p += rowLen
                    val objNum = first + i
                    i++
                    when (type) {
                        1 -> if (f1 > 0 && !xref.containsKey(objNum)) xref[objNum] = f1.toInt()
                        // 注意：第三个字段是"在对象流里的下标"，**可以为 0**。
                        // 早期写成 if (f2 > 0)，结果对象流里第一个对象永远读不到 ——
                        // 而很多现代 PDF 的第一个对象恰好就是 Catalog，直接导致整本读不出目录。
                        2 -> if (f1 > 0) inObjStm[objNum] = f1.toInt() to f2.toInt()
                    }
                }
                k += 2
            }
            mergeTrailer(obj.map)
            return prevOffset(PdfObject.Dict(obj.map))
        }

        private fun mergeTrailer(map: Map<String, PdfObject>) {
            val merged = HashMap(trailer)
            // 先读到的（更新的）优先，/Prev 指向的旧 trailer 不覆盖已有键
            map.forEach { (k, v) -> merged.putIfAbsent(k, v) }
            trailer = merged
        }

        private fun prevOffset(dict: PdfObject?): Int? {
            val map = (dict as? PdfObject.Dict)?.map ?: return null
            // /XRefStm 也要读（混合引用文件）
            (map["XRefStm"] as? PdfObject.Num)?.value?.toInt()?.let { stm ->
                if (stm in 0 until buf.size) readXrefStream(stm)
            }
            return (map["Prev"] as? PdfObject.Num)?.value?.toInt()?.takeIf { it in 0 until buf.size }
        }

        /** xref 不可用时的兜底：全文扫描 "N G obj" */
        fun bruteForceXref() {
            xref.clear()
            inObjStm.clear()
            var i = 0
            while (i < buf.size - 3) {
                if (matchesAscii(i, "obj")) {
                    // 向前回溯 "数字 数字 obj"
                    var p = i - 1
                    while (p >= 0 && isWhitespace(byteAt(p))) p--
                    val genEnd = p
                    while (p >= 0 && isAsciiDigit(byteAt(p))) p--
                    val genStart = p + 1
                    while (p >= 0 && isWhitespace(byteAt(p))) p--
                    val numEnd = p
                    while (p >= 0 && isAsciiDigit(byteAt(p))) p--
                    val numStart = p + 1
                    if (numStart <= numEnd && genStart <= genEnd && numStart > 1) {
                        val num = asciiAt(numStart, numEnd - numStart + 1).toIntOrNull()
                        if (num != null && num > 0 && !xref.containsKey(num)) {
                            // 对象体从 "obj" 之后开始
                            xref[num] = i + 3
                        }
                    }
                }
                i++
            }
            xrefKind = "bruteforce"
            // 从所有对象里找 /Type /Catalog
            var fallbackTrailer: Map<String, PdfObject> = emptyMap()
            var scanned = 0
            for (num in xref.keys.toList()) {
                if (scanned++ > 4000) break
                val obj = getObject(num)
                if (obj is PdfObject.Dict && nameOf(obj.map["Type"]) == "Catalog") {
                    val rootRef = PdfObject.Ref(num, 0)
                    fallbackTrailer = mapOf("Root" to rootRef)
                    break
                }
            }
            if (fallbackTrailer.isNotEmpty()) mergeTrailer(fallbackTrailer)
        }

        // ------------------------------------------------------------ 对象获取

        fun getObject(num: Int): PdfObject? {
            if (num <= 0) return null
            cache[num]?.let { return it }
            if (cache.containsKey(num)) return null

            val parsed: PdfObject? = when {
                xref.containsKey(num) -> parseObjectAt(xref.getValue(num))
                inObjStm.containsKey(num) -> {
                    val (stmNum, idx) = inObjStm.getValue(num)
                    loadObjStm(stmNum).getOrNull(idx)
                }

                else -> null
            }
            cache[num] = parsed
            return parsed
        }

        fun resolve(obj: PdfObject?): PdfObject? = when (obj) {
            null -> null
            is PdfObject.Ref -> getObject(obj.num)
            else -> obj
        }

        fun resolveDict(obj: PdfObject?): Map<String, PdfObject>? = when (val r = resolve(obj)) {
            is PdfObject.Dict -> r.map
            is PdfObject.Stream -> r.map
            else -> null
        }

        fun parseObjectAt(offset: Int): PdfObject? = runCatching {
            val p = Parser(buf, offset)
            p.skipObjectHeader()
            p.parseObject()
        }.getOrNull()

        private fun loadObjStm(stmNum: Int): List<PdfObject?> {
            objStmCache[stmNum]?.let { return it }
            val stm = getObject(stmNum) as? PdfObject.Stream ?: return emptyList()
            val data = decodeStream(stm.map, stm.raw) ?: return emptyList()
            val n = numOf(stm.map["N"])?.toInt() ?: return emptyList()
            val first = numOf(stm.map["First"])?.toInt() ?: return emptyList()

            // 头部是 n 组 "objNum offset" 的 ASCII 表
            val pairs = ArrayList<Pair<Int, Int>>(n)
            val header = Parser(data, 0)
            repeat(n) {
                val num = header.parseObject() as? PdfObject.Num ?: return@repeat
                val off = header.parseObject() as? PdfObject.Num ?: return@repeat
                pairs += num.value.toInt() to off.value.toInt()
            }

            val result = ArrayList<PdfObject?>(pairs.size)
            pairs.forEach { (_, rel) ->
                val abs = first + rel
                val obj = if (abs in 0 until data.size) {
                    runCatching { Parser(data, abs).parseObject() }.getOrNull()
                } else {
                    null
                }
                result += obj
            }
            objStmCache[stmNum] = result
            return result
        }

        // ------------------------------------------------------------ 页树

        fun buildPageIndex(catalog: Map<String, PdfObject>) {
            pageIndexOf.clear()
            pageCount = 0
            val pagesRef = catalog["Pages"] ?: return
            val seen = HashSet<Int>()
            walkPages(pagesRef, 0, seen)
        }

        private fun walkPages(node: PdfObject, depth: Int, seen: MutableSet<Int>) {
            if (depth > MAX_PAGE_TREE_DEPTH) return
            val refNum = (node as? PdfObject.Ref)?.num
            if (refNum != null && !seen.add(refNum)) return
            val map = resolveDict(node) ?: return
            when (nameOf(map["Type"])) {
                "Page" -> {
                    if (refNum != null) pageIndexOf[refNum] = pageCount
                    pageCount++
                }

                "Pages" -> {
                    val kids = resolve(map["Kids"]) as? PdfObject.Arr ?: return
                    kids.items.forEach { walkPages(it, depth + 1, seen) }
                }

                else -> {
                    // /Type 缺失时按有没有 /Kids 判断
                    val kids = resolve(map["Kids"]) as? PdfObject.Arr
                    if (kids != null) {
                        kids.items.forEach { walkPages(it, depth + 1, seen) }
                    } else if (map.containsKey("Contents") || map.containsKey("MediaBox")) {
                        if (refNum != null) pageIndexOf[refNum] = pageCount
                        pageCount++
                    }
                }
            }
        }

        // ------------------------------------------------------------ 大纲遍历

        /**
         * 遍历大纲条目链。
         * 注意：按 PDF 规范，一个**条目**的子节点键是 `/First`（不是 /FirstChild）——
         * /First 既用于 /Outlines 根，也用于任何有子条目的条目；
         * 只认 /FirstChild 会导致"只读出顶层几条、子章节全丢"（实测书虫只出 3 条而非 506 条）。
         */
        fun walkItems(
            firstRef: PdfObject?,
            level: Int,
            out: MutableList<OutlineNode>,
            visited: MutableSet<Int>,
        ) {
            if (level > MAX_OUTLINE_DEPTH) return
            var itemRef = firstRef
            var guard = 0
            while (itemRef != null && guard++ < MAX_OUTLINE_ITEMS) {
                val refNum = (itemRef as? PdfObject.Ref)?.num
                if (refNum != null && !visited.add(refNum)) return
                val map = resolveDict(itemRef) ?: return
                val title = decodeText(map["Title"]).trim()
                val page = destPage(map)
                if (title.isNotEmpty()) out += OutlineNode(title, page, level)
                // 子条目链
                walkItems(map["First"], level + 1, out, visited)
                itemRef = map["Next"]
            }
        }

        /** 章节目标 → 0 基页序号 */
        private fun destPage(item: Map<String, PdfObject>): Int {
            var dest = item["Dest"]
            if (dest == null) {
                val action = resolveDict(item["A"])
                dest = action?.get("D")
            }
            return destToPage(dest)
        }

        private fun destToPage(dest: PdfObject?): Int {
            if (dest == null) return -1
            val resolved = resolve(dest)
            when (resolved) {
                is PdfObject.Arr -> {
                    val first = resolved.items.firstOrNull() ?: return -1
                    return pageOfTarget(first)
                }

                is PdfObject.Name -> return namedDestPage(resolved.value)
                is PdfObject.Str -> return namedDestPage(asciiOf(resolved.bytes))
                is PdfObject.Dict -> return destToPage(resolved.map["D"])
                else -> return -1
            }
        }

        private fun pageOfTarget(target: PdfObject): Int {
            if (target is PdfObject.Ref) return pageIndexOf[target.num] ?: -1
            // 少数文件直接给页序号（整数）
            val n = numOf(target)?.toInt() ?: return -1
            return n.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

        /** 命名目标：先查 /Root/Dests 字典，再查 /Names → /Dests 名字树 */
        private fun namedDestPage(name: String): Int {
            val catalog = resolveDict(trailer["Root"]) ?: return -1
            (resolveDict(catalog["Dests"]))?.get(name)?.let { return destToPage(it) }

            val names = resolveDict(catalog["Names"]) ?: return -1
            val destsTree = resolveDict(names["Dests"]) ?: return -1
            return searchNameTree(destsTree, name, 0)
        }

        private fun searchNameTree(node: Map<String, PdfObject>, name: String, depth: Int): Int {
            if (depth > 32) return -1
            val names = resolve(node["Names"]) as? PdfObject.Arr
            if (names != null) {
                var i = 0
                while (i + 1 < names.items.size) {
                    val key = when (val k = resolve(names.items[i])) {
                        is PdfObject.Str -> asciiOf(k.bytes)
                        is PdfObject.Name -> k.value
                        else -> null
                    }
                    if (key == name) return destToPage(names.items[i + 1])
                    i += 2
                }
            }
            val kids = resolve(node["Kids"]) as? PdfObject.Arr ?: return -1
            kids.items.forEach { kid ->
                val map = resolveDict(kid) ?: return@forEach
                val hit = searchNameTree(map, name, depth + 1)
                if (hit >= 0) return hit
            }
            return -1
        }

        // ------------------------------------------------------------ 流解码 / 文本解码

        fun decodeStream(map: Map<String, PdfObject>, raw: ByteArray): ByteArray? {
            val filters = filterList(map["Filter"])
            var data = raw
            for (f in filters) {
                data = when (f) {
                    "FlateDecode", "Fl" -> inflate(data) ?: return null
                    // 图片类过滤器不会出现在我们关心的流上；遇到就放弃这一条
                    else -> return null
                }
            }
            predictor(map, data)?.let { data = it }
            return data
        }

        private fun filterList(obj: PdfObject?): List<String> = when (val r = resolve(obj)) {
            is PdfObject.Name -> listOf(r.value)
            is PdfObject.Arr -> r.items.mapNotNull { nameOf(resolve(it)) }
            else -> emptyList()
        }

        /**
         * zlib 解码。PDF 里的流经常有多余尾字节或缺少 adler32 校验和，
         * 所以不能用 `Inflater.inflate` 一把梭，要循环并在异常时保留已解出的部分。
         */
        private fun inflate(data: ByteArray): ByteArray? {
            fun run(nowrap: Boolean): ByteArray? {
                val inflater = Inflater(nowrap)
                try {
                    inflater.setInput(data)
                    val out = java.io.ByteArrayOutputStream(data.size * 3 + 64)
                    val chunk = ByteArray(16 * 1024)
                    while (!inflater.finished()) {
                        val n = try {
                            inflater.inflate(chunk)
                        } catch (t: Throwable) {
                            break
                        }
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                        } else {
                            out.write(chunk, 0, n)
                        }
                    }
                    val result = out.toByteArray()
                    return result.takeIf { it.isNotEmpty() }
                } finally {
                    inflater.end()
                }
            }
            return run(nowrap = false) ?: run(nowrap = true)
        }

        /** 交叉引用流常用的 PNG/TIFF Predictor */
        private fun predictor(map: Map<String, PdfObject>, data: ByteArray): ByteArray? {
            val parms = resolveDict(map["DecodeParms"]) ?: resolveDict(
                (resolve(map["Filter"]) as? PdfObject.Arr)?.items?.lastOrNull(),
            ) ?: return null
            val pred = numOf(parms["Predictor"])?.toInt() ?: 1
            if (pred < 2) return null
            val colors = numOf(parms["Colors"])?.toInt() ?: 1
            val bpc = numOf(parms["BitsPerComponent"])?.toInt() ?: 8
            val columns = numOf(parms["Columns"])?.toInt() ?: 1
            val bpp = (colors * bpc + 7) / 8
            val rowIn = (columns * colors * bpc + 7) / 8
            if (pred == 2) return data // TIFF predictor：极少见，直接放弃转换
            val rows = data.size / (rowIn + 1)
            if (rows <= 0) return null
            val out = ByteArray(rows * rowIn)
            var prev = ByteArray(rowIn)
            var p = 0
            for (r in 0 until rows) {
                val ft = data[p++].toInt() and 0xFF
                val row = data.copyOfRange(p, p + rowIn)
                p += rowIn
                when (ft) {
                    0 -> {}
                    1 -> for (i in 0 until rowIn) {
                        if (i >= bpp) row[i] = (row[i] + row[i - bpp]).toByte()
                    }

                    2 -> for (i in 0 until rowIn) row[i] = (row[i] + prev[i]).toByte()
                    3 -> for (i in 0 until rowIn) {
                        val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                        row[i] = (row[i] + ((left + (prev[i].toInt() and 0xFF)) / 2)).toByte()
                    }

                    4 -> for (i in 0 until rowIn) {
                        val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                        val b = prev[i].toInt() and 0xFF
                        val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                        val pa = kotlin.math.abs(b - c)
                        val pb = kotlin.math.abs(a - c)
                        val pc = kotlin.math.abs(a + b - 2 * c)
                        val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        row[i] = (row[i] + pr).toByte()
                    }
                }
                System.arraycopy(row, 0, out, r * rowIn, rowIn)
                prev = row
            }
            return out
        }

        /** PDF 文本字符串解码 */
        fun decodeText(obj: PdfObject?): String {
            val str = resolve(obj) as? PdfObject.Str ?: return ""
            val b = str.bytes
            return when {
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16BE)

                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                    String(b, 3, b.size - 3, Charsets.UTF_8)

                else -> String(b, Charsets.ISO_8859_1)
            }
        }

        fun nameOf(obj: PdfObject?): String? = (resolve(obj) as? PdfObject.Name)?.value

        fun numOf(obj: PdfObject?): Double? = (resolve(obj) as? PdfObject.Num)?.value

        // ------------------------------------------------------------ 字节工具

        fun byteAt(i: Int): Byte = if (i in buf.indices) buf[i] else 0

        fun asciiAt(start: Int, len: Int): String =
            String(buf, start.coerceIn(0, buf.size), len.coerceAtMost(buf.size - start.coerceIn(0, buf.size)), Charsets.ISO_8859_1)

        fun asciiOf(b: ByteArray): String = String(b, Charsets.ISO_8859_1)

        fun matchesAscii(pos: Int, text: String): Boolean {
            if (pos < 0 || pos + text.length > buf.size) return false
            for (i in text.indices) if (buf[pos + i] != text[i].code.toByte()) return false
            return true
        }

        fun startsWithAscii(pos: Int, text: String) = matchesAscii(pos, text)

        fun findLastAscii(text: String, before: Int, window: Int): Int {
            val from = (before - window).coerceAtLeast(0)
            var i = before - text.length
            while (i >= from) {
                if (matchesAscii(i, text)) return i
                i--
            }
            return -1
        }

        fun skipWhitespace(p0: Int): Int {
            var p = p0
            while (p < buf.size && isWhitespace(buf[p])) p++
            return p
        }

        fun skipWhitespaceAfterNumber(p0: Int): Int = skipWhitespace(p0)

        /** 读一个整数并返回"读完后的位置"；解析 xref 子段头必须用它 */
        fun readNumberAt(p0: Int): Pair<Long, Int>? {
            var p = skipWhitespace(p0)
            val start = p
            while (p < buf.size && isAsciiDigit(buf[p])) p++
            if (p == start) return null
            val value = asciiAt(start, p - start).toLongOrNull() ?: return null
            return value to p
        }

        fun readLongAt(p0: Int): Long? {
            var p = skipWhitespace(p0)
            val start = p
            while (p < buf.size && (isAsciiDigit(buf[p]) || buf[p] == '-'.code.toByte())) p++
            if (p == start) return null
            return asciiAt(start, p - start).toLongOrNull()
        }

        fun isWhitespace(b: Byte): Boolean =
            b == 0.toByte() || b == 9.toByte() || b == 10.toByte() || b == 12.toByte() ||
                b == 13.toByte() || b == 32.toByte()

        fun isAsciiDigit(b: Byte): Boolean = b >= '0'.code.toByte() && b <= '9'.code.toByte()
    }

    // ==================================================================== 对象语法解析

    /** 极简递归下降解析器：PDF 对象语法本身很小，够用即可 */
    private class Parser(private val buf: ByteArray, private var pos: Int) {

        fun parseObject(): PdfObject? {
            skipWs()
            if (pos >= buf.size) return null
            return when (val c = buf[pos]) {
                '<'.code.toByte() -> if (peek(1) == '<'.code.toByte()) parseDict() else parseHexString()
                '['.code.toByte() -> parseArray()
                '('.code.toByte() -> parseLiteralString()
                '/'.code.toByte() -> PdfObject.Name(parseName())
                't'.code.toByte(), 'f'.code.toByte() -> parseBool()
                'n'.code.toByte() -> {
                    pos += 4
                    PdfObject.Null
                }

                else -> if (c == '+'.code.toByte() || c == '-'.code.toByte() || c == '.'.code.toByte() ||
                    (c >= '0'.code.toByte() && c <= '9'.code.toByte())
                ) {
                    parseNumberOrRef()
                } else {
                    pos++
                    null
                }
            }
        }

        private fun peek(off: Int): Byte = if (pos + off < buf.size) buf[pos + off] else 0

        /**
         * xref 里的偏移指向 "N G obj" 这段**头**的开头，而不是对象体。
         * 不跳过它，parseObject 会把对象号当成一个整数返回 —— 这正是
         * "trailer 明明有 /Root，却一个对象都解析不出来"的根因。
         * 少数坏文件的偏移直接指向对象体，此时匹配失败就保持原位。
         */
        fun skipObjectHeader() {
            skipWs()
            val start = pos
            var p = pos
            var matched = true
            for (round in 0 until 2) {
                var digits = 0
                while (p < buf.size && buf[p] >= '0'.code.toByte() && buf[p] <= '9'.code.toByte()) {
                    p++
                    digits++
                }
                if (digits == 0) {
                    matched = false
                    break
                }
                while (p < buf.size && isWs(buf[p])) p++
            }
            if (matched && p + 3 <= buf.size &&
                buf[p] == 'o'.code.toByte() && buf[p + 1] == 'b'.code.toByte() && buf[p + 2] == 'j'.code.toByte()
            ) {
                pos = p + 3
            } else {
                pos = start
            }
        }

        private fun isWs(b: Byte): Boolean =
            b == 0.toByte() || b == 9.toByte() || b == 10.toByte() || b == 12.toByte() ||
                b == 13.toByte() || b == 32.toByte()

        private fun parseDict(): PdfObject {
            pos += 2
            val map = LinkedHashMap<String, PdfObject>()
            while (pos < buf.size) {
                skipWs()
                if (peek(0) == '>'.code.toByte() && peek(1) == '>'.code.toByte()) {
                    pos += 2
                    break
                }
                if (peek(0) != '/'.code.toByte()) {
                    // 容错：跳过无法识别的 token
                    pos++
                    continue
                }
                val key = parseName()
                skipWs()
                val value = if (peek(0) == '>'.code.toByte()) PdfObject.Null else parseObject() ?: PdfObject.Null
                map[key] = value
            }
            // 紧跟 stream 关键字 → 变成流对象
            val save = pos
            skipWs()
            if (matches("stream")) {
                pos += 6
                if (peek(0) == '\r'.code.toByte()) pos++
                if (peek(0) == '\n'.code.toByte()) pos++
                val dataStart = pos
                val len = (map["Length"] as? PdfObject.Num)?.value?.toInt()
                val end = if (len != null && len >= 0 && dataStart + len <= buf.size) {
                    dataStart + len
                } else {
                    indexOf("endstream", dataStart).takeIf { it > 0 } ?: buf.size
                }
                val raw = buf.copyOfRange(dataStart, end.coerceIn(dataStart, buf.size))
                pos = (end + "endstream".length).coerceAtMost(buf.size)
                return PdfObject.Stream(map, raw)
            }
            pos = save
            return PdfObject.Dict(map)
        }

        private fun parseArray(): PdfObject {
            pos++
            val items = ArrayList<PdfObject>()
            var guard = 0
            while (pos < buf.size && guard++ < 1 shl 20) {
                skipWs()
                if (peek(0) == ']'.code.toByte()) {
                    pos++
                    break
                }
                parseObject()?.let { items += it }
            }
            return PdfObject.Arr(items)
        }

        private fun parseName(): String {
            pos++
            val sb = StringBuilder()
            while (pos < buf.size) {
                val c = buf[pos]
                if (c == 0.toByte() || c == 32.toByte() || c == 10.toByte() || c == 13.toByte() ||
                    c == 9.toByte() || c == '/'.code.toByte() || c == '['.code.toByte() ||
                    c == ']'.code.toByte() || c == '<'.code.toByte() || c == '>'.code.toByte() ||
                    c == '('.code.toByte() || c == ')'.code.toByte() || c == '{'.code.toByte() ||
                    c == '}'.code.toByte() || c == '%'.code.toByte()
                ) {
                    break
                }
                // #xx 十六进制转义
                if (c == '#'.code.toByte() && pos + 2 < buf.size) {
                    val hex = String(buf, pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                    if (hex != null) {
                        sb.append(hex.toChar())
                        pos += 3
                        continue
                    }
                }
                sb.append(c.toInt().toChar())
                pos++
            }
            return sb.toString()
        }

        private fun parseLiteralString(): PdfObject {
            pos++
            val out = java.io.ByteArrayOutputStream()
            var depth = 1
            while (pos < buf.size) {
                val c = buf[pos]
                when {
                    c == '\\'.code.toByte() -> {
                        pos++
                        val e = if (pos < buf.size) buf[pos] else 0
                        when (e.toInt().toChar()) {
                            'n' -> out.write('\n'.code)
                            'r' -> out.write('\r'.code)
                            't' -> out.write('\t'.code)
                            'b' -> out.write(8)
                            'f' -> out.write(12)
                            '(' -> out.write('('.code)
                            ')' -> out.write(')'.code)
                            '\\' -> out.write('\\'.code)
                            '\n' -> {}
                            '\r' -> if (pos + 1 < buf.size && buf[pos + 1] == '\n'.code.toByte()) pos++
                            in '0'..'7' -> {
                                var v = 0
                                var n = 0
                                while (n < 3 && pos < buf.size && buf[pos].toInt().toChar() in '0'..'7') {
                                    v = v * 8 + (buf[pos].toInt().toChar() - '0')
                                    pos++
                                    n++
                                }
                                out.write(v)
                                pos--
                            }

                            else -> out.write(e.toInt())
                        }
                        pos++
                    }

                    c == '('.code.toByte() -> {
                        depth++
                        out.write('('.code)
                        pos++
                    }

                    c == ')'.code.toByte() -> {
                        depth--
                        pos++
                        if (depth == 0) break
                        out.write(')'.code)
                    }

                    else -> {
                        out.write(c.toInt())
                        pos++
                    }
                }
            }
            return PdfObject.Str(out.toByteArray())
        }

        private fun parseHexString(): PdfObject {
            pos++
            val sb = StringBuilder()
            while (pos < buf.size && buf[pos] != '>'.code.toByte()) {
                val ch = buf[pos].toInt().toChar()
                if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') sb.append(ch)
                pos++
            }
            pos++
            if (sb.length % 2 == 1) sb.append('0')
            val out = ByteArray(sb.length / 2)
            for (i in out.indices) {
                out[i] = sb.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
            return PdfObject.Str(out)
        }

        private fun parseBool(): PdfObject {
            val v = matches("true")
            pos += if (v) 4 else 5
            return PdfObject.Bool(v)
        }

        /** 数字，或 "N G R" 形式的间接引用 */
        private fun parseNumberOrRef(): PdfObject {
            val start = pos
            while (pos < buf.size) {
                val c = buf[pos]
                if (c == '+'.code.toByte() || c == '-'.code.toByte() || c == '.'.code.toByte() ||
                    (c >= '0'.code.toByte() && c <= '9'.code.toByte())
                ) {
                    pos++
                } else {
                    break
                }
            }
            val text = String(buf, start, pos - start, Charsets.ISO_8859_1)
            val value = text.toDoubleOrNull() ?: return PdfObject.Null

            // 只有整数才可能是 "N G R"
            if (!text.contains('.') && value >= 0 && value < 8_000_000) {
                val save = pos
                skipWs()
                val genStart = pos
                while (pos < buf.size && buf[pos] >= '0'.code.toByte() && buf[pos] <= '9'.code.toByte()) pos++
                if (pos > genStart) {
                    val gen = String(buf, genStart, pos - genStart, Charsets.ISO_8859_1).toIntOrNull()
                    val afterGen = pos
                    skipWs()
                    if (gen != null && pos < buf.size && buf[pos] == 'R'.code.toByte()) {
                        pos++
                        return PdfObject.Ref(value.toInt(), gen)
                    }
                    pos = afterGen
                }
                pos = save
            }
            return PdfObject.Num(value)
        }

        private fun matches(text: String): Boolean {
            if (pos + text.length > buf.size) return false
            for (i in text.indices) if (buf[pos + i] != text[i].code.toByte()) return false
            return true
        }

        private fun indexOf(text: String, from: Int): Int {
            val first = text[0].code.toByte()
            var i = from
            val last = buf.size - text.length
            while (i <= last) {
                if (buf[i] == first && matchesAt(i, text)) return i
                i++
            }
            return -1
        }

        private fun matchesAt(at: Int, text: String): Boolean {
            for (i in text.indices) if (buf[at + i] != text[i].code.toByte()) return false
            return true
        }

        private fun skipWs() {
            while (pos < buf.size) {
                val c = buf[pos]
                if (c == 0.toByte() || c == 9.toByte() || c == 10.toByte() || c == 12.toByte() ||
                    c == 13.toByte() || c == 32.toByte()
                ) {
                    pos++
                } else if (c == '%'.code.toByte()) {
                    // 注释
                    while (pos < buf.size && buf[pos] != '\n'.code.toByte()) pos++
                } else {
                    break
                }
            }
        }
    }
}

private const val MAX_OUTLINE_ITEMS = 20000
