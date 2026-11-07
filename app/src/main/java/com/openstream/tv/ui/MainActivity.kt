package com.openstream.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.data.db.EpgProgramEntity
import com.openstream.tv.data.repository.RefreshStatus
import com.openstream.tv.databinding.ActivityMainBinding
import com.openstream.tv.player.Playback
import com.openstream.tv.player.PlaybackErrors
import com.openstream.tv.player.Player
import com.openstream.tv.ui.channels.FocusTarget
import com.openstream.tv.ui.channels.GroupAdapter
import com.openstream.tv.ui.channels.GroupSelection
import com.openstream.tv.ui.channels.HomeArea
import com.openstream.tv.ui.channels.HomeBack
import com.openstream.tv.ui.channels.HomeFocus
import com.openstream.tv.ui.channels.MainViewModel
import com.openstream.tv.ui.channels.PreviewController
import com.openstream.tv.ui.channels.PreviewController.Action
import com.openstream.tv.ui.channels.homeBack
import com.openstream.tv.ui.epg.GuideActivity
import com.openstream.tv.ui.epg.GuideBlock
import com.openstream.tv.ui.epg.GuideChannel
import com.openstream.tv.ui.epg.GuideGridView
import com.openstream.tv.ui.epg.GuideSupport
import com.openstream.tv.ui.parental.requestPin
import com.openstream.tv.ui.player.PlayerActivity
import com.openstream.tv.ui.playlists.AddPlaylistActivity
import com.openstream.tv.ui.playlists.PlaylistsActivity
import com.openstream.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Date

/**
 * Home screen in preview mode: groups on the left, the guide grid beside them, and a small
 * live preview above. OK previews a channel; OK on it again, OK on the preview window, Play,
 * or holding OK on any channel goes full screen on the same player, so the switch is instant
 * and Back from full screen lands here with the preview still running.
 */
@OptIn(UnstableApi::class)
class MainActivity : com.openstream.tv.ui.parental.ProtectedActivity(), GuideGridView.Callback, Playback.Holder {

