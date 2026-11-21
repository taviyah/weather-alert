package com.weatheralert.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weatheralert.data.AppStore
import com.weatheralert.data.ForecastReuse
import com.weatheralert.data.LastRun
import com.weatheralert.data.OpenMeteo
import com.weatheralert.domain.AdviceEngine
import com.weatheralert.domain.AlertEngine
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.DayAdvice
import com.weatheralert.notify.LocationNews
import com.weatheralert.notify.Notifier
import com.weatheralert.widget.WeatherWidgetProvider
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Polls the forecast and notifies on anything new.
 *
 * Deliberately runs on the device rather than through a push service: with no server there is
 * nothing to host, no account to create, and no location data leaving the phone beyond the
 * coordinates in the Open-Meteo request itself.
 */
class WeatherCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        val locations = store.locationsNow()
        if (locations.isEmpty()) return Result.success()

        val thresholds = store.settingsNow()
        val today = LocalDate.now()
        var firstError: String? = null
        val news = mutableListOf<LocationNews>()
        // What the app itself fetched moments ago (on open, pull-to-refresh, adding a place) is
        // used as it is rather than downloaded a second time.
        val cached = store.forecastsNow()

        // One unreachable location must not stop the others from being checked, and one that
        // times out gets two more tries within this run instead of waiting for the next one.
        val stillFailing = retryFailed(locations, RETRY_PAUSES_MS) { loc ->
            try {
                val forecast = cached[loc.id]
                    ?.takeIf {
                        ForecastReuse.canReuse(it, thresholds.horizonDays, today, System.currentTimeMillis())
                    }
                    ?: OpenMeteo.fetchForecast(loc, thresholds.horizonDays).also { store.saveForecast(it) }

                val alerts = AlertEngine.evaluate(loc, forecast, thresholds, today)
                val freshAlerts = store.filterUnseen(alerts)

                // Only BAD advice earns an interruption. CAUTION ("whites may dull", "mask if you
                // are sensitive") is worth showing in the app but not worth a notification, and
                // promoting it would roughly triple the volume for very little added value.
                val advice = AdviceEngine.evaluate(forecast, thresholds, today, alerts)
                    .filter { it.level == AdviceLevel.BAD }
                val freshAdvice = store
                    .filterUnseenKeys(advice.map { adviceKey(loc.id, it) to it.date })
                    .map { it.first }
                    .toSet()
                val newAdvice = advice.filter { adviceKey(loc.id, it) in freshAdvice }

                if (freshAlerts.isNotEmpty() || newAdvice.isNotEmpty()) {
                    news += LocationNews(loc, freshAlerts, newAdvice)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firstError = e.message
                android.util.Log.w(OpenMeteo.TAG, "check failed for ${loc.name}", e)
                false
            }
        }
        val failures = stillFailing.size

        // Every place is told about in one notification, so it goes out once all are checked.
        // Marked seen only after posting: a run killed in between re-announces rather than loses.
        // Not marked at all when the post could not be shown (notifications blocked or the
        // channel off): the next check finds it again and announces it once they are back.
        val delivered = Notifier.notify(applicationContext, news)
        if (delivered) {
            for (n in news) {
                store.recordSeen(n.alerts)
                store.recordSeenKeys(n.advice.map { adviceKey(n.location.id, it) to it.date })
            }
        }

        store.pruneLedger(today)
        // The widget has no schedule of its own; this is the only thing that redraws it in the
        // background.
        WeatherWidgetProvider.refresh(applicationContext)
        store.saveLastRun(
            LastRun(
                epochMs = System.currentTimeMillis(),
                error = if (failures > 0) {
                    "$failures of ${locations.size} failed" + (firstError?.let { ": $it" } ?: "")
                } else {
                    null
                },
            ),
        )

        // Still failing after the in-run retries usually means the network is down for longer
        // than a minute. Hand over to WorkManager's backoff (10, 20, 40 min) a few times rather
        // than leaving those places stale until the next scheduled check, hours away. The ledger
        // makes the repeat harmless for places that already succeeded: nothing is re-notified.
        return if (failures > 0 && runAttemptCount < MAX_RUN_RETRIES) Result.retry() else Result.success()
    }

    /** Location id first, so [com.weatheralert.data.AppStore.removeLocation] prunes it by prefix. */
    private fun adviceKey(locationId: String, advice: DayAdvice) = "$locationId|${advice.key}"

    companion object {
        const val PERIODIC_NAME = "weather_check_periodic"
        const val ONESHOT_NAME = "weather_check_now"

        /**
         * Pauses between in-run retry rounds. A dead network fails fast, so this normally adds
         * about 80 s. Only if every request hangs to its full timeout can the rounds pass the
         * system's 10-minute job limit; the job is then stopped and WorkManager reschedules it,
         * which is the same outcome as returning retry.
         */
        private val RETRY_PAUSES_MS = listOf(20_000L, 60_000L)
        private const val MAX_RUN_RETRIES = 3
    }
}

object Scheduler {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * [hours] is a floor, not a promise: Doze and App Standby can stretch it, and WorkManager's
     * own minimum period is 15 minutes. Three hours matches how often the weather models the
     * forecast comes from are actually re-run, so checking more often mostly re-reads the same
     * numbers at the cost of battery.
     */
    fun schedulePeriodic(context: Context, hours: Int) {
        val request = PeriodicWorkRequestBuilder<WeatherCheckWorker>(
            hours.coerceIn(1, 24).toLong(), TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeatherCheckWorker.PERIODIC_NAME,
            // UPDATE keeps the existing schedule's elapsed time when only the interval changed,
            // so tweaking the setting does not reset the clock on every save.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Fired when the app opens, when a location is added, and by pull-to-refresh. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WeatherCheckWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WeatherCheckWorker.ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
