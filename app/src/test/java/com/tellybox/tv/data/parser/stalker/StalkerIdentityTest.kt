package com.tellybox.tv.data.parser.stalker

import org.junit.Assert.assertEquals
import org.junit.Test

class StalkerIdentityTest {

    private val mac = "00:1A:79:00:00:01"

    @Test
    fun `serial comes from the first 13 characters of the md5 of the mac`() {
        assertEquals("306F5BD82D720", StalkerIdentity.deriveSerial(mac))
    }

    @Test
    fun `device id is the sha256 of the mac`() {
        assertEquals(
            "D20A30551398D28B10BF3676E4D2442D2442F8A398E42BAB7F203313594891B6",
            StalkerIdentity.deriveDeviceId(mac),
        )
    }

    @Test
    fun `signature covers the whole identity`() {
        val identity = StalkerIdentity(mac)
        assertEquals(
            "BF502C927C603D51ABFBBAF4E8CF102A77802382B00EBA2EF3489724500FFC73",
            identity.signature,
        )
    }

    @Test
    fun `device id 2 follows device id 1 unless it is set`() {
        val identity = StalkerIdentity(mac)
        assertEquals(identity.deviceId, identity.deviceId2)

        val overridden = StalkerIdentity(mac, deviceIdOverride = "ABC", deviceId2Override = "DEF")
        assertEquals("ABC", overridden.deviceId)
        assertEquals("DEF", overridden.deviceId2)
    }

    @Test
    fun `overrides win over the derived values`() {
        val identity = StalkerIdentity(
            mac = mac,
            stbType = "MAG270",
            serialOverride = "0123456789ABC",
            deviceIdOverride = "DEVICE1",
            deviceId2Override = "DEVICE2",
        )
        assertEquals("0123456789ABC", identity.serial)
        assertEquals("DEVICE1", identity.deviceId)
        assertEquals("DEVICE2", identity.deviceId2)
        assertEquals("MAG270", identity.model)
    }

    @Test
    fun `blank overrides fall back to the derived values`() {
        val identity = StalkerIdentity(mac, stbType = "  ", serialOverride = " ", deviceIdOverride = "")
        assertEquals(StalkerIdentity.deriveSerial(mac), identity.serial)
        assertEquals(StalkerIdentity.deriveDeviceId(mac), identity.deviceId)
        assertEquals(StalkerIdentity.DEFAULT_STB_TYPE, identity.model)
    }

    @Test
    fun `signature changes when the identity is overridden`() {
        val derived = StalkerIdentity(mac).signature
        val overridden = StalkerIdentity(mac, serialOverride = "0123456789ABC").signature
        assertEquals(false, derived == overridden)
    }
}
