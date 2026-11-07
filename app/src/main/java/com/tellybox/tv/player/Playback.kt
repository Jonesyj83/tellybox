package com.tellybox.tv.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tellybox.tv.appContainer
import okhttp3.OkHttpClient

/**
 * Owns the single full-screen player so playback outlives the player screen (picture-in-picture,
 * leaving the app with the stream still playing). The screen attaches to it and detaches again;
 * [PlaybackService] keeps the process in the foreground and exposes it as a media session.
 *
 * The media session exists only while the player has something loaded and is playing or
 * paused ([SessionPolicy]); an idle, stopped or failed player publishes nothing, so remotes,
 * Bluetooth and the notification never see a player that isn't there.
 */
@OptIn(UnstableApi::class)
class Playback(private val context: Context, private val http: OkHttpClient) {
    var player: Player? = null
        private set
    private var config: PlayerConfig? = null
    private var session: MediaSession? = null
    private var sessionActivity: PendingIntent? = null
    private var owner: Any? = null
    private val main = Handler(Looper.getMainLooper())
    /** Short gaps (a zap, a retry) keep the session; only a player that stays idle loses it. */
    private val dropIdleSession = Runnable { if (!shouldPublish()) dropSession() }
    private val stateListener = object : Listener {
        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) = syncSession()
    }

    /** What is loaded, so a returning screen can pick it up instead of reconnecting. */
    var key: String? = null

    /** Channel the multiview screen left focused, for the player screen to continue with. */
    var handoffChannelId: Long? = null

    /** Live channel currently loaded (not catch-up, VOD or a recording), so the home preview can carry on with it. */
    var liveChannelId: Long? = null

    /** A screen that shares the player and needs to know when another one takes it over. */
    interface Holder {
        fun onPlaybackTaken()
    }

    val mediaSession: MediaSession? get() = session

    fun acquire(config: PlayerConfig, owner: Any): Player {
        val existing = player
        val previous = this.owner
        if (previous != null && previous !== owner) {
            this.owner = owner
            (previous as? Holder)?.onPlaybackTaken()
        }
        if (existing != null && config == this.config) {
            this.owner = owner
            return existing
        }
        release()
        this.owner = owner
        val created = PlayerFactory.create(context, http, config)
        player = created
        this.config = config
        created.exo.addListener(stateListener)
        return created
    }

    private fun shouldPublish(): Boolean {
        val exo = player?.exo ?: return false
        return SessionPolicy.publish(exo.playbackState, exo.currentMediaItem != null)
    }

    private fun syncSession() {
        val exo = player?.exo ?: return
        if (shouldPublish()) {
            main.removeCallbacks(dropIdleSession)
            if (session == null) {
                session = MediaSession.Builder(context, exo).setId("playback").build().also { s ->
                    sessionActivity?.let(s::setSessionActivity)
                }
                // Started while a screen is showing the stream; the session moves the service
                // into the foreground itself once something is actually playing.
                runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
            }
        } else if (session != null) {
            main.removeCallbacks(dropIdleSession)
            main.postDelayed(dropIdleSession, IDLE_GRACE_MS)
        }
    }

    /** Takes the session (and with it metadata, position and the notification) away. */
    private fun dropSession() {
        main.removeCallbacks(dropIdleSession)
        val s = session ?: return
        session = null
        s.release()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun isOwner(owner: Any) = this.owner === owner

    /** Nobody is using the player right now, though it may still be loaded and playing. */
    val isUnowned get() = owner == null

    /**
     * [owner] is going away but expects the screen below to carry on with the same stream:
     * the player stays loaded, unowned, until that screen acquires it (or releases it).
     */
    fun handBack(owner: Any) {
        if (this.owner === owner) this.owner = null
    }

    fun setSessionActivity(intent: Intent) {
        val pending = PendingIntent.getActivity(
            context, 0, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        sessionActivity = pending
        session?.setSessionActivity(pending)
    }

    /** Stops and frees the player. With [owner], only if that screen is still the one using it. */
    fun release(owner: Any? = null) {
        if (owner != null && owner !== this.owner) return
        val p = player
        dropSession()
        player = null
        config = null
        key = null
        liveChannelId = null
        sessionActivity = null
        this.owner = null
        p?.exo?.removeListener(stateListener)
        p?.release()
    }

    private companion object {
        const val IDLE_GRACE_MS = 3_000L
    }
}

/** When the media session should be visible to the system. */
object SessionPolicy {
    /** Only with media loaded and the player buffering, playing or paused; never idle or ended. */
    fun publish(playbackState: Int, hasMedia: Boolean): Boolean =
        hasMedia && (playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
            playbackState == androidx.media3.common.Player.STATE_READY)
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val playback get() = appContainer.playback

    override fun onCreate() {
        super.onCreate()
        // A finished stream shouldn't leave a notification behind.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = playback.mediaSession
        if (session == null) {
            stopSelf()
        } else if (!isSessionAdded(session)) {
            addSession(session)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = playback.mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        playback.release()
        stopSelf()
    }

    override fun onDestroy() {
        playback.mediaSession?.let { if (isSessionAdded(it)) removeSession(it) }
        super.onDestroy()
    }
}

/** Short, user-facing reason a stream failed. */
object PlaybackErrors {
    fun describe(context: Context, error: androidx.media3.common.PlaybackException): String {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return when {
            http != null -> context.getString(com.tellybox.tv.R.string.error_http, http.responseCode)
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                context.getString(com.tellybox.tv.R.string.error_timeout)
            error.errorCode in 2000..2999 -> context.getString(com.tellybox.tv.R.string.error_network)
            error.errorCode in 3000..3999 -> context.getString(com.tellybox.tv.R.string.error_format)
            error.errorCode in 4000..4999 -> context.getString(com.tellybox.tv.R.string.error_decoder)
            else -> error.errorCodeName
        }
    }
}

/** Picture-in-picture window shape for a video, within the range the system accepts. */
object PipAspect {
    // The system allows 1:2.39 to 2.39:1; stay just inside so rounding never trips the check.
    private const val MIN = 0.42f
    private const val MAX = 2.38f

    fun of(width: Int, height: Int, pixelRatio: Float = 1f): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = (width * (if (pixelRatio > 0f) pixelRatio else 1f) / height).coerceIn(MIN, MAX)
        // Whole numbers for Rational, precise to a thousandth.
        return (ratio * 1000).toInt() to 1000
    }
}
