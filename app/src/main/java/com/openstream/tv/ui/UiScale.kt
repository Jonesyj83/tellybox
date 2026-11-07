package com.openstream.tv.ui

/** Logical TV canvas sizing; physical output resolution is deliberately irrelevant. */
data class UiScale(val widthDp: Int, val heightDp: Int) {
    val scale = (heightDp / 505f).coerceIn(0.85f, 1.35f)
    fun dp(base: Number): Float = base.toFloat() * scale
    fun sp(base: Number): Float = base.toFloat() * scale
    fun rowHeight() = dp(30)
    fun railIcon() = dp(24)
    fun guideRowHeight() = dp(26)
    fun guideChannelWidth() = dp(150)
    fun guidePxPerMinute() = dp(4.2f)
    fun categoryRowHeight() = dp(28)
    fun posterWidth() = dp(110)
}
