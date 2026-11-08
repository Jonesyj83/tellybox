package com.tellybox.tv.data.parser.stalker

import com.tellybox.tv.data.parser.arr
import com.tellybox.tv.data.parser.bool
import com.tellybox.tv.data.parser.int
import com.tellybox.tv.data.parser.long
import com.tellybox.tv.data.parser.objects
import com.tellybox.tv.data.parser.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone

data class StalkerGenre(val id: String, val title: String)

data class StalkerChannel(
    val id: String,
    val name: String,
    val number: Int?,
    val cmd: String,
    val logo: String?,
    val genreId: String?,
    val xmltvId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
)

class StalkerException(message: String) : IOException(message)

/**
 * Minimal Ministra/Stalker middleware client, enough for live TV: it identifies as a
 * MAG set-top box by MAC address, gets a session token, and lists genres and channels.
 * Stream URLs on these portals are short-lived, so [createLink] must be called at play time.
 */
class StalkerClient(
    portalUrl: String,
    macAddress: String,
    private val http: OkHttpClient,
    private val userAgent: String? = null,
    stbType: String? = null,
    serial: String? = null,
    deviceId: String? = null,
    deviceId2: String? = null,
) {
    val mac: String = normalizeMac(macAddress)
    private val identity = StalkerIdentity(
        mac = mac,
        stbType = stbType?.takeIf { it.isNotBlank() } ?: StalkerIdentity.DEFAULT_STB_TYPE,
        serialOverride = serial,
        deviceIdOverride = deviceId,
        deviceId2Override = deviceId2,
    )
    private val candidates: List<HttpUrl> = endpointCandidates(portalUrl)
    private val referer = portalUrl.trim()
    private val lock = Mutex()

    private var endpoint: HttpUrl? = null
    private var token: String? = null

    suspend fun connect() = lock.withLock { ensureSessionLocked(force = true) }

    suspend fun genres(): List<StalkerGenre> = StalkerMapper.genres(call("itv", "get_genres"))

    suspend fun channels(): List<StalkerChannel> = StalkerMapper.channels(call("itv", "get_all_channels"))

    /** Exchanges a channel's `cmd` for a playable URL. */
    suspend fun createLink(cmd: String): String {
        val js = call("itv", "create_link", mapOf("cmd" to cmd, "forced_storage" to "undefined", "disable_ad" to "0"))
        val linked = (js as? JsonObject)?.str("cmd")
        return StalkerMapper.urlFromCmd(linked ?: cmd)
            ?: throw StalkerException("Portal returned no stream link")
    }

    suspend fun createArchiveLink(cmd: String, start: Long, stop: Long): String {
        val js = call("tv_archive", "create_link", mapOf(
            "cmd" to cmd, "utc" to (start / 1000).toString(),
            "lutc" to (stop / 1000).toString(), "forced_storage" to "undefined",
        ))
        val linked = (js as? JsonObject)?.str("cmd")
            ?: throw StalkerException("Portal returned no archive link")
        return StalkerMapper.urlFromCmd(linked)
            ?: throw StalkerException("Portal returned no archive link")
    }

    private suspend fun call(type: String, action: String, params: Map<String, String> = emptyMap()): JsonElement? {
        lock.withLock { ensureSessionLocked(force = false) }
        return try {
            request(type, action, params)
        } catch (e: StalkerException) {
            // Tokens expire without warning; one fresh handshake then give up.
            lock.withLock { ensureSessionLocked(force = true) }
            request(type, action, params)
        }
    }

    private suspend fun ensureSessionLocked(force: Boolean) {
        if (!force && token != null && endpoint != null) return
        var lastError: Exception? = null
        for (url in listOfNotNull(endpoint) + candidates) {
            try {
                endpoint = url
                token = null
                val hs = request("stb", "handshake", mapOf("token" to ""), allowNoToken = true)
                token = (hs as? JsonObject)?.str("token") ?: throw StalkerException("Handshake returned no token")
                request("stb", "get_profile", profileParams())
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        endpoint = null
        token = null
        throw StalkerException("Could not connect to portal: ${lastError?.message ?: "no response"}")
    }

    private suspend fun request(
        type: String,
        action: String,
        params: Map<String, String>,
        allowNoToken: Boolean = false,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val base = endpoint ?: throw StalkerException("Not connected")
        val url = base.newBuilder().apply {
            addQueryParameter("type", type)
            addQueryParameter("action", action)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()

        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent ?: USER_AGENT)
            .header("X-User-Agent", "Model: ${identity.model}; Link: WiFi")
            .header("Referer", referer)
            .header("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${TimeZone.getDefault().id}; sn=${identity.serial}")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw StalkerException("HTTP ${resp.code}")
            if (!allowNoToken && body.contains("Authorization failed", ignoreCase = true)) {
                throw StalkerException("Authorization failed")
            }
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: throw StalkerException("Unexpected response from portal")
            root["js"]
        }
    }

    private fun profileParams(): Map<String, String> = mapOf(
        "hd" to "1",
        "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343",
        "num_banks" to "2",
        "sn" to identity.serial,
        "stb_type" to identity.model,
        "image_version" to "218",
        "video_out" to "hdmi",
        "device_id" to identity.deviceId,
        "device_id2" to identity.deviceId2,
        "signature" to identity.signature,
        "auth_second_step" to "1",
        "hw_version" to "1.7-BD-00",
        "hw_version_2" to identity.hwVersion2,
        "not_valid_token" to "0",
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun normalizeMac(raw: String): String {
            val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
            return if (hex.length == 12) hex.chunked(2).joinToString(":") else raw.trim().uppercase()
        }

        fun isValidMac(raw: String): Boolean =
            raw.filter { it.isLetterOrDigit() }.let { h -> h.length == 12 && h.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' } }

        /**
         * Users paste anything from `host:port` to `.../stalker_portal/c/` to a direct
         * `load.php` URL. Work out which API paths are worth trying, most likely first.
         */
        fun endpointCandidates(portalUrl: String): List<HttpUrl> {
            var raw = portalUrl.trim()
            if (!raw.contains("://")) raw = "http://$raw"
            val url = raw.toHttpUrl()
            val root = url.newBuilder().encodedPath("/").query(null).build()
            val path = url.encodedPath
            if (path.endsWith(".php")) return listOf(url.newBuilder().query(null).build())

            val out = ArrayList<HttpUrl>()
            if (path.contains("/stalker_portal")) {
                out += root.resolve("/stalker_portal/server/load.php")!!
            }
            // Directory the user gave, minus a trailing "/c".
            val dir = path.trimEnd('/').removeSuffix("/c").trimEnd('/')
            if (dir.isNotEmpty()) {
                out += root.resolve("$dir/portal.php")!!
                out += root.resolve("$dir/server/load.php")!!
            }
            out += root.resolve("/portal.php")!!
            out += root.resolve("/stalker_portal/server/load.php")!!
            out += root.resolve("/server/load.php")!!
            return out.distinct()
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

object StalkerMapper {
    fun genres(js: JsonElement?): List<StalkerGenre> = js.objects().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        if (id == "*") return@mapNotNull null // the portal's own "All" pseudo-genre
        StalkerGenre(id, o.str("title") ?: id)
    }

    fun channels(js: JsonElement?): List<StalkerChannel> {
        val list = (js as? JsonObject)?.arr("data") ?: js
        return list.objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val cmd = o.str("cmd") ?: o.arr("cmds")?.objects()?.firstOrNull()?.str("url") ?: return@mapNotNull null
            StalkerChannel(
                id = id,
                name = o.str("name") ?: "Channel $id",
                number = o.int("number"),
                cmd = cmd,
                logo = o.str("logo"),
                genreId = o.str("tv_genre_id"),
                xmltvId = o.str("xmltv_id"),
                hasArchive = o.bool("tv_archive") || (o.long("archive") ?: 0) > 0,
                // Portals report archive depth in hours.
                archiveDays = ((o.int("tv_archive_duration") ?: 0) + 23) / 24,
            )
        }
    }

    /** `cmd` values look like "ffmpeg http://...", "auto http://..." or just the URL. */
    fun urlFromCmd(cmd: String): String? {
        val trimmed = cmd.trim()
        val idx = trimmed.indexOf("://")
        if (idx < 0) return null
        val schemeStart = trimmed.lastIndexOf(' ', idx).let { if (it < 0) 0 else it + 1 }
        return trimmed.substring(schemeStart).trim().ifEmpty { null }
    }
}
