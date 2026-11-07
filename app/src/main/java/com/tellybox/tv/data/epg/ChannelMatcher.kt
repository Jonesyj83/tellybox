package com.tellybox.tv.data.epg

import java.util.Locale

/** Matching names never changes the names shown to the viewer. */
object ChannelMatcher {
    data class Channel(val key: Long, val tvgId: String?, val names: List<String>)
    data class Guide(val id: String, val names: List<String>)
    data class Match(val channel: Long, val guideId: String, val pass: String)

    private val spaces = Regex("\\s+")
    private val region = Regex("^(?:au|australia|uk|gb|us|usa|ca|nz|ie|de|fr|it|es|in|za)\\s*[:|\\-]\\s*")
    private val noise = Regex("\\b(?:hd|fhd|uhd|4k|sd|hevc|h265|raw|backup|alt|vip)\\b")
    private val provider = Regex("\\s+(?:by|provider:)\\s+.*$")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    /** Removes anything inside (), [] or {}, by scanning rather than with a regex. */
    private fun stripBrackets(text: String): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (ch in text) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(ch)
            }
        }
        return out.toString()
    }

    fun normalize(name: String, stripTags: Boolean = true): String {
        var value = name.trim().lowercase(Locale.ROOT)
        if (stripTags) {
            value = region.replace(value, "")
            value = stripBrackets(value)
            // Explicit provider annotations are safe to discard; arbitrary words are not.
            value = provider.replace(value, "")
            value = noise.replace(value, " ")
        }
        return spaces.replace(punctuation.replace(value, " ").trim(), " ")
    }

    fun match(channels: List<Channel>, guides: List<Guide>): List<Match> {
        val exactIds = guides.associateBy { it.id }
        fun index(strip: Boolean): Map<String, Set<String>> {
            val out = HashMap<String, MutableSet<String>>()
            guides.forEach { g -> g.names.forEach { name ->
                val key = normalize(name, strip)
                if (key.isNotEmpty()) out.getOrPut(key) { HashSet() }.add(g.id)
            } }
            return out
        }
        val exact = index(false)
        val clean = index(true)
        // Index token-contained phrases, avoiding a playlist-by-guide cross product.
        fun phrases(name: String): List<String> {
            val tokens = name.split(' ')
            return buildList {
                for (start in tokens.indices) for (end in start + 2..tokens.size) {
                    // Numbers distinguish stations and time-shifted feeds; never drop them in a fallback.
                    if (tokens.indices.any { it !in start until end && tokens[it].any(Char::isDigit) }) continue
                    add(tokens.subList(start, end).joinToString(" "))
                }
            }
        }
        val contained = HashMap<String, MutableSet<String>>()
        clean.forEach { (name, ids) -> phrases(name).forEach { contained.getOrPut(it) { HashSet() }.addAll(ids) } }
        val result = LinkedHashMap<Long, Match>()
        val claimed = HashSet<String>()
        val ordered = channels.sortedBy { it.key }
        fun pass(label: String, candidates: (Channel) -> Set<String>) {
            ordered.forEach { c ->
                if (c.key in result) return@forEach
                // Ambiguity stays ambiguous even if another channel claimed a candidate.
                val ids = candidates(c)
                if (ids.size == 1) {
                    val id = ids.single()
                    if (claimed.add(id)) result[c.key] = Match(c.key, id, label)
                }
            }
        }
        pass("id") { c -> c.tvgId?.trim()?.takeIf { it in exactIds }?.let { setOf(it) }.orEmpty() }
        pass("name") { c -> c.names.flatMap { exact[normalize(it, false)].orEmpty() }.toSet() }
        pass("normalized") { c -> c.names.flatMap { clean[normalize(it)].orEmpty() }.toSet() }
        pass("contained") { c -> c.names.flatMap { name ->
            val key = normalize(name)
            contained[key].orEmpty() + phrases(key).flatMap { clean[it].orEmpty() }
        }.toSet() }
        return result.values.toList()
    }
}
