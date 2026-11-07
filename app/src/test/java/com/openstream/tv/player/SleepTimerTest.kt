package com.openstream.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val t0 = 1_000_000_000L
    private val minute = 60_000L

    @Test fun notSetByDefault() {
        val timer = SleepTimer()
        assertFalse(timer.active)
        assertNull(timer.remainingMs(t0))
        assertNull(timer.remainingMinutes(t0))
        assertFalse(timer.expired(t0))
    }

    @Test fun startCountsDownFromNow() {
        val timer = SleepTimer()
        timer.start(30, t0)
        assertTrue(timer.active)
        assertFalse(timer.untilProgrammeEnd)
        assertEquals(t0 + 30 * minute, timer.endsAt)
        assertEquals(30, timer.remainingMinutes(t0))
        assertEquals(18 * minute, timer.remainingMs(t0 + 12 * minute))
        assertEquals(18, timer.remainingMinutes(t0 + 12 * minute))
    }

    @Test fun minutesRoundUpUntilTheEnd() {
        val timer = SleepTimer()
        timer.start(60, t0)
        assertEquals(42, timer.remainingMinutes(t0 + 18 * minute))
        assertEquals(43, timer.remainingMinutes(t0 + 17 * minute + 1))
        assertEquals(1, timer.remainingMinutes(t0 + 60 * minute - 1))
        assertEquals(0, timer.remainingMinutes(t0 + 60 * minute))
    }

    @Test fun expiresAtTheEndTimeAndNotBefore() {
        val timer = SleepTimer()
        timer.start(90, t0)
        assertFalse(timer.expired(t0 + 90 * minute - 1))
        assertTrue(timer.expired(t0 + 90 * minute))
        assertTrue(timer.expired(t0 + 200 * minute))
        assertEquals(0L, timer.remainingMs(t0 + 200 * minute))
    }

    @Test fun cancelClearsIt() {
        val timer = SleepTimer()
        timer.start(120, t0)
        timer.cancel()
        assertFalse(timer.active)
        assertNull(timer.endsAt)
        assertFalse(timer.expired(t0 + 500 * minute))
    }

    @Test fun startingAgainReplacesTheOldTime() {
        val timer = SleepTimer()
        timer.start(120, t0)
        timer.start(30, t0 + 10 * minute)
        assertEquals(t0 + 40 * minute, timer.endsAt)
    }

    @Test fun endOfProgrammeGoesOffWhenItEnds() {
        val timer = SleepTimer()
        val stop = t0 + 25 * minute
        assertTrue(timer.startUntil(stop, t0))
        assertTrue(timer.untilProgrammeEnd)
        assertEquals(stop, timer.endsAt)
        assertEquals(25, timer.remainingMinutes(t0))
        assertFalse(timer.expired(stop - 1))
        assertTrue(timer.expired(stop))
    }

    @Test fun endOfProgrammeRefusesOneThatAlreadyEnded() {
        val timer = SleepTimer()
        assertFalse(timer.startUntil(t0, t0))
        assertFalse(timer.startUntil(t0 - minute, t0))
        assertFalse(timer.active)
    }

    @Test fun minutesThenEndOfProgrammeSwitchesMode() {
        val timer = SleepTimer()
        timer.startUntil(t0 + 10 * minute, t0)
        timer.start(30, t0)
        assertFalse(timer.untilProgrammeEnd)
        timer.cancel()
        assertFalse(timer.untilProgrammeEnd)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroMinutes() {
        SleepTimer().start(0, t0)
    }
}
