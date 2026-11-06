package com.openstream.tv.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.RecordingStatus
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
                    dao.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED }?.let {
                        dao.update(it.copy(status = RecordingStatus.FAILED,
                            error = "Android didn't allow the recording to start in the background. " +
                                "Allow \"Alarms & reminders\" for OpenStream in the system settings."))
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
