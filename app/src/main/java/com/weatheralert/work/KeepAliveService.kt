package com.weatheralert.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.weatheralert.MainActivity
import com.weatheralert.R
import com.weatheralert.data.AppStore
import com.weatheralert.ui.EInkDetect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * A foreground service that does nothing except keep the process in the foreground-service state.
 *
 * Battery-optimisation exemption is not enough on MediaTek phones that ship DuraSpeed (Bigme
 * HiBreak, many budget devices). DuraSpeed flips the app's RUN_ANY_IN_BACKGROUND app-op to
 * `ignore` — which is the system "Unrestricted" setting quietly unticking itself — and about 15
 * minutes after the app leaves the screen the system kills it as "cached idle & background
 * restricted" and force-stops the package. A force-stop deletes every job WorkManager scheduled,
 * so no check runs again until the app is opened by hand.
 *
 * A foreground service stops DuraSpeed from picking the app in the first place. Its policy
 * (`/system_ext/framework/duraspeed.jar`, collector `h`) marks any package whose process sits at
 * oom_adj ≤ 200 as protected (flag 0x2000) and never suppresses it; a foreground-service process
 * sits at exactly 200. On the same HiBreak Pro, two apps with this arrangement went six weeks
 * without a single kill, while this app was force-stopped within 17 minutes of every use.
 *
 * It is prevention, not cure: if the app-op is already `ignore` when the app goes to the
 * background, Android itself strips the foreground status of background-restricted apps, and the
 * kill follows. The service therefore has to be running *before* DuraSpeed's screen-off sweep,
 * which starting it on every process start and on boot ensures.
 *
 * The actual checks still run through WorkManager; this only keeps the process eligible for them.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Watching the forecast")
            .setContentText("Keeps background checks running on this phone")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        // Android 12+ can refuse this (ForegroundServiceStartNotAllowedException), for example
        // when the start came from the background and the app is not exempt from battery
        // optimisation, or after the system restricted it. Uncaught, that refusal crashes the
        // whole app; caught, the service just stops and the next process start tries again.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "keep-alive service not allowed to run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "WeatherAlert"
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 2

        fun createChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            // Minimum importance: no sound, no status-bar icon, folded to the bottom of the shade.
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background checks", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "Silent. Stops the phone from shutting down the forecast checks."
                        setShowBadge(false)
                    },
            )
        }

        /**
         * Safe to call repeatedly. Android 12+ refuses foreground-service starts from the
         * background unless the app is exempt from battery optimisation, so this can fail before
         * the user grants that; the home-screen banner asks for it, and the next start succeeds.
         */
        private fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "could not start keep-alive service", it) }
        }

        /** Starts the service when [enabled] (the user's setting), stops it otherwise. */
        fun sync(context: Context, enabled: Boolean) {
            if (enabled) {
                start(context)
            } else {
                runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            }
        }

        /**
         * Whether this phone probably kills background apps the way DuraSpeed does, which is
         * the only reason the service exists. Used once, for the default of the setting.
         *
         * Errs towards yes: on a phone that needs the service and does not get it, alerts stop
         * after the first use, while on one that gets it unneeded it only costs some memory. So
         * any MediaTek chip counts, as does any e-paper maker (the HiBreak is both).
         */
        fun likelyNeeded(): Boolean {
            val duraSpeed = listOf(
                "/system_ext/framework/duraspeed.jar",
                "/system/framework/duraspeed.jar",
            ).any { runCatching { File(it).exists() }.getOrDefault(false) }
            val hardware = Build.HARDWARE.orEmpty().lowercase(Locale.US)
            val mediaTek = hardware.startsWith("mt") || hardware.contains("mediatek") ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        Build.SOC_MANUFACTURER.orEmpty().contains("mediatek", ignoreCase = true)
                    )
            return duraSpeed || mediaTek || EInkDetect.looksLikeEInk()
        }
    }
}

/**
 * Brings the service back after a reboot or an app update, if the setting is on. Process start
 * alone is enough to run [com.weatheralert.WeatherAlertApp.onCreate], which also starts it, but a
 * receiver has to exist for the system to start the process at all.
 */
class KeepAliveStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // The setting is in DataStore, which is read asynchronously; goAsync keeps the
        // broadcast (and the boot-time permission to start a foreground service) open meanwhile.
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = AppStore(app).settingsNow().keepAlive
                KeepAliveService.sync(app, enabled)
            } finally {
                pending.finish()
            }
        }
    }
}
