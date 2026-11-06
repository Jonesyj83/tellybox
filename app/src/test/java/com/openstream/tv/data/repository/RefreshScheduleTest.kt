package com.openstream.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshScheduleTest {
    private val hour = 3_600_000L

    @Test fun periodFollowsSettingWithOneHourFloor() {
        assertEquals(12L, RefreshSchedule.periodHours(12))
        assertEquals(1L, RefreshSchedule.periodHours(0))
    }

    @Test fun slackLetsTheNextRunCatchAPlaylistRefreshedSlightlyLate() {
        val now = 100 * hour
        // Refreshed by the previous run, which fired ten minutes late.
        val lastUpdated = now - 12 * hour + 10 * 60_000L
        assertTrue(lastUpdated > RefreshSchedule.staleCutoff(now, 12))
        assertTrue(lastUpdated < RefreshSchedule.staleCutoff(now, 12, RefreshSchedule.SLACK_MS))
    }
}
