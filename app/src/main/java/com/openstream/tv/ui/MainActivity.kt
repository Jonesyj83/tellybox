package com.openstream.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.repository.RefreshStatus
import com.openstream.tv.databinding.ActivityMainBinding
import com.openstream.tv.ui.channels.ChannelAdapter
import com.openstream.tv.ui.channels.ChannelRow
import com.openstream.tv.ui.channels.GroupAdapter
import com.openstream.tv.ui.channels.GroupSelection
import com.openstream.tv.ui.channels.MainViewModel
import com.openstream.tv.ui.epg.GuideActivity
import com.openstream.tv.ui.player.PlayerActivity
import com.openstream.tv.ui.playlists.AddPlaylistActivity
import com.openstream.tv.ui.playlists.PlaylistsActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(appContainer) } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelect: Runnable? = null
    private var focusChannelsOnUpdate = false

    private val groupAdapter = GroupAdapter(
        onFocused = { row -> selectSoon(row.selection) },
        onClick = { row ->
            viewModel.select(row.selection)
            binding.channels.requestFocus()
        },
    )

    private val channelAdapter = ChannelAdapter(
        onClick = { row, position -> play(row, position) },
        onMenu = { row -> toggleFavorite(row) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.groups.layoutManager = LinearLayoutManager(this)
        binding.groups.adapter = groupAdapter
        binding.groups.itemAnimator = null
        binding.channels.layoutManager = LinearLayoutManager(this)
        binding.channels.adapter = channelAdapter
        binding.channels.itemAnimator = null

        binding.playlistsButton.setOnClickListener { startActivity(Intent(this, PlaylistsActivity::class.java)) }
        binding.emptyAddButton.setOnClickListener { startActivity(Intent(this, AddPlaylistActivity::class.java)) }
        binding.searchButton.setOnClickListener { showSearch() }
        binding.guideButton.setOnClickListener { openGuide() }

        if (savedInstanceState == null) {
            val container = appContainer
            container.appScope.launch { container.backgroundRefresh.refreshStale() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appContainer.backgroundRefresh.status.collect { status ->
                        binding.status.text = when (status) {
                            RefreshStatus.Idle -> ""
                            RefreshStatus.Guide -> getString(R.string.refreshing_guide)
                            is RefreshStatus.Playlist -> getString(R.string.refreshing_playlist, status.name)
                        }
                    }
                }
                launch {
                    viewModel.playlists.collect { list ->
                        if (list == null) return@collect
                        val empty = list.isEmpty()
                        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                        binding.content.visibility = if (empty) View.GONE else View.VISIBLE
                        if (empty) binding.emptyAddButton.requestFocus()
                    }
                }
                launch {
                    viewModel.groups.combine(viewModel.search) { g, s -> viewModel.groupRows(g, s) }
                        .collect { rows ->
                            val searchAdded = rows.firstOrNull()?.selection is GroupSelection.Search &&
                                groupAdapter.currentList.firstOrNull()?.selection != rows.first().selection
                            groupAdapter.submitList(rows) {
                                // A new search row goes in at the top; make sure it's on screen.
                                if (searchAdded) binding.groups.scrollToPosition(0)
                            }
                        }
                }
                launch {
                    viewModel.selection.collect { groupAdapter.activeSelection = it }
                }
                launch {
                    viewModel.channels.collect { rows ->
                        if (rows == null) return@collect
                        val firstLoad = channelAdapter.itemCount == 0 && rows.isNotEmpty()
                        channelAdapter.submitList(rows) {
                            // Land on the channel list rather than the header buttons.
                            val wanted = focusChannelsOnUpdate ||
                                (firstLoad && !binding.channels.hasFocus() && !binding.groups.hasFocus())
                            if (wanted && rows.isNotEmpty()) {
                                focusChannelsOnUpdate = false
                                binding.channels.scrollToPosition(0)
                                binding.channels.post { binding.channels.getChildAt(0)?.requestFocus() }
                            }
                        }
                        binding.channelsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                        binding.channelsEmpty.setText(
                            if (viewModel.selection.value == GroupSelection.Favorites) R.string.empty_favorites
                            else R.string.empty_group,
                        )
                    }
                }
            }
        }
    }

    /** Switch groups as focus moves, but not on every row flicked past. */
    private fun selectSoon(selection: GroupSelection) {
        pendingSelect?.let(handler::removeCallbacks)
        pendingSelect = Runnable { viewModel.select(selection) }.also { handler.postDelayed(it, 250) }
    }

    private fun play(row: ChannelRow, position: Int) {
        val list = channelAdapter.currentList.map { it.channel.id }.toLongArray()
        startActivity(PlayerActivity.intent(this, row.channel.id, list, position))
    }

    /** Opens the guide over whatever list is showing, focused on the highlighted channel. */
    private fun openGuide() {
        val rows = channelAdapter.currentList
        val focused = binding.channels.focusedChild?.let { binding.channels.getChildAdapterPosition(it) }
            ?.takeIf { it >= 0 }?.let { rows.getOrNull(it)?.channel?.id }
        startActivity(GuideActivity.intent(this, rows.map { it.channel.id }.toLongArray().takeIf { it.isNotEmpty() }, focused))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_GUIDE) {
            openGuide()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleFavorite(row: ChannelRow) {
        viewModel.toggleFavorite(row.channel) { added ->
            val msg = if (added) R.string.favorite_added else R.string.favorite_removed
            Toast.makeText(this, getString(msg, row.channel.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(viewModel.search.value.orEmpty())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_search)
            .setView(input)
            .setPositiveButton(R.string.action_search) { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(input.text.toString())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    private fun runSearch(query: String) {
        focusChannelsOnUpdate = query.isNotBlank()
        viewModel.search(query)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
