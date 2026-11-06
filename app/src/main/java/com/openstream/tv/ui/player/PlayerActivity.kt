package com.openstream.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import com.openstream.tv.data.settings.DisplayMode
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import coil.load
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.databinding.ActivityPlayerBinding
import com.openstream.tv.player.Player
import com.openstream.tv.player.PlayerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: Player? = null

    private var channelIds: LongArray = LongArray(0)
    private var index = 0
    private var current: ChannelEntity? = null
    private var lastUrl: String? = null
    private var triedHls = false
    private var loadJob: Job? = null
    private var watchStartedAt = 0L
    private val catchup get() = intent.hasExtra(EXTRA_ARCHIVE_START)
    private var archivePosition = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { binding.banner.visibility = View.GONE }

    private var nameTokens = ""
    private fun displayName(channel: com.openstream.tv.data.db.ChannelEntity) =
        com.openstream.tv.data.settings.ChannelNames.display(channel.name, nameTokens)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.channelOverlay.layoutManager = LinearLayoutManager(this)
        archivePosition = savedInstanceState?.getLong("archive_position") ?: 0L
        val requested = intent.getLongExtra(EXTRA_CHANNEL_ID, -1)
        channelIds = intent.getLongArrayExtra(EXTRA_CHANNEL_LIST) ?: longArrayOf(requested)
        index = savedInstanceState?.getInt(STATE_INDEX)
            ?: intent.getIntExtra(EXTRA_POSITION, channelIds.indexOf(requested)).coerceIn(0, (channelIds.size - 1).coerceAtLeast(0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
        outState.putLong("archive_position", player?.exo?.currentPosition ?: archivePosition)
    }

    override fun onStart() {
        super.onStart()
        val settings = runBlocking { appContainer.settings.get() }
        nameTokens = settings.channelNameTokens
        applyDisplayMode(settings.displayMode)
        val p = PlayerFactory.create(this, appContainer.http, settings.playerConfig)
        p.exo.addListener(object : Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    binding.errorBox.visibility = View.GONE
                    // Only count time once the stream actually plays; failed channels aren't "watched".
                    if (watchStartedAt == 0L) watchStartedAt = SystemClock.elapsedRealtime()
                }
            }
        })
        binding.playerView.player = p.exo
        player = p
        loadChannel(index)
    }

    override fun onStop() {
        if (catchup) archivePosition = player?.exo?.currentPosition ?: archivePosition
        recordWatch()
        loadJob?.cancel()
        binding.playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun loadChannel(i: Int) {
        if (channelIds.isEmpty()) return finish()
        recordWatch()
        index = i
        binding.errorBox.visibility = View.GONE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val container = appContainer
            val stored = container.channels.get(channelIds[i]) ?: return@launch
            val channel = try {
                val episode = intent.getLongExtra("episode_id", -1)
                if (episode >= 0) container.vod.episode(stored, episode) else stored
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError("Unable to open episode"); return@launch }
            current = channel
            triedHls = false
            showBanner(channel, null)

            val isVod = channel.kind != ContentKind.LIVE
            binding.playerView.useController = isVod || catchup

            try {
                val url = if (catchup) container.channels.resolveCatchupUrl(channel,
                    intent.getLongExtra(EXTRA_ARCHIVE_START, 0), intent.getLongExtra(EXTRA_ARCHIVE_STOP, 0))
                else container.channels.resolveStreamUrl(channel)
                lastUrl = url
                player?.play(url, channel.name, container.channels.userAgentFor(channel), channel.referrer)
                val resume = if (catchup) archivePosition else if (isVod && !intent.getBooleanExtra("from_beginning", false))
                    container.database.history().get(channel.playlistId, channel.channelKey)?.positionMs ?: 0L else 0L
                if (resume > 0) player?.exo?.seekTo(resume)
                intent.removeExtra("from_beginning")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message)
            }

            val now = container.epg.currentProgram(channel.tvgId)
            if (current?.id == channel.id) showBanner(channel, if (catchup) intent.getStringExtra(EXTRA_ARCHIVE_TITLE) else now?.title)
        }
    }

    private fun handleError(error: PlaybackException) {
        val url = lastUrl
        val ch = current
        // Extensionless URLs are often HLS underneath; give that one more go before giving up.
        if (!triedHls && url != null && ch != null && Player.guessMimeType(url) == null) {
            triedHls = true
            val position = player?.exo?.currentPosition ?: 0L
            lifecycleScope.launch {
                player?.play(url, ch.name, appContainer.channels.userAgentFor(ch), ch.referrer, forceHls = true)
                if ((ch.kind != ContentKind.LIVE || catchup) && position > 0) player?.exo?.seekTo(position)
            }
            return
        }
        showError(describe(error))
    }

    private fun describe(error: PlaybackException): String {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return when {
            http != null -> getString(R.string.error_http, http.responseCode)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.error_timeout)
            error.errorCode in 2000..2999 -> getString(R.string.error_network)
            error.errorCode in 3000..3999 -> getString(R.string.error_format)
            error.errorCode in 4000..4999 -> getString(R.string.error_decoder)
            else -> error.errorCodeName
        }
    }

    private fun showError(detail: String?) {
        binding.errorDetail.text = detail.orEmpty()
        binding.errorBox.visibility = View.VISIBLE
    }

    private fun showBanner(channel: ChannelEntity, program: String?) {
        binding.bannerNumber.text = channel.number?.toString().orEmpty()
        binding.bannerName.text = displayName(channel)
        binding.bannerProgram.text = program.orEmpty()
        binding.bannerProgram.visibility = if (program.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.bannerClock.text = DateFormat.getTimeFormat(this).format(Date())
        binding.bannerLogo.setImageDrawable(null)
        if (!channel.logoUrl.isNullOrBlank()) binding.bannerLogo.load(channel.logoUrl)
        binding.banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, BANNER_MS)
    }

    private fun recordWatch() {
        val ch = current ?: return
        if (watchStartedAt == 0L) return
        val watched = SystemClock.elapsedRealtime() - watchStartedAt
        watchStartedAt = 0L
        // Ignore quick zaps; they're not really "watched".
        if (watched < 5_000 && ch.kind == ContentKind.LIVE) return
        val position = if (player?.exo?.playbackState == androidx.media3.common.Player.STATE_ENDED || ch.kind == ContentKind.LIVE) 0L else player?.exo?.currentPosition ?: 0L
        appContainer.appScope.launch { appContainer.channels.recordWatched(ch, watched, position) }
    }

    private fun zap(delta: Int) {
        if (catchup || current?.kind != ContentKind.LIVE || channelIds.size < 2) return
        val next = Math.floorMod(index + delta, channelIds.size)
        loadChannel(next)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showOptions()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (binding.errorBox.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            loadChannel(index)
            return true
        }
        if (binding.channelOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                binding.channelOverlay.visibility = View.GONE
                binding.playerView.requestFocus()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        val vod = catchup || (current?.kind?.let { it != ContentKind.LIVE } ?: false)
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(1); return true }
        }
        if (!vod) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannels(); return true }
                KeyEvent.KEYCODE_INFO -> { current?.let { showBanner(it, null) }; return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChannels() {
        lifecycleScope.launch {
            val channels = appContainer.channels.getByIds(channelIds).filter { it.kind == ContentKind.LIVE }
            binding.channelOverlay.adapter = object : RecyclerView.Adapter<ChannelHolder>() {
                override fun getItemCount() = channels.size
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = ChannelHolder(TextView(parent.context).apply {
                    isFocusable = true; isClickable = true; textSize = 20f
                    setPadding(20, 18, 20, 18); setBackgroundResource(R.drawable.bg_focusable)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                })
                override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
                    val channel = channels[position]
                    holder.label.text = "${channel.number ?: ""}  ${displayName(channel)}"
                    holder.label.isActivated = channel.id == current?.id
                    holder.label.setOnClickListener {
                        binding.channelOverlay.visibility = View.GONE
                        binding.playerView.requestFocus()
                        loadChannel(channelIds.indexOf(channel.id))
                    }
                }
            }
            binding.banner.visibility = View.GONE
            binding.channelOverlay.visibility = View.VISIBLE
            val selected = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            binding.channelOverlay.scrollToPosition(selected)
            binding.channelOverlay.post {
                binding.channelOverlay.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
                    ?: binding.channelOverlay.requestFocus()
            }
        }
    }

    private class ChannelHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("Playback options")
            .setItems(arrayOf("Audio tracks", "Subtitles", "Display mode")) { _, which ->
                when (which) { 0 -> pickTrack(C.TRACK_TYPE_AUDIO); 1 -> pickTrack(C.TRACK_TYPE_TEXT); 2 -> pickDisplayMode() }
            }.show()
    }

    private fun pickTrack(type: Int) {
        val exo = player?.exo ?: return
        val tracks = exo.currentTracks.groups.filter { it.type == type }
        val choices = tracks.flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val labels = listOf(if (type == C.TRACK_TYPE_TEXT) "Off" else "Automatic") + choices.mapIndexed { index, (group, track) ->
            val format = group.getTrackFormat(track)
            listOfNotNull(format.label, format.language, format.channelCount.takeIf { it > 0 }?.let { "$it channels" })
                .joinToString(" · ").ifBlank { "Track ${index + 1}" }
        }
        val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
        val selected = if (disabled) 0 else choices.indexOfFirst { (group, track) -> group.isTrackSelected(track) }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio tracks")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && which == 0)
                if (which > 0) {
                    val (group, track) = choices[which - 1]
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                }
                exo.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.show()
    }

    private fun pickDisplayMode() {
        lifecycleScope.launch {
            val selected = appContainer.settings.get().displayMode.ordinal
            AlertDialog.Builder(this@PlayerActivity).setTitle("Display mode")
                .setSingleChoiceItems(arrayOf("Normal", "Full", "Zoom", "Reduced"), selected) { dialog, which ->
                    val mode = DisplayMode.entries[which]
                    applyDisplayMode(mode)
                    lifecycleScope.launch { appContainer.settings.setDisplayMode(mode) }
                    dialog.dismiss()
                }.show()
        }
    }

    private fun applyDisplayMode(mode: DisplayMode) {
        binding.playerView.resizeMode = when (mode) {
            DisplayMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            DisplayMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val scale = if (mode == DisplayMode.REDUCED) 0.8f else 1f
        binding.playerView.scaleX = scale
        binding.playerView.scaleY = scale
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ARCHIVE_START = "archive_start"
        private const val EXTRA_ARCHIVE_STOP = "archive_stop"
        private const val EXTRA_ARCHIVE_TITLE = "archive_title"

        fun catchupIntent(context: Context, channelId: Long, start: Long, stop: Long, title: String): Intent =
            intent(context, channelId, longArrayOf(channelId), 0)
                .putExtra(EXTRA_ARCHIVE_START, start)
                .putExtra(EXTRA_ARCHIVE_STOP, stop)
                .putExtra(EXTRA_ARCHIVE_TITLE, title)

        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_LIST = "channel_list"
        private const val EXTRA_POSITION = "position"
        private const val STATE_INDEX = "index"
        private const val BANNER_MS = 4_000L

        fun intent(context: Context, channelId: Long, list: LongArray, position: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_LIST, list)
                .putExtra(EXTRA_POSITION, position)
    }
}
