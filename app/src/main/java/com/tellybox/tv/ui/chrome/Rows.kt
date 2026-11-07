package com.tellybox.tv.ui.chrome

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tellybox.tv.databinding.ItemRowBinding

/**
 * A row in the standard list style (item_row): [title] on the left, [value] on the right,
 * an optional [detail] line underneath.
 */
data class SimpleRow(
    val id: Long,
    val title: CharSequence,
    val value: CharSequence = "",
    val detail: CharSequence = "",
    val highlight: Boolean = false,
    val dimmed: Boolean = false,
)

/** Fills an item_row binding; shared by every list so they cannot drift apart. */
fun ItemRowBinding.show(row: SimpleRow) {
    choiceTitle.text = row.title
    choiceDesc.text = row.value
    choiceDesc.visibility = if (row.value.isEmpty()) View.GONE else View.VISIBLE
    description.text = row.detail
    description.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
    root.isActivated = row.highlight
    root.alpha = if (row.dimmed) 0.55f else 1f
}

/** Focusable rows for D-pad lists. */
class SimpleRowAdapter(
    private val onLongClick: ((SimpleRow) -> Unit)? = null,
    private val onClick: (SimpleRow) -> Unit,
) : RecyclerView.Adapter<SimpleRowAdapter.Holder>() {
    class Holder(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

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
        Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.b.show(row)
        holder.b.root.setOnClickListener { onClick(row) }
        holder.b.root.setOnLongClickListener(onLongClick?.let { { _: View -> it(row); true } })
    }
}
