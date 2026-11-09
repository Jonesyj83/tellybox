package com.tellybox.tv.ui.multiview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import com.tellybox.tv.data.settings.AppSettings
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.db.GroupWithCount
import com.tellybox.tv.data.db.PlaylistEntity
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.data.settings.ChannelNames
import com.tellybox.tv.databinding.ItemScreenCountBinding
import com.tellybox.tv.databinding.MultiviewContentBinding
import com.tellybox.tv.player.BufferSize
import com.tellybox.tv.player.Player
import com.tellybox.tv.player.PlayerConfig
import com.tellybox.tv.player.PlayerFactory
import com.tellybox.tv.ui.chrome.Category
import com.tellybox.tv.ui.chrome.Section
import com.tellybox.tv.ui.chrome.SectionActivity
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import com.tellybox.tv.ui.chrome.TwoColumn
import com.tellybox.tv.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Several live channels at once, beside the shared rail. Opening it explains that every screen
 * takes a provider connection, asks for 2, 3 or 4 screens, then each channel is picked the
 * usual way: playlist (when there is more than one), category, channel. Each tile has its own
 * player; only the focused tile is audible. Players live only while this screen is visible,
 * so tiles never hold provider connections in the background. A playlist's declared
 * connection limit is enforced; a tile that stops getting data says so.
 */
@OptIn(UnstableApi::class)
class MultiviewActivity : SectionActivity() {

    private enum class Mode { COUNT, PICK, TILES }

    private inner class Tile(var channelId: Long) {
        val frame = FrameLayout(this@MultiviewActivity)
        val video = PlayerView(this@MultiviewActivity)
        val label = TextView(this@MultiviewActivity)
        val status = TextView(this@MultiviewActivity)
        val stall = StallClock()
        var player: Player? = null
        var channel: ChannelEntity? = null
        var url: String? = null
        var triedHls = false
        var error: String? = null
        var job: Job? = null
    }

