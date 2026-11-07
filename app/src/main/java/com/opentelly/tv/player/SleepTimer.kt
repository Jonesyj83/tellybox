package com.opentelly.tv.player

/**
 * Countdown for the sleep timer. Plain state with the clock passed in, so it can be tested.
 * It is time based: zapping to another channel leaves it running.
 */
class SleepTimer {
    /** Wall-clock time the timer goes off, or null when it isn't set. */
    var endsAt: Long? = null
        private set

    /** Set from "End of programme" rather than a number of minutes. */
    var untilProgrammeEnd = false
        private set

    val active get() = endsAt != null

    fun start(minutes: Int, now: Long) {
        require(minutes > 0) { "minutes must be positive" }
        endsAt = now + minutes * 60_000L
        untilProgrammeEnd = false
    }

    /** Goes off when the programme ends at [programmeStop]; false (and nothing set) if it already has. */
    fun startUntil(programmeStop: Long, now: Long): Boolean {
        if (programmeStop <= now) return false
        endsAt = programmeStop
        untilProgrammeEnd = true
        return true
    }

    fun cancel() {
        endsAt = null
        untilProgrammeEnd = false
    }

    /** Milliseconds left, 0 once expired, null when not set. */
    fun remainingMs(now: Long): Long? = endsAt?.let { (it - now).coerceAtLeast(0) }

    /** Whole minutes left, rounded up so "1 min" shows until the very end. */
    fun remainingMinutes(now: Long): Int? = remainingMs(now)?.let { ((it + 59_999) / 60_000).toInt() }

    fun expired(now: Long): Boolean = endsAt?.let { now >= it } ?: false

    companion object {
        val CHOICES = listOf(30, 60, 90, 120)
    }
}
