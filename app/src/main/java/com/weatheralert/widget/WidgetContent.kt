package com.weatheralert.widget

import android.content.Context
import com.weatheralert.data.AppStore
import com.weatheralert.domain.AdviceEngine
import com.weatheralert.domain.AlertEngine
import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Severity
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What the home-screen widget says.
 *
 * Deliberately not a miniature forecast: the stock weather widget already shows a temperature and
 * an icon, and repainting one every hour is exactly the churn this app exists to avoid. The widget
 * answers one question — is anything coming? — and on most days the answer is no, said once and
 * then left completely still.
 */
data class WidgetContent(
    val place: String,
    val morePlaces: String?,
    val headline: String,
    val detail: String?,
    /** "!!" for major, "!" for notable, null when all clear. Shape, not colour: see SeverityMark. */
    val mark: String?,
    /** Only advice that needs acting on. Null on a day where nothing does. */
    val advice: String?,
    val spillover: String?,
    val checked: String,
) {
    companion object {
        private val dayName = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
        private val shortDay = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

        val EMPTY = WidgetContent(
            place = "Weather Alert",
            morePlaces = null,
            headline = "No places watched",
            detail = "Open the app to add one",
            mark = null,
            advice = null,
            spillover = null,
            checked = "",
        )

        suspend fun build(context: Context): WidgetContent {
            val store = AppStore(context)
            return from(
                locations = store.locationsNow(),
                forecasts = store.forecastsNow(),
                settings = store.settingsNow(),
                lastRunEpochMs = store.lastRunNow().epochMs,
                today = LocalDate.now(),
            )
        }

        /**
         * Pure, so the wording can be tested without a device. Everything above this line just
         * loads the arguments.
         */
        fun from(
            locations: List<WatchedLocation>,
            forecasts: Map<String, LocationForecast>,
            settings: Thresholds,
            lastRunEpochMs: Long,
            today: LocalDate,
            nowMs: Long = System.currentTimeMillis(),
        ): WidgetContent {
            if (locations.isEmpty()) return EMPTY

            // The list order is the user's, set by dragging, so the top place is the one they
            // chose to care about most.
            val primary = locations.first()
            val forecast = forecasts[primary.id]

            val alerts = forecast?.let { AlertEngine.evaluate(primary, it, settings, today) }.orEmpty()
            val advice = forecast?.let { AdviceEngine.evaluate(it, settings, today, alerts) }.orEmpty()

            // Already sorted by date, then severity, so the first entry is the soonest and worst.
            val next = alerts.firstOrNull()

            val horizonEnd = today.plusDays((settings.horizonDays - 1).toLong())
            val headline = when {
                forecast == null -> "Waiting for the first forecast"
                next == null -> "All clear through ${horizonEnd.format(dayName)}"
                else -> "${whenLabel(next.localDate, today)} · ${next.headline}"
            }

            val todayAdvice = advice
                .filter { it.date == today.toString() && it.level != AdviceLevel.GOOD }
                .sortedBy { it.level != AdviceLevel.BAD }
                .joinToString(" · ") { shortAdvice(it.kind, it.headline) }
                .ifBlank { null }

            // Somewhere else having a major alert is worth one line, since the widget otherwise
            // only ever speaks for the top place.
            val spill = locations.drop(1).firstNotNullOfOrNull { loc ->
                val fc = forecasts[loc.id] ?: return@firstNotNullOfOrNull null
                AlertEngine.evaluate(loc, fc, settings, today)
                    .firstOrNull { it.severity == Severity.MAJOR }
                    ?.let { "${loc.name}: ${it.headline.lowercase(Locale.getDefault())} ${whenLabel(it.localDate, today).lowercase(Locale.getDefault())}" }
            }

            return WidgetContent(
                place = primary.name,
                morePlaces = if (locations.size > 1) "+${locations.size - 1}" else null,
                headline = headline,
                detail = next?.detail,
                mark = next?.let { if (it.severity == Severity.MAJOR) "!!" else "!" },
                advice = todayAdvice,
                spillover = spill,
                checked = agoLabel(lastRunEpochMs, nowMs),
            )
        }

        private fun whenLabel(date: LocalDate, today: LocalDate) = when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> date.format(shortDay)
        }

        /** The widget has one line for this, so the verdict is trimmed to its actionable half. */
        private fun shortAdvice(kind: AdviceKind, headline: String) = when (kind) {
            AdviceKind.LAUNDRY -> headline
                .replace("Do not hang laundry", "No laundry out")
                .replace("Whites may dull", "Laundry: hazy")
                .replace("Slow to dry", "Laundry: slow")
            AdviceKind.MASK -> headline
                .replace("Wear an N95 outside", "Wear an N95")
                .replace("Mask if you are sensitive", "Mask if sensitive")
                .replace("A mask will not help today", "Ozone: stay in")
            AdviceKind.CLOTHING -> headline
        }

        private fun agoLabel(epochMs: Long, nowMs: Long): String {
            if (epochMs <= 0L) return ""
            val m = TimeUnit.MILLISECONDS.toMinutes(nowMs - epochMs)
            return when {
                m < 1 -> "just now"
                m < 60 -> "${m}m ago"
                m < 60 * 24 -> "${m / 60}h ago"
                else -> "${m / 1440}d ago"
            }
        }
    }
}
