package com.midwayder.pleos

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.midwayder.pleos.databinding.ItemRowBinding

/**
 * 목적지 후보와 경유지 결과를 같은 목록으로 보여 준다.
 * cost 가 있으면(경유지) 오른쪽에 크게 쓴다. 선택된 행은 배경·왼쪽 막대·굵은 글씨로 표시한다(색만으로 구분하지 않음).
 */
data class Row(val title: String, val detail: String, val rank: Int? = null, val selected: Boolean = false, val cost: String? = null)

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
        val accent = ContextCompat.getColor(context, R.color.accent)
        with(holder.binding) {
            rank.text = row.rank?.toString().orEmpty()
            rank.visibility = if (row.rank == null) View.INVISIBLE else View.VISIBLE
            rank.setTextColor(if (row.selected) accent else ContextCompat.getColor(context, R.color.text_secondary))
            title.text = row.title
            title.typeface = if (row.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            detail.text = row.detail
            detail.visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
            cost.text = row.cost.orEmpty()
            cost.visibility = if (row.cost.isNullOrBlank()) View.GONE else View.VISIBLE
            cost.setTextColor(if (row.selected) accent else ContextCompat.getColor(context, R.color.text))
            root.isSelected = row.selected
            root.setOnClickListener { onTap(holder.bindingAdapterPosition) }
        }
    }
}
