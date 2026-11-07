package com.tellybox.tv.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import kotlinx.coroutines.CancellationException

/**
 * Hands a stream to another installed video app, for streams our own player struggles with.
 * A plain video VIEW intent; the user agent and referrer go along in the extras the common
 * players read ("headers" as name/value pairs, and the browser headers bundle).
 */
object ExternalPlayer {

    fun intent(url: String, title: String?, userAgent: String?, referrer: String?): Intent {
        val headers = buildList {
            if (!userAgent.isNullOrBlank()) { add("User-Agent"); add(userAgent) }
            if (!referrer.isNullOrBlank()) { add("Referer"); add(referrer) }
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            if (!title.isNullOrBlank()) {
                putExtra("title", title)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            if (headers.isNotEmpty()) {
                putExtra("headers", headers.toTypedArray())
                putExtra(android.provider.Browser.EXTRA_HEADERS, Bundle().apply {
                    headers.chunked(2).forEach { (name, value) -> putString(name, value) }
                })
            }
        }
    }

    /** Starts it; false (after telling the user) when nothing can play it. */
    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.external_player_none, Toast.LENGTH_LONG).show()
        false
    } catch (e: SecurityException) {
        Toast.makeText(context, context.getString(R.string.external_player_failed, e.message), Toast.LENGTH_LONG).show()
        false
    }

    /**
     * Resolves a fresh URL for [channel] (live, or the archive between [archiveStart] and
     * [archiveStop]) and opens it elsewhere. Stalker links are single use, so this never
     * reuses the one our own player already opened.
     */
    suspend fun open(context: Context, channel: ChannelEntity, title: String?, archiveStart: Long? = null, archiveStop: Long? = null): Boolean {
        val c = context.appContainer
        val url = try {
            if (archiveStart != null && archiveStop != null) c.channels.resolveCatchupUrl(channel, archiveStart, archiveStop)
            else c.channels.resolveStreamUrl(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.external_player_failed, e.message), Toast.LENGTH_LONG).show()
            return false
        }
        return launch(context, intent(url, title ?: channel.name, c.channels.userAgentFor(channel), channel.referrer))
    }
}
