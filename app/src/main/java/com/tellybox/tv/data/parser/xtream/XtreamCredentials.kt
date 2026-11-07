package com.tellybox.tv.data.parser.xtream

import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class XtreamCredentials(
    /** Scheme, host, port and any path prefix, no trailing slash: `http://host:8080`. */
    val server: String,
    val username: String,
    val password: String,
) {
    private val u get() = username.urlPathSegment()
    private val p get() = password.urlPathSegment()

    fun liveUrl(streamId: Long, extension: String = "ts") = "$server/live/$u/$p/$streamId.$extension"
    fun movieUrl(streamId: Long, extension: String) = "$server/movie/$u/$p/$streamId.$extension"
    fun seriesUrl(episodeId: Long, extension: String) = "$server/series/$u/$p/$episodeId.$extension"

    /**
     * Catch-up stream for a past programme. The start time goes in the server's timezone
     * (which is what panels expect); [serverTimeZone] defaults to UTC.
     */
    fun timeshiftUrl(
        streamId: Long,
        startMillis: Long,
        durationMinutes: Int,
        serverTimeZone: TimeZone = TimeZone.getTimeZone("UTC"),
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = serverTimeZone }
        return "$server/timeshift/$u/$p/$durationMinutes/${fmt.format(startMillis)}/$streamId.ts"
    }

    fun xmltvUrl() = "$server/xmltv.php?username=${username.urlQuery()}&password=${password.urlQuery()}"

    companion object {
        /**
         * Accepts what users actually paste: a bare host, `host:port`, a full
         * `http://host:port/` or a `get.php?username=..&password=..` M3U link (in which case
         * the credentials come from the query string when not given separately).
         */
        fun from(rawUrl: String, username: String? = null, password: String? = null): XtreamCredentials {
            var url = rawUrl.trim()
            if (!url.contains("://")) url = "http://$url"
            val uri = URI(url.replace(" ", "%20"))
            val query = parseQuery(uri.rawQuery)
            val user = username?.takeIf { it.isNotBlank() } ?: query["username"].orEmpty()
            val pass = password?.takeIf { it.isNotBlank() } ?: query["password"].orEmpty()

            var path = uri.rawPath.orEmpty()
            val knownEndpoints = listOf("get.php", "player_api.php", "xmltv.php", "panel_api.php")
            if (knownEndpoints.any { path.endsWith("/$it") }) path = path.substringBeforeLast('/')
            path = path.trimEnd('/')

            val port = if (uri.port > 0) ":${uri.port}" else ""
            return XtreamCredentials("${uri.scheme}://${uri.host}$port$path", user.trim(), pass.trim())
        }

        private fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').mapNotNull { pair ->
                val k = pair.substringBefore('=', "")
                if (k.isEmpty()) null
                else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }.toMap()
    }
}

private fun String.urlQuery(): String = java.net.URLEncoder.encode(this, "UTF-8")

/** Path-segment encoding: spaces as %20 rather than '+'. */
private fun String.urlPathSegment(): String = urlQuery().replace("+", "%20")
