package com.tellybox.tv.player

import com.tellybox.tv.ui.player.markerFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub line's play head. These numbers decide whether the bar feels like a bar or like
 * something twitching, so they get pinned down here rather than judged by eye on a TV.
 */
class TimeshiftBarGeometryTest {

    @Test
    fun `at the live edge the head sits at the end`() {
        assertEquals(1f, markerFraction(windowMs = 120_000, behindMs = 0), 0.0001f)
    }

    @Test
    fun `thirty seconds back in a two minute window is a quarter of the bar`() {
        assertEquals(0.75f, markerFraction(windowMs = 120_000, behindMs = 30_000), 0.0001f)
    }

    @Test
    fun `the same skip in a longer window moves the head less`() {
        assertEquals(0.95f, markerFraction(windowMs = 600_000, behindMs = 30_000), 0.0001f)
    }

    @Test
    fun `the oldest byte is the left end`() {
        assertEquals(0f, markerFraction(windowMs = 120_000, behindMs = 120_000), 0.0001f)
    }

    @Test
    fun `being further behind than the buffer holds clamps to the start`() {
        assertEquals(0f, markerFraction(windowMs = 60_000, behindMs = 90_000), 0.0001f)
    }

    @Test
    fun `an empty buffer does not divide by zero`() {
        assertEquals(1f, markerFraction(windowMs = 0, behindMs = 0), 0.0001f)
    }

    /**
     * Paused, the buffer keeps growing while the picture stays put. Each second must move the
     * head by the same small amount; a changing step is what reads as jumping about.
     */
    @Test
    fun `growing the window while paused moves the head evenly`() {
        val windowAtStart = 60_000L
        var previous = markerFraction(windowAtStart, behindMs = 0)
        for (second in 1..60) {
            val window = windowAtStart + second * 1_000L
            val current = markerFraction(window, behindMs = second * 1_000L)
            val step = previous - current
            assertTrue("step $step at second $second", step >= 0f && step < 0.02f)
            previous = current
        }
        assertTrue("head should have drifted left", previous < 0.55f)
    }

    @Test
    fun `seeking backwards moves the head left and forwards moves it right`() {
        val window = 180_000L
        assertEquals(0.6667f, markerFraction(window, behindMs = 60_000), 0.001f)
        assertEquals(0.3333f, markerFraction(window, behindMs = 120_000), 0.001f)
        assertEquals(0.8333f, markerFraction(window, behindMs = 30_000), 0.001f)
    }
}
