package com.tellybox.tv.player.timeshift

import com.tellybox.tv.player.timeshift.TimeshiftStorage.GB
import com.tellybox.tv.player.timeshift.TimeshiftStorage.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TimeshiftStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `needs a gigabyte and a half free to start`() {
        val r = TimeshiftStorage.capacity(1400 * MB, GB)
        assertEquals(TimeshiftStorage.Result.Unavailable(TimeshiftUnavailable.LowStorage(1400 * MB)), r)
    }

    @Test
    fun `takes the configured size when there is plenty of room`() {
        assertEquals(TimeshiftStorage.Result.Capacity(GB), TimeshiftStorage.capacity(32 * GB, GB))
    }

    @Test
    fun `never more than a quarter of free space`() {
        assertEquals(TimeshiftStorage.Result.Capacity(GB / 2), TimeshiftStorage.capacity(2 * GB, 2 * GB))
    }

    @Test
    fun `gives up below three hundred megabytes`() {
        assertTrue(TimeshiftStorage.shouldGiveUp(299 * MB))
        assertFalse(TimeshiftStorage.shouldGiveUp(300 * MB))
    }

    @Test
    fun `minutes for a size`() {
        // 1 GB at 5 Mbit/s is a little under half an hour.
        assertEquals(28, TimeshiftStorage.minutesFor(GB, 625_000))
        assertEquals(0, TimeshiftStorage.minutesFor(GB, 0))
    }

    @Test
    fun `sweep removes leftovers but not a file in use`() {
        val dir = tmp.newFolder("timeshift")
        val orphan = File(dir, "session-1-1.part").apply { writeText("x") }
        val closed = File(dir, "session-1-2.closed").apply { writeText("x") }
        val inUse = TimeshiftFiles.claim(dir).apply { writeText("x") }
        try {
            assertEquals(2, TimeshiftFiles.sweep(dir))
            assertFalse(orphan.exists())
            assertFalse(closed.exists())
            assertTrue(inUse.exists())
        } finally {
            TimeshiftFiles.release(inUse)
        }
        assertEquals(1, TimeshiftFiles.sweep(dir))
        assertFalse(inUse.exists())
    }

    @Test
    fun `sweep of a missing folder does nothing`() {
        assertEquals(0, TimeshiftFiles.sweep(File(tmp.root, "absent")))
    }
}
