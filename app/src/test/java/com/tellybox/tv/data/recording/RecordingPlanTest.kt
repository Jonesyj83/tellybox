package com.tellybox.tv.data.recording

import com.tellybox.tv.data.db.RecordingEntity
import com.tellybox.tv.data.db.RecordingStatus
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

    /** A programme from [from] to [to] minutes after [now], with the default 2 and 10 minute padding. */
    private fun padded(from: Long, to: Long, status: RecordingStatus = RecordingStatus.SCHEDULED) = RecordingEntity(
        playlistId = 1, channelKey = "c", channelName = "C", title = "t",
        programStart = now + from * min, programStop = now + to * min,
        startAt = now + (from - 2) * min, stopAt = now + (to + 10) * min, status = status,
    )

    @Test
    fun backToBackProgrammesStartWhenTheEarlierRecordingStops() {
        val first = padded(60, 120)
        val second = padded(120, 180)
        assertEquals(first.stopAt, RecordingPlan.startAfter(second, listOf(first)))
    }

    @Test
    fun recordingWithNothingInTheWayKeepsItsStart() {
        val next = padded(120, 180)
        assertEquals(next.startAt, RecordingPlan.startAfter(next, emptyList()))
        assertEquals(next.startAt, RecordingPlan.startAfter(next, listOf(padded(0, 30))))
    }

    @Test
    fun overlappingProgrammesStillClash() {
        assertNull(RecordingPlan.startAfter(padded(90, 150), listOf(padded(60, 120))))
        // Recording now while the channel's current programme is being recorded.
        val instant = RecordingPlan.instant(now + 70 * min, 30 * min)
        val recordNow = padded(60, 120).copy(programStart = instant.startAt, programStop = instant.stopAt,
            startAt = instant.startAt, stopAt = instant.stopAt)
        assertNull(RecordingPlan.startAfter(recordNow, listOf(padded(60, 120, RecordingStatus.RECORDING))))
    }

    @Test
    fun recordingThatFitsInsideTheEarlierPaddingIsRefused() {
        val first = padded(60, 120)
        val tiny = padded(120, 121).copy(startAt = now + 120 * min, stopAt = now + 125 * min)
        assertNull(RecordingPlan.startAfter(tiny, listOf(first)))
    }

    @Test
    fun aRowNothingIsRecordingKeepsWhatReachedTheFile() {
        val rec = padded(0, 60, RecordingStatus.RECORDING)
        val kept = RecordingPlan.closeOut(rec, 5_000, "note", RecordingStatus.CANCELLED)
        assertEquals(RecordingStatus.COMPLETED, kept.status)
        assertEquals(5_000L, kept.bytes)
        assertEquals("note", kept.error)

        val stopped = RecordingPlan.closeOut(rec, 0, "note", RecordingStatus.CANCELLED)
        assertEquals(RecordingStatus.CANCELLED, stopped.status)
        assertNull(stopped.error)

        val refused = RecordingPlan.closeOut(rec, 0, "note", RecordingStatus.FAILED)
        assertEquals(RecordingStatus.FAILED, refused.status)
        assertEquals("note", refused.error)
    }
}
