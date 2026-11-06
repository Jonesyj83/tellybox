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
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.data.db.RecordingStatus
import com.openstream.tv.data.settings.ChannelNames
import com.openstream.tv.player.BufferSize
import com.openstream.tv.player.Player
import com.openstream.tv.player.PlayerConfig
import com.openstream.tv.player.PlayerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Several live channels at once. Each tile has its own player; only the focused tile is
 * audible. Players live only while this screen is visible, so tiles never hold provider
 * connections in the background.
 */
@OptIn(UnstableApi::class)
class MultiviewActivity : AppCompatActivity() {

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

    private lateinit var root: FrameLayout
    private val tiles = mutableListOf<Tile>()
    private var enlarged: Int? = null
    private var channelIds = LongArray(0)
    private var choices: List<ChannelEntity>? = null
    private var config = PlayerConfig()
    private var nameTokens = ""
    private var askForSecond = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(root)
        channelIds = intent.getLongArrayExtra(EXTRA_LIST) ?: LongArray(0)
        val restored = savedInstanceState?.getLongArray(STATE_TILES)
        val initial = restored ?: longArrayOf(intent.getLongExtra(EXTRA_CHANNEL, -1))
        initial.filter { it >= 0 }.forEach { addTile(it, start = false) }
        enlarged = savedInstanceState?.getInt(STATE_ENLARGED, -1)?.takeIf { it in tiles.indices }
        if (tiles.isEmpty()) return finish()
        askForSecond = savedInstanceState == null
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { layoutTiles() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_TILES, tiles.map { it.channelId }.toLongArray())
        outState.putInt(STATE_ENLARGED, enlarged ?: -1)
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
        layoutTiles()
        (tiles.getOrNull(enlarged ?: 0) ?: tiles.first()).frame.requestFocus()
        if (askForSecond) {
            askForSecond = false
            root.post { addScreen() }
        }
    }

    override fun onStop() {
        tiles.forEach { stopTile(it) }
        super.onStop()
    }

    // --- tiles -------------------------------------------------------------------------

    private fun addTile(channelId: Long, start: Boolean = true): Tile {
        val tile = Tile(channelId)
        tile.video.apply {
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        val inset = dp(3)
        tile.frame.addView(tile.video, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
        tile.label.apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            maxLines = 2
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0xCC171B22.toInt())
        }
        tile.frame.addView(tile.label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        tile.frame.apply {
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            foreground = focusBorder()
            setOnClickListener { showTileMenu(tile) }
            setOnFocusChangeListener { _, has -> if (has) updateAudio() }
        }
        tiles += tile
        root.addView(tile.frame)
        if (start) {
            startTile(tile)
            layoutTiles()
        }
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

    private fun updateLabel(tile: Tile) {
        val name = tile.channel?.let { ch ->
            listOfNotNull(ch.number?.toString(), ChannelNames.display(ch.name, nameTokens)).joinToString("  ")
        }.orEmpty()
        tile.label.text = if (tile.error != null) "$name\n${tile.error}" else name
        tile.label.setTextColor(getColor(if (tile.error != null) R.color.error else R.color.text_primary))
    }

    /** Sound comes from the focused tile only. */
    private fun updateAudio() {
        tiles.forEach { it.player?.exo?.volume = if (it.frame.hasFocus()) 1f else 0f }
    }

    private fun layoutTiles() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0 || tiles.isEmpty()) return
        val boxes = MultiviewLayout.boxes(tiles.size, enlarged)
        tiles.forEachIndexed { i, tile ->
            val box = boxes[i]
            tile.frame.layoutParams = FrameLayout.LayoutParams(
                ((box.right - box.left) * w).toInt(), ((box.bottom - box.top) * h).toInt(),
            ).apply {
                leftMargin = (box.left * w).toInt()
                topMargin = (box.top * h).toInt()
            }
            // Small side tiles get a smaller label so it doesn't cover the picture.
            tile.label.textSize = if (box.right - box.left < 0.4f) 11f else 14f
        }
    }

    private fun focusedIndex() = tiles.indexOfFirst { it.frame.hasFocus() }.takeIf { it >= 0 } ?: 0

    // --- actions -----------------------------------------------------------------------

    private fun showTileMenu(tile: Tile) {
        val index = tiles.indexOf(tile)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (tile.error != null) items += getString(R.string.retry) to { loadTile(tile) }
        items += getString(R.string.multiview_change_channel) to { pickChannel(tile) }
        if (tiles.size > 1) {
            items += getString(if (enlarged == index) R.string.multiview_reduce else R.string.multiview_enlarge) to {
                enlarged = if (enlarged == index) null else index
                layoutTiles()
            }
        }
        if (tiles.size < MultiviewLayout.MAX_TILES) items += getString(R.string.multiview_add) to { addScreen() }
        if (tiles.size > 1) items += getString(R.string.multiview_remove) to { removeTile(tile) }
        items += getString(R.string.multiview_full_screen) to { exitTo(tile) }
        AlertDialog.Builder(this)
            .setTitle(tile.channel?.let { ChannelNames.display(it.name, nameTokens) } ?: getString(R.string.multiview))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun addScreen() {
        if (tiles.size >= MultiviewLayout.MAX_TILES) return
        chooseChannel(getString(R.string.multiview_add), null) { channel ->
            confirmConnection(channel, replacing = null) {
                val tile = addTile(channel.id)
                tile.frame.requestFocus()
            }
        }
    }

    private fun pickChannel(tile: Tile) {
        chooseChannel(getString(R.string.multiview_change_channel), tile.channelId) { channel ->
            confirmConnection(channel, replacing = tile) {
                tile.channelId = channel.id
                loadTile(tile)
            }
        }
    }

    private fun removeTile(tile: Tile) {
        val index = tiles.indexOf(tile)
        stopTile(tile)
        root.removeView(tile.frame)
        tiles.remove(tile)
        enlarged = enlarged?.let { e -> if (e == index) null else if (e > index) e - 1 else e }
        layoutTiles()
        tiles.getOrNull(index.coerceAtMost(tiles.size - 1))?.frame?.requestFocus()
        updateAudio()
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
        c.parental.visible(c.channels.getByIds(channelIds).filter { it.kind == ContentKind.LIVE }, c.database)
            .also { choices = it }
    }

    private fun chooseChannel(title: String, selectedId: Long?, chosen: (ChannelEntity) -> Unit) {
        lifecycleScope.launch {
            val list = loadChoices()
            if (list.isEmpty()) return@launch
            val labels = list.map { ch ->
                listOfNotNull(ch.number?.toString(), ChannelNames.display(ch.name, nameTokens)).joinToString("  ")
            }
            val selected = list.indexOfFirst { it.id == selectedId }
            AlertDialog.Builder(this@MultiviewActivity).setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { d, which ->
                    d.dismiss()
                    chosen(list[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
                .apply { setOnShowListener { if (selected > 0) listView?.setSelection(selected) } }
                .show()
        }
    }

    /** Warns before opening more streams from one provider than its account allows. */
    private fun confirmConnection(channel: ChannelEntity, replacing: Tile?, proceed: () -> Unit) {
        lifecycleScope.launch {
            val c = appContainer
            val playlist = c.playlists.get(channel.playlistId)
            val tilesOnPlaylist = tiles.count { it !== replacing && it.channel?.playlistId == channel.playlistId }
            val recording = c.database.recordings().withStatus(listOf(RecordingStatus.RECORDING))
                .count { it.playlistId == channel.playlistId }
            val limit = ConnectionBudget.exceededLimit(playlist?.maxConnections, tilesOnPlaylist + recording)
            if (limit == null) return@launch proceed()
            AlertDialog.Builder(this@MultiviewActivity)
                .setTitle(R.string.multiview_limit_title)
                .setMessage(resources.getQuantityString(R.plurals.multiview_limit_message, limit, limit, playlist?.name.orEmpty()))
                .setPositiveButton(R.string.multiview_add_anyway) { _, _ -> proceed() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Back to the single player, continuing with [tile]'s channel. */
    private fun exitTo(tile: Tile?) {
        appContainer.playback.handoffChannelId = (tile ?: tiles.getOrNull(focusedIndex()))?.channelId
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) tiles.getOrNull(focusedIndex())?.let { showTileMenu(it) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { exitTo(null); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun focusBorder() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(4), getColor(R.color.accent))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), getColor(R.color.surface_raised))
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_CHANNEL = "channel_id"
        private const val EXTRA_LIST = "channel_list"
        private const val STATE_TILES = "tiles"
        private const val STATE_ENLARGED = "enlarged"

        fun intent(context: Context, channelId: Long, list: LongArray): Intent =
            Intent(context, MultiviewActivity::class.java)
                .putExtra(EXTRA_CHANNEL, channelId)
                .putExtra(EXTRA_LIST, list)
    }
}
