package com.novelreader.pdf.ui.dict

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelreader.pdf.AppContainer
import com.novelreader.pdf.data.dict.DictCardFormatter
import com.novelreader.pdf.data.dict.DictionaryNormalizer
import com.novelreader.pdf.data.local.entity.DictionaryEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DictUiState(
    val query: String = "",
    val importing: Boolean = false,
    val progress: String? = null,
    val result: String = "",
    val suggestions: List<String> = emptyList(),
    val totalEntries: Int = 0,
)

class DictionaryViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.dictionaryRepository

    val dictionaries: StateFlow<List<DictionaryEntity>> = repo.observeDictionaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(DictUiState())
    val ui: StateFlow<DictUiState> = _ui.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch { refreshTotal() }
    }

    /** 导入一本或多本词书（多选时逐本串行，与批量导入 PDF 的策略一致） */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _ui.update { it.copy(importing = true, progress = "准备导入…", result = "") }
            val ok = mutableListOf<String>()
            val failed = mutableListOf<String>()
            uris.forEach { uri ->
                runCatching {
                    repo.import(uri) { stage, done ->
                        _ui.update {
                            it.copy(
                                progress = if (done > 0) "$stage（$done 条）" else stage,
                            )
                        }
                    }
                }.onSuccess { ok += "${it.name}（${it.entryCount} 条）" }
                    .onFailure { failed += "${uri.lastPathSegment?.substringAfterLast('/') ?: uri}：${it.message}" }
            }
            refreshTotal()
            val summary = buildString {
                if (ok.isNotEmpty()) append("已导入：\n").append(ok.joinToString("\n") { "· $it" })
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("失败：\n").append(failed.joinToString("\n") { "· $it" })
                }
            }
            _ui.update { it.copy(importing = false, progress = null, result = summary) }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _ui.update { it.copy(importing = false, progress = null) }
    }

    fun remove(dictId: String) {
        viewModelScope.launch {
            repo.remove(dictId)
            refreshTotal()
        }
    }

    fun setEnabled(dictId: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(dictId, enabled) }
    }

    /** 查词。命中多条时依次列出（同一本词书里一词多义也算多条） */
    fun query(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(query = trimmed, result = "查询中…", suggestions = emptyList()) }
            val lookup = repo.lookup(trimmed)
            val body = when {
                lookup.found -> lookup.hits.joinToString("\n\n————\n\n") { hit ->
                    DictCardFormatter.format(
                        word = hit.entry.word,
                        phonetic = hit.entry.phonetic,
                        prettyPos = DictionaryNormalizer.prettyPos(hit.entry.pos),
                        translation = hit.entry.translation,
                        definition = hit.entry.definition,
                        exchange = hit.entry.exchange,
                        collins = hit.entry.collins,
                        oxford = hit.entry.oxford,
                        tag = hit.entry.tag,
                        bnc = hit.entry.bnc,
                        frq = hit.entry.frq,
                        matchedForm = hit.matchedForm,
                    )
                }

                lookup.suggestions.isNotEmpty() ->
                    "没有「$trimmed」这个词。你是不是想找：\n" +
                        lookup.suggestions.joinToString("\n") { "· $it" }

                lookup.totalEntries == 0 -> "还没有导入任何词书。点右下角「导入词书」，选你自己准备的词典文件（CSV / TSV / JSON）。"

                else -> "没有查到「$trimmed」。"
            }
            _ui.update { it.copy(result = body, suggestions = lookup.suggestions) }
        }
    }

    private suspend fun refreshTotal() {
        val total = repo.totalEntries()
        _ui.update { it.copy(totalEntries = total) }
    }
}

class DictionaryViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DictionaryViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return DictionaryViewModel(container) as T
    }
}
