package com.openstream.tv.ui.channels

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.openstream.tv.R
import com.openstream.tv.databinding.ItemChannelBinding
import com.openstream.tv.databinding.ItemGroupBinding

class GroupAdapter(
    private val onFocused: (GroupRow) -> Unit,
    private val onClick: (GroupRow) -> Unit,
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
    }

    private object Diff : DiffUtil.ItemCallback<GroupRow>() {
        override fun areItemsTheSame(a: GroupRow, b: GroupRow) = a.selection == b.selection
        override fun areContentsTheSame(a: GroupRow, b: GroupRow) = a == b
    }

    companion object {
        private const val PAYLOAD_ACTIVE = "active"
    }
}

class ChannelAdapter(
    private val onClick: (ChannelRow, Int) -> Unit,
    private val onMenu: (ChannelRow) -> Unit,
) : ListAdapter<ChannelRow, ChannelAdapter.Holder>(Diff) {

    class Holder(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val c = row.channel
        with(holder.b) {
            number.text = c.number?.toString().orEmpty()
            name.text = c.name
            nowPlaying.text = row.nowPlaying
            nowPlaying.visibility = if (row.nowPlaying.isNullOrEmpty()) View.GONE else View.VISIBLE
            favorite.visibility = if (row.favorite) View.VISIBLE else View.GONE
            logoFallback.text = initials(c.name)
            logo.setImageDrawable(null)
            if (!c.logoUrl.isNullOrBlank()) {
                logo.load(c.logoUrl) {
                    crossfade(false)
                    listener(onError = { _, _ -> logo.setImageDrawable(null) })
                }
            }
            root.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onClick(getItem(it), it) }
            }
            root.setOnLongClickListener {
                onMenu(row)
                true
            }
            root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                    onMenu(row)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun initials(name: String): String =
        name.split(' ', '-', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
            .take(2).joinToString("") { it.take(1).uppercase() }

    private object Diff : DiffUtil.ItemCallback<ChannelRow>() {
        override fun areItemsTheSame(a: ChannelRow, b: ChannelRow) = a.channel.id == b.channel.id
        override fun areContentsTheSame(a: ChannelRow, b: ChannelRow) = a == b
    }
}
