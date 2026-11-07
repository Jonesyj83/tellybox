package com.opentelly.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {
    @Test fun canvasScaleAndClamps() {
        assertEquals(540f / 505f, UiScale(960, 540).scale, 0.0001f)
        assertEquals(0.85f, UiScale(640, 360).scale, 0f)
        assertEquals(0.85f, UiScale(1, 1).scale, 0f)
        assertEquals(1.35f, UiScale(3840, 2160).scale, 0f)
        assertEquals(648f / 505f, UiScale(1152, 648).scale, 0.0001f)
    }
    @Test fun dimensionsUseOneFactor() {
        val s = UiScale(640, 360)
        assertEquals(30 * s.scale, s.rowHeight(), 0f)
        assertEquals(24 * s.scale, s.railIcon(), 0f)
        assertEquals(26 * s.scale, s.guideRowHeight(), 0f)
        assertEquals(150 * s.scale, s.guideChannelWidth(), 0f)
        assertEquals(4.2f * s.scale, s.guidePxPerMinute(), 0f)
        assertEquals(28 * s.scale, s.categoryRowHeight(), 0f)
        assertEquals(110 * s.scale, s.posterWidth(), 0f)
        assertEquals(12 * s.scale, s.sp(12), 0f)
    }
}
