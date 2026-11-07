package com.opentelly.tv.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opentelly.tv.R
import com.opentelly.tv.appContainer
import com.opentelly.tv.schedule.Notifications
import kotlinx.coroutines.launch

/**
 * Shows the reminder popup. Android 10+ only lets an app open a screen from the
 * background when it is already on screen or may draw over other apps; otherwise a
 * full-screen notification is posted, which opens the popup when allowed or tapped.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        val c = context.appContainer
        c.appScope.launch {
            try {
                val reminder = c.reminders.take(id) ?: return@launch
                if (reminder.programStop <= System.currentTimeMillis()) return@launch
                val popup = ReminderPopupActivity.intent(context, reminder)
                val canOpen = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || c.foreground.visible ||
                    Settings.canDrawOverlays(context)
                if (canOpen) {
                    try {
                        context.startActivity(popup)
                        return@launch
                    } catch (_: Exception) {
                    }
                }
                notify(context, id, reminder.title, reminder.channelName, popup)
            } finally {
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Long, title: String, channel: String, popup: Intent) {
        Notifications.ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, id.toInt(), popup, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Starting soon on $channel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + id.toInt(), n) } catch (_: SecurityException) { }
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
        const val NOTIFICATION_BASE = 6000
    }
}
