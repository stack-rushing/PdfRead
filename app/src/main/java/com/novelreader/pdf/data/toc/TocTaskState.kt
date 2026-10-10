package com.novelreader.pdf.data.toc

/** 目录生成任务的进度快照（OCR 跑几千页时要让用户看得见、能取消） */
data class TocTaskState(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100 / total)

    companion object {
        val IDLE = TocTaskState()
    }
}
