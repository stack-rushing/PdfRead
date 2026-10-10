package com.novelreader.pdf.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit 文字识别封装。
 *
 * 选**中文识别器**（bundled 模型，随 APK 一起装、离线可用）的原因：
 * 这两类书都是中英混排——剑桥分级读物是英文正文 + 中文导读，书虫是英汉对照——
 * 中文模型对拉丁字母的识别同样可用，一个模型覆盖两种排版，不必按语言切换。
 *
 * 识别失败一律返回空字符串：目录生成是"尽力而为"的长任务，
 * 单页识别失败不该让整条流水线崩掉。
 */
class OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别一张图。返回的文本保留换行，行结构对"页眉标题"判定很重要 */
    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text -> if (cont.isActive) cont.resume(text.text) }
            .addOnFailureListener { if (cont.isActive) cont.resume("") }
            .addOnCanceledListener { if (cont.isActive) cont.resume("") }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
