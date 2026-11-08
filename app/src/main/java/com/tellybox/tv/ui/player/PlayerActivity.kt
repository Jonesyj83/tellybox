package com.tellybox.tv.ui.player

import com.tellybox.tv.ui.parental.requestPin
import android.content.Context
import android.content.Intent
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.annotation.RequiresApi
import com.tellybox.tv.player.PipAspect
import com.tellybox.tv.ui.multiview.MultiviewActivity
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
import com.tellybox.tv.data.settings.DisplayMode
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import coil.load
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.databinding.ActivityPlayerBinding
import com.tellybox.tv.player.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : com.tellybox.tv.ui.ScaledActivity(), com.tellybox.tv.player.Playback.Holder,
    com.tellybox.tv.player.SleepSchedule.Listener {

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
    /** Opened from the home preview: on the way out, leave the stream running for it. */
    private val returnToPreview get() = intent.getBooleanExtra(EXTRA_RETURN_TO_PREVIEW, false)
    /** Playing a saved recording rather than a channel. */
    private val fileMode get() = intent.hasExtra(EXTRA_FILE)
    private var archivePosition = 0L

    private val handler = Handler(Looper.getMainLooper())
    /** The banner and its buttons are one overlay: they appear and disappear together. */
    private val hideBanner = Runnable {
        binding.banner.visibility = View.GONE
        binding.recordButton.visibility = View.GONE
        binding.sleepButton.visibility = View.GONE
    }

    private var nameTokens = ""
    private fun displayName(channel: com.tellybox.tv.data.db.ChannelEntity) =
        com.tellybox.tv.data.settings.ChannelNames.display(channel.name, nameTokens)

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
        binding.recordButton.setOnClickListener { instantRecord() }
        binding.sleepButton.setOnClickListener { showSleep() }
        appContainer.sleep.addListener(this)
        updateSleep()
    }

    /** The sleep timer went off: playback is already stopped, so just leave. */
    override fun onSleep() {
        player?.exo?.removeListener(listener)
        binding.playerView.player = null
        player = null
        loadJob?.cancel()
        android.widget.Toast.makeText(this, R.string.sleep_fired, android.widget.Toast.LENGTH_LONG).show()
        if (!isFinishing) finish()
    }

    override fun onSleepChanged() = updateSleep()

    /** Countdown on the Sleep button and in the banner. */
    private fun updateSleep() {
        val left = appContainer.sleep.remainingMinutes()
        binding.sleepButton.text = if (left != null) getString(R.string.action_sleep_left, left) else getString(R.string.action_sleep)
        binding.bannerSleep.text = left?.let { getString(R.string.sleep_banner, it) }.orEmpty()
        binding.bannerSleep.visibility = if (left != null) View.VISIBLE else View.GONE
    }

    /** Only live channels can be recorded; the button is hidden for catch-up, VOD and files. */
    private fun updateButtons() {
        val pip = inPip()
        val live = !catchup && !fileMode && current?.kind == ContentKind.LIVE
        binding.cornerButtons.visibility = if (pip) View.GONE else View.VISIBLE
        binding.recordButton.visibility = if (live) View.VISIBLE else View.GONE
        binding.recordButton.isFocusable = !pip
        binding.sleepButton.isFocusable = !pip
    }

    private fun showSleep() {
        val sleep = appContainer.sleep
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val channel = current
            // "End of programme" needs a live programme with a known end.
            val program = if (channel != null && !catchup && !fileMode && channel.kind == ContentKind.LIVE)
                appContainer.epg.currentProgram(channel.id.toString(), now)?.takeIf { it.stop > now } else null
            val items = mutableListOf<Pair<String, () -> Unit>>()
            sleep.remainingMinutes()?.let { left ->
                items += getString(R.string.sleep_cancel, left) to {
                    sleep.cancel()
                    android.widget.Toast.makeText(this@PlayerActivity, R.string.sleep_cancelled, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            com.tellybox.tv.player.SleepTimer.CHOICES.forEach { m ->
                items += resources.getQuantityString(R.plurals.minutes, m, m) to {
                    sleep.start(m)
                    android.widget.Toast.makeText(this@PlayerActivity, getString(R.string.sleep_set, m), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            if (program != null) {
                items += getString(R.string.sleep_end_of_programme, DateFormat.getTimeFormat(this@PlayerActivity).format(Date(program.stop))) to {
                    if (sleep.startUntil(program.stop)) {
                        android.widget.Toast.makeText(this@PlayerActivity, R.string.sleep_set_programme, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            AlertDialog.Builder(this@PlayerActivity).setTitle(R.string.sleep_title)
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                .show()
        }
    }

    /** Hands the stream to another app, and frees our connection to it once that app opens. */
    private fun openExternal() {
        val channel = current ?: return
        lifecycleScope.launch {
            val opened = if (catchup) ExternalPlayer.open(this@PlayerActivity, channel,
                intent.getStringExtra(EXTRA_ARCHIVE_TITLE),
                intent.getLongExtra(EXTRA_ARCHIVE_START, 0), intent.getLongExtra(EXTRA_ARCHIVE_STOP, 0))
            else ExternalPlayer.open(this@PlayerActivity, channel, displayName(channel))
            if (opened) {
                if (catchup || channel.kind != ContentKind.LIVE) archivePosition = player?.exo?.currentPosition ?: archivePosition
                recordWatch()
                player?.exo?.stop()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
        outState.putLong("archive_position", player?.exo?.currentPosition ?: archivePosition)
    }

    private val listener = object : Listener {
        override fun onPlayerError(error: PlaybackException) = handleError(error)
        override fun onPlaybackStateChanged(state: Int) {
            if (state == androidx.media3.common.Player.STATE_READY) {
                reconnects = 0
                stallSince = 0L
                binding.errorBox.visibility = View.GONE
                // Only count time once the stream actually plays; failed channels aren't "watched".
                if (watchStartedAt == 0L) watchStartedAt = SystemClock.elapsedRealtime()
            }
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updatePip()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
    }
    private var backgroundPlayback = true

    // Live streams from a panel drop out now and then, usually every 15 to 30 minutes.
    // Instead of leaving the viewer on a dead picture, try to bring the stream back.
    private var stallSince = 0L
    private var reconnects = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val exo = player?.exo
            if (exo != null && !fileMode) {
                val buffering = exo.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                if (buffering && exo.playWhenReady) {
                    if (stallSince == 0L) stallSince = android.os.SystemClock.elapsedRealtime()
                    if (android.os.SystemClock.elapsedRealtime() - stallSince > STALL_MS) reconnect()
                } else {
                    stallSince = 0L
                }
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = runBlocking { appContainer.settings.get() }
        nameTokens = settings.channelNameTokens
        backgroundPlayback = settings.backgroundPlayback
        applyDisplayMode(settings.displayMode)
        val playback = appContainer.playback
        val p = playback.acquire(settings.playerConfig, this)
        p.exo.addListener(listener)
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, 2000)
        binding.playerView.player = p.exo
        player = p
        playback.handoffChannelId?.let { id ->
            playback.handoffChannelId = null
            channelIds.indexOf(id).takeIf { it >= 0 }?.let { index = it }
        }
        // Still playing from before (picture-in-picture, background): carry on without reconnecting.
        val resume = playback.key == playbackKey(index) &&
            p.exo.playbackState != androidx.media3.common.Player.STATE_IDLE
        loadChannel(index, resume)
        protectionJob = lifecycleScope.launch {
            appContainer.parental.changes.collect {
                binding.channelOverlay.visibility = View.GONE
                val ch = current
                if (ch != null && (!appContainer.parental.allowed(ch, appContainer.database) ||
                    (appContainer.parental.allScreens && !appContainer.parental.unlocked))) loadChannel(index)
            }
        }
    }

    override fun onPause() {
        // Before the home screen starts again, so it can pick the player straight back up.
        if (isFinishing && returnToPreview && player != null) {
            recordWatch()
            appContainer.playback.handBack(this)
        }
        super.onPause()
    }

    /** The home preview (or another screen) took the player over, e.g. while this was in picture-in-picture. */
    override fun onPlaybackTaken() {
        player?.exo?.removeListener(listener)
        binding.playerView.player = null
        player = null
        loadJob?.cancel()
        if (!isFinishing) finish()
    }

    private var protectionJob: Job? = null
    override fun onStop() {
        mainHandler.removeCallbacks(watchdog)
        if (catchup || fileMode) archivePosition = player?.exo?.currentPosition ?: archivePosition
        recordWatch()
        loadJob?.cancel()
        protectionJob?.cancel()
        player?.exo?.removeListener(listener)
        binding.playerView.player = null
        // Closing the picture-in-picture window, or leaving with background playback off, ends playback.
        val keep = !isFinishing && !inPip() && backgroundPlayback && player?.exo?.playWhenReady == true
        if (!keep) appContainer.playback.release(this)
        player = null
        super.onStop()
    }

    private fun playbackKey(i: Int): String =
        if (fileMode) "file:" + intent.getStringExtra(EXTRA_FILE)
        else channelKey(channelIds.getOrNull(i), intent.getLongExtra("episode_id", -1), intent.getLongExtra(EXTRA_ARCHIVE_START, -1))

    private fun inPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun pipSupported() = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @RequiresApi(26)
    private fun pipParams(): PictureInPictureParams {
        val size = player?.exo?.videoSize
        val (w, h) = PipAspect.of(size?.width ?: 0, size?.height ?: 0, size?.pixelWidthHeightRatio ?: 1f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(player?.exo?.playWhenReady == true).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePip() {
        if (pipSupported()) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip(): Boolean {
        if (!pipSupported() || player == null) return false
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Newer systems enter on their own via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < 31 && player?.exo?.playWhenReady == true && !inPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(hideBanner)
            binding.banner.visibility = View.GONE
            binding.channelOverlay.visibility = View.GONE
            binding.playerView.hideController()
            binding.playerView.useController = false
        } else {
            binding.playerView.useController = controllerWanted
        }
        updateButtons()
    }

    private var controllerWanted = false

    private var pinPending = false
    private fun loadChannel(i: Int, resume: Boolean = false) {
        if (fileMode) return playFile(resume)
        if (channelIds.isEmpty()) return finish()
        recordWatch()
        if (!resume) player?.exo?.stop()
        lastUrl = null
        binding.banner.visibility = View.GONE
        binding.channelOverlay.visibility = View.GONE
        current = null
        index = i
        binding.errorBox.visibility = View.GONE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val container = appContainer
            val stored = container.channels.get(channelIds[i]) ?: return@launch
            if (!container.parental.allowed(stored, container.database) || (container.parental.allScreens && !container.parental.unlocked)) {
                player?.exo?.stop()
                if (!pinPending) {
                    pinPending = true
                    requestPin(cancel = { pinPending = false; finish() }) { pinPending = false; loadChannel(i) }
                }
                return@launch
            }
            val channel = try {
                val episode = intent.getLongExtra("episode_id", -1)
                if (episode >= 0) container.vod.episode(stored, episode) else stored
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError("Unable to open episode"); return@launch }
            current = channel
            updateButtons()
            triedHls = false
            showBanner(channel, null)

            val isVod = channel.kind != ContentKind.LIVE
            controllerWanted = isVod || catchup
            binding.playerView.useController = controllerWanted && !inPip()
            container.playback.setSessionActivity(
                Intent(intent).putExtra(EXTRA_POSITION, i).putExtra(EXTRA_CHANNEL_ID, channelIds[i]),
            )

            if (resume) {
                lastUrl = player?.exo?.currentMediaItem?.localConfiguration?.uri?.toString()
                if (player?.exo?.isPlaying == true) watchStartedAt = SystemClock.elapsedRealtime()
            } else try {
                val url = if (catchup) container.channels.resolveCatchupUrl(channel,
                    intent.getLongExtra(EXTRA_ARCHIVE_START, 0), intent.getLongExtra(EXTRA_ARCHIVE_STOP, 0))
                else container.channels.resolveStreamUrl(channel)
                lastUrl = url
                player?.play(url, channel.name, container.channels.userAgentFor(channel), channel.referrer)
                container.playback.key = playbackKey(i)
                container.playback.liveChannelId =
                    channel.id.takeIf { !catchup && !isVod && intent.getLongExtra("episode_id", -1) < 0 }
                if (container.playback.liveChannelId != null) container.lastChannel.save(channel)
                val resume = if (catchup) archivePosition else if (isVod && !intent.getBooleanExtra("from_beginning", false))
                    container.database.history().get(channel.playlistId, channel.channelKey)?.positionMs ?: 0L else 0L
                if (resume > 0) player?.exo?.seekTo(resume)
                intent.removeExtra("from_beginning")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message)
            }

            val now = container.epg.currentProgram(channel.id.toString())
            if (current?.id == channel.id) showBanner(channel, if (catchup) intent.getStringExtra(EXTRA_ARCHIVE_TITLE) else now?.title)
        }
    }

    private fun playFile(resume: Boolean) {
        val file = java.io.File(intent.getStringExtra(EXTRA_FILE).orEmpty())
        binding.errorBox.visibility = View.GONE
        controllerWanted = true
        binding.playerView.useController = !inPip()
        updateButtons()
        appContainer.playback.setSessionActivity(intent)
        if (resume) return
        if (!file.exists()) return showError(getString(R.string.recording_file_missing))
        player?.play(android.net.Uri.fromFile(file).toString(), intent.getStringExtra(EXTRA_FILE_TITLE), null)
        appContainer.playback.key = playbackKey(index)
        appContainer.playback.liveChannelId = null
        if (archivePosition > 0) player?.exo?.seekTo(archivePosition)
    }

    /** Record button or the options menu: capture the channel being watched. */
    private fun instantRecord() {
        val channel = current ?: return
        if (catchup || fileMode || channel.kind != ContentKind.LIVE) return
        lifecycleScope.launch {
            val c = appContainer
            val active = c.recordings.activeFor(channel)
            if (active != null) {
                c.recordings.stop(active.id)
                android.widget.Toast.makeText(this@PlayerActivity, R.string.recording_stopped, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val program = c.epg.currentProgram(channel.id.toString(), now)
            val default = c.settings.get().instantRecordMinutes
            val options = mutableListOf<Pair<String, Long>>()
            if (program != null && program.stop > now) {
                options += getString(R.string.instant_until_end, DateFormat.getTimeFormat(this@PlayerActivity).format(Date(program.stop))) to
                    (program.stop - now + c.settings.get().recordPadAfter * 60_000L)
            }
            (listOf(default) + listOf(30, 60, 120, 180).filter { it != default }).forEach { m ->
                options += resources.getQuantityString(R.plurals.minutes, m, m) to m * 60_000L
            }
            AlertDialog.Builder(this@PlayerActivity).setTitle(R.string.instant_record_title)
                .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                    lifecycleScope.launch {
                        val title = program?.title ?: displayName(channel)
                        val result = c.recordings.recordNow(channel, displayName(channel), title, options[which].second)
                        val msg = result.fold(
                            { getString(R.string.recording_started, title) },
                            { getString(R.string.recording_failed_to_schedule, it.message) },
                        )
                        android.widget.Toast.makeText(this@PlayerActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
    }

    /**
     * Reopens the same stream where it left off. Live channels come back at the live edge,
     * anything with a position (catch-up, recordings, files) seeks back to where it was.
     */
    private fun reconnect() {
        stallSince = 0L
        val url = lastUrl
        val ch = current
        if (url == null || ch == null) {
            showError(getString(R.string.player_stream_stopped))
            return
        }
        if (reconnects >= MAX_RECONNECTS) {
            showError(getString(R.string.player_stream_stopped))
            return
        }
        reconnects++
        val keep = if (catchup || fileMode) archivePosition else 0L
        binding.errorDetail.text = getString(R.string.player_reconnecting, reconnects, MAX_RECONNECTS)
        binding.errorBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            delay(RECONNECT_DELAY_MS * reconnects)
            if (isFinishing || isDestroyed) return@launch
            player?.play(url, ch.name, appContainer.channels.userAgentFor(ch), ch.referrer, forceHls = triedHls)
            if (keep > 0) player?.exo?.seekTo(keep)
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
        // A dropped live stream is worth another go before we tell the viewer it's broken.
        if (ch != null && ch.kind == ContentKind.LIVE && !catchup && !fileMode && reconnects < MAX_RECONNECTS) {
            reconnect()
            return
        }
        showError(describe(error))
    }

    private fun describe(error: PlaybackException): String = com.tellybox.tv.player.PlaybackErrors.describe(this, error)

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
        updateSleep()
        binding.bannerLogo.setImageDrawable(null)
        if (!channel.logoUrl.isNullOrBlank()) binding.bannerLogo.load(channel.logoUrl)
        binding.banner.visibility = View.VISIBLE
        binding.sleepButton.visibility = View.VISIBLE
        if (!catchup && !fileMode && current?.kind == ContentKind.LIVE) {
            binding.recordButton.visibility = View.VISIBLE
        }
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

    /** The OK press being held, replayed on release if it turns out not to be a long press. */
    private var okDown: KeyEvent? = null
    private var okLongPressed = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Remotes here have no Menu key, so holding OK opens the player menu. A short press
        // is only known on release; it then goes through as a normal press (channel list,
        // retry, the focused button, the playback controls).
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && !inPip()) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        okDown = KeyEvent(event)
                        okLongPressed = false
                    } else if (event.isLongPress && !okLongPressed) {
                        okLongPressed = true
                        okDown = null
                        showOptions()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val down = okDown
                    okDown = null
                    if (okLongPressed || down == null) {
                        okLongPressed = false
                        return true
                    }
                    super.dispatchKeyEvent(down)
                    return super.dispatchKeyEvent(event)
                }
            }
        }
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
        if (keyCode == KeyEvent.KEYCODE_MEDIA_RECORD) {
            instantRecord()
            return true
        }
        val vod = catchup || fileMode || (current?.kind?.let { it != ContentKind.LIVE } ?: false)
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
        val channel = current
        lifecycleScope.launch {
            val items = mutableListOf<Pair<String, () -> Unit>>()
            val live = channel != null && !catchup && !fileMode && channel.kind == ContentKind.LIVE
            if (live) {
                val active = appContainer.recordings.activeFor(channel!!)
                items += getString(if (active != null) R.string.guide_stop_recording else R.string.guide_record) to { instantRecord() }
            }
            val left = appContainer.sleep.remainingMinutes()
            items += (if (left != null) getString(R.string.sleep_banner, left) else getString(R.string.sleep_title)) to { showSleep() }
            if (channel != null && !fileMode) items += getString(R.string.external_player) to { openExternal() }
            items += "Audio tracks" to { pickTrack(C.TRACK_TYPE_AUDIO) }
            items += "Subtitles" to { pickTrack(C.TRACK_TYPE_TEXT) }
            items += "Display mode" to { pickDisplayMode() }
            if (live) items += getString(R.string.multiview) to { openMultiview() }
            if (pipSupported()) items += getString(R.string.pip) to { enterPip(); Unit }
            AlertDialog.Builder(this@PlayerActivity).setTitle("Playback options")
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                .show()
        }
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

    private fun openMultiview() {
        val channel = current ?: return
        recordWatch()
        // Free this stream's connection before the tiles open theirs.
        player?.exo?.removeListener(listener)
        binding.playerView.player = null
        player = null
        appContainer.playback.release()
        startActivity(MultiviewActivity.intent(this, channel.id, channelIds))
    }

    override fun onDestroy() {
        // Cleared from the back stack while playing in the background.
        if (isFinishing) appContainer.playback.release(this)
        appContainer.sleep.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** How long a picture may sit buffering before we assume the stream has dropped. */
        private const val STALL_MS = 12_000L

        /** Reconnect attempts before falling back to the error box. */
        private const val MAX_RECONNECTS = 3

        /** Wait a bit longer between each attempt. */
        private const val RECONNECT_DELAY_MS = 1_500L

        private const val EXTRA_ARCHIVE_START = "archive_start"
        private const val EXTRA_ARCHIVE_STOP = "archive_stop"
        private const val EXTRA_ARCHIVE_TITLE = "archive_title"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_FILE_TITLE = "file_title"

        fun fileIntent(context: Context, path: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_FILE, path)
                .putExtra(EXTRA_FILE_TITLE, title)

        fun catchupIntent(context: Context, channelId: Long, start: Long, stop: Long, title: String): Intent =
            intent(context, channelId, longArrayOf(channelId), 0)
                .putExtra(EXTRA_ARCHIVE_START, start)
                .putExtra(EXTRA_ARCHIVE_STOP, stop)
                .putExtra(EXTRA_ARCHIVE_TITLE, title)

        private const val EXTRA_RETURN_TO_PREVIEW = "return_to_preview"

        /** What [Playback.key] holds while a channel plays live, shared with the home preview. */
        fun channelKey(channelId: Long?, episodeId: Long = -1, archiveStart: Long = -1) = "ch:$channelId:$episodeId:$archiveStart"

        /** Marks a player launch from the home screen, whose preview hands over and takes back the stream. */
        fun fromPreview(intent: Intent): Intent = intent.putExtra(EXTRA_RETURN_TO_PREVIEW, true)

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
