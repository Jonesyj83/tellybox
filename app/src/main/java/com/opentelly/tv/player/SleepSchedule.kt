package com.opentelly.tv.player

import android.os.Handler
import android.os.Looper

/**
 * The app's one sleep timer. Lives with [Playback] rather than a screen, so zapping, opening
 * the channel list or going to picture-in-picture don't reset it. When it goes off it stops
 * whatever is playing (full screen or the home preview) and tells the screens, which leave
 * the player and drop the preview.
 */
class SleepSchedule(private val playback: Playback, private val clock: () -> Long = System::currentTimeMillis) {
    private val timer = SleepTimer()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val fire = Runnable { check() }

    interface Listener {
        /** The timer went off and playback has been stopped. */
        fun onSleep()
        /** Set, changed or cancelled; refresh any countdown shown. */
        fun onSleepChanged() = Unit
    }

    val active get() = timer.active
    val untilProgrammeEnd get() = timer.untilProgrammeEnd
    fun remainingMinutes(): Int? = timer.remainingMinutes(clock())

    fun start(minutes: Int) {
        timer.start(minutes, clock())
        schedule()
    }

    fun startUntil(programmeStop: Long): Boolean {
        if (!timer.startUntil(programmeStop, clock())) return false
        schedule()
        return true
    }

    fun cancel() {
        main.removeCallbacks(fire)
        timer.cancel()
        notifyChanged()
    }

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }

    private fun schedule() {
        main.removeCallbacks(fire)
        // Re-checked on wake-up: Handler delays don't count deep sleep, the wall clock does.
        main.postDelayed(fire, (timer.remainingMs(clock()) ?: return).coerceAtMost(CHECK_EVERY_MS))
        notifyChanged()
    }

    private fun check() {
        if (!timer.active) return
        if (!timer.expired(clock())) return schedule()
        timer.cancel()
        playback.release()
        listeners.toList().forEach { it.onSleep() }
        notifyChanged()
    }

    private fun notifyChanged() = listeners.toList().forEach { it.onSleepChanged() }

    private companion object {
        const val CHECK_EVERY_MS = 60_000L
    }
}
