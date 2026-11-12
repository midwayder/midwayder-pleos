package com.midwayder.pleos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.midwayder.pleos.databinding.ItemRowBinding

/** 목적지 후보와 경유지 결과를 같은 목록으로 보여 준다. rank 가 있으면 순위 배지를 그린다. */
data class Row(val title: String, val detail: String, val rank: Int? = null, val selected: Boolean = false)

class RowAdapter(private val onTap: (Int) -> Unit) : RecyclerView.Adapter<RowAdapter.Holder>() {
    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        with(holder.binding) {
            rank.text = row.rank?.toString() ?: "·"
            rank.visibility = if (row.rank == null) View.INVISIBLE else View.VISIBLE
            rank.isSelected = row.selected
            rank.setTextColor(ContextCompat.getColor(context, if (row.selected) R.color.accent_strong else R.color.on_accent))
            title.text = row.title
            title.setTextColor(ContextCompat.getColor(context, if (row.selected) R.color.on_accent else R.color.text))
            detail.text = row.detail
            detail.visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
            detail.setTextColor(ContextCompat.getColor(context, if (row.selected) R.color.on_accent else R.color.text_secondary))
            root.isSelected = row.selected
            root.setOnClickListener { onTap(holder.bindingAdapterPosition) }
        }
    }
}
