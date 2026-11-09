package com.tellybox.tv.data.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLimitsTest {

    @Test
    fun `six hours exactly is still allowed`() {
        assertFalse(RecordingLimits.beyondLimit(RecordingLimits.PLATFORM_MS))
    }

    @Test
    fun `a minute past six hours is warned about`() {
        assertTrue(RecordingLimits.beyondLimit(RecordingLimits.PLATFORM_MS + 60_000))
    }

    @Test
    fun `a normal programme never warns`() {
        assertFalse(RecordingLimits.beyondLimit(90 * 60_000L))
    }
}
