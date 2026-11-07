package com.opentelly.tv.data.parser.xmltv

import java.util.Calendar
import java.util.TimeZone

object XmltvTime {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * Parses XMLTV timestamps: `YYYYMMDDhhmmss +hhmm`. Seconds, minutes, hours and the
     * offset are all optional in the spec; a missing offset is treated as UTC.
     * Returns epoch millis, or null if the value can't be read.
     */
    fun parse(value: String): Long? {
        val s = value.trim()
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        val digits = s.substring(0, i)
        if (digits.length < 8) return null

        fun part(from: Int, len: Int): Int =
            if (digits.length >= from + len) digits.substring(from, from + len).toInt() else 0

        val year = part(0, 4)
        val month = part(4, 2)
        val day = part(6, 2)
        val hour = part(8, 2)
        val minute = part(10, 2)
        val second = part(12, 2)
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val offsetMs = parseOffset(s.substring(i).trim()) ?: return null

        val cal = Calendar.getInstance(UTC).apply {
            clear()
            set(year, month - 1, day, hour, minute, second.coerceAtMost(59))
        }
        return cal.timeInMillis - offsetMs
    }

    /** "+0100", "-0530", "+01:00", "Z", "" (UTC). Returns null for garbage. */
    private fun parseOffset(raw: String): Long? {
        if (raw.isEmpty() || raw.equals("Z", true) || raw.equals("UTC", true) || raw.equals("GMT", true)) return 0
        val sign = when (raw[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = raw.substring(1).replace(":", "")
        if (body.length != 4 || !body.all { it.isDigit() }) return null
        val hours = body.substring(0, 2).toInt()
        val minutes = body.substring(2, 4).toInt()
        return sign * (hours * 3_600_000L + minutes * 60_000L)
    }

    /**
     * xmltv_ns is zero-based "season.episode.part" with optional "/total", e.g. "2.4/10."
     * → "S3 E5". Returns null if neither number is present.
     */
    fun formatXmltvNs(raw: String): String? {
        val parts = raw.replace(" ", "").split('.')
        fun num(p: String?): Int? = p?.substringBefore('/')?.toIntOrNull()?.plus(1)
        val season = num(parts.getOrNull(0))
        val episode = num(parts.getOrNull(1))
        return when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            season != null -> "S$season"
            else -> null
        }
    }
}
