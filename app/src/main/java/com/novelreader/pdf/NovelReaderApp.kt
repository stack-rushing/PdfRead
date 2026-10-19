package com.novelreader.pdf

import android.app.Application
import com.novelreader.pdf.ui.theme.NightMode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class NovelReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先定主题再建任何 Activity：晚一步就会先亮一下再变暗
        // PdfBox 需要知道字体缓存路径（文本提取与部分字体的 CMaps 都要用）
        PDFBoxResourceLoader.init(this)
        NightMode.restore(this)
        container = AppContainer(this)
    }
}
