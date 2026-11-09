package com.tellybox.tv.data.parser.m3u

import com.tellybox.tv.data.parser.decompressIfGzip
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
                    // #EXTVLCOPT / #EXTGRP may come before #EXTINF: keep what they set.
                    val before = pending
                    pending = parseExtInf(line).also { p ->
                        if (before != null) {
                            if (p.group == null) p.group = before.group
                            p.userAgent = before.userAgent
                            p.referrer = before.referrer
                        }
                    }
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

        // The duration is optional: only take the first word as one when it is a number,
        // so `#EXTINF:tvg-id="x",Name` keeps its tvg-id.
        val firstToken = meta.trimStart().takeWhile { !it.isWhitespace() }
        val parsedDuration = firstToken.toDoubleOrNull()
        val duration = parsedDuration?.toLong() ?: -1
        val attrs = parseAttributes(
            if (parsedDuration != null) meta.trimStart().drop(firstToken.length) else meta,
        )

        return PendingEntry(
            duration = duration,
            name = title.ifEmpty { null },
            attrs = attrs,
            group = attrs["group-title"]?.ifEmpty { null },
        )
    }

    /** Index of the first comma that isn't inside a quoted attribute value. */
    private fun findTitleComma(s: String): Int {
        val strict = findTitleComma(s, strict = true)
        // Quotes that never close cleanly (e.g. `a="x"b="y",Name`): use the first matching quote.
        return if (strict >= 0) strict else findTitleComma(s, strict = false)
    }

    private fun findTitleComma(s: String, strict: Boolean): Int {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                quote != null -> if (c == quote && (!strict || closesQuote(s, i))) quote = null
                c == '"' || c == '\'' -> {
                    // Only treat as quote when it opens an attribute value (after '=',
                    // spaces allowed in between).
                    var j = i - 1
                    while (j >= 0 && s[j] == ' ') j--
                    if (j >= 0 && s[j] == '=') quote = c
                }
                c == ',' -> return i
            }
        }
        return -1
    }

    /**
     * A quote only closes a value when a space, a comma or the end of the line follows it,
     * and only when what comes after that (spaces skipped) is the end, a comma or the next
     * `name=`. So `tvg-name="The "Best" Channel"` keeps its inner quotes, and the broken
     * `a="x"b="y"` with no space still splits into two attributes.
     */
    private fun closesQuote(s: String, i: Int): Boolean {
        val n = s.length
        var j = i + 1
        if (j < n && !s[j].isWhitespace() && s[j] != ',' && !s[j].isLetter()) return false
        while (j < n && s[j].isWhitespace()) j++
        if (j >= n || s[j] == ',') return true
        if (!s[j].isLetter()) return false
        while (j < n && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == '_')) j++
        while (j < n && s[j] == ' ') j++
        return j < n && s[j] == '='
    }

    /** Closing quote for the value opened at [open], or -1 if it never closes. */
    private fun findClosingQuote(s: String, open: Int): Int {
        val q = s[open]
        var i = s.indexOf(q, open + 1)
        while (i >= 0) {
            if (closesQuote(s, i)) return i
            i = s.indexOf(q, i + 1)
        }
        // Nothing closes cleanly (e.g. `a="x"b="y"`): fall back to the first matching quote.
        return s.indexOf(q, open + 1)
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
            val keyEnd = i
            // `key = "value"`: spaces are allowed on both sides of '='.
            while (i < n && s[i] == ' ') i++
            if (i >= n || s[i] != '=') {
                // bare token with no value, skip it
                continue
            }
            val key = s.substring(keyStart, keyEnd).lowercase()
            i++ // '='
            while (i < n && s[i] == ' ') i++
            val value: String
            if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val end = findClosingQuote(s, i)
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

    private fun buildEntry(p: PendingEntry, line: String, header: M3uHeader): M3uEntry {
        val a = p.attrs
        // Kodi style: `url|User-Agent=...&Referer=...`
        val url = line.substringBefore('|').trim()
        val pipe = pipeHeaders(line.substringAfter('|', ""))
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
            userAgent = p.userAgent ?: pipe["user-agent"] ?: a["http-user-agent"] ?: a["user-agent"],
            referrer = p.referrer ?: pipe["referer"] ?: pipe["referrer"]
                ?: a["http-referrer"] ?: a["http-referer"],
        )
    }

    /** Headers after the pipe of a Kodi-style URL: lower-cased names, URL-decoded values. */
    private fun pipeHeaders(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in s.split('&')) {
            val name = part.substringBefore('=', "").trim().lowercase()
            val raw = part.substringAfter('=', "").trim()
            if (name.isEmpty() || raw.isEmpty()) continue
            out[name] = runCatching {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(raw)
        }
        return out
    }
}
