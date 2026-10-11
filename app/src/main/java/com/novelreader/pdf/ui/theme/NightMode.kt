package com.novelreader.pdf.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * 夜间模式的落地：**界面本身**要变深色，而不只是把 PDF 位图反色。
 *
 * 之前（v2.2.3 及更早）的毛病：主题是 `DayNight`，`values-night/colors.xml` 也早就写好了深色配色，
 * 但**没有任何地方告诉 AppCompat 现在要进夜间**——它只会跟随系统深色开关。
 * 于是 App 自己的夜间模式只做了「把 PDF 位图反色」，工具栏、章节抽屉、底栏一律还是白的，
 * 晚上用起来比不开还刺眼。
 *
 * 设置本身存在 Room 里（唯一可信来源，两个界面都监听它）。但**启动那一瞬间**要用同步的方式
 * 决定加载哪套主题，而 Room 查询是异步的，所以这里镜像一份到 SharedPreferences，
 * 只服务于「启动时立刻读一次」。两边写入都在同一个地方发生（见 [apply]）。
 */
object NightMode {

    private const val PREFS_NAME = "ui_prefs"
    private const val KEY_MODE = "night_mode"

    /** 从未设置过 → 跟随系统 */
    const val FOLLOW_SYSTEM = -1
    private const val LIGHT = 0
    private const val DARK = 1

    /** App 启动时调用：把上次的选择恢复到 AppCompat 上 */
    fun restore(context: Context) {
        setDelegate(
            when (read(context)) {
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /**
     * 切换夜间模式。会写入镜像值并让 AppCompat 切主题——
     * 后者会让当前 Activity 重建，阅读器靠 Room 里存的页码回到原处。
     */
    fun apply(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, if (enabled) DARK else LIGHT)
            .apply()
        setDelegate(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    /**
     * 当前**实际生效**的是不是深色。
     *
     * 直接看资源解析结果，而不是读设置或系统配置：用户可能在系统里开着深色、
     * 却在 App 里手动切成亮色，这时只有 resources 知道真相。状态栏图标颜色就靠它。
     */
    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun read(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, FOLLOW_SYSTEM)

    /** 系统当前是不是深色（只在"首次运行取初值"时用得到） */
    fun systemIsDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun setDelegate(mode: Int) {
        // 相同值再设一次也会触发重建，所以先比一下
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
