package com.tellybox.tv.data.recording

import com.tellybox.tv.data.catchup.CatchupUrls
import com.tellybox.tv.data.db.RecordingEntity
import com.tellybox.tv.data.db.RecordingStatus
import java.util.Calendar
import java.util.TimeZone

/** Works out when and how a recording runs. No Android dependencies so it can be unit-tested. */
object RecordingPlan {
    sealed class Plan {
        /** Capture the live stream between these wall-clock times. */
        data class Live(val startAt: Long, val stopAt: Long) : Plan()

        /** Download the provider's archive of this span now. */
        data class Catchup(val start: Long, val stop: Long) : Plan()

        data class Refused(val reason: Refusal) : Plan()
    }

    enum class Refusal { NO_DURATION, NO_ARCHIVE, ARCHIVE_EXPIRED }

    private const val MINUTE = 60_000L

    /** Extra wall-clock time an archive download may take beyond the programme's length. */
    const val CATCHUP_SLACK_MS = 30 * MINUTE

    fun forProgram(
        start: Long,
        stop: Long,
        now: Long,
        padBeforeMinutes: Int,
        padAfterMinutes: Int,
        catchupDays: Int,
    ): Plan {
        if (stop <= start) return Plan.Refused(Refusal.NO_DURATION)
        val paddedStart = start - padBeforeMinutes.coerceAtLeast(0) * MINUTE
        val paddedStop = stop + padAfterMinutes.coerceAtLeast(0) * MINUTE
        if (stop <= now) {
            // Already over, so only the provider's archive has it. Prefer the padded span,
            // fall back to the bare programme if the padding pokes outside the archive.
            val window = listOf(paddedStart to minOf(paddedStop, now), start to stop)
                .firstOrNull { (from, to) -> CatchupUrls.available(catchupDays, from, to, now) }
            return when {
                window != null -> Plan.Catchup(window.first, window.second)
                catchupDays <= 0 -> Plan.Refused(Refusal.NO_ARCHIVE)
                else -> Plan.Refused(Refusal.ARCHIVE_EXPIRED)
            }
        }
        return Plan.Live(maxOf(paddedStart, now), paddedStop)
    }

    fun instant(now: Long, durationMs: Long): Plan.Live = Plan.Live(now, now + durationMs.coerceAtLeast(MINUTE))

    /**
     * When [rec] can start given the recordings already active on its channel, or null if it
     * clashes with one. With padding, one programme's recording runs into the start of the
     * next, so an overlap made only of padding is settled by starting [rec] when the earlier
     * recording stops.
     */
    fun startAfter(rec: RecordingEntity, active: List<RecordingEntity>): Long? {
        var start = rec.startAt
        for (other in active.sortedBy { it.stopAt }) {
            if (other.startAt >= rec.stopAt || other.stopAt <= start) continue
            // The other programme ends before this one begins, so only padding overlaps.
            if (other.programStop > rec.programStart) return null
            start = maxOf(start, other.stopAt)
        }
        return start.takeIf { it < rec.stopAt }
    }

    /**
     * A row that says RECORDING but that nothing is recording any more. Kept as a finished
     * recording with [note] if anything reached the file, otherwise given [emptyStatus]; the
     * note goes with that only when it is a failure.
     */
    fun closeOut(rec: RecordingEntity, fileSize: Long, note: String?, emptyStatus: RecordingStatus): RecordingEntity =
        if (fileSize > 0) rec.copy(status = RecordingStatus.COMPLETED, bytes = fileSize, error = note)
        else rec.copy(status = emptyStatus, error = if (emptyStatus == RecordingStatus.FAILED) note else null)

    /** Wall-clock deadline for an archive download started at [now]. */
    fun catchupDeadline(now: Long, start: Long, stop: Long): Long = now + (stop - start) + CATCHUP_SLACK_MS

    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** e.g. `BBC One - News at Six - 2026-11-07 1800.ts`, safe on FAT/exFAT USB drives too. */
    fun fileName(channel: String, title: String, start: Long, zone: TimeZone = TimeZone.getDefault()): String {
        fun clean(s: String) = s.replace(illegal, "_").replace(Regex("\\s+"), " ").trim().trim('.')
        val cal = Calendar.getInstance(zone).apply { timeInMillis = start }
        val stamp = "%04d-%02d-%02d %02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
        )
        val parts = listOf(clean(channel), clean(title)).filter { it.isNotEmpty() }.distinct()
        val stem = (parts + stamp).joinToString(" - ").take(150)
        return "$stem.ts"
    }
}

object ReminderPlan {
    /** When to pop up, or null if the programme has already started. */
    fun remindAt(programStart: Long, now: Long, minutesBefore: Int): Long? {
        if (programStart <= now) return null
        return maxOf(now, programStart - minutesBefore.coerceAtLeast(0) * 60_000L)
    }
}
