package com.tellybox.tv.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.RecordingEntity
import com.tellybox.tv.data.db.RecordingSource
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.data.recording.RecordingPlan
import com.tellybox.tv.data.recording.StreamRecorder
import com.tellybox.tv.schedule.Notifications
import com.tellybox.tv.ui.recordings.RecordingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs recordings in the foreground so they carry on with the app closed. Several can
 * run at once (one per channel), though many providers allow only one connection.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    /** Touched on the main thread only. */
    private val jobs = HashMap<Long, Job>()
    private val recorders = ConcurrentHashMap<Long, StreamRecorder>()
    private val titles = ConcurrentHashMap<Long, String>()
    /** Stops for jobs that were still starting up, checked before the started state is written. */
    private val earlyStops: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile private var timedOut = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> if (id >= 0) launch(id)
            ACTION_STOP -> if (id >= 0) stopRecording(id)
            // Restarted by the system after the process was killed mid-recording.
            null -> {
                scope.launch {
                    val running = appContainer.database.recordings().withStatus(listOf(RecordingStatus.RECORDING))
                    withContext(Dispatchers.Main) {
                        running.forEach { launch(it.id) }
                        stopIfIdle()
                    }
                }
                return START_STICKY
            }
        }
        main.post { stopIfIdle() }
        return START_STICKY
    }

    private fun launch(id: Long) {
        if (jobs.containsKey(id)) return
        val job = scope.launch {
            try {
                run(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appContainer.database.recordings().get(id)?.let {
                    finish(it.copy(status = if (it.bytes > 0) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
                        error = getString(com.tellybox.tv.R.string.rec_unexpected_stop, e.message ?: e.javaClass.simpleName)))
                }
            }
        }
        jobs[id] = job
        holdLocks(true)
        job.invokeOnCompletion {
            main.post {
                jobs.remove(id)
                titles.remove(id)
                earlyStops.remove(id)
                if (jobs.isNotEmpty()) goForeground() else stopIfIdle()
            }
        }
    }

    /** Main thread. */
    private fun stopRecording(id: Long) {
        if (jobs.containsKey(id)) {
            // Noted before the recorder is asked, so a job that registers its recorder in
            // between still sees the stop when it checks.
            earlyStops.add(id)
            recorders[id]?.stop()
            return
        }
        // Nothing here is recording it, e.g. the process died and the service never picked
        // it back up. The row would say RECORDING for good, so close it out. The app scope
        // outlives this service, which stops as soon as it sees it is idle.
        val c = appContainer
        val note = getString(com.tellybox.tv.R.string.rec_interrupted)
        c.appScope.launch {
            val dao = c.database.recordings()
            val rec = dao.get(id)?.takeIf { it.status == RecordingStatus.RECORDING } ?: return@launch
            val size = rec.filePath?.let { File(it).length() } ?: 0L
            dao.update(RecordingPlan.closeOut(rec, size, note, RecordingStatus.CANCELLED))
        }
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        holdLocks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun run(id: Long) {
        val c = appContainer
        val dao = c.database.recordings()
        val rec = dao.get(id) ?: return
        if (rec.status != RecordingStatus.SCHEDULED && rec.status != RecordingStatus.RECORDING) return
        val now = System.currentTimeMillis()
        val live = rec.source == RecordingSource.LIVE
        if (live && rec.stopAt <= now) {
            val size = rec.filePath?.let { File(it).length() } ?: 0L
            dao.update(
                if (size > 0) rec.copy(status = RecordingStatus.COMPLETED, bytes = size)
                else rec.copy(status = RecordingStatus.FAILED, error = getString(com.tellybox.tv.R.string.rec_window_passed)),
            )
            return
        }
        if (live && rec.startAt > now + 60_000) {
            // Woken early; put the alarm back for the real start.
            com.tellybox.tv.schedule.Alarms.set(this, rec.startAt, c.recordings.alarm(id))
            return
        }

        fun failure(reason: String) = rec.copy(status = RecordingStatus.FAILED, error = reason)
        val channel = c.database.channels().findByKey(rec.playlistId, rec.channelKey)
        if (channel == null) {
            finish(failure(getString(com.tellybox.tv.R.string.rec_channel_missing)))
            return
        }
        val url = try {
            if (live) c.channels.resolveStreamUrl(channel)
            else c.channels.resolveCatchupUrl(channel, rec.startAt, rec.stopAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(failure(getString(com.tellybox.tv.R.string.rec_stream_failed, e.message ?: e.javaClass.simpleName)))
            return
        }
        val dir = c.recordings.directory()
        if (dir.usableSpace < MIN_FREE_BYTES) {
            finish(failure(getString(com.tellybox.tv.R.string.rec_storage_low)))
            return
        }
        // Picking up a recording the system or a reboot cut short: the gap can't be filled.
        val resumed = rec.status == RecordingStatus.RECORDING && (rec.filePath?.let { File(it).length() } ?: 0L) > 0
        val file = rec.filePath?.let(::File) ?: uniqueFile(dir, RecordingPlan.fileName(rec.channelName, rec.title, rec.programStart))

        // Registered before the started state is written, so any stop from here on reaches it.
        val recorder = StreamRecorder(c.http)
        recorders[id] = recorder
        var lastSaved = 0L
        val result = try {
            // Stopped, cancelled or deleted while the stream was being looked up. Writing the
            // started state now would put RECORDING back over that and the file would grow.
            val current = dao.get(id)
            if (current == null || id in earlyStops ||
                (current.status != RecordingStatus.SCHEDULED && current.status != RecordingStatus.RECORDING)) {
                if (current != null && current.status == RecordingStatus.RECORDING) {
                    finish(RecordingPlan.closeOut(current, file.length(), getString(com.tellybox.tv.R.string.rec_interrupted), RecordingStatus.CANCELLED))
                }
                return
            }
            dao.update(current.copy(status = RecordingStatus.RECORDING, filePath = file.path, error = null))
            titles[id] = rec.title
            withContext(Dispatchers.Main) { goForeground() }

            recorder.record(
                StreamRecorder.Request(
                    url = url,
                    output = file,
                    stopAt = if (live) rec.stopAt else RecordingPlan.catchupDeadline(System.currentTimeMillis(), rec.startAt, rec.stopAt),
                    finite = !live,
                    userAgent = c.channels.userAgentFor(channel),
                    referrer = channel.referrer,
                    onProgress = { bytes ->
                        val t = System.currentTimeMillis()
                        if (t - lastSaved > 5_000) {
                            lastSaved = t
                            scope.launch { dao.setBytes(id, bytes) }
                        }
                    },
                ),
            )
        } finally {
            recorders.remove(id)
        }

        val latest = dao.get(id) ?: return // deleted while recording
        val note = when {
            timedOut -> getString(com.tellybox.tv.R.string.rec_limit_stopped_early)
            result.bytes > 0 && result.error != null -> getString(com.tellybox.tv.R.string.rec_ended_early, result.error)
            resumed && result.bytes > 0 -> getString(com.tellybox.tv.R.string.rec_resumed_gap)
            else -> result.error
        }
        finish(
            latest.copy(
                status = if (result.bytes > 0) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
                bytes = result.bytes,
                error = note,
            ),
        )
        if (result.bytes == 0L) file.delete()
    }

    private suspend fun finish(rec: RecordingEntity) {
        appContainer.database.recordings().update(rec)
        if (rec.status == RecordingStatus.FAILED) notifyFailure(rec)
    }

    @SuppressLint("MissingPermission")
    private fun notifyFailure(rec: RecordingEntity) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING_RESULT)
            .setSmallIcon(R.drawable.ic_stat_tellybox)
            .setContentTitle(getString(com.tellybox.tv.R.string.recording_failed_title, rec.title))
            .setContentText(rec.error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(rec.error))
            .setContentIntent(openList())
            .setAutoCancel(true)
            .build()
        try { nm.notify("rec_fail", rec.id.toInt(), n) } catch (_: SecurityException) { }
    }

    private fun openList() = PendingIntent.getActivity(
        this, 0, Intent(this, RecordingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun goForeground() {
        val text = when (titles.size) {
            0 -> getString(com.tellybox.tv.R.string.recording_preparing)
            1 -> titles.values.first()
            else -> getString(com.tellybox.tv.R.string.recording_count, titles.size)
        }
        val n: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_tellybox)
            .setContentTitle(getString(com.tellybox.tv.R.string.recording_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openList())
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun holdLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tellybox:recording")?.apply { acquire() }
            }
            if (wifiLock == null) {
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Tellybox:recording")?.apply { acquire() }
            }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
        }
    }

    /** Android 15 ends data-sync services after 6 hours; close the files cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        recorders.values.forEach { it.stop() }
    }

    override fun onDestroy() {
        recorders.values.forEach { it.stop() }
        holdLocks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.tellybox.tv.recording.START"
        private const val ACTION_STOP = "com.tellybox.tv.recording.STOP"
        private const val EXTRA_ID = "recording_id"
        private const val NOTIFICATION_ID = 4100
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024

        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
            )
        }

        fun stop(context: Context, id: Long) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
            )
        }

        fun uniqueFile(dir: File, name: String): File {
            var file = File(dir, name)
            var n = 2
            while (file.exists()) {
                file = File(dir, name.removeSuffix(".ts") + " ($n).ts")
                n++
            }
            return file
        }
    }
}
