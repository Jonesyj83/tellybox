package com.openstream.tv.ui.multiview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.data.db.RecordingStatus
import com.openstream.tv.data.settings.ChannelNames
import com.openstream.tv.databinding.ItemScreenCountBinding
import com.openstream.tv.databinding.MultiviewContentBinding
import com.openstream.tv.player.BufferSize
import com.openstream.tv.player.Player
import com.openstream.tv.player.PlayerConfig
import com.openstream.tv.player.PlayerFactory
import com.openstream.tv.ui.chrome.Category
import com.openstream.tv.ui.chrome.Section
import com.openstream.tv.ui.chrome.SectionActivity
import com.openstream.tv.ui.chrome.SimpleRow
import com.openstream.tv.ui.chrome.SimpleRowAdapter
import com.openstream.tv.ui.chrome.TwoColumn
import com.openstream.tv.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Several live channels at once, beside the shared rail. Opening it asks for 2, 3 or 4
 * screens, then each channel is picked from the usual categories and channel list. Each
 * tile has its own player; only the focused tile is audible. Players live only while this
 * screen is visible, so tiles never hold provider connections in the background.
 */
@OptIn(UnstableApi::class)
class MultiviewActivity : SectionActivity() {

    private enum class Mode { COUNT, PICK, TILES }

    private inner class Tile(var channelId: Long) {
        val frame = FrameLayout(this@MultiviewActivity)
        val video = PlayerView(this@MultiviewActivity)
        val label = TextView(this@MultiviewActivity)
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
    private val pickerAdapter = SimpleRowAdapter { row -> pickerRows.firstOrNull { it.id == row.id }?.let(::onChannelPicked) }

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
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_TILES, tiles.map { it.channelId }.toLongArray())
        outState.putInt(STATE_ENLARGED, grid.enlarged ?: -1)
    }

    override fun onStart() {
        super.onStart()
        val settings = runBlocking { appContainer.settings.get() }
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
    }

    override fun onStop() {
        tiles.forEach { stopTile(it) }
        super.onStop()
    }

    override fun onDestroy() {
        picker.release()
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
        b.root.setOnClickListener { startSetup(count) }
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
            if (loadChoices().isEmpty()) {
                Toast.makeText(this@MultiviewActivity, R.string.multiview_no_channels, Toast.LENGTH_LONG).show()
                return@launch
            }
            val p = SlotPlan(count, firstChannel)
            plan = p
            pickFor(p.firstEmpty() ?: return@launch finishSetup())
        }
    }

    /** Opens the categories + channels picker for [slot]. */
    private fun pickFor(slot: Int) {
        val p = plan ?: return
        targetSlot = slot
        val wasPicking = mode == Mode.PICK
        showMode(Mode.PICK)
        picker.b.title.text = getString(R.string.multiview_pick_title, slot + 1, p.count)
        picker.b.subtitle.text = (0 until p.count).joinToString("   ") { i ->
            getString(R.string.multiview_slot, i + 1, p[i]?.let { id -> chosen[id]?.let(::displayName) } ?: getString(R.string.multiview_slot_empty))
        }
        if (!wasPicking) {
            loadPickerCategories()
        } else {
            // Same list, new marks for what is now on which screen.
            renderPickerRows()
        }
    }

    private fun loadPickerCategories() {
        lifecycleScope.launch {
            val groups = appContainer.channels.observeGroups().first()
            val cats = listOf(
                Category(KEY_FAVORITES, getString(R.string.group_favorites)),
                Category(KEY_ALL, getString(R.string.group_all), groups.sumOf { it.channelCount }),
            ) + groups.map { Category(it.id.toString(), it.name, it.channelCount) }
            picker.categories.submit(cats)
            val key = picker.categories.activeKey ?: KEY_ALL
            picker.categories.activeKey = key
            showPickerChannels(key)
            picker.b.list.post { picker.focusCategories() }
        }
    }

    private fun showPickerChannels(key: String) {
        pickerJob?.cancel()
        pickerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val c = appContainer
                val flow: Flow<List<ChannelEntity>> = when (key) {
                    KEY_FAVORITES -> c.channels.observeFavorites()
                    KEY_ALL -> c.channels.observeAll()
                    else -> c.channels.observeGroup(key.toLong())
                }
                flow.collect { list ->
                    pickerRows = list.filter { it.kind == ContentKind.LIVE }
                    renderPickerRows()
                }
            }
        }
    }

    private fun renderPickerRows() {
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

    private fun stopTile(tile: Tile) {
        tile.job?.cancel()
        tile.video.player = null
        tile.player?.release()
        tile.player = null
    }

    private fun loadTile(tile: Tile) {
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
        plan = planFromTiles(extra = 1)
        pickFor(tiles.size)
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

    /** Warns before opening more streams from one provider than its account allows. */
    private fun confirmConnection(channel: ChannelEntity, slot: Int, proceed: () -> Unit) {
        lifecycleScope.launch {
            val c = appContainer
            val playlist = c.playlists.get(channel.playlistId)
            val others = plan?.channels.orEmpty().filterIndexed { i, id -> i != slot && id != null }
            val onPlaylist = others.count { id -> chosen[id]?.playlistId == channel.playlistId }
            val recording = c.database.recordings().withStatus(listOf(RecordingStatus.RECORDING))
                .count { it.playlistId == channel.playlistId }
            val limit = ConnectionBudget.exceededLimit(playlist?.maxConnections, onPlaylist + recording)
            if (limit == null) return@launch proceed()
            AlertDialog.Builder(this@MultiviewActivity)
                .setTitle(R.string.multiview_limit_title)
                .setMessage(resources.getQuantityString(R.plurals.multiview_limit_message, limit, limit, playlist?.name.orEmpty()))
                .setPositiveButton(R.string.multiview_add_anyway) { _, _ -> proceed() }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

        /** From the player: [channelId] fills screen 1, the rest are picked. */
        fun intent(context: Context, channelId: Long, list: LongArray): Intent =
            Intent(context, MultiviewActivity::class.java)
                .putExtra(EXTRA_CHANNEL, channelId)
                .putExtra(EXTRA_LIST, list)
    }
}
