package com.weatheralert.domain

import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.AlertKind
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.Severity
import com.weatheralert.model.WeatherAlert
import java.time.LocalDate

/**
 * One day's alerts and advice folded into a single entry for the home card.
 *
 * Shown as separate rows, one wet day produced three entries saying the same thing: "Rain
 * expected · 4.2 mm, 70% chance", "Do not hang laundry · 70% chance of rain", "Take a raincoat ·
 * 70% chance of rain". Here the alerts become [facts] on one line, and each tip keeps its action
 * but loses any reason an alert on the same day already states.
 */
data class DayDigest(
    val date: LocalDate,
    val facts: List<Fact>,
    val tips: List<Tip>,
) {
    data class Fact(val kind: AlertKind, val severity: Severity, val text: String)

    /** [reason] is null when an alert on the same day already explains it. */
    data class Tip(val kind: AdviceKind, val level: AdviceLevel, val action: String, val reason: String?)

    val major: Boolean get() = facts.any { it.severity == Severity.MAJOR }
    val worstAdvice: AdviceLevel? get() = tips.maxOfOrNull { it.level }

    companion object {

        /** Days in date order; within a day, alerts keep the engine's order (major first). */
        fun build(alerts: List<WeatherAlert>, advice: List<DayAdvice>): List<DayDigest> {
            val dates = (alerts.map { it.localDate } + advice.map { it.localDate }).distinct().sorted()
            return dates.map { date ->
                val dayAlerts = alerts.filter { it.localDate == date }
                val kinds = dayAlerts.map { it.kind }.toSet()
                DayDigest(
                    date = date,
                    facts = dayAlerts.map { Fact(it.kind, it.severity, it.short.ifEmpty { it.headline.lowercase() }) },
                    tips = advice
                        .filter { it.localDate == date && it.level != AdviceLevel.GOOD }
                        .sortedByDescending { it.level }
                        .map { Tip(it.kind, it.level, it.headline, uncoveredReason(it, kinds)) },
                )
            }
        }

        /**
         * What is left of the tip's reason once the parts an alert already says are removed.
         * Reasons are comma-joined clauses built by [AdviceEngine], so each clause is matched on
         * the words it is built from.
         */
        internal fun uncoveredReason(tip: DayAdvice, alertKinds: Set<AlertKind>): String? {
            val air = AlertKind.DUST in alertKinds || AlertKind.AIR_QUALITY in alertKinds
            // A mask tip is entirely about the air, including its "masks do not filter gases"
            // sentence, which itself contains commas. With an air alert there is nothing left to say.
            if (tip.kind == AdviceKind.MASK && air) return null

            val clauses = tip.reason.split(", ").filter { it.isNotBlank() }
            var droppedAny = false
            val left = clauses.filterNot { c ->
                val covered = when {
                    "rain" in c -> AlertKind.RAIN in alertKinds
                    "PM10" in c || "PM2.5" in c || "dust" in c -> air
                    "gusts" in c -> AlertKind.WIND_RISE in alertKinds || AlertKind.WIND_DROP in alertKinds
                    "than yesterday" in c -> AlertKind.TEMP_DROP in alertKinds || AlertKind.TEMP_RISE in alertKinds
                    "after dark" in c -> AlertKind.COLD in alertKinds || AlertKind.FROST in alertKinds
                    // "worst around 14:00" only qualifies the clause before it.
                    c.startsWith("worst around") || c.startsWith("peaks around") -> droppedAny
                    else -> false
                }
                if (covered) droppedAny = true
                covered
            }
            return left.joinToString(", ").ifEmpty { null }
        }
    }
}
