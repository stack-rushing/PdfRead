package com.novelreader.pdf.ui.reader

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.novelreader.pdf.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewPager2 的页面适配器：一页一个 Bitmap，横向滑动天然吸附（ViewPager2 自带 page snap）。
 *
 * 相比直接使用 PDFView，这条路径的优势是「完全可控」：
 * 可以自由插入页码、批注层、长按取词等自定义渲染。
 *
 * 放大功能在这里的三个落点：
 * 1. 页面视图是 [ZoomableImageView]（捏合/双击/拖动）；
 * 2. 缓存键是 **`页码@渲染宽度`**——放大后同一页会有一份高清版，两份都要能各自命中；
 * 3. 放大到位后按更高分辨率**重渲染**当前页再换上去，否则扫描件放大就是一团糊。
 */
class PdfPageAdapter(
    private val renderer: PdfPageRenderer,
    private val scope: CoroutineScope,
    private val hostWidthProvider: () -> Int,
    /** 放大状态变化：宿主据此关掉 ViewPager2 的滑动，让横向手势变成平移 */
    private val onZoomChanged: (Boolean) -> Unit = {},
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    /** 夜间模式：对已渲染的位图做反色，无需重新渲染，切换零延迟 */
    var nightMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /**
     * 位图缓存：按 1/6 可用堆内存上限缓存已渲染页面。
     * 留出余量是因为 ViewPager2 的 offscreenPageLimit 会同时持有相邻页，
     * 再叠加渲染中产生的临时 Bitmap，以及放大后的高清版。
     */
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 当前绑定在页面上的 ViewHolder：高清页渲染回来时要精确换到对应那一页 */
    private val boundHolders = HashMap<Int, PageHolder>()

    inner class PageHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var renderJob: Job? = null
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val image = holder.binding.pageImage
        // ViewHolder 会被复用，缩放状态与回调都必须重新设置
        image.resetZoom()
        image.onZoomChanged = { zoomed -> onZoomChanged(zoomed) }
        image.onZoomSettled = { scale -> requestHighRes(position, scale) }

        val width = baseRenderWidth()
        val key = cacheKey(position, width)
        val cached = cache.get(key)
        if (cached != null) {
            image.setImageBitmap(cached)
            holder.binding.pageLoading.isVisible = false
            applyNightMode(holder)
            return
        }

        image.setImageDrawable(null)
        holder.binding.pageLoading.isVisible = true
        holder.renderJob?.cancel()

        holder.renderJob = scope.launch {
            val bitmap = renderer.renderPage(position, width) ?: return@launch
            cache.put(key, bitmap)
            // 渲染是异步的，回来时该 ViewHolder 可能已经被复用给别的页码了
            if (holder.bindingAdapterPosition == position) {
                image.setImageBitmap(bitmap)
                holder.binding.pageLoading.isVisible = false
                applyNightMode(holder)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) boundHolders[position] = holder
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION && boundHolders[position] === holder) {
            boundHolders.remove(position)
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.renderJob?.cancel()
        holder.renderJob = null
        super.onViewRecycled(holder)
    }

    fun clearCache() {
        cache.evictAll()
        boundHolders.clear()
    }

    // ------------------------------------------------------------------ 放大后的高清重渲染

    /**
     * 放大到位后按更高分辨率重渲染这一页。
     *
     * 为什么必须做：低清页是按 ViewPager2 宽度渲染的，放大 2.5 倍等于把每个像素拉大 2.5 倍，
     * 扫描件上的小字会立刻糊成一团——而"放大看清小字"正是这个功能存在的唯一理由。
     * 换图时 [ZoomableImageView.setImageBitmap] 只重算基准倍数、保留当前缩放，所以画面不会跳。
     */
    private fun requestHighRes(position: Int, scale: Float) {
        val base = baseRenderWidth()
        val target = ZoomMath.renderWidthFor(scale, base)
        if (target <= base) return

        val key = cacheKey(position, target)
        cache.get(key)?.let { cached ->
            swapBitmap(position, cached)
            return
        }
        scope.launch {
            val bitmap = renderer.renderPage(position, target) ?: return@launch
            cache.put(key, bitmap)
            swapBitmap(position, bitmap)
        }
    }

    private fun swapBitmap(position: Int, bitmap: Bitmap) {
        val holder = boundHolders[position] ?: return
        if (holder.bindingAdapterPosition != position) return
        holder.binding.pageImage.setImageBitmap(bitmap)
        applyNightMode(holder)
    }

    private fun baseRenderWidth(): Int =
        hostWidthProvider().takeIf { it > 0 } ?: DEFAULT_RENDER_WIDTH

    private fun cacheKey(page: Int, width: Int): String = "$page@$width"

    private fun applyNightMode(holder: PageHolder) {
        holder.binding.pageImage.colorFilter = if (nightMode) INVERT_FILTER else null
    }

    private companion object {
        const val DEFAULT_RENDER_WIDTH = 1080

        val INVERT_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        fun cacheSizeKb(): Int {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return (maxKb / 6).coerceAtLeast(16 * 1024)
        }
    }
}
