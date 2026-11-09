package com.tellybox.tv.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecordingPlanTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun futureProgrammeIsPaddedBothSides() {
        val plan = RecordingPlan.forProgram(now + 60 * min, now + 120 * min, now, 2, 10, 0)
        assertEquals(RecordingPlan.Plan.Live(now + 58 * min, now + 130 * min), plan)
    }

    @Test
    fun programmeInProgressStartsNow() {
        val plan = RecordingPlan.forProgram(now - 10 * min, now + 20 * min, now, 5, 5, 0)
        assertEquals(RecordingPlan.Plan.Live(now, now + 25 * min), plan)
    }

    @Test
    fun endedProgrammeUsesCatchupWhenAvailable() {
        val plan = RecordingPlan.forProgram(now - 90 * min, now - 30 * min, now, 2, 5, 3)
        assertEquals(RecordingPlan.Plan.Catchup(now - 92 * min, now - 25 * min), plan)
    }

    @Test
    fun paddingIsClampedToNowForJustEndedProgramme() {
        val plan = RecordingPlan.forProgram(now - 60 * min, now - 1 * min, now, 0, 10, 1)
        assertEquals(RecordingPlan.Plan.Catchup(now - 60 * min, now), plan)
    }

    @Test
    fun endedProgrammeWithoutArchiveIsRefused() {
        val none = RecordingPlan.forProgram(now - 90 * min, now - 30 * min, now, 0, 0, 0)
        assertTrue(none is RecordingPlan.Plan.Refused)
        val tooOld = RecordingPlan.forProgram(now - 3 * 24 * 60 * min, now - 3 * 24 * 60 * min + 30 * min, now, 0, 0, 1)
        assertEquals(RecordingPlan.Refusal.ARCHIVE_EXPIRED, (tooOld as RecordingPlan.Plan.Refused).reason)
    }

    @Test
    fun zeroLengthIsRefused() {
        assertTrue(RecordingPlan.forProgram(now, now, now - 1, 0, 0, 0) is RecordingPlan.Plan.Refused)
    }

    @Test
    fun fileNameIsSafeAndReadable() {
        val start = 1_793_210_400_000L // 2026-10-28 18:00 UTC
        val name = RecordingPlan.fileName("UK: BBC/One", "News: \"Late\" Edition?", start, TimeZone.getTimeZone("UTC"))
        assertEquals("UK_ BBC_One - News_ _Late_ Edition_ - 2026-10-28 1800.ts", name)
        assertEquals("Film - 2026-10-28 1800.ts", RecordingPlan.fileName("Film", "Film", start, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun reminderTimes() {
        assertEquals(now + 8 * min, ReminderPlan.remindAt(now + 10 * min, now, 2))
        assertEquals(now, ReminderPlan.remindAt(now + 1 * min, now, 5))
        assertNull(ReminderPlan.remindAt(now - 1, now, 5))
    }
}
