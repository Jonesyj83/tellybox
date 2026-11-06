package com.openstream.tv.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openstream.tv.appContainer
import kotlinx.coroutines.launch

/** Alarms don't survive a reboot or an app update; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.appContainer
        c.appScope.launch {
            try {
                c.recordings.restore()
                c.reminders.restore()
            } finally {
                pending.finish()
            }
        }
    }
}
