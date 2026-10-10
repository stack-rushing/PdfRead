package com.novelreader.pdf.data.model

/**
 * 书架顺序用的**自然排序**：数字段按数值大小比，其余按字符比（忽略大小写）。
 *
 * 为什么不用 SQL 的 `ORDER BY title`：那是逐字节比较（SQLite 的 BINARY collation），
 * 于是 `2-10` 会排在 `2-2` 前面（'1' < '2'）。而人名/书名里的编号恰恰是"数字"语义，
 * 期望的顺序是 `2-2 → 2-10 → 2-13`。
 *
 * 为什么不在 SQL 里做：SQLite 没有内置自然排序，要么写自定义 collation（得注册到连接上），
 * 要么加一个排序列（那就得再来一次数据库迁移 + 回填历史数据）。
 * 书架总共几十本，在 Kotlin 里排序的开销可以忽略——**用不着为它动数据库结构**。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val cmp = compareNumberRun(a, i, b, j)
                if (cmp != 0) return cmp
                i = endOfDigits(a, i)
                j = endOfDigits(b, j)
            } else {
                val xa = ca.lowercaseChar()
                val xb = cb.lowercaseChar()
                if (xa != xb) return xa.compareTo(xb)
                i++
                j++
            }
        }
        // 一方是另一方的前缀时，短的在前
        return (a.length - i).compareTo(b.length - j)
    }

    /**
     * 比较两个数字段。先跳过前导零，再"位数少的更小、位数相同逐位比"——
     * 这样不用把长数字转成整数（`2-999999999999` 这种也不会溢出）。
     */
    private fun compareNumberRun(a: String, ai: Int, b: String, bi: Int): Int {
        val aStart = skipLeadingZeros(a, ai)
        val bStart = skipLeadingZeros(b, bi)
        val aEnd = endOfDigits(a, aStart)
        val bEnd = endOfDigits(b, bStart)
        val aLen = aEnd - aStart
        val bLen = bEnd - bStart
        // 前导零已跳过，所以位数就是大小关系
        if (aLen != bLen) return aLen - bLen
        for (k in 0 until aLen) {
            val diff = a[aStart + k].compareTo(b[bStart + k])
            if (diff != 0) return diff
        }
        // 数值相等（如 07 与 7）：**前导零多的排前面**（`2级07` 在 `2级7` 之前）。
        // 注意方向——写成 (aStart-ai).compareTo(bStart-bi) 会把 07 排到 7 后面（测试抓到了）。
        return (bStart - bi).compareTo(aStart - ai)
    }

    private fun skipLeadingZeros(s: String, from: Int): Int {
        var k = from
        while (k < s.length - 1 && s[k] == '0' && s[k + 1].isDigit()) k++
        return k
    }

    private fun endOfDigits(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }
}
