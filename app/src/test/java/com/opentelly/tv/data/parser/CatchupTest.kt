package com.opentelly.tv.data.parser

import com.opentelly.tv.data.catchup.CatchupUrls
import org.junit.Assert.*
import org.junit.Test

class CatchupTest {
    @Test fun archiveWindow() {
        val day = 86_400_000L
        val now = 10 * day
        assertTrue(CatchupUrls.available(2, 8 * day, 9 * day, now))
        assertFalse(CatchupUrls.available(2, 8 * day - 1, 9 * day, now))
        assertFalse(CatchupUrls.available(0, 9 * day, now, now))
        assertFalse(CatchupUrls.available(2, now, now + 1000, now))
        assertFalse(CatchupUrls.available(2, now, now, now))
    }

    @Test fun templatesAndAppend() {
        assertEquals("http://h/archive?start=100&end=220&minutes=2",
            CatchupUrls.build("http://h/live", "default",
                "http://h/archive?start={utc}&end={utcend}&minutes={duration:60}", 100000, 220000, 300000))
        assertEquals("http://h/live?utc=100&lutc=300",
            CatchupUrls.build("http://h/live", "shift", null, 100000, 220000, 300000))
        assertEquals("http://h/live?start=100",
            CatchupUrls.build("http://h/live", "append", "?start={start}", 100000, 220000, 300000))
        assertNull(CatchupUrls.build("http://h/live", null, null, 100000, 220000, 300000))
    }

    @Test fun xtreamArchive() {
        assertEquals("http://h/timeshift/user/pass/2/1970-01-01:00-00/42.ts",
            CatchupUrls.build("http://h/live/user/pass/42.ts", "xc", null, 0, 120000, 300000))
    }
}
