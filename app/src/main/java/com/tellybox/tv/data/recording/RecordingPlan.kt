package com.tellybox.tv.data.recording

import com.tellybox.tv.data.catchup.CatchupUrls
import java.util.Calendar
import java.util.TimeZone

/** Works out when and how a recording runs. No Android dependencies so it can be unit-tested. */
object RecordingPlan {
    sealed class Plan {
        /** Capture the live stream between these wall-clock times. */
        data class Live(val startAt: Long, val stopAt: Long) : Plan()

        /** Download the provider's archive of this span now. */
        data class Catchup(val start: Long, val stop: Long) : Plan()

        data class Refused(val reason: String) : Plan()
    }

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
        if (stop <= start) return Plan.Refused("This programme has no running time")
        val paddedStart = start - padBeforeMinutes.coerceAtLeast(0) * MINUTE
        val paddedStop = stop + padAfterMinutes.coerceAtLeast(0) * MINUTE
        if (stop <= now) {
            // Already over, so only the provider's archive has it. Prefer the padded span,
            // fall back to the bare programme if the padding pokes outside the archive.
            val window = listOf(paddedStart to minOf(paddedStop, now), start to stop)
                .firstOrNull { (from, to) -> CatchupUrls.available(catchupDays, from, to, now) }
            return when {
                window != null -> Plan.Catchup(window.first, window.second)
                catchupDays <= 0 -> Plan.Refused("This programme has ended and the channel has no catch-up archive")
                else -> Plan.Refused("This programme is older than the channel's $catchupDays-day archive")
            }
        }
        return Plan.Live(maxOf(paddedStart, now), paddedStop)
    }

    fun instant(now: Long, durationMs: Long): Plan.Live = Plan.Live(now, now + durationMs.coerceAtLeast(MINUTE))

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
