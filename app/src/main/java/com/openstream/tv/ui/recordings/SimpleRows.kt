package com.openstream.tv.ui.recordings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.openstream.tv.databinding.ItemChoiceBinding

data class SimpleRow(val id: Long, val title: CharSequence, val detail: CharSequence, val highlight: Boolean = false)

/** Two-line focusable rows for D-pad lists. */
class SimpleRowAdapter(private val onClick: (SimpleRow) -> Unit) : RecyclerView.Adapter<SimpleRowAdapter.Holder>() {
    class Holder(val b: ItemChoiceBinding) : RecyclerView.ViewHolder(b.root)

    var rows: List<SimpleRow> = emptyList()
        private set

    init { setHasStableIds(true) }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SimpleRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int) = rows[position].id
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.b.choiceTitle.text = row.title
        holder.b.choiceDesc.text = row.detail
        holder.b.choiceDesc.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
        holder.b.root.isActivated = row.highlight
        holder.b.root.setOnClickListener { onClick(row) }
    }
}
