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
 */
class PdfPageAdapter(
    private val renderer: PdfPageRenderer,
    private val scope: CoroutineScope,
    private val hostWidthProvider: () -> Int,
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
     * 再叠加渲染中产生的临时 Bitmap。
     */
    private val cache = object : LruCache<Int, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    inner class PageHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var renderJob: Job? = null
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val cached = cache.get(position)
        if (cached != null) {
            holder.binding.pageImage.setImageBitmap(cached)
            holder.binding.pageLoading.isVisible = false
            applyNightMode(holder)
            return
        }

        holder.binding.pageImage.setImageDrawable(null)
        holder.binding.pageLoading.isVisible = true
        holder.renderJob?.cancel()

        val targetWidth = hostWidthProvider().takeIf { it > 0 } ?: DEFAULT_RENDER_WIDTH
        holder.renderJob = scope.launch {
            val bitmap = renderer.renderPage(position, targetWidth) ?: return@launch
            cache.put(position, bitmap)
            // 渲染是异步的，回来时该 ViewHolder 可能已经被复用给别的页码了
            if (holder.bindingAdapterPosition == position) {
                holder.binding.pageImage.setImageBitmap(bitmap)
                holder.binding.pageLoading.isVisible = false
                applyNightMode(holder)
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.renderJob?.cancel()
        holder.renderJob = null
        super.onViewRecycled(holder)
    }

    fun clearCache() = cache.evictAll()

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
