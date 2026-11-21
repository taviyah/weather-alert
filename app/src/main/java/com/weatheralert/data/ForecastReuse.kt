package com.weatheralert.data

import com.weatheralert.model.LocationForecast
import java.time.LocalDate

/**
 * When a forecast in the cache is new enough to use instead of downloading it again.
 *
 * Opening the app fetches every place for the screen and then starts the background check so it
 * can decide on notifications. Before this rule the check downloaded every place a second time,
 * seconds after the first: three requests per place, twice. The models behind the data are re-run
 * every few hours, so a forecast from ten minutes ago is as current as a new one.
 */
object ForecastReuse {

    const val WINDOW_MS = 10 * 60_000L

    /**
     * True when [forecast] was fetched within [WINDOW_MS] of [nowMs] and already reaches the last
     * day of the horizon. A horizon widened in settings therefore still triggers a download.
     */
    fun canReuse(
        forecast: LocationForecast?,
        horizonDays: Int,
        today: LocalDate,
        nowMs: Long,
    ): Boolean {
        if (forecast == null) return false
        val age = nowMs - forecast.fetchedAtEpochMs
        if (age < 0 || age > WINDOW_MS) return false
        val lastNeeded = today.plusDays((horizonDays.coerceIn(1, 14) - 1).toLong())
        return forecast.days.any { !it.localDate.isBefore(lastNeeded) }
    }
}
