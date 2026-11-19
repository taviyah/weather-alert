package com.weatheralert

import android.app.Application
import com.weatheralert.data.AppStore
import com.weatheralert.notify.Notifier
import com.weatheralert.ui.EInkDetect
import com.weatheralert.work.KeepAliveService
import com.weatheralert.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WeatherAlertApp : Application() {

    lateinit var store: AppStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        Notifier.createChannels(this)
        KeepAliveService.createChannel(this)
        // Every process start, including one the system makes to run a scheduled check, so the
        // service comes back even if something stopped it.
        KeepAliveService.start(this)

        // Re-arms the schedule on every cold start. WorkManager persists it across reboots on
        // its own, but this also picks up an interval the user changed while the app was dead.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settings = store.settingsNow()

            // Decide the e-paper default exactly once. After that the user's choice stands, so
            // turning it off on a Boox does not get undone on the next launch.
            if (!settings.eInkDefaultApplied) {
                store.saveSettings(
                    settings.copy(
                        eInkMode = EInkDetect.looksLikeEInk(),
                        eInkDefaultApplied = true,
                    ),
                )
            }

            Scheduler.schedulePeriodic(this@WeatherAlertApp, settings.checkEveryHours)
        }
    }
}
