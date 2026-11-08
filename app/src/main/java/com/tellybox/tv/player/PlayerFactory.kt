package com.tellybox.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tellybox.tv.player.timeshift.DataSourceUpstream
import com.tellybox.tv.player.timeshift.TimeshiftDataSource
import com.tellybox.tv.player.timeshift.TimeshiftSession
import com.tellybox.tv.player.timeshift.Upstream
import okhttp3.OkHttpClient

/** How much to buffer before and during playback. Bigger rides out flaky providers. */
enum class BufferSize(val minMs: Int, val maxMs: Int, val startMs: Int) {
    SMALL(5_000, 15_000, 1_000),
    MEDIUM(15_000, 50_000, 2_500),
    LARGE(30_000, 90_000, 4_000),
    VERY_LARGE(60_000, 180_000, 6_000),
}

data class PlayerConfig(
    val buffer: BufferSize = BufferSize.MEDIUM,
    val preferSoftwareDecoders: Boolean = false,
    val tunneling: Boolean = false,
    /** Only one player may own audio focus; multiview tiles mix their own volume instead. */
    val handleAudioFocus: Boolean = true,
    /** Caps the picked video variant, so several streams at once stay within decoder limits. */
    val maxVideoHeight: Int? = null,
    val audioPassthrough: Boolean = true,
    val preferSoftwareAudioDecoders: Boolean = false,
)

@OptIn(UnstableApi::class)
object PlayerFactory {

    fun create(context: Context, http: OkHttpClient, config: PlayerConfig = PlayerConfig()): Player {
        val httpFactory = OkHttpDataSource.Factory(http)
        // DefaultDataSource adds file/content/asset support and picks up the RTMP
        // extension automatically when it's on the classpath.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(if (config.audioPassthrough) androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
                        else androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
            }
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (config.preferSoftwareDecoders) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )

        if (config.preferSoftwareDecoders || config.preferSoftwareAudioDecoders) {
            renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    .sortedBy {
                        val preferSoftware = if (mimeType.startsWith("audio/")) config.preferSoftwareAudioDecoders else config.preferSoftwareDecoders
                        if (it.softwareOnly == preferSoftware) 0 else 1
                    }
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.buffer.minMs,
                config.buffer.maxMs,
                config.buffer.startMs,
                config.buffer.startMs * 2,
            )
            .build()

        // Tunneling is a DefaultTrackSelector option, not part of the generic track parameters.
        val trackSelector = DefaultTrackSelector(context).apply {
            if (config.tunneling) {
                setParameters(buildUponParameters().setTunnelingEnabled(true))
            }
            config.maxVideoHeight?.let { h ->
                setParameters(buildUponParameters().setMaxVideoSize(h * 16 / 9, h))
            }
        }

        val exo = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                config.handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        return Player(exo, httpFactory, dataSourceFactory)
    }
}

/** Thin wrapper that knows how to turn an IPTV URL into a playable item. */
@OptIn(UnstableApi::class)
class Player internal constructor(
    val exo: ExoPlayer,
    private val httpFactory: OkHttpDataSource.Factory,
    /** What live streams are read through; a timeshift session reads the provider with the same stack. */
    private val upstreamFactory: DataSource.Factory,
) {
    /** The buffer the picture is playing from, while a live channel is timeshifted. */
    var timeshift: TimeshiftSession? = null
        private set

    /** Buffer offset the current timeshift item was opened at. */
    private var timeshiftStart = 0L
    private var timeshiftTitle: String? = null

    fun play(url: String, title: String?, userAgent: String?, referrer: String? = null, forceHls: Boolean = false) {
        endTimeshift()
        setHeaders(userAgent, referrer)

        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMimeType(url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun setHeaders(userAgent: String?, referrer: String?) {
        val headers = buildMap {
            if (!referrer.isNullOrBlank()) put("Referer", referrer)
        }
        httpFactory.setUserAgent(userAgent)
        httpFactory.setDefaultRequestProperties(headers)
    }

    /** The provider side of a timeshift session, with the channel's headers applied. */
    fun upstream(userAgent: String?, referrer: String?): Upstream {
        setHeaders(userAgent, referrer)
        return DataSourceUpstream(upstreamFactory)
    }

    /** Hands [session] to this player and starts playing it from the beginning of the buffer. */
    fun playTimeshift(session: TimeshiftSession, title: String?) {
        if (timeshift !== session) endTimeshift()
        timeshift = session
        timeshiftTitle = title
        playBuffer(0)
    }

    /** Restarts playback at [offset] in the buffer: a seek, as far as the viewer is concerned. */
    fun playBuffer(offset: Long) {
        val session = timeshift ?: return
        timeshiftStart = offset
        val item = MediaItem.Builder()
            .setUri(TimeshiftDataSource.uriFor(offset))
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(timeshiftTitle).build())
            .build()
        val keepPlaying = exo.playWhenReady || exo.playbackState == androidx.media3.common.Player.STATE_IDLE
        exo.setMediaSource(DefaultMediaSourceFactory(TimeshiftDataSource.Factory(session)).createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = keepPlaying
    }

    /** Content time of the picture on screen, on the session's timeline. */
    fun timeshiftPosition(): Long {
        val session = timeshift ?: return 0L
        return session.contentAt(timeshiftStart) + exo.currentPosition.coerceAtLeast(0)
    }

    /** Stops buffering and deletes the buffer. The player itself is left as it is. */
    fun endTimeshift() {
        val session = timeshift ?: return
        timeshift = null
        session.close()
    }

    fun release() {
        endTimeshift()
        exo.release()
    }

    companion object {
        /**
         * ExoPlayer infers the format from the file extension. Lots of IPTV URLs have
         * none, or a misleading one, so give it a hint where the URL makes it obvious.
         */
        fun guessMimeType(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val query = url.substringAfter('?', "").lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") || "/hls/" in path ||
                    query.contains("m3u8") || query.contains("output=hls") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") || "/dash/" in path -> MimeTypes.APPLICATION_MPD
                path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
                else -> null
            }
        }
    }
}
