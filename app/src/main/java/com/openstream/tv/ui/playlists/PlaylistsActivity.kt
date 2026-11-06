package com.openstream.tv.ui.playlists

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.PlaylistEntity
import com.openstream.tv.data.db.PlaylistType
import com.openstream.tv.databinding.ActivityPlaylistsBinding
import com.openstream.tv.databinding.ItemPlaylistBinding
import kotlinx.coroutines.launch

class PlaylistsActivity : com.openstream.tv.ui.parental.ProtectedActivity() {

    private lateinit var binding: ActivityPlaylistsBinding
    private val adapter = PlaylistAdapter { showActions(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.addButton.setOnClickListener { startActivity(Intent(this, AddPlaylistActivity::class.java)) }
        binding.refreshAllButton.setOnClickListener { refreshAll() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.playlists.observePlaylists().collect { list ->
                    adapter.submitList(list)
                    adapter.details = list.associate { playlist ->
                        val db = appContainer.database
                        playlist.id to "${db.channels().countForPlaylist(playlist.id)} channels · ${db.groups().getForPlaylist(playlist.id).size} groups · ${if (db.epg().getSourcesForPlaylist(playlist.id).any { it.enabled }) "Guide attached" else "No guide"}"
                    }
                    adapter.notifyDataSetChanged()
                    binding.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    binding.refreshAllButton.isEnabled = list.isNotEmpty()
                    if (list.isEmpty()) binding.addButton.requestFocus()
                }
            }
        }
    }

    private fun showActions(playlist: PlaylistEntity) {
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
            .show()
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
            .setPositiveButton("Save and re-fetch", null).setNegativeButton(R.string.cancel, null).create()
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
                binding.status.text = "Updating ${edited.name}…"
                val container = appContainer
                container.appScope.launch {
                    val result = container.playlists.edit(edited)
                    val wanted = epg.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    container.database.epg().getSourcesForPlaylist(edited.id).filter { it.url !in wanted }.forEach { container.epg.deleteSource(it.id) }
                    wanted.forEach { container.epg.addSource(it, edited.name, edited.id) }
                    result.onSuccess { container.epg.refreshForPlaylist(edited.id) }
                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.status.text = result.fold({ "Playlist updated" }, { "Saved; re-fetch failed: ${it.message}" })
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
        binding.status.text = getString(R.string.playlist_refreshing, playlist.name)
        // Run in the app scope so leaving the screen doesn't abandon a half-done import.
        container.appScope.launch {
            val result = container.playlists.refresh(playlist.id)
            result.onSuccess { container.epg.refreshForPlaylist(playlist.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.status.text = ""
                val msg = result.fold(
                    onSuccess = {
                        getString(R.string.playlist_refresh_done, playlist.name, it.describe(this@PlaylistsActivity))
                    },
                    onFailure = { getString(R.string.playlist_refresh_failed, playlist.name, it.message ?: "") },
                )
                binding.status.text = msg
            }
        }
    }

    private fun refreshAll() {
        val playlists = adapter.currentList.toList()
        binding.refreshAllButton.isEnabled = false
        val container = appContainer
        container.appScope.launch {
            val results = mutableListOf<String>()
            playlists.forEachIndexed { index, playlist ->
                runOnUiThread { if (!isDestroyed) binding.status.text = "Refreshing ${index + 1}/${playlists.size}: ${playlist.name}…" }
                val result = container.playlists.refresh(playlist.id)
                result.onSuccess { container.epg.refreshForPlaylist(playlist.id) }
                results += result.fold({ "${playlist.name}: success" }, { "${playlist.name}: failed — ${it.message}" })
            }
            runOnUiThread { if (!isDestroyed) {
                binding.status.text = results.joinToString("\n")
                binding.refreshAllButton.isEnabled = true
            } }
        }
    }
}

private class PlaylistAdapter(
    private val onClick: (PlaylistEntity) -> Unit,
) : ListAdapter<PlaylistEntity, PlaylistAdapter.Holder>(Diff) {

    var details: Map<Long, String> = emptyMap()

    class Holder(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = getItem(position)
        val ctx = holder.b.root.context
        holder.b.name.text = p.name
        holder.b.type.text = when (p.type) {
            PlaylistType.M3U -> "M3U"
            PlaylistType.XTREAM -> "Xtream Codes"
            PlaylistType.STALKER -> "Stalker"
        }
        holder.b.detail.text = when {
            !p.enabled -> ctx.getString(R.string.playlist_disabled)
            p.lastError != null -> ctx.getString(R.string.playlist_failed, p.lastError)
            p.lastUpdated == 0L -> ctx.getString(R.string.playlist_never_updated)
            System.currentTimeMillis() - p.lastUpdated < 60_000 -> ctx.getString(R.string.playlist_updated_now)
            else -> ctx.getString(
                R.string.playlist_updated,
                DateUtils.getRelativeTimeSpanString(p.lastUpdated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
        holder.b.detail.append("\n" + details[p.id].orEmpty())
        holder.b.detail.append("\nLast refreshed: " + if (p.lastUpdated == 0L) "Never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.lastUpdated)))
        holder.b.root.alpha = if (p.enabled) 1f else 0.55f
        holder.b.root.setOnClickListener { onClick(p) }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistEntity>() {
        override fun areItemsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a.id == b.id
        override fun areContentsTheSame(a: PlaylistEntity, b: PlaylistEntity) = a == b
    }
}
