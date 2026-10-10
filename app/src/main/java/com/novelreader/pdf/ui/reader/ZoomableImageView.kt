package com.novelreader.pdf.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可缩放、可拖动的页面视图。
 *
 * 交互：**双指捏合缩放**、**双击在「适应屏幕 ↔ 2.5 倍」之间切换**、放大后**单指拖动**平移。
 *
 * 和宿主（ViewPager2）的分工，是整个功能里最容易做错的一点：
 * - **没放大时**，横向手势必须留给 ViewPager2 去翻页 —— 所以这里不主动
 *   `requestDisallowInterceptTouchEvent(true)`，宿主照常能把手势"抢走"；
 * - **放大后**，横向手势应该是平移页面而不是翻页 —— 于是主动禁止宿主拦截，
 *   并回调 [onZoomChanged] 让宿主把 `isUserInputEnabled` 关掉。
 *
 * 另外，放大到底片分辨率之上只会更糊，所以手指离开时回调 [onZoomSettled]，
 * 由宿主决定要不要按更高分辨率重新渲染这一页（见 [ZoomMath.renderWidthFor]）。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 当前是否处于放大状态 */
    var isZoomed: Boolean = false
        private set

    /** 放大状态变化（宿主据此开关 ViewPager2 的滑动） */
    var onZoomChanged: ((Boolean) -> Unit)? = null

    /** 手势结束时的当前倍数（宿主据此决定是否重渲染高清页） */
    var onZoomSettled: ((Float) -> Unit)? = null

    private val matrix = Matrix()
    private var zoom = ZoomMath.MIN_SCALE
    private var baseScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var contentWidth = 0
    private var contentHeight = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // 双指一旦落下就不许宿主抢：否则捏合会被当成翻页
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = ZoomMath.clampScale(zoom * detector.scaleFactor)
                if (target == zoom) return true
                // 以捏合焦点为中心缩放：焦点下的那个字保持不动
                scaleAround(detector.focusX, detector.focusY, target)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!isZoomed) return false
                tx -= distanceX
                ty -= distanceY
                applyTransform()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = ZoomMath.doubleTapTarget(zoom)
                if (target == ZoomMath.MIN_SCALE) {
                    resetZoom()
                } else {
                    scaleAround(e.x, e.y, target)
                }
                onZoomSettled?.invoke(zoom)
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        contentWidth = bm?.width ?: 0
        contentHeight = bm?.height ?: 0
        // 换图（例如放大后换成高清版）时**不重置倍数**，只是重算基准：
        // 高清页与低清页宽高比一致，所以画面不会跳。
        recomputeBaseScale()
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeBaseScale()
        applyTransform()
    }

    /** 回到「适应屏幕」。翻页/复用 ViewHolder 时调用 */
    fun resetZoom() {
        zoom = ZoomMath.MIN_SCALE
        tx = 0f
        ty = 0f
        applyTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onZoomSettled?.invoke(zoom)
            }
        }
        // 始终消费：这样双指手势不会被宿主吞掉；
        // 未放大时不主动 disallow，所以横向滑动照样能被 ViewPager2 拦截去翻页。
        return true
    }

    /** 以 ([focusX], [focusY]) 为不动点缩放到 [target] 倍 */
    private fun scaleAround(focusX: Float, focusY: Float, target: Float) {
        if (zoom <= 0f) return
        val ratio = target / zoom
        tx = focusX - (focusX - tx) * ratio
        ty = focusY - (focusY - ty) * ratio
        zoom = target
        applyTransform()
    }

    private fun recomputeBaseScale() {
        baseScale = ZoomMath.fitScale(contentWidth, contentHeight, width, height)
    }

    private fun applyTransform() {
        val scale = baseScale * zoom
        val scaledWidth = contentWidth * scale
        val scaledHeight = contentHeight * scale
        tx = ZoomMath.clampTranslate(tx, scaledWidth, width.toFloat())
        ty = ZoomMath.clampTranslate(ty, scaledHeight, height.toFloat())

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
        imageMatrix = matrix

        val zoomed = ZoomMath.isZoomed(zoom)
        if (zoomed != isZoomed) {
            isZoomed = zoomed
            onZoomChanged?.invoke(zoomed)
        }
    }
}
