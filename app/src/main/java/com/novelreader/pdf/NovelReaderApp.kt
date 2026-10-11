package com.novelreader.pdf

import android.app.Application
import com.novelreader.pdf.ui.theme.NightMode

class NovelReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先定主题再建任何 Activity：晚一步就会先亮一下再变暗
        NightMode.restore(this)
        container = AppContainer(this)
    }
}
