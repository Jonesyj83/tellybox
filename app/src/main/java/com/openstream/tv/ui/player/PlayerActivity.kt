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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val p = PlayerFactory.create(this, appContainer.http, runBlocking { appContainer.settings.get() }.playerConfig)
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
            val channel = container.channels.get(channelIds[i]) ?: return@launch
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
                if (catchup && archivePosition > 0) player?.exo?.seekTo(archivePosition)
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
            lifecycleScope.launch {
                player?.play(url, ch.name, appContainer.channels.userAgentFor(ch), ch.referrer, forceHls = true)
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
        binding.bannerName.text = channel.name
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
        if (watched < 5_000) return
        val position = if (ch.kind == ContentKind.LIVE) 0L else player?.exo?.currentPosition ?: 0L
        appContainer.appScope.launch { appContainer.channels.recordWatched(ch, watched, position) }
    }

    private fun zap(delta: Int) {
        if (catchup || channelIds.size < 2) return
        val next = Math.floorMod(index + delta, channelIds.size)
        loadChannel(next)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (binding.errorBox.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            loadChannel(index)
            return true
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                    if (binding.errorBox.visibility == View.VISIBLE) loadChannel(index)
                    else current?.let { ch ->
                        lifecycleScope.launch {
                            showBanner(ch, appContainer.epg.currentProgram(ch.tvgId)?.title)
                        }
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
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
