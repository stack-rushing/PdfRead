package com.novelreader.pdf.data.pdf

/**
 * 双语目录标题拆分的启发式实现。
 *
 * 分级读物的 PDF 大纲常见几种写法：
 * - "Chapter 1 The Stranger"                 -> (Chapter 1 The Stranger, null)
 * - "第一章 神秘来客"                          -> (第一章 神秘来客, null)
 * - "第一章 神秘来客 Chapter 1 The Stranger"    -> (第一章 神秘来客, Chapter 1 The Stranger)
 * - "1. Chapter 1 The Stranger 第一章 神秘来客" -> (第一章 神秘来客, 1. Chapter 1 The Stranger)
 *
 * 策略：取「首个 CJK 字符到最后一个 CJK 字符」的连续区间作为中文标题（保留区间内的空格与标点），
 * 把该区间抠掉后剩下的部分作为英文标题。
 * 这是展示层的便利手段，**不参与任何定位计算**；如果拆错了，用导出的 JSON 手改即可覆盖。
 */
object BilingualTitle {

    private val cjk = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    private val separatorOnly = Regex("^[\\s\\-_/|·•、,，.。:：;；()（）\\[\\]【】]+|[\\s\\-_/|·•、,，.。:：;；()（）\\[\\]【】]+$")

    data class Split(val title: String, val titleEn: String?)

    fun split(raw: String): Split {
        val text = raw.trim()
        if (text.isEmpty()) return Split("未命名章节", null)

        val matches = cjk.findAll(text).toList()
        if (matches.isEmpty()) return Split(text, null)

        val zh = text.substring(matches.first().range.first, matches.last().range.last + 1).trim()

        val en = buildString {
            append(text.substring(0, matches.first().range.first))
            append(' ')
            append(text.substring(matches.last().range.last + 1))
        }
            .replace(Regex("\\s+"), " ")
            .replace(separatorOnly, "")
            .trim()

        return when {
            zh.isEmpty() -> Split(text, null)
            // 抠掉中文后只剩数字/标点（如 "1 这宗案件开始了" 抠出 "1"）：
            // 这种标题里的序号是标题的一部分（书虫的中文章节名就长这样），必须保留原文。
            en.none { it.isLetter() } -> Split(text, null)
            en.length < 2 -> Split(zh, null)
            en.equals(zh, ignoreCase = true) -> Split(zh, null)
            else -> Split(zh, en)
        }
    }
}
