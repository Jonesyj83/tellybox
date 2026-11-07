package com.opentelly.tv.data.catchup

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Builds catch-up (archive) URLs for M3U channels from the `catchup`, `catchup-source`
 * and stream URL attributes. The conventions follow what IPTV providers commonly put in
 * their playlists:
 *
 * - `default`: catchup-source is a complete URL template
 * - `append`: catchup-source is appended to the live stream URL
 * - `shift` / `timeshift`: `utc` and `lutc` query parameters are added to the stream URL
 * - `flussonic` / `fs`: the stream path is rewritten to Flussonic's archive form
 * - `xc`: an Xtream-style live URL is rewritten to its /timeshift/ form
 *
 * Templates understand `{utc}`, `{start}`, `{utcend}`, `{end}`, `{lutc}`, `{now}`,
 * `{timestamp}`, `{duration}`, `{offset}`, `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}`, the
 * divisor forms `{duration:60}` / `{offset:60}`, formatted forms like
 * `{utc:Y-m-d H:M:S}`, and the same names written `${start}`.
 */
object CatchupUrls {
    fun available(days: Int, start: Long, stop: Long, now: Long): Boolean =
        days > 0 && start < stop && stop <= now &&
            start >= now - TimeUnit.DAYS.toMillis(days.toLong())


    /** Times are epoch millis. Returns null when the channel can't do catch-up this way. */
    fun build(
        streamUrl: String,
        type: String?,
        source: String?,
        start: Long,
        stop: Long,
        now: Long,
    ): String? {
        val t = type?.trim()?.lowercase().orEmpty()
        val src = source?.trim().orEmpty()
        return when {
            t == "append" && src.isNotEmpty() -> fill(streamUrl + src, start, stop, now)
            t == "shift" || t == "timeshift" ->
                if (src.isNotEmpty() && src.contains('{')) fill(appendQuery(streamUrl, src.trimStart('?', '&')), start, stop, now)
                else appendQuery(streamUrl, "utc=${start / 1000}&lutc=${now / 1000}")
            t == "flussonic" || t == "flussonic-hls" || t == "flussonic-ts" || t == "fs" -> flussonic(streamUrl, start, stop)
            t == "xc" -> xtream(streamUrl, start, stop)
            src.isNotEmpty() -> {
                // "default", or no type at all but a source: a full URL, or a relative tail.
                if (src.contains("://")) fill(src, start, stop, now) else fill(streamUrl + src, start, stop, now)
            }
            else -> null
        }
    }

    private fun appendQuery(url: String, query: String): String =
        url + (if (url.contains('?')) "&" else "?") + query

    private val placeholder = Regex("""\$?\{([a-zA-Z]+)(?::([^}]*))?\}""")

    fun fill(template: String, start: Long, stop: Long, now: Long): String {
        val startS = start / 1000
        val stopS = stop / 1000
        val nowS = now / 1000
        return placeholder.replace(template) { m ->
            val name = m.groupValues[1]
            val arg = m.groupValues[2].takeIf { m.groups[2] != null }
            fun epoch(value: Long) = if (arg != null) formatTime(value * 1000, arg) else value.toString()
            fun divided(value: Long) = (value / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
            when (name) {
                "utc", "start" -> epoch(startS)
                "utcend", "end" -> epoch(stopS)
                "lutc", "now", "timestamp" -> epoch(nowS)
                "duration" -> divided(stopS - startS)
                "offset" -> divided(nowS - startS)
                "Y" -> formatTime(start, "Y")
                "m" -> formatTime(start, "m")
                "d" -> formatTime(start, "d")
                "H" -> formatTime(start, "H")
                "M" -> formatTime(start, "M")
                "S" -> formatTime(start, "S")
                else -> m.value
            }
        }
    }

    /** Formats with single-letter fields: Y year, m month, d day, H hour, M minute, S second (UTC). */
    fun formatTime(millis: Long, pattern: String, zone: TimeZone = TimeZone.getTimeZone("UTC")): String {
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = millis }
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                'Y' -> sb.append("%04d".format(cal.get(java.util.Calendar.YEAR)))
                'm' -> sb.append("%02d".format(cal.get(java.util.Calendar.MONTH) + 1))
                'd' -> sb.append("%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH)))
                'H' -> sb.append("%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY)))
                'M' -> sb.append("%02d".format(cal.get(java.util.Calendar.MINUTE)))
                'S' -> sb.append("%02d".format(cal.get(java.util.Calendar.SECOND)))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Flussonic: `http://host/name/index.m3u8?token=x` becomes
     * `http://host/name/index-START-DURATION.m3u8?token=x`; MPEG-TS URLs (`/name/mpegts`
     * or `/name`) become `/name/timeshift_abs-START.ts`.
     */
    fun flussonic(url: String, start: Long, stop: Long): String? {
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val base = url.substringBefore('?')
        val slash = base.lastIndexOf('/')
        if (slash < 0 || !base.contains("://")) return null
        val dir = base.substring(0, slash)
        val file = base.substring(slash + 1)
        val startS = start / 1000
        val dur = (stop - start) / 1000
        return when {
            file.endsWith(".m3u8") -> {
                val stem = file.removeSuffix(".m3u8").let { if (it == "index" || it == "playlist" || it == "mono") it else "index" }
                val keepStem = if (file.removeSuffix(".m3u8") == stem) stem else "index"
                if (keepStem == "index" && file != "index.m3u8" && file != "playlist.m3u8" && file != "mono.m3u8") {
                    // e.g. /name/video.m3u8 -> archive lives next to it
                    "$dir/video-$startS-$dur.m3u8$query"
                } else {
                    "$dir/$keepStem-$startS-$dur.m3u8$query"
                }
            }
            file == "mpegts" || file.endsWith(".ts") -> "$dir/timeshift_abs-$startS.ts$query"
            else -> "$base/timeshift_abs-$startS.ts$query"
        }
    }

    /** `http://host:port/user/pass/123.ts` or `/live/user/pass/123.m3u8` to the /timeshift/ form. */
    fun xtream(url: String, start: Long, stop: Long, serverZone: TimeZone = TimeZone.getTimeZone("UTC")): String? {
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?(\?.*)?$""").find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        val minutes = TimeUnit.MILLISECONDS.toMinutes(stop - start).coerceAtLeast(1)
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = serverZone }
        return "$host/timeshift/$user/$pass/$minutes/${fmt.format(start)}/$id.ts"
    }
}
