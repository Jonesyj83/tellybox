package com.tellybox.tv.ui.recording

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.tellybox.tv.R
import com.tellybox.tv.data.recording.RecordingLimits

/**
 * Asks before setting a recording longer than Android will run in the background. The recording
 * is never shortened silently: the viewer either accepts the early stop or picks a shorter one.
 */
fun Activity.confirmLongRecording(durationMs: Long, onConfirm: () -> Unit) {
    if (!RecordingLimits.beyondLimit(durationMs)) {
        onConfirm()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.recording_limit_title)
        .setMessage(R.string.recording_limit_message)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.recording_limit_continue) { _, _ -> onConfirm() }
        .show()
}