    private lateinit var binding: ActivityMainBinding
    private lateinit var guide: GuideSupport
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(appContainer) } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelect: Runnable? = null
    private var focusGridOnUpdate = false
    private var shownSelection: GroupSelection? = null
    private var landed = false

    private val preview = PreviewController()
    private var pendingPreview: Runnable? = null
    private var previewPlayer: Player? = null
    private var previewJob: Job? = null
    private var previewChannel: ChannelEntity? = null
    /** Channel ids the preview was picked from, so full screen can zap through the same list. */
    private var previewList: LongArray = LongArray(0)
    private var previewUrl: String? = null
    private var triedHls = false
    private var lockedOut = false

    private val tick = object : Runnable {
        override fun run() {
            binding.clock.text = DateFormat.getTimeFormat(this@MainActivity).format(Date())
            binding.grid.invalidate()
            updateNowPlaying()
            handler.postDelayed(this, 30_000)
        }
    }

    private val groupAdapter = GroupAdapter(
        onFocused = { row -> selectSoon(row.selection) },
        onClick = { row ->
            viewModel.select(row.selection)
            if (shownSelection != row.selection) focusGridOnUpdate = true
            else if (binding.grid.channels.isNotEmpty()) binding.grid.requestFocus()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        // Leave room for programme details when the logical canvas is narrower than a TV baseline.
        // These are base dp here; ScaledActivity applies the shared factor before drawing.
        val mainColumnBase = uiScale.widthDp / uiScale.scale - 48 * 2 - 44 - 220 - 12
        binding.previewTile.layoutParams.width =
            (minOf(270f, ((mainColumnBase - 16) / 2).coerceAtLeast(80f)) * resources.displayMetrics.density).toInt()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleScope.launch { appContainer.settings.settings.collect { settings ->
            binding.clock.visibility = if (settings.showClock) View.VISIBLE else View.GONE
            binding.grid.dimPast = settings.dimPast
            binding.grid.panelOpacity = settings.panelOpacity
            // Off by default: the preview only changes when you press OK. On, it follows
            // whatever row you rest on, for people who prefer browsing that way.
            preview.followFocus = settings.previewFollowsFocus
        } }


        binding.groups.layoutManager = LinearLayoutManager(this)
        binding.groups.adapter = groupAdapter
        binding.groups.itemAnimator = null
        if (com.openstream.tv.BuildConfig.DEBUG) {
            var lastGeometry = ""
            binding.root.viewTreeObserver.addOnGlobalLayoutListener {
                val density = resources.displayMetrics.density
                val row = uiScale.categoryRowHeight() * density
                val geometry = "canvas=${uiScale.widthDp}x${uiScale.heightDp} scale=${uiScale.scale} " +
                    "categories=${((binding.groups.height - binding.groups.paddingTop - binding.groups.paddingBottom) / row).toInt()} " +
                    "guide=${binding.grid.visibleRowCapacity} rail=${binding.guideButton.width} " +
                    "groupsBottom=${binding.groups.bottom} contentHeight=${binding.content.height}"
                if (geometry != lastGeometry) {
                    android.util.Log.d("UiScale", geometry)
                    lastGeometry = geometry
                }
            }
        }


        guide = GuideSupport(this, binding.grid)
        with(binding.grid) {
            callback = this@MainActivity
            setMetrics(rowHeightDp = 26f, channelWidthDp = 196f, pxPerMinuteDp = 4.2f)
            exitLeftAtNow = true
            onExitLeft = { moveFocus(HomeFocus.leftFrom(HomeArea.GUIDE)); true }
            onExitUp = { moveFocus(HomeFocus.upFromGridTop(previewShowing)) }
        }

        // Subtitles are unreadable at this size and cover the channel label.
        binding.previewPlayer.subtitleView?.visibility = View.GONE
        binding.previewTile.setOnClickListener { act(preview.tile()) }
        binding.previewTile.setOnFocusChangeListener { _, focused ->
            binding.previewWatch.setText(if (focused) R.string.preview_action_ok else R.string.preview_action_play)
        }
        binding.watchButton.setOnClickListener { act(preview.tile()) }
        binding.previewTile.setOnLongClickListener {
            previewChannel?.let { showChannelMenu(it, null, null) }
            true
        }

        binding.remindersButton.setOnClickListener { startActivity(Intent(this, com.openstream.tv.ui.reminders.RemindersActivity::class.java)) }
        binding.multiviewButton.setOnClickListener { lifecycleScope.launch {
            val channels = appContainer.channels.observeAll().first()
            if (channels.isEmpty()) Toast.makeText(this@MainActivity, "Add an enabled live playlist first", Toast.LENGTH_LONG).show()
            else AlertDialog.Builder(this@MainActivity).setTitle("Choose first multiview channel").setItems(channels.map { it.name }.toTypedArray()) { _, index ->
                startActivity(com.openstream.tv.ui.multiview.MultiviewActivity.intent(this@MainActivity, channels[index].id, channels.map { it.id }.toLongArray()))
            }.show()
        } }
        val railButtons = listOf(binding.guideButton, binding.vodButton, binding.recordingsButton, binding.remindersButton,
            binding.searchButton, binding.multiviewButton, binding.playlistsButton, binding.settingsButton)
        // The rail carries its own labels now, so nothing floats over the categories.
        lifecycleScope.launch { appContainer.playlists.observePlaylists().collect { playlists ->
            binding.playlistsButton.foreground = if (playlists.any { it.lastError != null }) getDrawable(R.drawable.rail_badge) else null
        } }
        lifecycleScope.launch { appContainer.database.recordings().observeAll().collect { recordings ->
            binding.recordingsButton.foreground = if (recordings.any { it.status == com.openstream.tv.data.db.RecordingStatus.RECORDING }) getDrawable(R.drawable.rail_badge) else null
        } }
        binding.playlistsButton.setOnClickListener { startActivity(Intent(this, PlaylistsActivity::class.java)) }
        binding.emptyAddButton.setOnClickListener { startActivity(Intent(this, AddPlaylistActivity::class.java)) }
        binding.searchButton.setOnClickListener { showSearch() }
        binding.vodButton.setOnClickListener { startActivity(Intent(this, com.openstream.tv.ui.vod.VodActivity::class.java)) }
        binding.guideButton.setOnClickListener { moveFocus(FocusTarget.GRID) }
        binding.recordingsButton.setOnClickListener {
            startActivity(Intent(this, com.openstream.tv.ui.recordings.RecordingsActivity::class.java))
        }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.emptySettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val action = if (binding.content.visibility != View.VISIBLE) HomeBack.EXIT
                else homeBack(focusArea(), viewModel.search.value != null)
                when (action) {
                    HomeBack.FOCUS_GROUPS -> focusActiveGroup()
                    HomeBack.CLEAR_SEARCH -> runSearch("")
                    HomeBack.EXIT -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        savedInstanceState?.getLong(STATE_PREVIEW, -1)?.takeIf { it >= 0 }?.let { id ->
            preview.adopt(id)
            previewList = savedInstanceState.getLongArray(STATE_PREVIEW_LIST) ?: longArrayOf(id)
        }

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
                        binding.content.visibility = View.VISIBLE
                        if (empty) {
                            stopPreview()
                            binding.emptyAddButton.requestFocus()
                        }
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
                    viewModel.channels.collect { value -> value?.let { (sel, rows) -> showChannels(sel, rows) } }
                }
                launch {
                    var last: Long? = null
                    viewModel.epgVersion.collect { v ->
                        if (last != null && last != v) guide.invalidate()
                        last = v
                    }
                }
                launch {
                    // A channel that becomes blocked (unlock window ran out) stops previewing.
                    appContainer.parental.changes.collect {
                        val ch = previewChannel ?: return@collect
                        if (!previewAllowed(ch)) stopPreview()
                    }
                }
            }
        }
    }

    // ---- channels / grid ------------------------------------------------------------------

    private fun showChannels(selection: GroupSelection, rows: List<com.openstream.tv.ui.channels.ChannelRow>) {
        val sameList = selection == shownSelection
        shownSelection = selection
        val entities = rows.map { it.channel.copy(name = it.displayName) }
        guide.channels = entities
        binding.grid.setChannels(
            entities.mapIndexed { i, c -> GuideSupport.guideChannel(c, rows[i].favorite) },
            focusChannelId = preview.previewing,
            keepPosition = sameList,
        )
        binding.grid.playingChannelId = preview.previewing
        binding.channelsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.channelsEmpty.setText(
            if (selection == GroupSelection.Favorites) R.string.empty_favorites else R.string.empty_group,
        )
        if (rows.isEmpty()) {
            if (binding.grid.hasFocus()) focusActiveGroup()
            return
        }
        // Land on the guide rather than the preview or the buttons, the first time and after a search or group pick.
        if (focusGridOnUpdate || (!landed && !binding.groups.hasFocus())) {
            focusGridOnUpdate = false
            landed = true
            binding.grid.requestFocus()
        }
    }

    override fun programsFor(channel: GuideChannel): List<EpgProgramEntity>? = guide.programsFor(channel)

    override fun onVisibleRows(first: Int, last: Int) = guide.onVisibleRows(first, last)

    override fun onFocus(channel: GuideChannel, block: GuideBlock) {
        if (previewChannel == null) showInfo(channel, block)

    }

    private fun updateNowPlaying() {
        val channel = previewChannel ?: return
        binding.infoChannel.text = channel.name
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            val programs = channel.id.toString().let { id ->
                appContainer.epg.programsFor(listOf(id), now, now + 1)[id.lowercase()]
            }.orEmpty()
            if (previewChannel?.id != channel.id) return@launch
            val program = programs.firstOrNull { now >= it.start && now < it.stop }
            binding.infoTitle.text = program?.title.orEmpty()
            binding.infoDescription.text = program?.description.orEmpty()
            binding.infoTime.text = program?.let {
                val format = DateFormat.getTimeFormat(this@MainActivity)
                "${format.format(Date(it.start))} – ${format.format(Date(it.stop))}"
            }.orEmpty()
            binding.infoProgress.progress = program?.let {
                (((now - it.start) * 1000 / (it.stop - it.start).coerceAtLeast(1)).toInt()).coerceIn(0, 1000)
            } ?: 0
        }
    }

    private fun showInfo(channel: GuideChannel, block: GuideBlock) {
        val info = guide.describe(channel, block)
        binding.infoChannel.text = listOfNotNull(channel.number?.toString(), channel.name).joinToString("  ")
        binding.infoTitle.text = if (block.program != null) info.title else ""
        binding.infoTime.text = info.timeOnly
        binding.infoDescription.text = info.description
    }

    override fun onSelect(row: Int, block: GuideBlock) {
        val channel = guide.channelFor(row) ?: return
        if (channel.kind != ContentKind.LIVE) return openVod(channel)
        if (guide.isReplayable(row, block)) {
            guide.catchupIntent(row, block)?.let { startActivity(PlayerActivity.fromPreview(it)) }
            return
        }
        act(preview.pick(channel.id))
    }

    override fun onLongSelect(row: Int, block: GuideBlock) {
        val channel = guide.channelFor(row) ?: return
        if (channel.kind != ContentKind.LIVE) return openVod(channel)
        act(preview.longPress(channel.id))
    }

    override fun onMenu(row: Int, block: GuideBlock) {
        val channel = guide.channelFor(row) ?: return
        showChannelMenu(channel, row, block)
    }

    private fun gridIds() = guide.channels.map { it.id }.toLongArray()

    // ---- preview --------------------------------------------------------------------------

    private fun act(action: Action) {
        when (action) {
            Action.None -> Unit
            Action.Cancel -> cancelPendingPreview()
            is Action.FullScreen -> {
                cancelPendingPreview()
                goFullScreen(action.channelId)
            }
            is Action.Start -> {
                cancelPendingPreview()
                val id = action.channelId
                val list = gridIds()
                val start = Runnable {
                    pendingPreview = null
                    if (preview.commit(id)) {
                        previewList = list
                        startPreview(id)
                    }
                }
                pendingPreview = start
                if (action.delayMs <= 0) handler.post(start) else handler.postDelayed(start, action.delayMs)
            }
        }
    }

    private fun cancelPendingPreview() {
        pendingPreview?.let(handler::removeCallbacks)
        pendingPreview = null
    }

    private val previewListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val url = previewUrl
            val ch = previewChannel
            // Extensionless URLs are often HLS underneath; give that one more go, like the player does.
            if (!triedHls && url != null && ch != null && Player.guessMimeType(url) == null) {
                triedHls = true
                lifecycleScope.launch {
                    previewPlayer?.play(url, ch.name, appContainer.channels.userAgentFor(ch), ch.referrer, forceHls = true)
                }
                return
            }
            ch?.let { preview.failed(it.id) }
            showPreviewError(PlaybackErrors.describe(this@MainActivity, error))
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == androidx.media3.common.Player.STATE_READY) {
                previewChannel?.let { preview.playing(it.id) }
                binding.previewError.visibility = View.GONE
            }
        }
    }

    private suspend fun previewAllowed(channel: ChannelEntity): Boolean {
        val p = appContainer.parental
        return p.allowed(channel, appContainer.database) && !(p.allScreens && !p.unlocked)
    }

    /** Takes the shared player for this screen, creating it if needed, and shows it in the tile. */
    private fun attachPlayer(): Player {
        previewPlayer?.let { return it }
        val config = runBlocking { appContainer.settings.get() }.playerConfig
        val p = appContainer.playback.acquire(config, this)
        p.exo.addListener(previewListener)
        binding.previewPlayer.player = p.exo
        previewPlayer = p
        appContainer.playback.setSessionActivity(Intent(this, MainActivity::class.java))
        return p
    }

    private fun detachPlayer() {
        previewPlayer?.exo?.removeListener(previewListener)
        binding.previewPlayer.player = null
        previewPlayer = null
    }

    private fun startPreview(channelId: Long) {
        previewJob?.cancel()
        binding.grid.playingChannelId = channelId
        binding.previewHint.visibility = View.GONE
        binding.previewError.visibility = View.GONE
        previewJob = lifecycleScope.launch {
            val stored = appContainer.channels.get(channelId) ?: return@launch stopPreview()
            val channel = guide.channels.firstOrNull { it.id == channelId } ?: stored.copy(
                name = com.openstream.tv.data.settings.ChannelNames.display(stored.name, appContainer.settings.get().channelNameTokens),
            )
            showPreviewLabel(channel)
            if (!previewAllowed(stored)) {
                previewPlayer?.exo?.stop()
                requestPin(cancel = { stopPreview() }) {
                    preview.adopt(channelId)
                    startPreview(channelId)
                }
                return@launch
            }
            previewChannel = channel
            updateNowPlaying()
            val player = attachPlayer()
            val playback = appContainer.playback
            if (playback.key == PlayerActivity.channelKey(channelId) &&
                player.exo.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                return@launch // already playing this one
            }
            triedHls = false
            try {
                val url = appContainer.channels.resolveStreamUrl(stored)
                previewUrl = url
                player.play(url, channel.name, appContainer.channels.userAgentFor(stored), stored.referrer)
                playback.key = PlayerActivity.channelKey(channelId)
                playback.liveChannelId = channelId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player.exo.stop()
                playback.key = null
                playback.liveChannelId = null
                preview.failed(channelId)
                showPreviewError(e.message ?: getString(R.string.error_network))
            }
        }
    }

    private fun showPreviewLabel(channel: ChannelEntity) {
        binding.previewLabel.text = listOfNotNull(channel.number?.toString(), channel.name).joinToString("  ")
        binding.previewLabel.visibility = View.VISIBLE
        binding.previewWatch.visibility = View.VISIBLE
        binding.watchButton.text = getString(R.string.preview_watch_button, channel.name)
        binding.watchButton.visibility = View.VISIBLE
    }

    private fun showPreviewError(detail: String) {
        binding.previewErrorDetail.text = detail
        binding.previewError.visibility = View.VISIBLE
    }

    /** Stops the preview and frees the player; the tile goes back to its hint. */
    private fun stopPreview() {
        cancelPendingPreview()
        previewJob?.cancel()
        preview.stop()
        previewChannel = null
        previewUrl = null
        detachPlayer()
        appContainer.playback.release(this)
        binding.grid.playingChannelId = null
        showPreviewIdle(R.string.preview_hint)
    }

    private fun showPreviewIdle(text: Int) {
        binding.previewHint.setText(text)
        binding.previewHint.visibility = View.VISIBLE
        binding.previewError.visibility = View.GONE
        binding.previewLabel.visibility = View.GONE
        binding.previewWatch.visibility = View.GONE
        // Don't let focus fall wherever Android puts it when the button it's on disappears.
        if (binding.watchButton.hasFocus()) binding.previewTile.requestFocus()
        binding.watchButton.visibility = View.GONE
    }

    private fun goFullScreen(channelId: Long) {
        val list = if (channelId == preview.previewing && channelId in previewList) previewList
        else gridIds().takeIf { channelId in it } ?: longArrayOf(channelId)
        startActivity(PlayerActivity.fromPreview(PlayerActivity.intent(this, channelId, list, list.indexOf(channelId))))
    }

    /** The full-screen player (or picture-in-picture) took over the stream. */
    override fun onPlaybackTaken() {
        detachPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("focus_rail", false)) binding.guideButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
        if (!lockedOut) resumePreview()
    }

    /**
     * Coming back to the screen: pick up the stream the full-screen player handed back
     * (possibly a channel zapped to there), or restart the preview that was showing.
     */
    private fun resumePreview() {
        val playback = appContainer.playback
        if (!playback.isUnowned && !playback.isOwner(this)) {
            // Something else, e.g. a picture-in-picture window, is using the player.
            if (preview.previewing != null) showPreviewIdle(R.string.preview_elsewhere)
            return
        }
        val handedBack = playback.liveChannelId.takeIf { playback.player != null }
        if (handedBack != null && playback.key == PlayerActivity.channelKey(handedBack)) {
            if (preview.previewing != handedBack) {
                preview.adopt(handedBack)
                if (handedBack !in previewList) previewList = gridIds().takeIf { handedBack in it } ?: longArrayOf(handedBack)
            }
            binding.grid.focusChannel(handedBack)
            startPreview(handedBack)
            return
        }
        val id = preview.previewing
        if (id == null) {
            // Nothing to continue; don't leave a stream someone else left behind running.
            if (playback.isUnowned) playback.release()
            showPreviewIdle(R.string.preview_hint)
            return
        }
        startPreview(id)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        cancelPendingPreview()
        previewJob?.cancel()
        detachPlayer()
        // No-op when the full-screen player has taken over; otherwise the preview stops with the screen.
        appContainer.playback.release(this)
        super.onStop()
    }

    override fun onParentalLock() {
        lockedOut = true
        previewJob?.cancel()
        detachPlayer()
        appContainer.playback.release(this)
    }

    override fun onParentalUnlock() {
        lockedOut = false
        resumePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        preview.previewing?.let {
            outState.putLong(STATE_PREVIEW, it)
            outState.putLongArray(STATE_PREVIEW_LIST, previewList)
        }
    }

    // ---- focus / keys ---------------------------------------------------------------------

    private fun focusArea(): HomeArea = when {
        binding.groups.hasFocus() -> HomeArea.GROUPS
        binding.grid.hasFocus() -> HomeArea.GUIDE
        binding.previewTile.hasFocus() -> HomeArea.PREVIEW
        binding.menuRail.hasFocus() -> HomeArea.RAIL
        else -> HomeArea.HEADER
    }

    private fun focusActiveGroup(): Boolean {
        val selected = (0 until binding.groups.childCount).map { binding.groups.getChildAt(it) }
            .firstOrNull { it.isActivated }
        return selected?.requestFocus() == true || binding.groups.requestFocus()
    }

    /** Something is in the preview window, so the Watch button is up and Up from the grid goes there. */
    private val previewShowing get() = binding.watchButton.visibility == View.VISIBLE

    private fun moveFocus(target: FocusTarget) {
        val moved = when (target) {
            FocusTarget.GRID -> binding.grid.channels.isNotEmpty() && binding.grid.requestFocus()
            FocusTarget.GROUPS -> focusActiveGroup()
            FocusTarget.PREVIEW -> binding.previewTile.requestFocus()
            FocusTarget.WATCH -> binding.watchButton.requestFocus()
            FocusTarget.HEADER, FocusTarget.RAIL -> binding.guideButton.requestFocus()
        }
        if (!moved && target == FocusTarget.GRID) focusActiveGroup()
    }

    /** Up/Down between areas, by fixed rules rather than Android's focus search. Null = not ours. */
    private fun routeVertical(keyCode: Int): FocusTarget? {
        val up = keyCode == KeyEvent.KEYCODE_DPAD_UP
        return when {
            binding.previewTile.hasFocus() ->
                if (up) HomeFocus.upFromPreview(previewShowing) else HomeFocus.downFromPreview()
            binding.watchButton.hasFocus() ->
                if (up) null else HomeFocus.downFromHeader(previewShowing)
            else -> null
        }
    }

    private var mediaKeyHandled = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.content.visibility == View.VISIBLE) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                    if (binding.emptyState.visibility == View.VISIBLE) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) binding.guideButton.requestFocus()
                        else if (binding.menuRail.hasFocus()) binding.emptyAddButton.requestFocus()
                        return true
                    }
                    val area = focusArea()
                    val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    if (area == HomeArea.RAIL || area == HomeArea.GROUPS || area == HomeArea.PREVIEW || area == HomeArea.HEADER) {
                        if (event.repeatCount == 0) moveFocus(if (left) {
                            if (binding.watchButton.hasFocus()) FocusTarget.PREVIEW else HomeFocus.leftFrom(area)
                        } else HomeFocus.rightFrom(area))
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    if (binding.menuRail.hasFocus()) {
                        val rail = binding.menuRail
                        // Only the item rows take focus; the spacer between the group and the
                        // settings icon must not swallow a Down press.
                        val items = (0 until rail.childCount).map { rail.getChildAt(it) }
                            .filter { it.isFocusable }
                        if (items.isNotEmpty()) {
                            val index = items.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
                            val next = (index + if (up) -1 else 1).coerceIn(0, items.size - 1)
                            items[next].requestFocus()
                        }
                        return true
                    }
                    if (binding.groups.hasFocus()) {
                        val holder = currentFocus?.let { binding.groups.findContainingViewHolder(it) }
                        val index = holder?.bindingAdapterPosition ?: 0
                        val next = HomeFocus.verticalIndex(index, groupAdapter.itemCount, up)
                        binding.groups.scrollToPosition(next)
                        binding.groups.post { binding.groups.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
                        return true
                    }
                    val target = routeVertical(event.keyCode)
                    if (target != null) {
                        if (event.repeatCount == 0) moveFocus(target)
                        return true
                    }
                    if (binding.watchButton.hasFocus()) return true
                }
                // Play from anywhere on the screen: full screen on what's previewing.
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (down) {
                        if (event.repeatCount == 0) mediaKeyHandled = preview.previewing != null
                        if (mediaKeyHandled) {
                            if (event.repeatCount == 0) act(preview.tile())
                            return true
                        }
                    } else if (mediaKeyHandled) {
                        mediaKeyHandled = false
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            KeyEvent.KEYCODE_MENU -> if (binding.previewTile.hasFocus()) {
                previewChannel?.let { showChannelMenu(it, null, null) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- menus and other screens ----------------------------------------------------------

    /** Options for a channel: from a guide cell ([row] and [block] set) or from the preview window. */
    private fun showChannelMenu(channel: ChannelEntity, row: Int?, block: GuideBlock?) {
        lifecycleScope.launch {
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            val live = channel.kind == ContentKind.LIVE
            if (live) {
                actions += getString(R.string.preview_watch_full) to { act(preview.longPress(channel.id)) }
                if (preview.previewing != channel.id) actions += getString(R.string.preview_start) to { act(preview.pick(channel.id)) }
                else if (row == null) actions += getString(R.string.preview_stop) to { stopPreview() }
            } else {
                actions += getString(R.string.guide_watch_live) to { openVod(channel) }
            }
            val favorite = (channel.playlistId to channel.channelKey) in viewModel.favoriteKeys.value
            actions += getString(if (favorite) R.string.favorite_remove else R.string.favorite_add) to { toggleFavorite(channel) }
            if (row != null && block != null) {
                if (guide.isReplayable(row, block)) {
                    actions += getString(R.string.guide_watch_archive) to {
                        guide.catchupIntent(row, block)?.let { startActivity(PlayerActivity.fromPreview(it)) }
                        Unit
                    }
                }
                actions += guide.programActions(row, block)
                actions += getString(R.string.guide_jump_now) to { binding.grid.jumpToNow() }
            }
            actions += getString(R.string.open_full_guide) to { openGuide(channel.id) }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(block?.program?.title ?: channel.name)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .show()
        }
    }

    private fun openVod(channel: ChannelEntity) {
        startActivity(Intent(this, com.openstream.tv.ui.vod.VodDetailActivity::class.java).putExtra("channel_id", channel.id))
    }

    /** Selection changes as focus moves through the groups, but not on every row flicked past. */
    private fun selectSoon(selection: GroupSelection) {
        pendingSelect?.let(handler::removeCallbacks)
        pendingSelect = Runnable { viewModel.select(selection) }.also { handler.postDelayed(it, 250) }
    }

    /** Opens the full guide over whatever list is showing, focused on the highlighted channel. */
    private fun openGuide(focusId: Long? = binding.grid.focusedChannel?.id) {
        startActivity(GuideActivity.intent(this, gridIds().takeIf { it.isNotEmpty() }, focusId))
    }

    private fun toggleFavorite(channel: ChannelEntity) {
        viewModel.toggleFavorite(channel) { added ->
            val msg = if (added) R.string.favorite_added else R.string.favorite_removed
            Toast.makeText(this, getString(msg, channel.name), Toast.LENGTH_SHORT).show()
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
        focusGridOnUpdate = query.isNotBlank()
        viewModel.search(query)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            detachPlayer()
            appContainer.playback.release(this)
        }
        super.onDestroy()
    }

    companion object {
        private const val STATE_PREVIEW = "preview_channel"
        private const val STATE_PREVIEW_LIST = "preview_list"
    }
}
