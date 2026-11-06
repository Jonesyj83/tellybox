package com.openstream.tv.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VodActivity : com.openstream.tv.ui.parental.ProtectedActivity() {
    private lateinit var categories: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private var itemsJob: Job? = null
    private var groupJob: Job? = null
    private var selectedKind = ContentKind.MOVIE
    private var selectedGroup: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24)) }
        val tabs = LinearLayout(this)
        for ((label, kind) in listOf("Movies" to ContentKind.MOVIE, "Series" to ContentKind.SERIES)) {
            tabs.addView(Button(this).apply { text = label; setOnClickListener { selectKind(kind) } })
        }
        root.addView(tabs)
        status = TextView(this)
        root.addView(status)
        val body = LinearLayout(this)
        categories = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(categories) }, LinearLayout.LayoutParams(dp(220), -1))
        grid = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@VodActivity, 4) }
        body.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        selectKind(savedInstanceState?.getString("kind")?.let(ContentKind::valueOf) ?: ContentKind.MOVIE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("kind", selectedKind.name)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun selectKind(kind: ContentKind) {
        selectedKind = kind
        selectedGroup = null
        showItems(null)
        groupJob?.cancel()
        groupJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.channels.observeGroups(kind).collect { groups ->
                    categories.removeAllViews()
                    fun category(label: String, id: Long?) {
                        categories.addView(Button(this@VodActivity).apply {
                            text = label
                            setOnClickListener { selectedGroup = id; showItems(id) }
                        })
                    }
                    category("All ${if (kind == ContentKind.MOVIE) "movies" else "series"}", null)
                    groups.forEach { category(it.name, it.id) }
                    if (selectedGroup != null && groups.none { it.id == selectedGroup }) {
                        selectedGroup = null
                        showItems(null)
                    }
                }
            }
        }
    }

    private fun showItems(group: Long?) {
        itemsJob?.cancel()
        itemsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (group == null) appContainer.channels.observeAll(selectedKind) else appContainer.channels.observeGroup(group)
                flow.collect { items ->
                    status.text = when {
                        items.isNotEmpty() -> "${items.size} titles"
                        group == null -> getString(R.string.vod_empty_all)
                        else -> "No titles in this category"
                    }
                    grid.adapter = Posters(items) { channel ->
                        startActivity(Intent(this@VodActivity, VodDetailActivity::class.java).putExtra("channel_id", channel.id))
                    }
                }
            }
        }
    }

    private class Posters(val items: List<ChannelEntity>, val open: (ChannelEntity) -> Unit) : RecyclerView.Adapter<Posters.Holder>() {
        class Holder(val box: LinearLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
                setPadding(8, 8, 8, 8); setBackgroundResource(R.drawable.bg_focusable)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val label = TextView(parent.context).apply { maxLines = 2; textSize = 18f; minHeight = 56 }
            box.addView(image, LinearLayout.LayoutParams(-1, (210 * parent.resources.displayMetrics.density).toInt()))
            box.addView(label)
            return Holder(box, image, label)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.label.text = item.name
            holder.image.load(item.logoUrl)
            holder.box.contentDescription = item.name
            holder.box.setOnClickListener { open(item) }
        }
    }
}
