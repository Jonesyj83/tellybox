package com.tellybox.tv.data.metadata

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class CatalogueTitle(val name: String, val year: Int?, val season: Int?, val episode: Int?)

object TitleMatcher {
    private val episode = Regex("""(?i)\b(?:s(\d{1,2})e(\d{1,3})|(\d{1,2})x(\d{1,3}))\b""")
    private val year = Regex("""(?:[(\[{]\s*|\s)(19\d{2}|20\d{2})(?:\s*[)\]}]|\s*$)""")
    private val tags = Regex("""(?i)\b(?:4k|8k|uhd|fhd|hd|sd|1080p|720p|2160p|cam|hdtv|bluray|blu-ray|web[ .-]?dl|webrip|x26[45]|hevc)\b""")
    private val suffix = Regex("""(?i)(?:[|:–-]\s*)?(?:english|french|german|spanish|hindi|arabic|netflix|amazon|disney\+?|hbo)(?:\s|[-|:])*$""")
    fun normalize(raw: String): CatalogueTitle {
        val ep = episode.find(raw)
        val season = ep?.let { (it.groups[1]?.value ?: it.groups[3]?.value)?.toIntOrNull() }
        val number = ep?.let { (it.groups[2]?.value ?: it.groups[4]?.value)?.toIntOrNull() }
        val release = year.findAll(raw).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        var text = episode.replace(raw, " ")
        text = text.replace(Regex("""\([^)]*\)|\[[^]]*]|\{[^}]*}"""), " ")
        text = tags.replace(text, " ").trim()
        text = text.replace(Regex("""(?:[\s|:–-]+)(?:19\d{2}|20\d{2})\s*$"""), "").trim()
        repeat(4) { text = suffix.replace(text, "").trim() }
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT).replace(Regex("['’]"), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (text.split(' ').all { it in setOf("english", "latest", "movies", "series", "vod", "tv") }) text = ""
        return CatalogueTitle(text, release, season, number)
    }

    fun score(title: CatalogueTitle, candidate: String, candidateYear: Int?): Double {
        val other = normalize(candidate).name
        if (title.name.isBlank() || other.isBlank()) return 0.0
        if (title.year != null && candidateYear != null && abs(title.year - candidateYear) > 1) return 0.0
        if (title.name == other) return 1.0
        val a = title.name.split(' ').toSet()
        val b = other.split(' ').toSet()
        // Only long titles tolerate a single omitted word; short names and sequels must be exact.
        if (a.size < 5 || b.size < 5 || a.any { it.all(Char::isDigit) } || b.any { it.all(Char::isDigit) }) return 0.0
        val overlap = a.intersect(b).size.toDouble() / a.union(b).size
        return if (overlap >= 0.85) overlap else 0.0
    }
}
