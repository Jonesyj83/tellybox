package com.openstream.tv.data.parental

import org.junit.Assert.*
import org.junit.Test

class PinSecurityTest {
    @Test fun pinFormat() {
        assertTrue(PinSecurity.valid("0123"))
        assertTrue(PinSecurity.valid("12345678"))
        listOf("", "123", "123456789", "12a4", "１２３４", "1234 ").forEach { assertFalse(PinSecurity.valid(it)) }
    }
    @Test fun saltedHashAndVerification() {
        val salt = PinSecurity.salt()
        val hash = PinSecurity.hash("0123", salt)
        assertEquals(32, hash.size)
        assertTrue(PinSecurity.matches("0123", salt, hash))
        assertFalse(PinSecurity.matches("0124", salt, hash))
        assertFalse(hash.contentEquals(PinSecurity.hash("0123", PinSecurity.salt())))
        assertFalse(hash.contentEquals("0123".toByteArray()))
    }
    @Test fun allTimedWindowsExpireAtBoundary() {
        for (minutes in listOf(10, 30, 60, 120)) {
            var now = 1000L
            val window = UnlockWindow { now }
            assertFalse(window.isOpen())
            window.unlock(minutes)
            now += minutes * 60_000L - 1
            assertTrue(window.isOpen())
            now++
            assertFalse(window.isOpen())
        }
    }
    @Test fun sessionUnlockIsNotInheritedByNewSession() {
        val window = UnlockWindow { 1000L }
        window.unlock(0)
        assertTrue(window.isOpen())
        assertFalse(UnlockWindow { 1000L }.isOpen())
        window.lock()
        assertFalse(window.isOpen())
    }
    @Test fun fifthFailureLocksForSixtySecondsAndSurvivesReload() {
        var attempts = PinAttempts()
        repeat(4) { attempts = attempts.failed(1000); assertEquals(0L, attempts.secondsLeft(1000)) }
        attempts = attempts.failed(1000)
        assertEquals(60L, attempts.secondsLeft(1000))
        val reloaded = PinAttempts(attempts.failures, attempts.blockedUntil)
        assertEquals(1L, reloaded.secondsLeft(60_999))
        assertEquals(0L, reloaded.secondsLeft(61_000))
        assertEquals(reloaded, reloaded.failed(2000))
        assertEquals(PinAttempts(1, 0), reloaded.failed(61_000))
    }
}
