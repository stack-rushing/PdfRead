package com.novelreader.pdf.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.NovelReaderApp
import com.novelreader.pdf.R
import com.novelreader.pdf.data.model.BookRef
import com.novelreader.pdf.data.toc.TocMode
import com.novelreader.pdf.databinding.BottomSheetChaptersBinding
import kotlinx.coroutines.launch

/**
 * 章节抽屉（BottomSheetDialog）。
 *
 * 与上一版最大的不同：**层级化 + 可折叠**。
 * 书虫 4 级合集是 16 本书 / 506 条大纲，平铺根本没法用；
 * 这里默认只展开"当前章节所在的路径"，其余分组折叠，点箭头逐层展开。
 *
 * 数据与进度都取自 Activity 作用域的 [ReaderViewModel]（`activityViewModels`），
 * 所以目录生成任务跑起来后抽屉里能实时看到进度，完成后列表自动刷新，
 * 不需要任何 Fragment 回调或 arguments 回传。
 */
class ChapterSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChaptersBinding? = null
    private val binding get() = _binding!!

    private val container: AppContainer
        get() = (requireActivity().application as NovelReaderApp).container

    private val bookRef: BookRef by lazy {
        val args = requireArguments()
        BookRef(
            bookId = args.getString(BaseReaderActivity.ARG_BOOK_ID).orEmpty(),
            uri = args.getString(BaseReaderActivity.ARG_BOOK_URI).orEmpty(),
            title = args.getString(BaseReaderActivity.ARG_BOOK_TITLE).orEmpty(),
            localPath = args.getString(BaseReaderActivity.ARG_BOOK_LOCAL_PATH),
        )
    }

    private val viewModel: ReaderViewModel by activityViewModels {
        ReaderViewModelFactory(container, bookRef)
    }

    private lateinit var adapter: ChapterAdapter

    private var collapsed = mutableSetOf<Int>()
    private var initializedCollapse = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetChaptersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSheetTitle.text = getString(R.string.reader_chapters_of, bookRef.title)
        binding.tvManualHint.text = getString(
            R.string.reader_manual_hint,
            container.tocRepository.localOverridePath(bookRef.bookId),
        )

        adapter = ChapterAdapter(
            onClick = { index -> viewModel.selectChapter(index) },
            // 长按分组：直接跳到这一组的开头（例如"第 7 本书"）
            onLongClick = { index -> viewModel.selectChapter(index) },
        )
        binding.chapterList.layoutManager = LinearLayoutManager(requireContext())
        binding.chapterList.adapter = adapter
        binding.chapterList.setHasFixedSize(false)

        binding.btnSheetGenerate.setOnClickListener { showModeDialog() }
        binding.btnSheetExport.setOnClickListener { viewModel.exportChapters() }
        binding.btnSheetClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reader_menu_toc_clear)
                .setMessage(R.string.reader_toc_clear_confirm)
                .setNegativeButton(R.string.reader_cancel, null)
                .setPositiveButton(R.string.reader_ok) { _, _ -> viewModel.clearToc() }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: ReaderUiState) {
        val chapters = state.chapters
        binding.tvSheetSource.text = getString(
            R.string.reader_toc_source,
            state.tocSourceLabel,
            chapters.count { !it.isGroup },
        )

        if (chapters.isNotEmpty() && !initializedCollapse) {
            initializedCollapse = true
            initCollapse(state)
            binding.chapterList.post {
                val row = adapter.rowOf(state.currentChapterIndex)
                if (row >= 0) binding.chapterList.scrollToPosition(row)
            }
        }

        adapter.submit(
            chapters = chapters,
            collapsed = collapsed,
            currentIndex = state.currentChapterIndex,
            currentPath = state.currentPath.map { it.index }.toSet(),
        )
        // adapter 内部会拷贝折叠集合，这里同步回外层，保证重进抽屉时保持用户的展开状态
        collapsed = (0 until chapters.size).filter { adapter.isCollapsed(it) }.toMutableSet()

        val task = state.tocTask
        binding.tocTaskRow.isVisible = task.running
        if (task.running) {
            binding.tocTaskProgress.isIndeterminate = task.total <= 0
            if (task.total > 0) binding.tocTaskProgress.progress = task.percent
            binding.tocTaskText.text = if (task.total > 0) {
                getString(R.string.reader_toc_progress, task.phase, task.done, task.total, task.percent)
            } else {
                task.phase
            }
        }

        binding.emptyHint.isVisible = chapters.isEmpty() && !state.chaptersLoading && !task.running
    }

    /** 默认只展开当前章节所在的路径，其余分组全部收起（16 本书铺开没法看） */
    private fun initCollapse(state: ReaderUiState) {
        val path = state.currentPath.map { it.index }.toSet()
        val result = mutableSetOf<Int>()
        state.chapters.filter { it.isGroup }.forEach { group ->
            // 顶层分组永远展开，否则用户连第一层都看不到
            if (group.parentIndex != -1 || group.index !in path) result += group.index
        }
        path.forEach { result -= it }
        collapsed = result
    }

    private fun showModeDialog() {
        val modes = arrayOf(
            TocMode.AUTO to getString(R.string.reader_toc_mode_auto),
            TocMode.OUTLINE_ONLY to getString(R.string.reader_toc_mode_outline),
            TocMode.OCR_REANCHOR to getString(R.string.reader_toc_mode_reanchor),
            TocMode.OCR_SCAN to getString(R.string.reader_toc_mode_scan),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reader_menu_toc_auto)
            .setItems(modes.map { it.second }.toTypedArray()) { _, which ->
                viewModel.generateToc(modes[which].first)
            }
            .setNegativeButton(R.string.reader_cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            peekHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
            skipCollapsed = false
        }
    }

    override fun onDestroyView() {
        binding.chapterList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ChapterSheetFragment"

        fun newInstance(book: BookRef): ChapterSheetFragment = ChapterSheetFragment().apply {
            arguments = Bundle().apply {
                putString(BaseReaderActivity.ARG_BOOK_ID, book.bookId)
                putString(BaseReaderActivity.ARG_BOOK_URI, book.uri)
                putString(BaseReaderActivity.ARG_BOOK_TITLE, book.title)
                putString(BaseReaderActivity.ARG_BOOK_LOCAL_PATH, book.localPath)
            }
        }
    }
}
