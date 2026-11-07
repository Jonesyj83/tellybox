package com.opentelly.tv.ui.chrome

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.opentelly.tv.databinding.ItemGroupBinding

/** One entry in a section's left column. [key] identifies it; [count] shows on the right. */
data class Category(val key: String, val name: String, val count: Int? = null)

/**
 * The left-hand column of every section, drawn with exactly the same rows as the live
 * categories (item_group): focus shows the accent bar, the chosen one stays shaded.
 */
class CategoryAdapter(
    private val onFocused: (Category) -> Unit = {},
    private val onClick: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    class Holder(val b: ItemGroupBinding) : RecyclerView.ViewHolder(b.root)

    var items: List<Category> = emptyList()
        private set

    var activeKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTIVE)
        }

    init { setHasStableIds(true) }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Category>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    fun indexOf(key: String?) = items.indexOfFirst { it.key == key }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIVE)) holder.b.root.isActivated = items[position].key == activeKey
        else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.b.name.text = item.name
        holder.b.count.text = item.count?.toString().orEmpty()
        holder.b.root.isActivated = item.key == activeKey
        holder.b.root.setOnFocusChangeListener { _, has ->
            if (has) holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onFocused(items[it]) }
        }
        holder.b.root.setOnClickListener { onClick(item) }
    }

    companion object {
        private const val PAYLOAD_ACTIVE = "active"
    }
}
