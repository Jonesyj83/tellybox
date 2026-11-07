package com.opentelly.tv.data.recording

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Just enough of HLS to record a stream: pick a variant from a master playlist, then walk
 * the media playlist's segments. Recordings are written as concatenated MPEG-TS, which is
 * a valid file on its own, so only TS segments (plain or AES-128) are handled. Anything
 * that would need more than byte concatenation is reported through [Media.unsupported].
 */
sealed class HlsPlaylist {
    data class Variant(val url: String, val bandwidth: Long)

    data class Master(val variants: List<Variant>, val unsupported: String? = null) : HlsPlaylist() {
        /** Recordings keep the best picture on offer. */
        fun best(): Variant? = variants.maxByOrNull { it.bandwidth }
    }

    class Key(val method: String, val url: String?, val iv: ByteArray?)

    data class Segment(val url: String, val sequence: Long, val durationSec: Double, val key: Key?)

    data class Media(
        val targetDurationSec: Int,
        val mediaSequence: Long,
        val segments: List<Segment>,
        val ended: Boolean,
        val unsupported: String? = null,
    ) : HlsPlaylist()

    companion object {
        fun looksLikePlaylist(firstBytes: String): Boolean =
            firstBytes.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

        fun parse(text: String, baseUrl: String): HlsPlaylist {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl)
            else parseMedia(lines, baseUrl)
        }

        private fun parseMaster(lines: List<String>, baseUrl: String): Master {
            val variants = ArrayList<Variant>()
            val separateAudio = HashSet<String>()
            var pending: Map<String, String>? = null
            var unsupported: String? = null
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val a = attributes(line.substringAfter(':'))
                        if (a["TYPE"] == "AUDIO" && a["URI"] != null) a["GROUP-ID"]?.let(separateAudio::add)
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                    line.startsWith("#") -> Unit
                    pending != null -> {
                        val a = pending
                        pending = null
                        if (a["AUDIO"] != null && a["AUDIO"] in separateAudio) {
                            unsupported = "The stream carries its sound as a separate track, which can't be recorded yet"
                            continue
                        }
                        resolve(baseUrl, line)?.let { variants += Variant(it, a["BANDWIDTH"]?.toLongOrNull() ?: 0) }
                    }
                }
            }
            return Master(variants, unsupported.takeIf { variants.isEmpty() })
        }

        private fun parseMedia(lines: List<String>, baseUrl: String): Media {
            var target = 6
            var firstSequence = 0L
            var ended = false
            var unsupported: String? = null
            var key: Key? = null
            var duration = 0.0
            val segments = ArrayList<Segment>()
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        target = line.substringAfter(':').trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: target
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        firstSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#EXT-X-MAP") ->
                        unsupported = "The stream uses fragmented MP4 segments, which can't be recorded yet"
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = attributes(line.substringAfter(':'))
                        val method = a["METHOD"]?.uppercase() ?: "NONE"
                        val format = a["KEYFORMAT"]
                        key = when {
                            method == "NONE" -> null
                            method == "AES-128" && (format == null || format == "identity") ->
                                Key(method, a["URI"]?.let { resolve(baseUrl, it) }, a["IV"]?.let(::parseIv))
                            else -> {
                                unsupported = "The stream is protected with DRM ($method) and can't be recorded"
                                null
                            }
                        }
                    }
                    line.startsWith("#EXTINF:") ->
                        duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line.startsWith("#") -> Unit
                    else -> {
                        resolve(baseUrl, line)?.let {
                            segments += Segment(it, firstSequence + segments.size, duration, key)
                        }
                        duration = 0.0
                    }
                }
            }
            return Media(target, firstSequence, segments, ended, unsupported)
        }

        /** Parses `KEY=value,KEY="quoted, value"` attribute lists. */
        fun attributes(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            val regex = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
            regex.findAll(text).forEach { m ->
                out[m.groupValues[1]] = if (m.groups[3] != null) m.groupValues[3] else m.groupValues[2].trim()
            }
            return out
        }

        private fun parseIv(hex: String): ByteArray? {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty() || clean.length > 32 || clean.any { Character.digit(it, 16) < 0 }) return null
            val padded = clean.padStart(32, '0')
            return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        /** IV used when the key tag has none: the segment's media sequence number, big-endian. */
        fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }

        fun resolve(baseUrl: String, reference: String): String? {
            if (reference.contains("://")) return reference
            return baseUrl.toHttpUrlOrNull()?.resolve(reference)?.toString()
        }
    }
}
