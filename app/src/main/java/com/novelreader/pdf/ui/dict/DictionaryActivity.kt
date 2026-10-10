package com.novelreader.pdf.ui.dict

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelreader.pdf.NovelReaderApp
import com.novelreader.pdf.R
import com.novelreader.pdf.databinding.ActivityDictionaryBinding
import kotlinx.coroutines.launch

/**
 * 词书与查词。
 *
 * App 不附带任何词典数据：这里导入的是**用户自己准备的文件**（CSV / TSV / JSON），
 * 解析进本地数据库后即可查询。原文件不会被复制或修改。
 */
class DictionaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDictionaryBinding

    private val viewModel: DictionaryViewModel by viewModels {
        DictionaryViewModelFactory((application as NovelReaderApp).container)
    }

    private lateinit var adapter: DictionaryAdapter

    private val pickDict = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        viewModel.import(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dictToolbar.setNavigationOnClickListener { finish() }

        adapter = DictionaryAdapter(
            onToggle = { dict, enabled -> viewModel.setEnabled(dict.dictId, enabled) },
            onDelete = { dict -> confirmDelete(dict.name, dict.dictId) },
        )
        binding.dictList.layoutManager = LinearLayoutManager(this)
        binding.dictList.adapter = adapter

        binding.btnDictQuery.setOnClickListener { submit() }
        binding.dictQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }

        binding.fabImportDict.setOnClickListener {
            // 词典文件什么 MIME 都可能被报出来，干脆放开
            runCatching { pickDict.launch(arrayOf("*/*")) }
                .onFailure { binding.dictProgress.text = getString(R.string.dict_no_picker) }
        }

        observe()
    }

    private fun submit() {
        val text = binding.dictQuery.text?.toString().orEmpty()
        viewModel.query(text)
    }

    private fun confirmDelete(name: String, dictId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setMessage(R.string.dict_delete_confirm)
            .setNegativeButton(R.string.reader_cancel, null)
            .setPositiveButton(R.string.dict_delete) { _, _ -> viewModel.remove(dictId) }
            .show()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.dictionaries.collect { adapter.submitList(it) }
                }
                launch {
                    viewModel.ui.collect { ui ->
                        binding.dictResult.text = ui.result
                        binding.dictProgress.isVisible = ui.progress != null
                        binding.dictProgress.text = ui.progress.orEmpty()
                        binding.btnDictQuery.isEnabled = !ui.importing
                        binding.dictQuery.setText(ui.query.takeIf { it.isNotEmpty() } ?: binding.dictQuery.text?.toString().orEmpty())
                    }
                }
            }
        }
    }
}
