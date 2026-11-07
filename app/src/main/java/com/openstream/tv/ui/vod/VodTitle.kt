package com.openstream.tv.ui.vod

/** A VOD name split for the poster card: "Heat (1995)" -> title "Heat", year "1995". */
data class VodTitle(val title: String, val year: String?) {
    companion object {
        private val openers = charArrayOf('(', '[')

        /**
         * Finds a trailing year in brackets, after a dash, or just at the end. Written as
         * plain string handling: it runs for every card while scrolling.
         */
        fun parse(name: String, maxYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1): VodTitle {
            val trimmed = name.trim()
            var end = trimmed.length
            // "(1995)" or "[1995]"
            if (end > 0 && (trimmed[end - 1] == ')' || trimmed[end - 1] == ']')) {
                val open = trimmed.lastIndexOfAny(openers)
                if (open >= 0) {
                    val inside = trimmed.substring(open + 1, end - 1).trim()
                    if (isYear(inside, maxYear)) return VodTitle(clean(trimmed.substring(0, open)), inside)
                }
                return VodTitle(trimmed, null)
            }
            // "Heat - 1995" or "Heat 1995"
            if (end >= 4) {
                val tail = trimmed.substring(end - 4)
                val before = trimmed.getOrNull(end - 5)
                if (isYear(tail, maxYear) && (before == null || before == ' ' || before == '-')) {
                    val title = clean(trimmed.substring(0, end - 4))
                    if (title.isNotEmpty()) return VodTitle(title, tail)
                }
            }
            return VodTitle(trimmed, null)
        }

        /** Four digits from 1900 to next year; "Blade Runner 2049" keeps its number. */
        private fun isYear(s: String, maxYear: Int): Boolean {
            if (s.length != 4 || !s.all { it in '0'..'9' }) return false
            return s.toInt() in 1900..maxYear
        }

        private fun clean(s: String) = s.trim().trimEnd('-', '–', '|', ':').trim()
    }
}
