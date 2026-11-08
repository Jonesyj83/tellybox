package com.tellybox.tv.data.parser.stalker

import java.security.MessageDigest

/**
 * The identity a portal expects from a set-top box. Most portals are happy with values derived
 * from the MAC address, but some are provisioned against a specific serial and device id, so
 * each one can be overridden.
 */
data class StalkerIdentity(
    val mac: String,
    val stbType: String = DEFAULT_STB_TYPE,
    val serialOverride: String? = null,
    val deviceIdOverride: String? = null,
    val deviceId2Override: String? = null,
) {
    val serial: String
        get() = serialOverride?.trim()?.takeIf { it.isNotEmpty() } ?: deriveSerial(mac)

    val deviceId: String
        get() = deviceIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: deriveDeviceId(mac)

    val deviceId2: String
        get() = deviceId2Override?.trim()?.takeIf { it.isNotEmpty() }
            ?: deviceIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: deriveDeviceId(mac)

    /** Portals that check it want a hash over the whole identity, not just the MAC. */
    val signature: String
        get() = sha256(mac + serial + deviceId + deviceId2).uppercase()

    /** Portals compare this against their own copy of the box firmware id. */
    val hwVersion2: String
        get() = sha1(mac)

    /** Shown as the X-User-Agent model, e.g. "Model: MAG250; Link: WiFi". */
    val model: String
        get() = stbType.trim().ifEmpty { DEFAULT_STB_TYPE }

    companion object {
        const val DEFAULT_STB_TYPE = "MAG250"

        /** The boxes these portals were written against. */
        val STB_TYPES = listOf(
            "MAG200", "MAG245", "MAG250", "MAG254", "MAG256",
            "MAG270", "MAG322", "MAG324", "MAG420", "MAG520",
        )

        fun deriveSerial(mac: String): String = md5(mac).take(13).uppercase()

        fun deriveDeviceId(mac: String): String = sha256(mac).uppercase()

        private fun md5(value: String): String = digest("MD5", value)

        private fun sha256(value: String): String = digest("SHA-256", value)

        private fun sha1(value: String): String = digest("SHA-1", value)

        private fun digest(algorithm: String, value: String): String =
            MessageDigest.getInstance(algorithm)
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
