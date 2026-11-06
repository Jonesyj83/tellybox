package com.openstream.tv.data.parental

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinSecurity {
    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }
    fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun matches(pin: String, salt: ByteArray, expected: ByteArray) = MessageDigest.isEqual(hash(pin, salt), expected)
}

class UnlockWindow(private val clock: () -> Long) {
    private var deadline = 0L
    fun unlock(minutes: Int) { deadline = if (minutes == 0) Long.MAX_VALUE else clock() + minutes * 60_000L }
    fun lock() { deadline = 0L }
    fun isOpen() = clock() < deadline
}

/** The returned state is persisted so restarting does not reset failed attempts. */
data class PinAttempts(val failures: Int = 0, val blockedUntil: Long = 0) {
    fun secondsLeft(now: Long) = ((blockedUntil - now + 999) / 1000).coerceAtLeast(0)
    fun failed(now: Long): PinAttempts = when {
        secondsLeft(now) > 0 -> this
        failures >= 4 -> PinAttempts(0, now + 60_000)
        else -> PinAttempts(failures + 1, 0)
    }
}
