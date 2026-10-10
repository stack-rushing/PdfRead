package com.novelreader.pdf.ui.shelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.novelreader.pdf.NovelReaderApp
import com.novelreader.pdf.R
import com.novelreader.pdf.data.repository.BookRepository
import com.novelreader.pdf.databinding.ActivityMainBinding
import com.novelreader.pdf.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

/**
 * 书架：导入 PDF（SAF）+ 列出最近阅读 + 断点进度与目录状态一览。
 *
 * 交互：
 * - 单击 → 打开阅读器；
 * - 右侧垃圾桶 → 移除书架记录（保留本地副本）；
 * - 长按卡片 → 询问是否连本地副本一起删除。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as NovelReaderApp).container)
    }

    private lateinit var adapter: BookShelfAdapter

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        // 立刻申请持久化读权限：否则下次冷启动打开同一本书会因授权丢失而失败
        BookRepository.takePersistablePermission(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        viewModel.import(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = BookShelfAdapter(
            onOpen = { summary -> viewModel.open(summary) },
            onDelete = { summary -> viewModel.remove(summary) },
            onDeleteWithFile = { summary -> confirmDeleteWithFile(summary) },
        )
        binding.shelfList.layoutManager = LinearLayoutManager(this)
        binding.shelfList.adapter = adapter

        binding.fabImport.setOnClickListener { launchPicker() }

        observeViewModel()
    }

    private fun launchPicker() {
        // 部分网盘 / 文件管理器把 PDF 报成 octet-stream，两个都带上更稳
        runCatching { pickPdf.launch(arrayOf("application/pdf", "application/octet-stream")) }
            .onFailure { snack("没有可用的文件选择器") }
    }

    private fun confirmDeleteWithFile(summary: com.novelreader.pdf.data.model.BookSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(summary.title)
            .setMessage(R.string.shelf_delete_with_file_confirm)
            .setNegativeButton(R.string.reader_cancel, null)
            .setPositiveButton(R.string.reader_ok) { _, _ -> viewModel.removeWithLocalCopy(summary) }
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.books)
                        binding.emptyView.isVisible = !state.loading && state.books.isEmpty()
                        binding.importingView.isVisible = state.importing
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ShelfEvent.OpenBook -> openReader(event.book)
                            is ShelfEvent.Message -> snack(event.text)
                        }
                    }
                }
            }
        }
    }

    private fun openReader(book: com.novelreader.pdf.data.model.BookRef) {
        runCatching { startActivity(ReaderActivity.intent(this, book)) }
            .onFailure { snack("无法打开阅读器：${it.message}") }
    }

    private fun snack(message: String) {
        Snackbar.make(binding.shelfRoot, message, Snackbar.LENGTH_SHORT).show()
    }

    /** 文件管理器把 PDF 分享给本应用时也可直接导入 */
    @Suppress("unused")
    private fun handleIncomingUri(uri: Uri?) {
        if (uri == null) return
        BookRepository.takePersistablePermission(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        viewModel.import(uri)
    }
}
