package com.tellybox.tv.data.recording

import java.util.concurrent.TimeUnit

/**
 * How long Android will let a background recording run.
 *
 * A data-sync foreground service is cut off after six hours in a day on Android 15, whatever the
 * storage and the network are doing. Nothing in the app can extend that, so the honest thing is to
 * say so before the timer is set rather than after the file stops.
 */
object RecordingLimits {

    val PLATFORM_MS: Long = TimeUnit.HOURS.toMillis(6)

    fun beyondLimit(durationMs: Long): Boolean = durationMs > PLATFORM_MS
}
