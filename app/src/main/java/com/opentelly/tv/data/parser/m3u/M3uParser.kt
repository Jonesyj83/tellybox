package com.opentelly.tv.data.parser.m3u

import com.opentelly.tv.data.parser.decompressIfGzip
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.io.StringReader

data class M3uHeader(
    /** EPG URLs advertised in the header (url-tvg / x-tvg-url), may hold several. */
    val epgUrls: List<String> = emptyList(),
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class M3uEntry(
    val name: String,
    val url: String,
    val duration: Long = -1,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val groupTitle: String? = null,
    val channelNumber: Int? = null,
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Every attribute found on the #EXTINF line, lower-cased keys. */
    val attributes: Map<String, String> = emptyMap(),
)

data class M3uPlaylist(
    val header: M3uHeader,
    val entries: List<M3uEntry>,
)

/**
 * Line-based M3U/M3U8 parser. Tolerant by design: real-world lists are frequently
 * missing the #EXTM3U header, use unquoted attributes, put commas inside quoted values,
 * or have stray blank lines and Windows line endings.
 */
object M3uParser {

    fun parse(text: String): M3uPlaylist = parse(StringReader(text))

    fun parse(input: InputStream): M3uPlaylist =
        parse(input.decompressIfGzip().reader(Charsets.UTF_8))

    fun parse(reader: Reader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>()
        var header = M3uHeader()
        var pending: PendingEntry? = null

        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        var first = true
        buffered.forEachLine { raw ->
            var line = raw.trim()
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            if (line.isEmpty()) return@forEachLine

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    header = parseHeader(line)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val p = pending ?: PendingEntry().also { pending = it }
                    if (p.group == null) p.group = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val p = pending ?: PendingEntry().also { pending = it }
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> p.userAgent = value
                        "http-referrer", "http-referer" -> p.referrer = value
                    }
                }
                line.startsWith("#") -> {
                    // #KODIPROP, #EXT-X-*, comments: nothing we use yet.
                }
                else -> {
                    entries += buildEntry(pending ?: PendingEntry(), line, header)
                    pending = null
                }
            }
        }
        return M3uPlaylist(header, entries)
    }

    private class PendingEntry(
        var duration: Long = -1,
        var name: String? = null,
        var attrs: Map<String, String> = emptyMap(),
        var group: String? = null,
        var userAgent: String? = null,
        var referrer: String? = null,
    )

    private fun parseHeader(line: String): M3uHeader {
        val attrs = parseAttributes(line.drop("#EXTM3U".length))
        val epg = listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"], attrs["tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return M3uHeader(
            epgUrls = epg,
            catchupType = attrs["catchup"] ?: attrs["catchup-type"],
            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.toIntOrNull(),
            catchupSource = attrs["catchup-source"],
            attributes = attrs,
        )
    }

    private fun parseExtInf(line: String): PendingEntry {
        // #EXTINF:<duration> [attr="value" ...],<display name>
        val body = line.substringAfter(':', "")
        val commaIdx = findTitleComma(body)
        val meta = if (commaIdx >= 0) body.substring(0, commaIdx) else body
        val title = if (commaIdx >= 0) body.substring(commaIdx + 1).trim() else ""

        val durationToken = meta.trimStart().takeWhile { !it.isWhitespace() }
        val duration = durationToken.toDoubleOrNull()?.toLong() ?: -1
        val attrs = parseAttributes(meta.trimStart().drop(durationToken.length))

        return PendingEntry(
            duration = duration,
            name = title.ifEmpty { null },
            attrs = attrs,
            group = attrs["group-title"]?.ifEmpty { null },
        )
    }

    /** Index of the first comma that isn't inside a quoted attribute value. */
    private fun findTitleComma(s: String): Int {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> {
                    // Only treat as quote when it opens an attribute value (after '=').
                    if (i > 0 && s[i - 1] == '=') quote = c
                }
                c == ',' -> return i
            }
        }
        return -1
    }

    /**
     * Parses `key="value" key2=value2 key3='v'` pairs. Keys are lower-cased.
     * Unterminated quotes run to end of string rather than failing.
     */
    internal fun parseAttributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') {
                // bare token with no value, skip it
                continue
            }
            val key = s.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val q = s[i]
                val end = s.indexOf(q, i + 1)
                if (end < 0) {
                    value = s.substring(i + 1)
                    i = n
                } else {
                    value = s.substring(i + 1, end)
                    i = end + 1
                }
            } else {
                val start = i
                while (i < n && !s[i].isWhitespace()) i++
                value = s.substring(start, i)
            }
            if (key.isNotEmpty()) out[key] = value.trim()
        }
        return out
    }

    private fun buildEntry(p: PendingEntry, url: String, header: M3uHeader): M3uEntry {
        val a = p.attrs
        val tvgName = a["tvg-name"]?.ifEmpty { null }
        val name = p.name ?: tvgName ?: url.substringAfterLast('/').ifEmpty { url }
        return M3uEntry(
            name = name,
            url = url,
            duration = p.duration,
            tvgId = a["tvg-id"]?.ifEmpty { null },
            tvgName = tvgName,
            tvgLogo = (a["tvg-logo"] ?: a["logo"])?.ifEmpty { null },
            groupTitle = p.group,
            channelNumber = (a["tvg-chno"] ?: a["channel-number"] ?: a["tvg-num"])?.toIntOrNull(),
            catchupType = (a["catchup"] ?: a["catchup-type"])?.ifEmpty { null } ?: header.catchupType,
            catchupDays = (a["catchup-days"] ?: a["timeshift"] ?: a["tvg-rec"])?.toIntOrNull()
                ?: header.catchupDays,
            catchupSource = a["catchup-source"]?.ifEmpty { null } ?: header.catchupSource,
            userAgent = p.userAgent ?: a["http-user-agent"] ?: a["user-agent"],
            referrer = p.referrer ?: a["http-referrer"] ?: a["http-referer"],
            attributes = a,
        )
    }
}
