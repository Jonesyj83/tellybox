package com.openstream.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
)

@OptIn(UnstableApi::class)
object PlayerFactory {

    fun create(context: Context, http: OkHttpClient, config: PlayerConfig = PlayerConfig()): Player {
        val httpFactory = OkHttpDataSource.Factory(http)
        // DefaultDataSource adds file/content/asset support and picks up the RTMP
        // extension automatically when it's on the classpath.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (config.preferSoftwareDecoders) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )

        if (config.preferSoftwareDecoders) {
            renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    .sortedBy { if (it.softwareOnly) 0 else 1 }
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
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        return Player(exo, httpFactory)
    }
}

/** Thin wrapper that knows how to turn an IPTV URL into a playable item. */
@OptIn(UnstableApi::class)
class Player internal constructor(
    val exo: ExoPlayer,
    private val httpFactory: OkHttpDataSource.Factory,
) {
    fun play(url: String, title: String?, userAgent: String?, referrer: String? = null, forceHls: Boolean = false) {
        val headers = buildMap {
            if (!referrer.isNullOrBlank()) put("Referer", referrer)
        }
        httpFactory.setUserAgent(userAgent)
        httpFactory.setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMimeType(url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()

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
