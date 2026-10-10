package com.novelreader.pdf.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.novelreader.pdf.R
import com.novelreader.pdf.data.model.BookRef
import kotlinx.coroutines.launch
import java.io.File

/**
 * 阅读器：**系统 PdfRenderer + ViewPager2**，唯一的渲染内核。
 *
 * 为什么最终选它（而不是继续用 android-pdf-viewer）：
 * - pdfium 那套早在 2019 年就停止维护，且编译自 `com.android.support`，得一直开着 Jetifier；
 * - 它的 native so 在 Android 15 的 16KB page size 下不保证对齐，属于上架/长期可用的隐患；
 * - ViewPager2 自带页面吸附、预加载与转场动画，本来就是"小说翻页"最合适的载体；
 * - 渲染结果是我们自己的 Bitmap，以后要加长按取词、批注、双页模式都有落脚点。
 *
 * 代价：需要可 seek 的文件（用应用私有副本解决），且不支持加密 PDF。
 */
class ReaderActivity : BaseReaderActivity() {

    private var pageRenderer: PdfPageRenderer? = null
    private var pageAdapter: PdfPageAdapter? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onSetupPageHost(state: ReaderUiState, savedPage: Int) {
        binding.pager.isVisible = true
        binding.pager.offscreenPageLimit = 1

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 恢复滑动：上一页可能停在放大状态、把 isUserInputEnabled 关掉了
                binding.pager.isUserInputEnabled = true
                viewModel.onPageChanged(position)
            }
        }
        pageChangeCallback = callback
        binding.pager.registerOnPageChangeCallback(callback)

        lifecycleScope.launch {
            val file = resolveLocalFile()
            if (file == null) {
                viewModel.onDocumentError(getString(R.string.reader_renderer_need_local))
                return@launch
            }

            val renderer = PdfPageRenderer(file)
            val count = try {
                renderer.open()
            } catch (t: Throwable) {
                runCatching { renderer.close() }
                viewModel.onDocumentError(
                    getString(R.string.reader_renderer_failed, t.message ?: t.javaClass.simpleName),
                )
                return@launch
            }

            pageRenderer = renderer
            val adapter = PdfPageAdapter(renderer, lifecycleScope, { binding.pager.width }) { zoomed ->
                // 放大时关掉 ViewPager2 的滑动：横向手势要用来平移页面，而不是翻页
                binding.pager.isUserInputEnabled = !zoomed
            }
            adapter.nightMode = state.nightMode
            pageAdapter = adapter
            binding.pager.adapter = adapter
            binding.pager.setBackgroundColor(if (state.nightMode) Color.BLACK else Color.WHITE)
            binding.pager.setCurrentItem(savedPage.coerceIn(0, (count - 1).coerceAtLeast(0)), false)
            viewModel.onDocumentReady(count)
        }
    }

    override fun onJumpToPage(page: Int, smooth: Boolean) {
        runCatching { binding.pager.setCurrentItem(page, smooth) }
    }

    override fun onApplyNightMode(enabled: Boolean) {
        binding.pager.setBackgroundColor(if (enabled) Color.BLACK else Color.WHITE)
        pageAdapter?.nightMode = enabled
    }

    override fun onReleaseEngine() {
        pageChangeCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        pageAdapter?.clearCache()
        pageAdapter = null
        val renderer = pageRenderer
        pageRenderer = null
        // 用 Application 作用域关闭，避免 lifecycleScope 已取消导致 native 句柄泄漏
        container.appScope.launch { runCatching { renderer?.close() } }
    }

    /**
     * 取本地副本：优先用文件路径，其次从原始 uri 重新拷贝。
     * 同时把路径回填到 Room，下次打开无需再拷贝。
     */
    private suspend fun resolveLocalFile(): File? {
        bookRef.localPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0L) return file
        }
        val uri = bookRef.uri.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val restored = container.bookRepository.rehydrate(bookRef.bookId, uri) ?: return null
        container.libraryRepository.updateLocalPath(bookRef.bookId, restored.absolutePath)
        return restored
    }

    companion object {
        fun intent(context: Context, book: BookRef): Intent =
            BaseReaderActivity.intent(context, book, ReaderActivity::class.java)
    }
}
