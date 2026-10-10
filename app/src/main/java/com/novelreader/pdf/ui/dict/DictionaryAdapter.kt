package com.novelreader.pdf.ui.dict

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelreader.pdf.data.local.entity.DictionaryEntity
import com.novelreader.pdf.databinding.ItemDictionaryBinding

/**
 * 已导入词书的列表项：名称 + 条目数 + 启用开关 + 删除。
 *
 * 开关的意义：装了英汉和英英两本时可以只启用其中一本，查询只走启用的。
 */
class DictionaryAdapter(
    private val onToggle: (DictionaryEntity, Boolean) -> Unit,
    private val onDelete: (DictionaryEntity) -> Unit,
) : ListAdapter<DictionaryEntity, DictionaryAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemDictionaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDictionaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.tvDictName.text = item.name
        holder.binding.tvDictMeta.text = buildString {
            append(item.entryCount).append(" 条 · ").append(item.format.uppercase())
        }
        // 先解绑再设值：RecyclerView 复用时会触发监听，导致把别人的状态写回去
        holder.binding.swDictEnabled.setOnCheckedChangeListener(null)
        holder.binding.swDictEnabled.isChecked = item.enabled
        holder.binding.swDictEnabled.setOnCheckedChangeListener { _, checked ->
            onToggle(item, checked)
        }
        holder.binding.btnDictDelete.setOnClickListener { onDelete(item) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DictionaryEntity>() {
            override fun areItemsTheSame(a: DictionaryEntity, b: DictionaryEntity) = a.dictId == b.dictId
            override fun areContentsTheSame(a: DictionaryEntity, b: DictionaryEntity) = a == b
        }
    }
}
