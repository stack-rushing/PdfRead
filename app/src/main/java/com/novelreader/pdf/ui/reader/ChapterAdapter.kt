package com.novelreader.pdf.ui.reader

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.novelreader.pdf.R
import com.novelreader.pdf.data.model.Chapter
import com.novelreader.pdf.databinding.ItemChapterBinding

/**
 * 层级章节适配器：把树按"折叠状态"摊平成可见行。
 *
 * 不用 ExpandableListView / 树形控件的原因：章节树只有 2~3 层，
 * 自己算一次可见行（O(n)）比引入带状态的树控件简单得多，也更好控制缩进与当前章节高亮。
 */
class ChapterAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    private data class Row(
        val chapter: Chapter,
        val depth: Int,
        val hasChildren: Boolean,
        val expanded: Boolean,
        val isCurrent: Boolean,
        val inCurrentPath: Boolean,
    )

    class Holder(val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root)

    private var chapters: List<Chapter> = emptyList()
    private var collapsed: MutableSet<Int> = mutableSetOf()
    private var currentIndex: Int = -1
    private var currentPath: Set<Int> = emptySet()
    private var rows: List<Row> = emptyList()

    fun submit(
        chapters: List<Chapter>,
        collapsed: Set<Int>,
        currentIndex: Int,
        currentPath: Set<Int>,
    ) {
        this.chapters = chapters
        this.collapsed = collapsed.toMutableSet()
        this.currentIndex = currentIndex
        this.currentPath = currentPath
        rebuild()
    }

    fun rowOf(chapterIndex: Int): Int = rows.indexOfFirst { it.chapter.index == chapterIndex }

    fun isCollapsed(index: Int): Boolean = collapsed.contains(index)

    private fun toggle(index: Int) {
        if (!collapsed.remove(index)) collapsed += index
        rebuild()
    }

    private fun rebuild() {
        rows = flatten()
        notifyDataSetChanged()
    }

    private fun flatten(): List<Row> {
        if (chapters.isEmpty()) return emptyList()
        val n = chapters.size
        val hasChild = BooleanArray(n)
        chapters.forEach { c -> if (c.parentIndex in 0 until n) hasChild[c.parentIndex] = true }

        val hidden = BooleanArray(n)
        val depth = IntArray(n)
        val out = ArrayList<Row>(n)
        chapters.forEachIndexed { i, c ->
            val parent = c.parentIndex
            if (parent in 0 until n) {
                if (hidden[parent]) {
                    hidden[i] = true
                    return@forEachIndexed
                }
                depth[i] = depth[parent] + 1
            }
            val expanded = i !in collapsed
            if (hasChild[i] && !expanded) hidden[i] = true
            out += Row(
                chapter = c,
                depth = depth[i],
                hasChildren = hasChild[i],
                expanded = expanded,
                isCurrent = i == currentIndex,
                inCurrentPath = i in currentPath,
            )
        }
        return out
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val chapter = row.chapter
        val res = holder.itemView.context.resources
        val density = res.displayMetrics.density

        holder.binding.tvIndex.text =
            if (chapter.isGroup) "" else (chapter.index + 1).toString().padStart(2, '0')
        holder.binding.tvChapterTitle.text = chapter.title
        holder.binding.tvChapterTitle.setTypeface(
            null,
            if (chapter.isGroup) Typeface.BOLD else Typeface.NORMAL,
        )
        holder.binding.tvChapterTitleEn.isVisible = !chapter.titleEn.isNullOrBlank()
        holder.binding.tvChapterTitleEn.text = chapter.titleEn.orEmpty()
        holder.binding.tvPageRange.text = when {
            chapter.isGroup && chapter.isResolved ->
                res.getString(R.string.chapter_group_pages, chapter.startPage + 1, chapter.endPage + 1)

            chapter.isResolved ->
                res.getString(R.string.chapter_page_range, chapter.startPage + 1, chapter.endPage + 1)

            else -> res.getString(R.string.chapter_page_single, chapter.startPage + 1)
        }

        holder.binding.ivExpand.isVisible = row.hasChildren
        holder.binding.ivExpand.rotation = if (row.expanded) 90f else 0f
        holder.binding.ivExpand.setOnClickListener { toggle(row.chapter.index) }
        // 整行点击也能折叠分组，避免用户必须精确点到小箭头上
        holder.itemView.setOnClickListener {
            if (row.hasChildren) toggle(row.chapter.index) else onClick(row.chapter.index)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(row.chapter.index)
            true
        }

        val indent = (4 * density).toInt() + row.depth * (14 * density).toInt()
        holder.itemView.setPaddingRelative(
            indent,
            holder.itemView.paddingTop,
            holder.itemView.paddingEnd,
            holder.itemView.paddingBottom,
        )

        holder.itemView.isActivated = row.isCurrent
        // 当前章节路径上的节点保持满不透明，其余略微压暗，一眼能看出"我在哪"
        holder.itemView.alpha =
            if (row.isCurrent || currentPath.isEmpty() || row.inCurrentPath) 1f else 0.62f
    }
}
