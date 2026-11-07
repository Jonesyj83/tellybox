package com.tellybox.tv.ui.playlists

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.PlaylistEntity
import com.tellybox.tv.data.db.PlaylistType
import com.tellybox.tv.ui.chrome.Category
import com.tellybox.tv.ui.chrome.Section
import com.tellybox.tv.ui.chrome.SectionActivity
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import com.tellybox.tv.ui.chrome.TwoColumn
import kotlinx.coroutines.launch

class PlaylistsActivity : SectionActivity() {

    private enum class Filter { ALL, ENABLED, DISABLED }

    override val section = Section.PLAYLISTS
    private lateinit var view: TwoColumn
    private lateinit var refreshAllButton: Button
    private lateinit var addButton: Button
    private val adapter = SimpleRowAdapter { row -> playlists.firstOrNull { it.id == row.id }?.let { showActions(it) } }
    private var playlists: List<PlaylistEntity> = emptyList()
    private var details: Map<Long, String> = emptyMap()
    private var filter = Filter.ALL
    private var landed = false

    /** Progress and results of refreshes; shown under the header. */
    private var status: CharSequence
        get() = view.b.notice.text
        set(value) {
            view.b.notice.text = value
            view.b.notice.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            filter = Filter.valueOf(c.key)
            render()
        }
        columns = view
        setSectionContent(view.b.root)
        view.b.title.setText(R.string.playlists_title)
        view.b.list.layoutManager = LinearLayoutManager(this)
        view.b.list.adapter = adapter
        view.categories.activeKey = filter.name
        refreshAllButton = action(getString(R.string.refresh_all)) { refreshAll() }
        addButton = action(getString(R.string.action_add_playlist)) { startActivity(Intent(this, AddPlaylistActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.playlists.observePlaylists().collect { list ->
                    val db = appContainer.database
                    details = list.associate { playlist ->
                        playlist.id to "${db.channels().countForPlaylist(playlist.id)} channels · ${db.groups().getForPlaylist(playlist.id).size} groups · ${if (db.epg().getSourcesForPlaylist(playlist.id).any { it.enabled }) "Guide attached" else "No guide"}"
                    }
                    playlists = list
                    refreshAllButton.isEnabled = list.isNotEmpty()
                    render()
                    if (list.isEmpty()) addButton.requestFocus()
                }
            }
        }
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    private fun action(label: String, onClick: () -> Unit): Button {
        val button = Button(this, null, 0, R.style.Button_TV_Small).apply {
            text = label
            setOnClickListener { onClick() }
        }
        view.b.actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * resources.displayMetrics.density).toInt() })
        return button
    }

    private fun matches(p: PlaylistEntity, f: Filter) = when (f) {
        Filter.ALL -> true
        Filter.ENABLED -> p.enabled
        Filter.DISABLED -> !p.enabled
    }

    private fun render() {
        val names = mapOf(Filter.ALL to R.string.playlists_all, Filter.ENABLED to R.string.playlists_enabled, Filter.DISABLED to R.string.playlists_disabled)
        view.categories.submit(Filter.entries.map { f -> Category(f.name, getString(names.getValue(f)), playlists.count { matches(it, f) }) })
        val focusedId = view.b.list.focusedChild?.let { view.b.list.getChildItemId(it) }
        val rows = playlists.filter { matches(it, filter) }.map { p ->
            SimpleRow(p.id, p.name, typeLabel(p.type), listOf(stateLine(p), details[p.id].orEmpty()).filter { it.isNotEmpty() }.joinToString("\n"), dimmed = !p.enabled)
        }
        adapter.submit(rows)
        view.b.empty.setText(R.string.playlists_empty)
        view.b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (focusedId != null) {
            val pos = rows.indexOfFirst { it.id == focusedId }
            if (pos >= 0) view.b.list.post { view.b.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        } else if (!landed && rows.isNotEmpty()) {
            landed = true
            view.b.list.post { view.focusList() }
        }
    }

    private fun typeLabel(t: PlaylistType) = when (t) {
        PlaylistType.M3U -> "M3U"
        PlaylistType.XTREAM -> "Xtream Codes"
        PlaylistType.STALKER -> "Stalker"
    }

    private fun stateLine(p: PlaylistEntity): String = when {
        !p.enabled -> getString(R.string.playlist_disabled)
        p.lastError != null -> getString(R.string.playlist_failed, p.lastError)
        p.lastUpdated == 0L -> getString(R.string.playlist_never_updated)
        System.currentTimeMillis() - p.lastUpdated < 60_000 -> getString(R.string.playlist_updated_now)
        else -> getString(
            R.string.playlist_updated,
            DateUtils.getRelativeTimeSpanString(p.lastUpdated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
        )
    }

    private fun showActions(playlist: PlaylistEntity, focusIndex: Int = 0) {
        val actions = listOf(
            getString(R.string.playlist_refresh),
            getString(if (playlist.enabled) R.string.playlist_disable else R.string.playlist_enable),
            getString(R.string.playlist_delete),
            "Edit playlist",
            "Manage groups and channels",
        )
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> refresh(playlist)
                    1 -> lifecycleScope.launch { appContainer.playlists.setEnabled(playlist, !playlist.enabled) }
                    2 -> confirmDelete(playlist)
                    3 -> editPlaylist(playlist)
                    4 -> startActivity(Intent(this, GroupManagementActivity::class.java).putExtra("playlist_id", playlist.id))
                }
            }
            .create().apply {
                setOnShowListener { listView.setSelection(focusIndex); listView.requestFocus() }
            }.show()
    }

    private fun editPlaylist(playlist: PlaylistEntity) = lifecycleScope.launch {
        val form = android.widget.LinearLayout(this@PlaylistsActivity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        fun field(label: String, value: String?, secret: Boolean = false): android.widget.EditText {
            form.addView(android.widget.TextView(this@PlaylistsActivity).apply { text = label })
            return android.widget.EditText(this@PlaylistsActivity).apply {
                setSingleLine()
                inputType = if (secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else android.text.InputType.TYPE_CLASS_TEXT
                setText(value.orEmpty())
                form.addView(this)
            }
        }
        val sources = appContainer.database.epg().getSourcesForPlaylist(playlist.id)
        val epg = field("EPG URLs (one per line; clear to remove)", sources.filter { it.enabled }.joinToString("\n") { it.url }).apply { setSingleLine(false); minLines = 2 }
        val name = field("Playlist name", playlist.name)
        val url = field("Source URL or file URI", playlist.url)
        val user = if (playlist.type == PlaylistType.XTREAM) field("Username", playlist.username) else null
        val password = if (playlist.type == PlaylistType.XTREAM) field("Password", playlist.password, true) else null
        val mac = if (playlist.type == PlaylistType.STALKER) field("MAC address", playlist.macAddress) else null
        val ua = field("User agent (optional)", playlist.userAgent)
        val dialog = AlertDialog.Builder(this@PlaylistsActivity).setTitle("Edit playlist")
            .setView(android.widget.ScrollView(this@PlaylistsActivity).apply { addView(form) })
            .setPositiveButton("Save and re-fetch", null).setNegativeButton(R.string.cancel) { _, _ -> showActions(playlist, 3) }.create()
        dialog.setOnCancelListener { showActions(playlist, 3) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isBlank() || url.text.isBlank()) {
                    Toast.makeText(this@PlaylistsActivity, "Name and source are required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (user != null && (user.text.isBlank() || password!!.text.isBlank())) {
                    Toast.makeText(this@PlaylistsActivity, "Username and password are required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val edited = playlist.copy(name = name.text.toString().trim(), url = url.text.toString().trim(),
                    username = user?.text?.toString() ?: playlist.username,
                    password = password?.text?.toString() ?: playlist.password,
                    macAddress = mac?.text?.toString()?.trim() ?: playlist.macAddress,
                    userAgent = ua.text.toString().trim().ifEmpty { null })
                dialog.dismiss()
                status = "Updating ${edited.name}…"
                val container = appContainer
                container.appScope.launch {
                    val result = container.playlists.edit(edited)
                    val wanted = epg.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    container.database.epg().getSourcesForPlaylist(edited.id).filter { it.url !in wanted }.forEach { container.epg.deleteSource(it.id) }
                    wanted.forEach { container.epg.addSource(it, edited.name, edited.id) }
                    result.onSuccess { container.epg.refreshForPlaylist(edited.id) }
                    runOnUiThread {
                        if (!isDestroyed) {
                            status = result.fold({ "Playlist updated" }, { "Saved; re-fetch failed: ${it.message}" })
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(playlist: PlaylistEntity) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { appContainer.playlists.delete(playlist.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh(playlist: PlaylistEntity) {
        val container = appContainer
        status = getString(R.string.playlist_refreshing, playlist.name)
        // Run in the app scope so leaving the screen doesn't abandon a half-done import.
        container.appScope.launch {
            val result = container.playlists.refresh(playlist.id)
            result.onSuccess { container.epg.refreshForPlaylist(playlist.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                status = ""
                val msg = result.fold(
                    onSuccess = {
                        getString(R.string.playlist_refresh_done, playlist.name, it.describe(this@PlaylistsActivity))
                    },
                    onFailure = { getString(R.string.playlist_refresh_failed, playlist.name, it.message ?: "") },
                )
                status = msg
            }
        }
    }

    private fun refreshAll() {
        val playlists = playlists.toList()
        refreshAllButton.isEnabled = false
        val container = appContainer
        container.appScope.launch {
            val results = mutableListOf<String>()
            playlists.forEachIndexed { index, playlist ->
                runOnUiThread { if (!isDestroyed) status = "Refreshing ${index + 1}/${playlists.size}: ${playlist.name}…" }
                val result = container.playlists.refresh(playlist.id)
                result.onSuccess { container.epg.refreshForPlaylist(playlist.id) }
                results += result.fold({ "${playlist.name}: success" }, { "${playlist.name}: failed — ${it.message}" })
            }
            runOnUiThread { if (!isDestroyed) {
                status = results.joinToString("\n")
                refreshAllButton.isEnabled = true
            } }
        }
    }
}

