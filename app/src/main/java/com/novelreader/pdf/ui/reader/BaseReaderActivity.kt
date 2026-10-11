package com.novelreader.pdf.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.NovelReaderApp
import com.novelreader.pdf.R
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.toc.TocMode
import com.novelreader.pdf.databinding.ActivityReaderBinding
import com.novelreader.pdf.ui.theme.NightMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 阅读器基类：沉浸式窗口、点击显隐控件、进度条、目录抽屉、生命周期落盘、错误兜底。
 *
 * 现在只有 **PdfRenderer + ViewPager2** 一个渲染内核（android-pdf-viewer / pdfium 已移除），
 * 所以这一层不再需要抽象渲染引擎；保留它是因为"窗口/手势/落盘/目录"这些逻辑
 * 与渲染完全正交，放在基类里比塞进 Activity 更清楚。
 */
abstract class BaseReaderActivity : AppCompatActivity() {

    protected lateinit var binding: ActivityReaderBinding

    protected val container: AppContainer
        get() = (application as NovelReaderApp).container

    /** 从 Intent 还原书籍上下文；用 lazy 保证 `by viewModels` 首次访问时已就绪 */
    protected val bookRef: BookRef by lazy {
        BookRef(
            bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty(),
            uri = intent.getStringExtra(EXTRA_BOOK_URI).orEmpty(),
            title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty().ifBlank { "未命名书籍" },
            localPath = intent.getStringExtra(EXTRA_BOOK_LOCAL_PATH),
        )
    }

    protected val viewModel: ReaderViewModel by viewModels {
        ReaderViewModelFactory(container, bookRef)
    }

    private var engineInitialized = false
    private var controlsVisibleShown = true
    private var nightModeApplied = false
    private var welcomeShown = false
    private var userTouchedControls = false

    private var insetTop = 0
    private var insetBottom = 0

    // ------------------------------------------------------------------ 子类契约

    /** 挂载页面视图并启动加载。[savedPage] 是断点页 */
    protected abstract fun onSetupPageHost(state: ReaderUiState, savedPage: Int)

    protected abstract fun onJumpToPage(page: Int, smooth: Boolean)

    protected abstract fun onApplyNightMode(enabled: Boolean)

