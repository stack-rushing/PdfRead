package com.novelreader.pdf.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 阅读区手势层：把屏幕横向切成「左翻页 / 中间显隐控件 / 右翻页」三个热区。
 *
 * 关键实现细节：用 [dispatchTouchEvent] 而不是 `onInterceptTouchEvent`。
 * `onInterceptTouchEvent` 返回 true 会把事件从子 View 手里抢走，破坏 PDFView 的拖动；
 * 而 `dispatchTouchEvent` 是事件分发链的入口，在这里用 GestureDetector「只观察不消费」，
 * 既能拿到所有点击（包括被 PDFView / ViewPager2 处理掉的那些），又完全不影响子 View 的手势。
 *
 * 同时，这个 View 在布局里只包裹阅读区、不包裹顶/底控制面板，
 * 因此点击按钮不会被误判成「切换控件显隐」。
 */
class TapZoneLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onCenterTap: (() -> Unit)? = null
    var onLeftTap: (() -> Unit)? = null
    var onRightTap: (() -> Unit)? = null

    /** 左右热区占宽度的比例，默认各 25% */
    var edgeRatio: Float = 0.25f

    /** 是否启用左右点击翻页（关掉的话左右点击等同中央点击） */
    var edgeTapEnabled: Boolean = true

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x)
                return false
            }
        },
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        runCatching { detector.onTouchEvent(ev) }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleTap(x: Float) {
        val width = width.toFloat()
        if (width <= 0f) return
        if (!edgeTapEnabled) {
            onCenterTap?.invoke()
            return
        }
        when {
            x < width * edgeRatio -> (onLeftTap ?: onCenterTap)?.invoke()
            x > width * (1f - edgeRatio) -> (onRightTap ?: onCenterTap)?.invoke()
            else -> onCenterTap?.invoke()
        }
    }
}
