package com.novelreader.pdf

import android.app.Application
import com.novelreader.pdf.ui.theme.NightMode
import kotlinx.coroutines.launch

class NovelReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先定主题再建任何 Activity：晚一步就会先亮一下再变暗
        NightMode.restore(this)
        container = AppContainer(this)

        // 首次运行（还没手动切过）：把系统深色设置写成初值。
        //
        // 不写的话会出现错位：界面跟随系统变深色，而 PDF 反色只看数据库里那个还没设过的值，
        // 于是系统深色的手机上会出现「界面全黑、PDF 惨白」——正好是最刺眼的那种组合。
        if (NightMode.read(this) == NightMode.FOLLOW_SYSTEM) {
            val dark = NightMode.systemIsDark(this)
            NightMode.apply(this, dark)
            container.appScope.launch { runCatching { container.libraryRepository.setNightMode(dark) } }
        }
    }
}
