package com.tellybox.tv.player.timeshift

import android.content.Context
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.repository.ChannelRepository
import com.tellybox.tv.data.settings.SettingsRepository
import com.tellybox.tv.player.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The one place timeshift sessions are started, so the home preview and the player screen
 * buffer a channel the same way: same storage folder and size setting, same free-space
 * checks, and the previous channel's buffer deleted first.
 */
class TimeshiftSessions(
    private val context: Context,
    private val settings: SettingsRepository,
    private val channels: ChannelRepository,
) {
    sealed class Result {
        data class Opened(val session: TimeshiftSession) : Result()
        data class Unavailable(val reason: TimeshiftUnavailable) : Result()
        /** Not a live channel; timeshift doesn't apply and there is nothing to tell the viewer. */
        data object NotLive : Result()
    }

    /**
     * Starts buffering [channel] from [url] on [player], or says why it can't be. Any buffer
     * [player] already holds is closed and deleted first. Only a live MPEG-TS stream over HTTP
     * can be buffered. The session is returned started but not yet playing; hand it to
     * [Player.playTimeshift]. If the caller is cancelled, or [stillWanted] says no once the
     * session is open, the session is closed again and this throws [CancellationException].
     */
    suspend fun open(
        player: Player,
        channel: ChannelEntity,
        url: String,
        userAgent: String?,
        stillWanted: () -> Boolean = { true },
    ): Result {
        player.endTimeshift()
        if (channel.kind != ContentKind.LIVE) return Result.NotLive
        val scheme = android.net.Uri.parse(url).scheme?.lowercase()
        val mime = Player.guessMimeType(url)
        if ((scheme != "http" && scheme != "https") || (mime != null && mime != androidx.media3.common.MimeTypes.VIDEO_MP2T)) {
            return Result.Unavailable(TimeshiftUnavailable.UnsupportedStream)
        }
        val bytes = settings.get().timeshiftBufferMb * TimeshiftStorage.MB
        val upstream = player.upstream(userAgent, channel.referrer)
        val dir = TimeshiftStorage.dir(context)
        // Not cancellable: a session opened as the viewer zaps away must still be closed, not leaked.
        val result = withContext(Dispatchers.IO + NonCancellable) {
            TimeshiftSession.open(dir, bytes, upstream, url) {
                runBlocking { channels.resolveStreamUrl(channel) }
            }
        }
        return when (result) {
            is TimeshiftSession.OpenResult.Unavailable -> Result.Unavailable(result.reason)
            is TimeshiftSession.OpenResult.Opened -> {
                if (!currentCoroutineContext().isActive || !stillWanted()) {
                    result.session.close()
                    throw CancellationException()
                }
                Result.Opened(result.session)
            }
        }
    }
}
