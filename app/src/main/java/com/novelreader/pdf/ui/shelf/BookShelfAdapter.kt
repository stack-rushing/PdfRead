package com.novelreader.pdf.ui.shelf

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelreader.pdf.R
import com.novelreader.pdf.data.model.BookSummary
import com.novelreader.pdf.data.model.TocSource
import com.novelreader.pdf.databinding.ItemBookBinding

/**
 * 书架列表。用 ListAdapter + DiffUtil：
 * Room 每次发射都是新 List 实例，不做 diff 会导致整表重绑、滚动位置抖动。
 */
class BookShelfAdapter(
    private val onOpen: (BookSummary) -> Unit,
    private val onDelete: (BookSummary) -> Unit,
    private val onDeleteWithFile: (BookSummary) -> Unit,
) : ListAdapter<BookSummary, BookShelfAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.binding.tvTitle.text = item.title
        holder.binding.tvProgress.text = buildString {
            if (item.totalPages > 0) {
                append(
                    context.getString(
                        R.string.shelf_page_format,
                        item.displayPage,
                        item.totalPages,
                        (item.percent * 100).toInt(),
                    ),
                )
            } else {
                append(context.getString(R.string.shelf_not_opened))
            }
            // 把目录来源也写在书架上：一眼能看出哪本书还没生成目录
            append(" · ")
            append(TocSource.label(item.tocSource))
        }
        holder.binding.progressBar.isVisible = item.totalPages > 0
        holder.binding.progressBar.setProgressCompat((item.percent * 100).toInt(), false)

        holder.binding.bookCard.setOnClickListener { onOpen(item) }
        holder.binding.bookCard.setOnLongClickListener {
            onDeleteWithFile(item)
            true
        }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BookSummary>() {
            override fun areItemsTheSame(oldItem: BookSummary, newItem: BookSummary): Boolean =
                oldItem.bookId == newItem.bookId

            override fun areContentsTheSame(oldItem: BookSummary, newItem: BookSummary): Boolean =
                oldItem == newItem
        }
    }
}
