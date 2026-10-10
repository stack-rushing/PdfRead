package com.novelreader.pdf.data.toc

/** 「自动生成目录」的四种策略。OCR 模式是按页渲染+识别的，耗时随页数线性增长，必须由用户显式选择。 */
enum class TocMode(val label: String) {
    /** 先用内置书签；书签缺失或页码明显漂移时才上 OCR */
    AUTO("自动（书签优先，不够再 OCR）"),

    /** 只读 PDF 内置书签：秒级完成，但 calibre 从 EPUB 转出来的 PDF 页码会漂移 */
    OUTLINE_ONLY("仅用 PDF 内置书签"),

    /** 有书签但页码不准：按标题在 OCR 页眉里重新锚定真实页码 */
    OCR_REANCHOR("OCR 重锚定已有章节页码"),

    /** 完全没书签（扫描件）：OCR 每页页眉，按标题模式挑出章节起始页 */
    OCR_SCAN("OCR 扫描页眉生成目录"),
}
