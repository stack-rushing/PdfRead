package com.novelreader.pdf.ui.shelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.novelreader.pdf.ui.dict.DictionaryActivity
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

    /** 批量：多选 PDF → 逐本导入并自动跑 OCR 全页眉扫描生成目录 */
    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { BookRepository.takePersistablePermission(this, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModel.importPdfs(uris)
    }

    /** 批量：多选目录 JSON → 按书名匹配书架上的书，整表替换 */
    private val pickTocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        viewModel.importTocs(uris)
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
        binding.btnCancelBatch.setOnClickListener { viewModel.cancelBatch() }

        observeViewModel()
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_shelf, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_batch_import_pdf -> {
            // 部分网盘 / 文件管理器把 PDF 报成 octet-stream，两个都带上更稳
            runCatching { pickPdfs.launch(arrayOf("application/pdf", "application/octet-stream")) }
                .onFailure { snack(getString(R.string.shelf_batch_pick_pdf)) }
            true
        }

        R.id.action_batch_import_toc -> {
            // JSON 的 MIME 报得很乱：application/json / text/plain / octet-stream 都见过
            runCatching {
                pickTocs.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }.onFailure { snack(getString(R.string.shelf_batch_pick_toc)) }
            true
        }

        R.id.action_dictionary -> {
            runCatching { startActivity(Intent(this, DictionaryActivity::class.java)) }
                .onFailure { snack(it.message ?: "无法打开词书页") }
            true
        }

        else -> super.onOptionsItemSelected(item)
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
                        binding.importingView.isVisible = state.importing || viewModel.batch.value.running
                        if (state.importing) binding.importingText.setText(R.string.shelf_importing)
                    }
                }
                launch {
                    // 批量任务进度：第 3 / 7 本 · 扫描页眉 45/102
                    viewModel.batch.collect { batch ->
                        binding.importingView.isVisible = batch.running || viewModel.state.value.importing
                        binding.btnCancelBatch.isVisible = batch.running
                        binding.importingDetail.isVisible = batch.running
                        if (!batch.running) return@collect
                        binding.importingText.text = batch.title
                        val label = getString(R.string.shelf_batch_progress, batch.index, batch.total, batch.phase)
                        binding.importingDetail.text = if (batch.pages > 0) {
                            getString(
                                R.string.shelf_batch_progress_pages,
                                batch.index, batch.total, batch.phase, batch.done, batch.pages,
                            )
                        } else {
                            label
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ShelfEvent.OpenBook -> openReader(event.book)
                            is ShelfEvent.Message -> snack(event.text)
                            is ShelfEvent.TocImported -> showImportSummary(event.summary)
                        }
                    }
                }
            }
        }
    }

    /** 批量结果逐条摊开给用户看：成功、没匹配上的、失败的都列出来，不做"静默成功" */
    private fun showImportSummary(summary: TocImportSummary) {
        if (summary.handled == 0) {
            snack(getString(R.string.shelf_batch_done_none))
            return
        }
        val parts = mutableListOf<String>()
        if (summary.imported.isNotEmpty()) {
            parts += getString(
                R.string.shelf_batch_done_ok,
                summary.imported.size,
                summary.imported.joinToString("\n") { "· $it" },
            )
        }
        if (summary.unmatched.isNotEmpty()) {
            parts += getString(
                R.string.shelf_batch_done_unmatched,
                summary.unmatched.size,
                summary.unmatched.joinToString("\n") { "· $it" },
            )
        }
        if (summary.failed.isNotEmpty()) {
            parts += getString(
                R.string.shelf_batch_done_failed,
                summary.failed.size,
                summary.failed.joinToString("\n") { "· $it" },
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shelf_batch_done_title)
            .setMessage(parts.joinToString("\n\n"))
            .setPositiveButton(R.string.reader_ok, null)
            .show()
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
