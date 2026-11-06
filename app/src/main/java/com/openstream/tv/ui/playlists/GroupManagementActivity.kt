package com.openstream.tv.ui.playlists

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelGroupEntity
import kotlinx.coroutines.launch

/** Ordering mode keeps focus on the moved row for repeated remote presses. */
class GroupManagementActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var groupId: Long? = null
    private var movingId: Long? = null
    private val playlistId get() = intent.getLongExtra("playlist_id", 0)
    private val db get() = appContainer.database

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        hint = TextView(this)
        root.addView(hint)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
        render()
    }

    private fun render(focusId: Long? = null) {
        lifecycleScope.launch {
            val groups = db.groups().getForPlaylist(playlistId).sortedWith(compareBy({ it.kind.name }, { it.sortOrder }, { it.id }))
            val selected = groups.find { it.id == groupId }
            val channels = selected?.let { group -> db.channels().getForPlaylist(playlistId).filter { it.groupId == group.id } }
            val tokens = appContainer.settings.get().channelNameTokens
            hint.text = if (movingId != null) "Move: press Up / Down. OK or Back finishes."
                else selected?.let { "${it.name} — select a channel to reorder" } ?: "Select a group to hide, show, or reorder"
            list.removeAllViews()
            if (selected != null) {
                list.addView(Button(this@GroupManagementActivity).apply {
                    setBackgroundResource(R.drawable.bg_focusable)
                    isAllCaps = false
                    text = "Back to groups"
                    setOnClickListener { groupId = null; movingId = null; render() }
                })
            }
            val ids = channels?.map { it.id } ?: groups.map { it.id }
            ids.forEachIndexed { index, id ->
                val group = if (channels == null) groups[index] else null
                val label = channels?.get(index)?.let { com.openstream.tv.data.settings.ChannelNames.display(it.name, tokens) } ?: "${group!!.name} (${group.kind.name.lowercase()})${if (group.hidden) " · Hidden" else ""}"
                val button = Button(this@GroupManagementActivity).apply {
                    setBackgroundResource(R.drawable.bg_focusable)
                    isAllCaps = false
                    text = label
                    setOnClickListener {
                        if (movingId != null) { movingId = null; render(id) }
                        else if (group != null) actions(group)
                        else { movingId = id; render(id) }
                    }
                    setOnKeyListener { _, key, event ->
                        if (movingId != id || key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
                        else {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                val next = index + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                if (next in ids.indices && (channels != null || groups[next].kind == group!!.kind)) {
                                    val ordered = ids.toMutableList().apply { add(next, removeAt(index)) }
                                    lifecycleScope.launch {
                                        db.withTransaction {
                                            ordered.forEachIndexed { position, rowId ->
                                                if (channels == null) db.groups().setOrder(rowId, position)
                                                else db.channels().setOrder(rowId, position)
                                            }
                                        }
                                        render(id)
                                    }
                                }
                            }
                            true
                        }
                    }
                }
                list.addView(button)
                if (id == focusId) button.requestFocusFromTouch()
            }
        }
    }

    private fun actions(group: ChannelGroupEntity) {
        AlertDialog.Builder(this).setTitle(group.name)
            .setItems(arrayOf(if (group.hidden) "Show group" else "Hide group", "Move group", "Reorder channels")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch { db.groups().setHidden(group.id, !group.hidden); render(group.id) }
                    1 -> { movingId = group.id; render(group.id) }
                    2 -> { groupId = group.id; render() }
                }
            }.show()
    }

    @Deprecated("Uses remote back navigation")
    override fun onBackPressed() {
        when {
            movingId != null -> { val id = movingId; movingId = null; render(id) }
            groupId != null -> { groupId = null; render() }
            else -> super.onBackPressed()
        }
    }
}
