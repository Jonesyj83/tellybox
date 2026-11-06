package com.openstream.tv.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openstream.tv.appContainer
import okhttp3.OkHttpClient

/**
 * Owns the single full-screen player so playback outlives the player screen (picture-in-picture,
 * leaving the app with the stream still playing). The screen attaches to it and detaches again;
 * [PlaybackService] keeps the process in the foreground and exposes it as a media session.
 */
@OptIn(UnstableApi::class)
class Playback(private val context: Context, private val http: OkHttpClient) {
    var player: Player? = null
        private set
    private var config: PlayerConfig? = null
    private var session: MediaSession? = null
    private var owner: Any? = null

    /** What is loaded, so a returning screen can pick it up instead of reconnecting. */
    var key: String? = null

    /** Channel the multiview screen left focused, for the player screen to continue with. */
    var handoffChannelId: Long? = null

    val mediaSession: MediaSession? get() = session

    fun acquire(config: PlayerConfig, owner: Any): Player {
        val existing = player
        if (existing != null && config == this.config) {
            this.owner = owner
            return existing
        }
        release()
        this.owner = owner
        val created = PlayerFactory.create(context, http, config)
        player = created
        this.config = config
        session = MediaSession.Builder(context, created.exo).setId("playback").build()
        // A plain start while the screen is visible; the session moves the service into the
        // foreground itself once something is actually playing.
        context.startService(Intent(context, PlaybackService::class.java))
        return created
    }

    fun isOwner(owner: Any) = this.owner === owner

    fun setSessionActivity(intent: Intent) {
        val pending = PendingIntent.getActivity(
            context, 0, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session?.setSessionActivity(pending)
    }

    /** Stops and frees the player. With [owner], only if that screen is still the one using it. */
    fun release(owner: Any? = null) {
        if (owner != null && owner !== this.owner) return
        val s = session
        val p = player
        session = null
        player = null
        config = null
        key = null
        this.owner = null
        s?.release()
        p?.release()
        if (p != null) context.stopService(Intent(context, PlaybackService::class.java))
    }
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