    protected abstract fun onReleaseEngine()

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupControls()
        observeViewModel()
    }

    /**
     * 断点续读的关键落点：
     * onPause 覆盖「按 Home / 切到其他 App / 锁屏」，onStop 覆盖「完全不可见」，
     * ViewModel.onCleared 覆盖「返回键退出」。
     */
    override fun onPause() {
        viewModel.flush()
        super.onPause()
    }

    override fun onStop() {
        viewModel.flush()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { onReleaseEngine() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 沉浸式 / 边距

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // 取历史最大值：系统栏隐藏时 insets 会变 0，用 max 保证控件位置不跳
            insetTop = maxOf(insetTop, bars.top)
            insetBottom = maxOf(insetBottom, bars.bottom)
            binding.topBar.updatePadding(top = insetTop)
            binding.bottomBar.updatePadding(bottom = insetBottom)
            insets
        }
    }

    private fun applySystemBars(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 用**实际生效的主题**判断，而不是系统深色开关：
        // 用户可能在系统里开着深色、却在 App 里手动切成亮色，那时系统配置说了不算。
        val dark = NightMode.isDark(this)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ 交互绑定

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnToc.setOnClickListener { viewModel.openChapterList() }
        binding.btnNightMode.setOnClickListener { viewModel.toggleNightMode() }
        binding.btnMore.setOnClickListener { anchor -> showOverflowMenu(anchor) }
        binding.btnPrevChapter.setOnClickListener { viewModel.prevChapter() }
        binding.btnNextChapter.setOnClickListener { viewModel.nextChapter() }
        binding.btnPrevPage.setOnClickListener { viewModel.prevPage() }
        binding.btnNextPage.setOnClickListener { viewModel.nextPage() }
        binding.btnRetry.setOnClickListener { recreate() }
        binding.btnTocCancel.setOnClickListener { viewModel.cancelToc() }

        // 点击热区：中央显隐控件，左右翻页
        binding.tapZone.onCenterTap = {
            userTouchedControls = true
            viewModel.toggleControls()
        }
        binding.tapZone.onLeftTap = { viewModel.prevPage() }
        binding.tapZone.onRightTap = { viewModel.nextPage() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.onSeekBarPreview(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                viewModel.onSeekBarDragStart()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                viewModel.onSeekBarCommit(seekBar?.progress ?: 0)
            }
        })
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, MENU_TOC_AUTO, 0, R.string.reader_menu_toc_auto)
            menu.add(Menu.NONE, MENU_TOC_OUTLINE, 1, R.string.reader_menu_toc_outline)
            menu.add(Menu.NONE, MENU_TOC_OCR_REANCHOR, 2, R.string.reader_menu_toc_ocr_reanchor)
            menu.add(Menu.NONE, MENU_TOC_OCR_SCAN, 3, R.string.reader_menu_toc_ocr_scan)
            menu.add(Menu.NONE, MENU_TOC_EXPORT, 4, R.string.reader_menu_export_chapters)
            menu.add(Menu.NONE, MENU_TOC_CLEAR, 5, R.string.reader_menu_toc_clear)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_TOC_AUTO -> {
                        confirmTocMode(TocMode.AUTO); true
                    }

                    MENU_TOC_OUTLINE -> {
                        viewModel.generateToc(TocMode.OUTLINE_ONLY); true
                    }

                    MENU_TOC_OCR_REANCHOR -> {
                        confirmTocMode(TocMode.OCR_REANCHOR); true
                    }

                    MENU_TOC_OCR_SCAN -> {
                        confirmTocMode(TocMode.OCR_SCAN); true
                    }

                    MENU_TOC_EXPORT -> {
                        viewModel.exportChapters(); true
                    }

                    MENU_TOC_CLEAR -> {
                        viewModel.clearToc(); true
                    }

                    else -> false
                }
            }
        }.show()
    }

    /** OCR 是按页跑的，2278 页的书要先讲清楚代价再让用户点 */
    private fun confirmTocMode(mode: TocMode) {
        val pages = viewModel.uiState.value.totalPages
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reader_toc_confirm_title)
            .setMessage(getString(R.string.reader_toc_confirm_message, mode.label, pages))
            .setNegativeButton(R.string.reader_cancel, null)
            .setPositiveButton(R.string.reader_ok) { _, _ -> viewModel.generateToc(mode) }
            .show()
    }

    // ------------------------------------------------------------------ 状态渲染

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(state: ReaderUiState) {
        if (!state.progressLoaded) return

        if (!engineInitialized) {
            engineInitialized = true
            onSetupPageHost(state, state.initialPage)
        }

        // 顶部：当前章节（翻页时由 ViewModel 动态计算；书虫合集还能看到"在哪本书里"）
        binding.tvHeaderTitle.text = state.headerTitle
        val subtitle = state.headerSubtitle ?: state.headerFallback
        binding.tvHeaderSubtitle.text = subtitle
        binding.tvHeaderSubtitle.isVisible = subtitle.isNotBlank()

        // 底部：章节名 + 页码 + 进度
        binding.tvChapterName.text = state.currentChapter?.title
            ?: getString(R.string.reader_chapter_unknown)
        binding.tvPageIndicator.text = if (state.totalPages > 0) {
            getString(
                R.string.reader_page_indicator_percent,
                state.displayPage,
                state.totalPages,
                state.progressPercent,
            )
        } else {
            getString(R.string.reader_page_indicator, state.displayPage, state.totalPages)
        }

        if (!state.seekBarDragging && binding.seekBar.progress != state.progressPercent) {
            binding.seekBar.progress = state.progressPercent
        }

        binding.btnPrevChapter.isEnabled = state.hasPrevChapter
        binding.btnNextChapter.isEnabled = state.hasNextChapter
        binding.btnPrevPage.isEnabled = state.currentPage > 0
        binding.btnNextPage.isEnabled = state.currentPage < state.totalPages - 1

        if (controlsVisibleShown != state.controlsVisible) {
            controlsVisibleShown = state.controlsVisible
            animateControls(state.controlsVisible)
        }

        if (nightModeApplied != state.nightMode) {
            nightModeApplied = state.nightMode
            // 先切界面主题（工具栏/抽屉/底栏跟着变深色），再让引擎处理页面位图。
            // 切主题会让本 Activity 重建，阅读器靠 Room 里的页码回到原处。
            NightMode.apply(this, state.nightMode)
            onApplyNightMode(state.nightMode)
        }

        binding.errorPanel.isVisible = state.error != null
        binding.tvErrorText.text = state.error.orEmpty()

        renderTocTask(state)

        if (state.documentReady) {
            hideLoading()
            maybeShowWelcome()
        }
    }

    private fun renderTocTask(state: ReaderUiState) {
        val task = state.tocTask
        binding.tocPanel.isVisible = task.running
        if (!task.running) return
        binding.tocProgress.isIndeterminate = task.total <= 0
        if (task.total > 0) binding.tocProgress.progress = task.percent
        binding.tocText.text = if (task.total > 0) {
            getString(R.string.reader_toc_progress, task.phase, task.done, task.total, task.percent)
        } else {
            task.phase
        }
    }

    private fun handleEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.JumpToPage -> onJumpToPage(event.page, event.smooth)
            is ReaderEvent.Message -> snack(event.text)
            is ReaderEvent.ChaptersExported -> {
                snack(
                    getString(R.string.reader_exported, event.path),
                    actionLabel = getString(R.string.reader_share),
                ) { shareJson(event.path) }
            }

            ReaderEvent.OpenChapterList -> showChapterSheet()
            ReaderEvent.Finish -> finish()
        }
    }

    private fun animateControls(visible: Boolean) {
        val offset = resources.displayMetrics.density * 16f
        listOf(binding.topBar, binding.bottomBar).forEach { bar ->
            bar.animate().cancel()
            val target = if (bar === binding.topBar) -offset else offset
            if (visible) {
                bar.visibility = View.VISIBLE
                bar.alpha = 0f
                bar.translationY = target
                bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            } else {
                bar.animate().alpha(0f).translationY(target).setDuration(140L)
                    .withEndAction { bar.visibility = View.GONE }
                    .start()
            }
        }
        applySystemBars(visible)
    }

    /** 首次打开时把控件亮出来 2.5 秒，让用户看到"上次读到第几页"，然后自动回到沉浸式 */
    private fun maybeShowWelcome() {
        if (welcomeShown) return
        welcomeShown = true
        viewModel.setControlsVisible(true)
        lifecycleScope.launch {
            delay(WELCOME_DURATION_MS)
            if (!userTouchedControls) viewModel.setControlsVisible(false)
        }
    }

    private fun hideLoading() {
        binding.loading.isVisible = false
        binding.loadingText.isVisible = false
    }

    private fun showChapterSheet() {
        if (supportFragmentManager.findFragmentByTag(ChapterSheetFragment.TAG) != null) return
        ChapterSheetFragment.newInstance(bookRef).show(supportFragmentManager, ChapterSheetFragment.TAG)
    }

    private fun shareJson(path: String) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.reader_share_chooser)))
        }.onFailure { snack("分享失败：${it.message}") }
    }

    protected fun snack(
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        Snackbar.make(binding.readerRoot, message, Snackbar.LENGTH_LONG).apply {
            if (actionLabel != null && action != null) setAction(actionLabel) { action() }
        }.show()
    }

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val EXTRA_BOOK_URI = "extra_book_uri"
        private const val EXTRA_BOOK_TITLE = "extra_book_title"
        private const val EXTRA_BOOK_LOCAL_PATH = "extra_book_local_path"

        private const val MENU_TOC_AUTO = 1
        private const val MENU_TOC_OUTLINE = 2
        private const val MENU_TOC_OCR_REANCHOR = 3
        private const val MENU_TOC_OCR_SCAN = 4
        private const val MENU_TOC_EXPORT = 5
        private const val MENU_TOC_CLEAR = 6

        private const val WELCOME_DURATION_MS = 2500L

        fun intent(context: Context, book: BookRef, clazz: Class<out BaseReaderActivity>): Intent =
            Intent(context, clazz).apply {
                putExtra(EXTRA_BOOK_ID, book.bookId)
                putExtra(EXTRA_BOOK_URI, book.uri)
                putExtra(EXTRA_BOOK_TITLE, book.title)
                putExtra(EXTRA_BOOK_LOCAL_PATH, book.localPath)
            }

        /** 供目录抽屉复用的参数键（抽屉要自己造一份 BookRef 拿 ViewModel） */
        fun putBookExtras(intent: Intent, book: BookRef) {
            intent.putExtra(EXTRA_BOOK_ID, book.bookId)
            intent.putExtra(EXTRA_BOOK_URI, book.uri)
            intent.putExtra(EXTRA_BOOK_TITLE, book.title)
            intent.putExtra(EXTRA_BOOK_LOCAL_PATH, book.localPath)
        }

        const val ARG_BOOK_ID = EXTRA_BOOK_ID
        const val ARG_BOOK_URI = EXTRA_BOOK_URI
        const val ARG_BOOK_TITLE = EXTRA_BOOK_TITLE
        const val ARG_BOOK_LOCAL_PATH = EXTRA_BOOK_LOCAL_PATH
    }
}
