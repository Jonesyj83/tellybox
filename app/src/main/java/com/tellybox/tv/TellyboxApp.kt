package com.tellybox.tv

import android.app.Application
import com.tellybox.tv.data.db.TellyboxDatabase
import com.tellybox.tv.data.repository.BackgroundRefresh
import com.tellybox.tv.data.repository.ChannelRepository
import com.tellybox.tv.data.repository.EpgRepository
import com.tellybox.tv.data.repository.PlaylistRepository
import com.tellybox.tv.data.repository.StalkerClientCache
import com.tellybox.tv.data.repository.createHttpClient
import com.tellybox.tv.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TellyboxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foreground)
        com.tellybox.tv.schedule.Notifications.ensureChannels(this)
        // Alarms are lost on force-stop; put back anything still pending.
        container.settings.prime(container.appScope)
        container.appScope.launch {
            container.recordings.restore()
            container.reminders.restore()
        }
        // A timeshift buffer left behind by a run that was killed is no use to anyone now.
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.tellybox.tv.player.timeshift.TimeshiftFiles.sweep(com.tellybox.tv.player.timeshift.TimeshiftStorage.dir(this@TellyboxApp))
        }
        // Follows the guide refresh interval setting, including later changes to it.
        container.appScope.launch {
            container.settings.settings.map { it.guideRefreshHours }.distinctUntilChanged().collect {
                com.tellybox.tv.data.repository.RefreshWorker.schedule(this@TellyboxApp, it)
            }
        }
    }
}

/** Whether any of our screens is visible, so a reminder may open its popup directly. */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val visible get() = started > 0
    override fun onActivityStarted(activity: android.app.Activity) { started++ }
    override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}

/** Hand-rolled dependency graph; small enough that a DI framework isn't worth it. */
class AppContainer(app: Application) {
    /** For work that should outlive a screen, like a playlist refresh started from the wizard. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val parental = com.tellybox.tv.data.parental.ParentalRepository(app)
    val settings = SettingsRepository(app)
    val database = TellyboxDatabase.create(app)
    val http = createHttpClient()
    private val stalkerClients = StalkerClientCache(http)

    val playlists = PlaylistRepository(app, database, http, stalkerClients) { parental.configured }
    val catalogueCredentials = com.tellybox.tv.data.settings.CatalogueCredentials(app)
    val vod = com.tellybox.tv.data.repository.VodRepository(database, http, stalkerClients, settings, catalogueCredentials)
    val channels = ChannelRepository(database, stalkerClients, parental)
    val epg = EpgRepository(app, database, http, settings)
    val backgroundRefresh = BackgroundRefresh(playlists, epg, settings)
    val recordings = com.tellybox.tv.data.recording.RecordingRepository(app, database, settings)
    val reminders = com.tellybox.tv.reminders.ReminderRepository(app, database, settings)
    val foreground = ForegroundTracker()
    val playback = com.tellybox.tv.player.Playback(app, http)
    val sleep = com.tellybox.tv.player.SleepSchedule(playback)
    val lastChannel = com.tellybox.tv.data.settings.LastChannelStore(app)
    val timeshift = com.tellybox.tv.player.timeshift.TimeshiftSessions(app, settings, channels)
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as TellyboxApp).container
