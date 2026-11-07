package com.opentelly.tv.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_RECORDING_RESULT = "recording_result"
    const val CHANNEL_REMINDER = "reminder"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, "Recording in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING_RESULT, "Recording problems", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
    }
}
