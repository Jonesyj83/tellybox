package com.openstream.tv

import android.app.Application
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.repository.BackgroundRefresh
import com.openstream.tv.data.repository.ChannelRepository
import com.openstream.tv.data.repository.EpgRepository
import com.openstream.tv.data.repository.PlaylistRepository
import com.openstream.tv.data.repository.StalkerClientCache
import com.openstream.tv.data.repository.createHttpClient
import com.openstream.tv.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class OpenStreamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foreground)
        com.openstream.tv.schedule.Notifications.ensureChannels(this)
        // Alarms are lost on force-stop; put back anything still pending.
        container.appScope.launch {
            container.recordings.restore()
            container.reminders.restore()
        }
        // Follows the guide refresh interval setting, including later changes to it.
        container.appScope.launch {
            container.settings.settings.map { it.guideRefreshHours }.distinctUntilChanged().collect {
                com.openstream.tv.data.repository.RefreshWorker.schedule(this@OpenStreamApp, it)
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

    val parental = com.openstream.tv.data.parental.ParentalRepository(app)
    val settings = SettingsRepository(app)
    val database = OpenStreamDatabase.create(app)
    val http = createHttpClient()
    private val stalkerClients = StalkerClientCache(http)

    val playlists = PlaylistRepository(app, database, http, stalkerClients)
    val vod = com.openstream.tv.data.repository.VodRepository(database, http)
    val channels = ChannelRepository(database, stalkerClients, parental)
    val epg = EpgRepository(app, database, http, settings)
    val backgroundRefresh = BackgroundRefresh(playlists, epg, settings)
    val recordings = com.openstream.tv.data.recording.RecordingRepository(app, database, settings)
    val reminders = com.openstream.tv.reminders.ReminderRepository(app, database, settings)
    val foreground = ForegroundTracker()
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as OpenStreamApp).container
