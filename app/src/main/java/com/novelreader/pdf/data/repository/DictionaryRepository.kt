package com.novelreader.pdf.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.novelreader.pdf.data.dict.DictionaryFileParser
import com.novelreader.pdf.data.dict.DictionaryNormalizer
import com.novelreader.pdf.data.dict.ParsedEntry
import com.novelreader.pdf.data.local.AppDatabase
import com.novelreader.pdf.data.local.entity.DictEntryEntity
import com.novelreader.pdf.data.local.entity.DictFormEntity
import com.novelreader.pdf.data.local.entity.DictionaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 一次命中：条目 + 它是通过哪个变形命中的（null 表示直接命中） */
data class DictHit(val entry: DictEntryEntity, val matchedForm: String? = null)

/** 一次查询的完整结果 */
data class DictLookup(
    val query: String,
    val normalized: String,
    val hits: List<DictHit> = emptyList(),
    /** 没查到时给的近似词（OCR 误识别的兜底） */
    val suggestions: List<String> = emptyList(),
    val totalEntries: Int = 0,
) {
    val found: Boolean get() = hits.isNotEmpty()
}

/**
 * 词书仓库：导入用户自己的词典文件，并提供查询。
 *
 * 三条硬性约定：
 * 1. **App 不附带任何词典数据**——词书来自用户自己选的文件；
 * 2. **不复制、不修改用户的文件**：只从 `content://` 流式读一遍，解析结果进本地数据库，
 *    之后查询完全不依赖原文件（原文件删了也能查）；
 * 3. **同名文件重新导入 = 覆盖更新**，不会在库里留两份。
 */
class DictionaryRepository(context: Context, db: AppDatabase) {

    private val appContext = context.applicationContext
    private val dao = db.dictDao()
    private val parser = DictionaryFileParser()

    fun observeDictionaries(): Flow<List<DictionaryEntity>> = dao.observeAll()

    suspend fun totalEntries(): Int = withContext(Dispatchers.IO) { dao.totalEntries() }

    suspend fun setEnabled(dictId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setEnabled(dictId, enabled)
    }

    suspend fun remove(dictId: String) = withContext(Dispatchers.IO) { dao.remove(dictId) }

    /**
     * 导入一本词书（流式解析 + 分批入库）。
     *
     * `onProgress` 会被高频调用（每批一次），调用方自己做节流即可。
     */
    suspend fun import(
        uri: Uri,
        onProgress: (stage: String, done: Int) -> Unit = { _, _ -> },
    ): DictionaryEntity = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "未命名词书"
        // 同名即同一本：重新导入时整本替换，不留重复
        val dictId = md5(displayName)

        val raw = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取文件：$displayName")

        raw.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { reader ->
                val firstLine = reader.readLine().orEmpty()
                val format = parser.detectFormat(displayName, firstLine)
                val stream = parser.Stream(reader, format, pushedBackLine = firstLine)

                dao.remove(dictId)
                onProgress("正在解析 $displayName", 0)

                var total = 0
                while (true) {
                    // 批与批之间是挂起点：这里取消能立刻生效，也不会把半个词库留在库里
                    coroutineContext.ensureActive()
                    val batch = stream.nextBatch(BATCH)
                    if (batch.isEmpty()) break
                    total += insertBatch(dictId, batch)
                    onProgress("正在导入 $displayName", total)
                }

                if (total == 0) {
                    dao.remove(dictId)
                    throw IllegalStateException("没有解析出任何词条，检查文件格式（支持 CSV / TSV / JSON）")
                }

                val entity = DictionaryEntity(
                    dictId = dictId,
                    name = displayName.substringBeforeLast('.').ifBlank { displayName },
                    format = format.name.lowercase(),
                    sourceName = displayName,
                    entryCount = total,
                    addedAt = System.currentTimeMillis(),
                )
                dao.insertDict(entity)
                onProgress("导入完成", total)
                entity
            }
        }
    }

    /** 查询：精确 → 词形还原 → 模糊兜底 */
    suspend fun lookup(query: String): DictLookup = withContext(Dispatchers.IO) {
        val normalized = DictionaryNormalizer.normalize(query)
        if (normalized.isEmpty()) return@withContext DictLookup(query, normalized)

        val exact = dao.lookup(normalized)
        if (exact.isNotEmpty()) {
            return@withContext DictLookup(
                query = query,
                normalized = normalized,
                hits = exact.map { DictHit(it) },
                totalEntries = dao.totalEntries(),
            )
        }

        val viaForm = dao.lookupForm(normalized)
        if (viaForm.isNotEmpty()) {
            return@withContext DictLookup(
                query = query,
                normalized = normalized,
                hits = viaForm.map { DictHit(it, matchedForm = normalized) },
                totalEntries = dao.totalEntries(),
            )
        }

        DictLookup(
            query = query,
            normalized = normalized,
            suggestions = fuzzy(normalized),
            totalEntries = dao.totalEntries(),
        )
    }

    suspend fun suggest(prefix: String, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val key = DictionaryNormalizer.normalize(prefix)
        if (key.isEmpty()) emptyList() else dao.suggest(key, limit)
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun insertBatch(dictId: String, batch: List<ParsedEntry>): Int {
        val rows = batch.mapNotNull { e ->
            val normalized = DictionaryNormalizer.normalize(e.word)
            if (normalized.isEmpty()) return@mapNotNull null
            DictEntryEntity(
                dictId = dictId,
                normalized = normalized,
                word = e.word,
                phonetic = e.phonetic,
                translation = e.translation,
                definition = e.definition,
                pos = e.pos,
                exchange = e.exchange,
                collins = e.collins,
                oxford = e.oxford,
                tag = e.tag,
                bnc = e.bnc,
                frq = e.frq,
            )
        }
        if (rows.isEmpty()) return 0

        val ids = dao.insertEntries(rows)
        val forms = ArrayList<DictFormEntity>()
        rows.forEachIndexed { i, row ->
            val entryId = ids.getOrNull(i) ?: return@forEachIndexed
            if (entryId <= 0L) return@forEachIndexed
            DictionaryNormalizer.formsOf(row.exchange).forEach { form ->
                // 自己映射自己没有意义（running → running）
                if (form != row.normalized) forms += DictFormEntity(dictId, form, entryId)
            }
        }
        if (forms.isNotEmpty()) dao.insertForms(forms)
        return rows.size
    }

    /** OCR 误识别的兜底：先按前缀取候选，再按编辑距离 ≤1 筛 */
    private suspend fun fuzzy(normalized: String, limit: Int = 6): List<String> {
        if (normalized.length < 3) return emptyList()
        val prefixes = linkedSetOf(normalized.take(2), normalized.dropLast(1))
        val candidates = LinkedHashSet<String>()
        prefixes.forEach { p -> if (p.length >= 2) candidates += dao.suggest(p, 80) }
        return candidates
            .map { it to DictionaryNormalizer.normalize(it) }
            .filter { (_, norm) -> kotlin.math.abs(norm.length - normalized.length) <= 1 }
            .map { (word, norm) -> Triple(word, norm, DictionaryNormalizer.levenshtein(norm, normalized, 1)) }
            .filter { it.third <= 1 }
            .sortedWith(compareBy({ it.third }, { it.second.length }))
            .map { it.first }
            .distinct()
            .take(limit)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** 每批入库条数：太大则进度不动、太小则事务开销高 */
        const val BATCH = 2000
    }
}
