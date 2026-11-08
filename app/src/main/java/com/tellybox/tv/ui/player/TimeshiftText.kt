package com.tellybox.tv.ui.player

import android.content.Context
import com.tellybox.tv.R
import com.tellybox.tv.player.timeshift.TimeshiftMode
import com.tellybox.tv.player.timeshift.TimeshiftStorage
import com.tellybox.tv.player.timeshift.TimeshiftUnavailable

/** Wording for timeshift, shared by the player and the settings screen. */
object TimeshiftText {
    fun reason(context: Context, reason: TimeshiftUnavailable): String = when (reason) {
        is TimeshiftUnavailable.LowStorage -> context.getString(
            R.string.timeshift_low_storage, size(TimeshiftStorage.MIN_FREE_TO_START), size(reason.freeBytes),
        )
        TimeshiftUnavailable.StorageRanLow -> context.getString(R.string.timeshift_storage_ran_low)
        TimeshiftUnavailable.WriteFailed -> context.getString(R.string.timeshift_write_failed)
        TimeshiftUnavailable.UnsupportedStream -> context.getString(R.string.timeshift_unsupported)
    }

    fun mode(context: Context, mode: TimeshiftMode): String = context.getString(
        when (mode) {
            TimeshiftMode.OFF -> R.string.timeshift_off
            TimeshiftMode.PAUSE_ONLY -> R.string.timeshift_pause_only
            TimeshiftMode.FULL -> R.string.timeshift_full
        },
    )

    /** Binary units with one decimal place, the way storage settings show them. */
    fun size(bytes: Long): String = when {
        bytes >= TimeshiftStorage.GB -> {
            val tenths = bytes * 10 / TimeshiftStorage.GB
            if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
        }
        else -> "${bytes / TimeshiftStorage.MB} MB"
    }

    /** "1:05" or "1:02:05" behind live. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
