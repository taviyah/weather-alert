package com.weatheralert.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Whether the OS is actually letting the background checks run.
 *
 * This matters more for this app than for most. Android demotes apps you do not open through
 * ACTIVE → WORKING_SET → FREQUENT → RARE, and in RARE it defers background work to roughly once a
 * day. An alert app is by definition one you never open, so it decays straight into the bucket
 * that breaks it — silently, while continuing to look fine.
 *
 * Exempting the app from battery optimisation takes it out of that ladder entirely.
 */
object BackgroundHealth {

    private const val TAG = "WeatherAlert"

    fun isExemptFromBatteryOptimisation(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Opens the system prompt asking for the exemption. Some builds hide or reject the direct
     * dialog, so this falls back to the battery-optimisation list and finally to the app's own
     * settings page, where the toggle can always be reached by hand.
     */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallbacks = listOf(
            direct,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        for (intent in fallbacks) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }
        Log.w(TAG, "no activity could handle a battery-optimisation request")
    }

    /**
     * True when a check should have happened by now and did not.
     *
     * The grace factor is deliberately generous: WorkManager's period is a floor, not a promise,
     * and normal Doze batching can stretch a 3-hour job well past 3 hours without anything being
     * wrong. Two and a half missed cycles means something actually is.
     */
    fun isStalled(lastRunEpochMs: Long, checkEveryHours: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastRunEpochMs <= 0L) return false
        val elapsed = nowMs - lastRunEpochMs
        val allowed = TimeUnit.HOURS.toMillis(checkEveryHours.toLong()) * 5 / 2
        return elapsed > allowed
    }

    fun hoursSince(lastRunEpochMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        TimeUnit.MILLISECONDS.toHours(nowMs - lastRunEpochMs)
}
