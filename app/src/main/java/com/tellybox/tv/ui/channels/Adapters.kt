package com.tellybox.tv.ui.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tellybox.tv.R
import com.tellybox.tv.databinding.ItemGroupBinding

class GroupAdapter(
    private val onFocused: (GroupRow) -> Unit,
    private val onClick: (GroupRow) -> Unit,
    /** Press and hold, or Menu, on a row that has something worth offering. */
    private val onLongClick: (GroupRow) -> Unit = {},
) : ListAdapter<GroupRow, GroupAdapter.Holder>(Diff) {

    var activeSelection: GroupSelection? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTIVE)
        }

    class Holder(val b: ItemGroupBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIVE)) {
            holder.b.root.isActivated = getItem(position).selection == activeSelection
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val ctx = holder.b.root.context
        holder.b.name.text = when (val s = row.selection) {
            GroupSelection.Favorites -> ctx.getString(R.string.group_favorites)
            GroupSelection.Recent -> ctx.getString(R.string.group_recent)
            GroupSelection.All -> ctx.getString(R.string.group_all)
            is GroupSelection.Search -> ctx.getString(R.string.group_search, s.query)
            is GroupSelection.Group -> row.name
        }
        holder.b.count.text = row.count?.toString().orEmpty()
        holder.b.root.isActivated = row.selection == activeSelection
        holder.b.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onFocused(getItem(it)) }
        }
        holder.b.root.setOnClickListener { onClick(row) }
        holder.b.root.setOnLongClickListener { onLongClick(row); true }
    }

    private object Diff : DiffUtil.ItemCallback<GroupRow>() {
        override fun areItemsTheSame(a: GroupRow, b: GroupRow) = a.selection == b.selection
        override fun areContentsTheSame(a: GroupRow, b: GroupRow) = a == b
    }

    companion object {
        private const val PAYLOAD_ACTIVE = "active"
    }
}