    override val section = Section.MULTIVIEW
    private lateinit var content: MultiviewContentBinding
    private lateinit var grid: MultiviewGrid
    private lateinit var picker: TwoColumn
    private val tiles = mutableListOf<Tile>()
    private var mode = Mode.COUNT
    private var plan: SlotPlan? = null
    /** The screen being chosen in the picker. */
    private var targetSlot = 0
    private var started = false
    private var startJob: Job? = null
    /** Picked channels by id, for names and the connection check before tiles exist. */
    private val chosen = HashMap<Long, ChannelEntity>()
    private var audioTile = 0
    private var fromPlayer = false
    private var firstChannel: Long? = null
    private var channelIds = LongArray(0)
    private var choices: List<ChannelEntity>? = null
    private var config = PlayerConfig()
    private var nameTokens = ""
    private var pickerJob: Job? = null
    private var pickerRows: List<ChannelEntity> = emptyList()
    private val pickerAdapter = SimpleRowAdapter { row -> onPickerRow(row) }
    /** Playlists with live channels; with more than one, the picker starts on them. */
    private var pickerPlaylists: List<PlaylistEntity> = emptyList()
    private var pickerGroups: List<GroupWithCount> = emptyList()
    /** The playlist whose categories the picker shows; null while choosing the playlist. */
    private var pickerPlaylist: Long? = null
    /** Category rows on the right while choosing a playlist. */
    private var playlistLevelRows: List<Category> = emptyList()
    /** Most screens the playlists' declared limits allow; null when any limit is unknown. */
    private var capacity: Int? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val checkStalls = object : Runnable {
        override fun run() {
            tiles.forEach(::sampleStall)
            ticker.postDelayed(this, STALL_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome.root.setBackgroundColor(getColor(R.color.bg))
        chrome.root.keepScreenOn = true
        content = MultiviewContentBinding.inflate(layoutInflater, chrome.sectionContent, false)
        setSectionContent(content.root)

        grid = MultiviewGrid(this).apply {
            margin = px(10)
            gap = px(6)
        }
        content.tilesHost.addView(grid, FrameLayout.LayoutParams(-1, -1))

        picker = TwoColumn(layoutInflater, content.pickerHost) { c -> showPickerChannels(c.key) }
        content.pickerHost.addView(picker.b.root)
        picker.b.list.layoutManager = LinearLayoutManager(this)
        picker.b.list.adapter = pickerAdapter

        listOf(content.count2 to 2, content.count3 to 3, content.count4 to 4).forEach { (b, n) -> bindCount(b, n) }

        fromPlayer = intent.hasExtra(EXTRA_CHANNEL)
        firstChannel = intent.getLongExtra(EXTRA_CHANNEL, -1).takeIf { it >= 0 }
        channelIds = intent.getLongArrayExtra(EXTRA_LIST) ?: LongArray(0)
        val restored = savedInstanceState?.getLongArray(STATE_TILES)?.filter { it >= 0 }.orEmpty()
        if (restored.isNotEmpty()) {
            restored.forEach { addTile(it) }
            grid.enlarged = savedInstanceState?.getInt(STATE_ENLARGED, -1)?.takeIf { it in tiles.indices }
            showMode(Mode.TILES)
        } else {
            showCountPicker()
            introduce()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_TILES, tiles.map { it.channelId }.toLongArray())
        outState.putInt(STATE_ENLARGED, grid.enlarged ?: -1)
    }

    override fun onStart() {
        super.onStart()
        started = true
        val cached = appContainer.settings.current()
        if (cached != null) startWith(cached) else startJob = lifecycleScope.launch { startWith(appContainer.settings.get()) }
    }

    /** Everything onStart needs, once the settings are known. */
    private fun startWith(settings: AppSettings) {
        // Settings can arrive after the viewer has already backed out; starting the tiles then
        // would leave decoders running against the provider with nothing on screen.
        if (isFinishing || isDestroyed || !started) return
        nameTokens = settings.channelNameTokens
        // No tunneling (one tunnel per device), no audio focus fights between tiles, HD at most
        // so two or more decoders keep up, and a modest buffer to keep memory in check.
        config = settings.playerConfig.copy(
            tunneling = false,
            handleAudioFocus = false,
            maxVideoHeight = 720,
            buffer = if (settings.buffer.ordinal > BufferSize.MEDIUM.ordinal) BufferSize.MEDIUM else settings.buffer,
        )
        tiles.forEach { startTile(it) }
        if (mode == Mode.TILES) focusTile(audioTile)
        ticker.removeCallbacks(checkStalls)
        ticker.postDelayed(checkStalls, STALL_TICK_MS)
    }

    override fun onStop() {
        started = false
        startJob?.cancel()
        startJob = null
        ticker.removeCallbacks(checkStalls)
        tiles.forEach { stopTile(it) }
        super.onStop()
    }

    override fun onDestroy() {
        picker.release()
        ticker.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- modes -------------------------------------------------------------------------

    private fun showMode(next: Mode) {
        mode = next
        content.countPicker.visibility = if (next == Mode.COUNT) View.VISIBLE else View.GONE
        content.pickerHost.visibility = if (next == Mode.PICK) View.VISIBLE else View.GONE
        content.tilesHost.visibility = if (next == Mode.TILES) View.VISIBLE else View.GONE
        columns = if (next == Mode.PICK) picker else null
        updateAudio()
    }

    private fun bindCount(b: ItemScreenCountBinding, count: Int) {
        b.number.text = count.toString()
        b.label.text = getString(R.string.multiview_screens, count)
        b.shape.setText(
            when (count) {
                2 -> R.string.multiview_shape_2
                3 -> R.string.multiview_shape_3
                else -> R.string.multiview_shape_4
            },
        )
        b.root.setOnClickListener {
            val most = capacity
            if (most != null && count > most) explainCapacity(most) else startSetup(count)
        }
    }

    /** Playlists with live channels, each with how many of its connections recordings hold. */
    private suspend fun livePlaylists(): List<Pair<PlaylistEntity, Int>> {
        val c = appContainer
        val ids = c.channels.observeGroups().first().map { it.playlistId }.toSet()
        val recording = c.database.recordings().withStatus(listOf(RecordingStatus.RECORDING))
        return c.playlists.getPlaylists().filter { it.id in ids }
            .map { p -> p to recording.count { it.playlistId == p.id } }
    }

    private suspend fun refreshCapacity(): List<Pair<PlaylistEntity, Int>> {
        val lists = livePlaylists()
        capacity = ConnectionBudget.screensAvailable(lists.map { (p, rec) -> p.maxConnections to rec })
        listOf(content.count2 to 2, content.count3 to 3, content.count4 to 4).forEach { (b, n) ->
            b.root.alpha = if (capacity.let { it != null && n > it }) 0.45f else 1f
        }
        return lists
    }

    /**
     * The one-off explanation on opening: every screen is a provider connection. Also says
     * what any declared limits allow. Skipped once the user has asked not to see it again.
     */
    private fun introduce() {
        lifecycleScope.launch {
            val lists = refreshCapacity()
            if (!appContainer.settings.get().multiviewConnectionWarning) return@launch
            val lines = mutableListOf(getString(R.string.multiview_connections_message))
            lists.mapNotNull { (p, _) -> p.maxConnections?.takeIf { it > 0 }?.let { p to it } }.forEach { (p, max) ->
                lines += resources.getQuantityString(R.plurals.multiview_playlist_allows, max, max, p.name)
            }
            capacity?.takeIf { it < MultiviewLayout.MAX_TILES }?.let { lines += capacityText(it) }
            if (isFinishing) return@launch
            AlertDialog.Builder(this@MultiviewActivity)
                .setTitle(R.string.multiview_connections_title)
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(R.string.multiview_got_it, null)
                .setNegativeButton(R.string.multiview_dont_show) { _, _ ->
                    lifecycleScope.launch { appContainer.settings.setMultiviewConnectionWarning(false) }
                }
                .show()
        }
    }

    private fun capacityText(most: Int) =
        if (most == 0) getString(R.string.multiview_no_capacity)
        else resources.getQuantityString(R.plurals.multiview_capacity, most, most)

    /** Refuses a screen count the declared limits can't serve, rather than letting tiles stall. */
    private fun explainCapacity(most: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.multiview_limit_title)
            .setMessage(capacityText(most))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showCountPicker() {
        plan = null
        showMode(Mode.COUNT)
        val first = firstChannel
        content.countHint.setText(R.string.multiview_how_many)
        if (first != null) lifecycleScope.launch {
            appContainer.channels.get(first)?.let { ch ->
                chosen[ch.id] = ch
                content.countHint.text = getString(R.string.multiview_how_many_first, displayName(ch))
            }
        }
        content.count2.root.post { content.count2.root.requestFocus() }
    }

    private fun startSetup(count: Int) {
        lifecycleScope.launch {
            refreshCapacity()
            capacity?.let { most -> if (count > most) return@launch explainCapacity(most) }
            if (loadChoices().isEmpty()) {
                Toast.makeText(this@MultiviewActivity, R.string.multiview_no_channels, Toast.LENGTH_LONG).show()
                return@launch
            }
            val p = SlotPlan(count, firstChannel)
            plan = p
            pickFor(p.firstEmpty() ?: return@launch finishSetup())
        }
    }

    /** Opens the picker for [slot]: playlist (if several), then categories, then channels. */
    private fun pickFor(slot: Int) {
        val p = plan ?: return
        targetSlot = slot
        val wasPicking = mode == Mode.PICK
        showMode(Mode.PICK)
        picker.b.title.text = getString(R.string.multiview_pick_title, slot + 1, p.count)
        renderChips()
        if (!wasPicking) {
            loadPicker()
        } else {
            // Same list, new marks for what is now on which screen.
            renderPickerRows()
        }
    }

    /** One chip per screen in the header: its channel or "empty", the one being chosen lit. */
    private fun renderChips() {
        val p = plan ?: return
        val actions = picker.b.actions
        val chips = p.chips(targetSlot)
        val hadFocus = actions.hasFocus()
        actions.removeAllViews()
        chips.forEach { chip ->
            val name = chip.channelId?.let { id -> chosen[id]?.let(::displayName) } ?: getString(R.string.multiview_slot_empty)
            val button = Button(this, null, 0, R.style.Button_TV_Small).apply {
                text = getString(R.string.multiview_chip, chip.slot + 1, name)
                isActivated = chip.target
                alpha = if (chip.filled || chip.target) 1f else 0.7f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(150)
                setOnClickListener { if (targetSlot != chip.slot) pickFor(chip.slot) }
            }
            actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        if (hadFocus) actions.getChildAt(targetSlot)?.requestFocus()
    }

    private fun loadPicker() {
        lifecycleScope.launch {
            val c = appContainer
            pickerGroups = c.channels.observeGroups().first()
            val ids = pickerGroups.map { it.playlistId }.toSet()
            pickerPlaylists = c.playlists.getPlaylists().filter { it.id in ids }
            // Start where the screen being changed already is, or straight in when there is one playlist.
            val current = plan?.get(targetSlot)?.let { chosen[it]?.playlistId }
            val only = pickerPlaylists.singleOrNull()?.id
            when {
                only != null -> showCategories(only)
                pickerPlaylists.size > 1 && current != null && current in ids -> showCategories(current)
                else -> showPlaylists()
            }
        }
    }

    private val multiplePlaylists get() = pickerPlaylists.size > 1

    /** Step 1 with several playlists: the playlists on the left, their categories on the right. */
    private fun showPlaylists(focusOn: Long? = null) {
        pickerJob?.cancel()
        pickerPlaylist = null
        picker.b.subtitle.setText(R.string.multiview_choose_playlist)
        picker.categories.submit(pickerPlaylists.map { p ->
            Category(KEY_PLAYLIST + p.id, p.name, pickerGroups.filter { it.playlistId == p.id }.sumOf { it.channelCount })
        })
        val key = KEY_PLAYLIST + (focusOn ?: pickerPlaylists.firstOrNull()?.id ?: return)
        picker.categories.activeKey = key
        showPickerChannels(key)
        picker.b.list.post { picker.focusCategories() }
    }

    /** Favourites, all channels and groups of [playlistId], the standard category rows. */
    private fun categoriesOf(playlistId: Long): List<Category> {
        val groups = pickerGroups.filter { it.playlistId == playlistId }
        return listOf(
            Category(KEY_FAVORITES, getString(R.string.group_favorites)),
            Category(KEY_ALL, getString(R.string.group_all), groups.sumOf { it.channelCount }),
        ) + groups.map { Category(it.id.toString(), it.name, it.channelCount) }
    }

    /** Step 2: one playlist's categories on the left, channels on the right. */
    private fun showCategories(playlistId: Long, key: String? = null, focusList: Boolean = false) {
        pickerPlaylist = playlistId
        picker.b.subtitle.text = if (multiplePlaylists) pickerPlaylists.firstOrNull { it.id == playlistId }?.name.orEmpty() else ""
        val cats = categoriesOf(playlistId)
        picker.categories.submit(cats)
        val active = key?.takeIf { k -> cats.any { it.key == k } } ?: KEY_ALL
        picker.categories.activeKey = active
        showPickerChannels(active)
        picker.b.list.post { if (focusList) picker.focusList() else picker.focusCategories() }
    }

    private fun showPickerChannels(key: String) {
        pickerJob?.cancel()
        if (key.startsWith(KEY_PLAYLIST)) {
            // Choosing a playlist: its categories as rows, OK on one opens it.
            val id = key.removePrefix(KEY_PLAYLIST).toLong()
            playlistLevelRows = categoriesOf(id)
            pickerRows = emptyList()
            pickerAdapter.submit(playlistLevelRows.mapIndexed { i, cat ->
                SimpleRow(i.toLong(), cat.name, cat.count?.toString().orEmpty())
            })
            picker.b.empty.visibility = View.GONE
            return
        }
        playlistLevelRows = emptyList()
        val playlist = pickerPlaylist
        pickerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val c = appContainer
                val flow: Flow<List<ChannelEntity>> = when (key) {
                    KEY_FAVORITES -> c.channels.observeFavorites()
                    KEY_ALL -> c.channels.observeAll()
                    else -> c.channels.observeGroup(key.toLong())
                }
                flow.collect { list ->
                    pickerRows = list.filter { it.kind == ContentKind.LIVE && (playlist == null || it.playlistId == playlist) }
                    renderPickerRows()
                }
            }
        }
    }

    private fun onPickerRow(row: SimpleRow) {
        if (pickerPlaylist == null) {
            val playlistKey = picker.categories.activeKey ?: return
            val cat = playlistLevelRows.getOrNull(row.id.toInt()) ?: return
            showCategories(playlistKey.removePrefix(KEY_PLAYLIST).toLong(), cat.key, focusList = true)
            return
        }
        pickerRows.firstOrNull { it.id == row.id }?.let(::onChannelPicked)
    }

    private fun renderPickerRows() {
        if (pickerPlaylist == null) return
        val slots = plan?.channels.orEmpty()
        pickerAdapter.submit(pickerRows.map { ch ->
            val on = slots.indexOf(ch.id)
            SimpleRow(
                ch.id,
                displayName(ch),
                if (on >= 0) getString(R.string.multiview_on_screen, on + 1) else "",
                highlight = on >= 0,
            )
        })
        picker.b.empty.setText(R.string.empty_group)
        picker.b.empty.visibility = if (pickerRows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onChannelPicked(channel: ChannelEntity) {
        val p = plan ?: return
        val slot = targetSlot
        confirmConnection(channel, slot) {
            chosen[channel.id] = channel
            p.fill(slot, channel.id)
            renderChips()
            val next = p.firstEmpty()
            if (next != null) pickFor(next) else finishSetup()
        }
    }

    /** Every screen has a channel: make the tiles match the plan and show them. */
    private fun finishSetup() {
        val p = plan ?: return
        val ids = p.channels.filterNotNull()
        ids.forEachIndexed { i, id ->
            val tile = tiles.getOrNull(i)
            if (tile == null) {
                addTile(id).also { if (isStarted()) startTile(it) }
            } else if (tile.channelId != id) {
                tile.channelId = id
                if (tile.player != null) loadTile(tile)
            }
        }
        while (tiles.size > ids.size) dropTile(tiles.last())
        plan = null
        showMode(Mode.TILES)
        focusTile(targetSlot.coerceIn(0, tiles.size - 1))
    }

    /** Back out of the picker without finishing it. */
    private fun cancelPicking() {
        if (tiles.isEmpty()) showCountPicker()
        else {
            plan = null
            showMode(Mode.TILES)
            focusTile(audioTile)
        }
    }

    private fun isStarted() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    // --- tiles -------------------------------------------------------------------------

    private fun addTile(channelId: Long): Tile {
        val tile = Tile(channelId)
        tile.video.apply {
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        val inset = dp(2)
        tile.frame.addView(tile.video, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
        tile.label.apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            maxLines = 2
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC171B22.toInt())
                cornerRadius = px(4).toFloat()
            }
        }
        tile.frame.addView(tile.label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        tile.status.apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 11f
            maxLines = 2
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC171B22.toInt())
                cornerRadius = px(4).toFloat()
            }
        }
        tile.frame.addView(tile.status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        tile.frame.apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            isLongClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            foreground = focusBorder()
            setOnClickListener { showTileMenu(tile) }
            setOnLongClickListener { showTileMenu(tile); true }
            setOnFocusChangeListener { _, has ->
                if (has) {
                    audioTile = tiles.indexOf(tile).coerceAtLeast(0)
                    updateAudio()
                }
            }
        }
        tiles += tile
        grid.addView(tile.frame)
        return tile
    }

    private fun startTile(tile: Tile) {
        if (tile.player == null) {
            val p = PlayerFactory.create(this, appContainer.http, config)
            p.exo.volume = 0f
            p.exo.addListener(object : Listener {
                override fun onPlayerError(error: PlaybackException) = tileError(tile, error)
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY && tile.error != null) {
                        tile.error = null
                        updateLabel(tile)
                    }
                }
            })
            tile.player = p
            tile.video.player = p.exo
        }
        loadTile(tile)
        updateAudio()
    }

    /** Says when a tile has had no data for a while, and clears as soon as it plays again. */
    private fun sampleStall(tile: Tile) {
        val exo = tile.player?.exo
        val active = exo != null && tile.error == null && exo.playWhenReady && exo.playerError == null &&
            exo.currentMediaItem != null &&
            exo.playbackState.let { it == androidx.media3.common.Player.STATE_BUFFERING || it == androidx.media3.common.Player.STATE_READY }
        val seconds = tile.stall.sample(System.currentTimeMillis(), exo?.currentPosition ?: 0L, active)
        if (seconds == null) {
            tile.status.visibility = View.GONE
        } else {
            tile.status.text = getString(R.string.multiview_stalled, seconds) + "\n" + getString(R.string.multiview_stalled_hint)
            tile.status.visibility = View.VISIBLE
        }
    }

    private fun stopTile(tile: Tile) {
        tile.stall.reset()
        tile.status.visibility = View.GONE
        tile.job?.cancel()
        tile.video.player = null
        tile.player?.release()
        tile.player = null
    }

    private fun loadTile(tile: Tile) {
        tile.stall.reset()
        tile.status.visibility = View.GONE
        tile.job?.cancel()
        tile.player?.exo?.stop()
        tile.error = null
        tile.triedHls = false
        tile.job = lifecycleScope.launch {
            val c = appContainer
            val channel = c.channels.get(tile.channelId)
            tile.channel = channel
            if (channel == null || !c.parental.allowed(channel, c.database)) {
                tile.error = getString(R.string.multiview_unavailable)
                updateLabel(tile)
                return@launch
            }
            updateLabel(tile)
            try {
                val url = c.channels.resolveStreamUrl(channel)
                tile.url = url
                tile.player?.play(url, channel.name, c.channels.userAgentFor(channel), channel.referrer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tile.error = e.message ?: getString(R.string.error_network)
                updateLabel(tile)
            }
        }
    }

    private fun tileError(tile: Tile, error: PlaybackException) {
        val url = tile.url
        val channel = tile.channel
        if (!tile.triedHls && url != null && channel != null && Player.guessMimeType(url) == null) {
            tile.triedHls = true
            lifecycleScope.launch {
                tile.player?.play(url, channel.name, appContainer.channels.userAgentFor(channel), channel.referrer, forceHls = true)
            }
            return
        }
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        tile.error = when (http?.responseCode) {
            null -> getString(R.string.error_network)
            401, 403, 429, 458, 509 -> getString(R.string.multiview_refused, http.responseCode)
            else -> getString(R.string.error_http, http.responseCode)
        }
        updateLabel(tile)
    }

    private fun displayName(ch: ChannelEntity) =
        listOfNotNull(ch.number?.toString(), ChannelNames.display(ch.name, nameTokens)).joinToString("  ")

    private fun updateLabel(tile: Tile) {
        val name = tile.channel?.let(::displayName).orEmpty()
        tile.label.text = if (tile.error != null) "$name\n${tile.error}" else name
        tile.label.setTextColor(getColor(if (tile.error != null) R.color.error else R.color.text_primary))
    }

    /** Sound comes from the focused tile only, and stays on it while the rail has focus. */
    private fun updateAudio() {
        tiles.forEachIndexed { i, t -> t.player?.exo?.volume = if (mode == Mode.TILES && i == audioTile) 1f else 0f }
    }

    private fun focusTile(index: Int) {
        val tile = tiles.getOrNull(index) ?: tiles.firstOrNull() ?: return
        audioTile = tiles.indexOf(tile)
        tile.frame.post { tile.frame.requestFocus() }
        updateAudio()
    }

    private fun focusedIndex() = tiles.indexOfFirst { it.frame.hasFocus() }.takeIf { it >= 0 } ?: audioTile

    // --- actions -----------------------------------------------------------------------

    private fun showTileMenu(tile: Tile) {
        val index = tiles.indexOf(tile)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (tile.error != null) items += getString(R.string.retry) to { loadTile(tile) }
        items += getString(R.string.multiview_change_channel) to { changeChannel(index) }
        if (tiles.size > 1) {
            items += getString(if (grid.enlarged == index) R.string.multiview_reduce else R.string.multiview_enlarge) to {
                grid.enlarged = if (grid.enlarged == index) null else index
            }
        }
        if (tiles.size < MultiviewLayout.MAX_TILES) items += getString(R.string.multiview_add) to { addScreen() }
        if (tiles.size > 1) items += getString(R.string.multiview_remove) to { removeTile(tile) }
        items += getString(R.string.multiview_full_screen) to { fullScreen(tile) }
        AlertDialog.Builder(this)
            .setTitle(tile.channel?.let { ChannelNames.display(it.name, nameTokens) } ?: getString(R.string.multiview))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** A plan matching the tiles on screen, with [extra] empty screens after them. */
    private fun planFromTiles(extra: Int = 0): SlotPlan =
        SlotPlan(tiles.size + extra).also { p -> tiles.forEachIndexed { i, t -> p.fill(i, t.channelId); t.channel?.let { chosen[it.id] = it } } }

    private fun changeChannel(index: Int) {
        plan = planFromTiles()
        pickFor(index)
    }

    private fun addScreen() {
        if (tiles.size >= MultiviewLayout.MAX_TILES) return
        lifecycleScope.launch {
            refreshCapacity()
            capacity?.let { most -> if (tiles.size >= most) return@launch explainCapacity(most) }
            plan = planFromTiles(extra = 1)
            pickFor(tiles.size)
        }
    }

    private fun dropTile(tile: Tile) {
        val index = tiles.indexOf(tile)
        stopTile(tile)
        grid.removeView(tile.frame)
        tiles.remove(tile)
        grid.enlarged = grid.enlarged?.let { e -> if (e == index) null else if (e > index) e - 1 else e }
        audioTile = audioTile.coerceIn(0, (tiles.size - 1).coerceAtLeast(0))
    }

    private fun removeTile(tile: Tile) {
        val index = tiles.indexOf(tile)
        dropTile(tile)
        focusTile(index.coerceAtMost(tiles.size - 1))
    }

    /** Channel up/down on the focused tile, through the same list the player zaps through. */
    private fun zap(delta: Int) {
        val tile = tiles.getOrNull(focusedIndex()) ?: return
        lifecycleScope.launch {
            val list = loadChoices()
            if (list.size < 2) return@launch
            val at = list.indexOfFirst { it.id == tile.channelId }
            tile.channelId = list[Math.floorMod(at + delta, list.size)].id
            loadTile(tile)
        }
    }

    private suspend fun loadChoices(): List<ChannelEntity> = choices ?: run {
        val c = appContainer
        val list = if (channelIds.isNotEmpty()) c.channels.getByIds(channelIds) else c.channels.observeAll().first()
        c.parental.visible(list.filter { it.kind == ContentKind.LIVE }, c.database).also { choices = it }
    }

    /**
     * Refuses a channel whose playlist has no connection left (other screens and running
     * recordings count). Playlists that don't declare a limit are allowed; the opening
     * warning already covers them.
     */
    private fun confirmConnection(channel: ChannelEntity, slot: Int, proceed: () -> Unit) {
        lifecycleScope.launch {
            val c = appContainer
            val playlist = c.playlists.get(channel.playlistId)
            val onPlaylist = plan?.countOn(channel.playlistId, except = slot) { id -> chosen[id]?.playlistId } ?: 0
            val recording = c.database.recordings().withStatus(listOf(RecordingStatus.RECORDING))
                .count { it.playlistId == channel.playlistId }
            when (val budget = ConnectionBudget.decide(playlist?.maxConnections, onPlaylist + recording)) {
                is Budget.Full -> AlertDialog.Builder(this@MultiviewActivity)
                    .setTitle(R.string.multiview_limit_title)
                    .setMessage(resources.getQuantityString(R.plurals.multiview_limit_message, budget.limit, budget.limit, playlist?.name.orEmpty()))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                Budget.Unknown, is Budget.Allowed -> proceed()
            }
        }
    }

    /** One channel full screen: back to the player we came from, or a new one. */
    private fun fullScreen(tile: Tile) {
        if (fromPlayer) return exitTo(tile)
        val ids = tiles.map { it.channelId }.toLongArray()
        tiles.forEach { stopTile(it) }
        startActivity(PlayerActivity.intent(this, tile.channelId, ids, ids.indexOf(tile.channelId)))
        finish()
    }

    /** Back to the single player, continuing with [tile]'s channel. */
    private fun exitTo(tile: Tile?) {
        appContainer.playback.handoffChannelId = (tile ?: tiles.getOrNull(focusedIndex()))?.channelId ?: firstChannel
        finish()
    }

    /** Back steps out one stage: list -> categories -> tiles or screen count -> out. */
    override fun onSectionBack(): Boolean {
        if (!rail.hasFocus()) {
            when (mode) {
                Mode.PICK -> {
                    if (picker.rightHasFocus()) return picker.focusCategories()
                    val playlist = pickerPlaylist
                    if (playlist != null && multiplePlaylists) {
                        showPlaylists(focusOn = playlist)
                        return true
                    }
                    cancelPicking()
                    return true
                }
                Mode.TILES, Mode.COUNT -> Unit
            }
        }
        if (fromPlayer) exitTo(null) else goLive()
        return true
    }

    override fun focusContent(): Boolean = when (mode) {
        Mode.COUNT -> content.count2.root.requestFocus()
        Mode.PICK -> picker.focusCategories()
        Mode.TILES -> { focusTile(audioTile); true }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && mode == Mode.TILES && !rail.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) tiles.getOrNull(focusedIndex())?.let { showTileMenu(it) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == Mode.TILES) when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun focusBorder() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(3), getColor(R.color.accent))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(1), getColor(R.color.surface_raised))
        })
    }

    /** For layout params and padding, which screen scaling enlarges on its own. */
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** For sizes screen scaling never sees: the grid's spacing and drawn borders. */
    private fun px(v: Int) = (uiScale.dp(v) * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_CHANNEL = "channel_id"
        private const val EXTRA_LIST = "channel_list"
        private const val STATE_TILES = "tiles"
        private const val STATE_ENLARGED = "enlarged"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ALL = "all"
        private const val KEY_PLAYLIST = "playlist:"
        private const val STALL_TICK_MS = 1_000L

        /** From the player: [channelId] fills screen 1, the rest are picked. */
        fun intent(context: Context, channelId: Long, list: LongArray): Intent =
            Intent(context, MultiviewActivity::class.java)
                .putExtra(EXTRA_CHANNEL, channelId)
                .putExtra(EXTRA_LIST, list)
    }
}
