package com.tellybox.tv.ui

/**
 * Logical TV canvas sizing; physical output resolution is deliberately irrelevant.
 *
 * [compact] is a phone or a small tablet: those screens are far taller than a TV in dp, which
 * would push the scale to its ceiling and blow everything up. They also get a narrower channel
 * column and a wider minute, so a film title fits instead of being cut to three letters.
 */
data class UiScale(val widthDp: Int, val heightDp: Int, val compact: Boolean = false) {
    val scale = if (compact) {
        (heightDp / 900f).coerceIn(0.85f, 1.0f)
    } else {
        (heightDp / 505f).coerceIn(0.85f, 1.35f)
    }
    fun dp(base: Number): Float = base.toFloat() * scale
    fun sp(base: Number): Float = base.toFloat() * scale
    fun rowHeight() = dp(if (compact) 40 else 30)
    fun railIcon() = dp(24)
    fun guideRowHeight() = dp(26)
    fun guideChannelWidth() = dp(if (compact) 118 else 150)
    fun guidePxPerMinute() = dp(if (compact) 5.5f else 4.2f)
    fun categoryRowHeight() = dp(28)
    fun posterWidth() = dp(110)
}
