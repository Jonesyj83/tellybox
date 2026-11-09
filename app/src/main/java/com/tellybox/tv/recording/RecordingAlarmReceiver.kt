package com.tellybox.tv.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.data.recording.RecordingPlan
import kotlinx.coroutines.launch

/** Fires when a scheduled recording is due and hands it to [RecordingService]. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        try {
            RecordingService.start(context, id)
        } catch (e: Exception) {
            // Android 12+ refuses background service starts unless the alarm was exact.
            val pending = goAsync()
            val c = context.appContainer
            c.appScope.launch {
                try {
                    val dao = c.database.recordings()
                    val note = context.getString(com.tellybox.tv.R.string.rec_background_denied)
                    val rec = dao.get(id) ?: return@launch
                    when (rec.status) {
                        RecordingStatus.SCHEDULED -> dao.update(rec.copy(status = RecordingStatus.FAILED, error = note))
                        // One being picked up again after a restart. Left alone it would say
                        // RECORDING until the app next opened.
                        RecordingStatus.RECORDING -> {
                            val size = rec.filePath?.let { java.io.File(it).length() } ?: 0L
                            dao.update(RecordingPlan.closeOut(rec, size, note, RecordingStatus.FAILED))
                        }
                        else -> Unit
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "recording_id"
    }
}
