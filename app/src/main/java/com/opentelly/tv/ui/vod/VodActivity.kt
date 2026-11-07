package com.opentelly.tv.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.opentelly.tv.R
import com.opentelly.tv.appContainer
import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ContentKind
import com.opentelly.tv.databinding.ItemPosterBinding
import com.opentelly.tv.ui.chrome.Category
import com.opentelly.tv.ui.chrome.Section
import com.opentelly.tv.ui.chrome.SectionActivity
import com.opentelly.tv.ui.chrome.TwoColumn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Movies and series: categories on the left in the live TV row style, posters on the right. */
class VodActivity : SectionActivity() {

    override val section = Section.MOVIES
    private lateinit var view: TwoColumn
    private lateinit var grid: GridLayoutManager
    private val posters = Posters { channel ->
        startActivity(Intent(this, VodDetailActivity::class.java).putExtra("channel_id", channel.id))
    }
    private val kindButtons = HashMap<ContentKind, Button>()
    private var itemsJob: Job? = null
    private var groupJob: Job? = null
    private var selectedKind = ContentKind.MOVIE
    private var selectedGroup: Long? = null
    private var landed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            selectedGroup = c.key.toLongOrNull()
            showItems()
        }
        columns = view
        setSectionContent(view.b.root)
        grid = GridLayoutManager(this, 5)
        view.b.list.layoutManager = grid
        view.b.list.adapter = posters
        view.b.list.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val card = uiScale.posterWidth() * resources.displayMetrics.density
            val span = ((r - l - v.paddingLeft - v.paddingRight) / card).toInt().coerceAtLeast(1)
            if (grid.spanCount != span) v.post { grid.spanCount = span }
        }
        for ((label, kind) in listOf(R.string.vod_movies to ContentKind.MOVIE, R.string.vod_series to ContentKind.SERIES)) {
            val button = Button(this, null, 0, R.style.Button_TV_Small).apply {
                setText(label)
                setOnClickListener { if (kind != selectedKind) selectKind(kind) }
            }
            view.b.actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            })
            kindButtons[kind] = button
        }
        selectedGroup = savedInstanceState?.getLong(STATE_GROUP, -1)?.takeIf { it >= 0 }
        selectKind(savedInstanceState?.getString(STATE_KIND)?.let(ContentKind::valueOf) ?: ContentKind.MOVIE, keepGroup = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_KIND, selectedKind.name)
        outState.putLong(STATE_GROUP, selectedGroup ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    private fun kindLabel() = getString(if (selectedKind == ContentKind.MOVIE) R.string.vod_movies else R.string.vod_series)

    private fun selectKind(kind: ContentKind, keepGroup: Boolean = false) {
        selectedKind = kind
        if (!keepGroup) selectedGroup = null
        kindButtons.forEach { (k, b) -> b.isActivated = k == kind }
        view.b.title.text = kindLabel()
        view.categories.activeKey = selectedGroup?.toString() ?: KEY_ALL
        showItems()
        groupJob?.cancel()
        groupJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.channels.observeGroups(kind).collect { groups ->
                    val all = getString(if (kind == ContentKind.MOVIE) R.string.vod_all_movies else R.string.vod_all_series)
                    view.categories.submit(
                        listOf(Category(KEY_ALL, all, groups.sumOf { it.channelCount })) +
                            groups.map { Category(it.id.toString(), it.name, it.channelCount) },
                    )
                    if (selectedGroup != null && groups.none { it.id == selectedGroup }) {
                        selectedGroup = null
                        view.categories.activeKey = KEY_ALL
                        showItems()
                    }
                }
            }
        }
    }

    private fun showItems() {
        val group = selectedGroup
        itemsJob?.cancel()
        itemsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (group == null) appContainer.channels.observeAll(selectedKind) else appContainer.channels.observeGroup(group)
                flow.collect { items ->
                    val firstShow = posters.currentList.isEmpty()
                    posters.submitList(items) { if (firstShow) view.b.list.scrollToPosition(0) }
                    view.b.subtitle.text = if (items.isEmpty()) "" else resources.getQuantityString(R.plurals.vod_titles, items.size, items.size)
                    view.b.empty.text = getString(if (group == null) R.string.vod_empty_all else R.string.vod_empty_group)
                    view.b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    if (!landed) {
                        landed = true
                        view.b.list.post { view.focusCategories() }
                    }
                }
            }
        }
    }

    private class Posters(val open: (ChannelEntity) -> Unit) : ListAdapter<ChannelEntity, Posters.Holder>(Diff) {
        class Holder(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val b = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.posterFrame.ratio = 1.5f
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val title = VodTitle.parse(item.name)
            holder.b.title.text = title.title
            holder.b.year.text = title.year.orEmpty()
            holder.b.year.visibility = if (title.year == null) View.GONE else View.VISIBLE
            holder.b.fallback.text = title.title
            // The title sits under the image, so a missing poster still says what it is.
            holder.b.poster.load(item.logoUrl)
            holder.b.root.contentDescription = item.name
            holder.b.root.setOnClickListener { open(item) }
        }

        private object Diff : DiffUtil.ItemCallback<ChannelEntity>() {
            override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity) = a.id == b.id
            override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity) = a == b
        }
    }

    companion object {
        private const val KEY_ALL = "all"
        private const val STATE_KIND = "kind"
        private const val STATE_GROUP = "group"
    }
}
