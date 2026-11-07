package com.opentelly.tv.data.settings

/** Literal, case-sensitive edge tokens, one per line. Raw provider names stay intact. */
object ChannelNames {
    fun display(raw: String, configured: String): String {
        val tokens = configured.lineSequence().filter { it.isNotBlank() }.distinct()
            .sortedByDescending { it.length }.toList()
        var name = raw
        while (true) {
            val token = tokens.firstOrNull { name.startsWith(it) || name.endsWith(it) } ?: break
            val next = (if (name.startsWith(token)) name.removePrefix(token) else name.removeSuffix(token)).trim()
            if (next.isEmpty()) return raw
            name = next
        }
        return name
    }
}
