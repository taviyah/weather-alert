package com.weatheralert.domain

import com.weatheralert.model.AlertKind
import com.weatheralert.model.AqiScale
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Severity
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import com.weatheralert.model.WeatherAlert
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a fetched forecast into the list of things worth telling the user about.
 *
 * Pure and side-effect free on purpose: every rule here is covered by unit tests, which is the
 * only practical way to check thresholds without waiting for real weather to happen.
 */
object AlertEngine {

    fun evaluate(
        location: WatchedLocation,
        forecast: LocationForecast,
        t: Thresholds,
        today: LocalDate,
    ): List<WeatherAlert> {
        val days = forecast.days.sortedBy { it.date }
        val lastDay = today.plusDays((t.horizonDays - 1).toLong())
        val out = mutableListOf<WeatherAlert>()

        days.forEachIndexed { i, day ->
            val date = day.localDate
            if (date.isBefore(today) || date.isAfter(lastDay)) return@forEachIndexed
            // The temperature rules are all comparative; with no preceding day there is nothing
            // to compare against, so they sit out this iteration while rain and air still run.
            val prev = days.getOrNull(i - 1)

            fun add(kind: AlertKind, sev: Severity, headline: String, detail: String, short: String) {
                out += WeatherAlert(
                    locationId = location.id,
                    locationName = location.name,
                    date = day.date,
                    kind = kind,
                    severity = sev,
                    headline = headline,
                    detail = detail,
                    short = short,
                )
            }

            if (t.enableRain) {
                rain(day, t)?.let { add(AlertKind.RAIN, it.severity, it.headline, it.detail, it.short) }
            }

            val air = forecast.airFor(date)
            var dustFired = false
            if (t.enableDust && air != null) {
                val pm10 = air.pm10Max
                val dust = air.dustMax
                val severe = (pm10 != null && pm10 >= t.pm10SevereUgm3) ||
                    (dust != null && dust >= t.dustSevereUgm3)
                val notable = (pm10 != null && pm10 >= t.pm10Ugm3) ||
                    (dust != null && dust >= t.dustUgm3)
                if (severe || notable) {
                    dustFired = true
                    val at = air.peakHour?.let { String.format(Locale.US, ", worst around %02d:00", it) }.orEmpty()
                    val parts = listOfNotNull(
                        pm10?.let { "PM10 ${it.roundToInt()}" },
                        dust?.takeIf { it > 0 }?.let { "dust ${it.roundToInt()}" },
                    ).joinToString(", ")
                    add(
                        AlertKind.DUST,
                        if (severe) Severity.MAJOR else Severity.NOTABLE,
                        if (severe) "Heavy dust" else "Dusty air",
                        parts + " ug/m3" + at,
                        (if (severe) "heavy dust" else "dust") +
                            (pm10?.let { ", PM10 ${it.roundToInt()}" } ?: ""),
                    )
                }
            }

            // A dust event is exactly what drives AQI into the red, so reporting both would be the
            // same news twice. Dust is the more specific and more actionable of the two, so it wins.
            if (t.enableAirQuality && air != null && !dustFired) {
                val aqi = if (t.aqiScale == AqiScale.EUROPEAN) air.euAqiMax else air.usAqiMax
                if (aqi != null && aqi >= t.aqiPoor) {
                    val scale = if (t.aqiScale == AqiScale.EUROPEAN) "EU AQI" else "US AQI"
                    val driver = listOfNotNull(
                        air.pm25Max?.let { "PM2.5 ${it.roundToInt()}" },
                        air.pm10Max?.let { "PM10 ${it.roundToInt()}" },
                    ).joinToString(", ")
                    add(
                        AlertKind.AIR_QUALITY,
                        if (aqi >= t.aqiVeryPoor) Severity.MAJOR else Severity.NOTABLE,
                        if (aqi >= t.aqiVeryPoor) "Very poor air quality" else "Poor air quality",
                        "$scale $aqi" + if (driver.isNotEmpty()) " ($driver)" else "",
                        (if (aqi >= t.aqiVeryPoor) "very poor air" else "poor air") + ", $scale $aqi",
                    )
                }
            }

            if (prev != null && t.enableTempChange) {
                tempChange(prev, day, t)?.let { add(it.kind, it.severity, it.headline, it.detail, it.short) }
            }

            if (prev != null && t.enableWindChange) {
                windChange(prev, day, t)?.let { add(it.kind, it.severity, it.headline, it.detail, it.short) }
            }

            if (prev != null && t.enableExtremes) {
                // Crossings only, with a deadband. Firing on the level would re-alert on every day
                // of a week-long heat wave; firing on a bare crossing would re-alert every other
                // day in a climate that hovers at the threshold. The margin demands that the
                // previous day sat clearly on the other side.
                val band = t.extremeDeadbandC
                if (day.tempMax >= t.heatC && prev.tempMax < t.heatC - band) {
                    val severe = day.tempMax >= t.heatSevereC
                    add(
                        AlertKind.HEAT,
                        if (severe) Severity.MAJOR else Severity.NOTABLE,
                        if (severe) "Extreme heat" else "Hot day",
                        "High ${deg(day.tempMax)}, feels like ${deg(day.apparentMax)}",
                        (if (severe) "extreme heat" else "hot") + ", high ${deg(day.tempMax)}",
                    )
                }
                val frost = day.tempMin <= t.frostC && prev.tempMin > t.frostC + band
                if (frost) {
                    add(AlertKind.FROST, Severity.MAJOR, "Frost", "Low ${deg(day.tempMin)}", "frost, low ${deg(day.tempMin)}")
                }
                if (!frost && day.tempMin <= t.coldC && prev.tempMin > t.coldC + band) {
                    add(
                        AlertKind.COLD,
                        Severity.NOTABLE,
                        "Cold night",
                        "Low ${deg(day.tempMin)}, feels like ${deg(day.apparentMin)}",
                        "cold night, low ${deg(day.tempMin)}",
                    )
                }
            }
        }

        return out.sortedWith(
            compareBy({ it.date }, { it.severity != Severity.MAJOR }, { it.kind.ordinal }),
        )
    }

    private fun rain(day: DayWeather, t: Thresholds): TempHit? {
        val storm = day.weatherCode in 95..99
        val detail = buildString {
            append(String.format(Locale.US, "%.1f mm", day.precipMm))
            if (day.precipProbPct > 0) append(", ${day.precipProbPct}% chance")
            if (storm) append(", thunderstorms")
        }
        val amount = (if (day.precipMm >= 10) "%.0f mm" else "%.1f mm").format(Locale.US, day.precipMm) +
            if (day.precipProbPct > 0) " (${day.precipProbPct}%)" else ""
        return when {
            day.precipMm >= t.rainHeavyMm -> TempHit(
                AlertKind.RAIN, Severity.MAJOR, if (storm) "Storms" else "Heavy rain", detail,
                (if (storm) "storms " else "heavy rain ") + amount,
            )
            day.precipMm >= t.rainMinMm && day.precipProbPct >= t.rainProbPct -> TempHit(
                AlertKind.RAIN, Severity.NOTABLE, if (storm) "Thundery showers" else "Rain expected", detail,
                (if (storm) "thundery showers " else "rain ") + amount,
            )
            else -> null
        }
    }

    private data class TempHit(
        val kind: AlertKind,
        val severity: Severity,
        val headline: String,
        val detail: String,
        val short: String,
    )

    private data class Candidate(
        val label: String,
        val from: Double,
        val to: Double,
        /** The bar this candidate is judged against, as a multiple of the configured threshold. */
        val scale: Double,
        /** Raw air temperature for the same slot, shown as context when feels-like wins. */
        val rawFrom: Double,
        val rawTo: Double,
    ) {
        val apparent: Boolean get() = scale != 1.0
    }

    /**
     * Compares like with like: max against the previous day's max, min against min. Comparing max
     * against min would just re-discover the normal daily heating cycle every single day.
     *
     * Feels-like is judged against a proportionally higher bar — see [Thresholds.apparentMultiplier].
     * Without that it swamps the raw comparison, because humidity makes apparent temperature the
     * more volatile of the two.
     */
    private fun tempChange(prev: DayWeather, day: DayWeather, t: Thresholds): TempHit? {
        val m = t.apparentMultiplier
        val candidates = buildList {
            add(Candidate("Daytime high", prev.tempMax, day.tempMax, 1.0, prev.tempMax, day.tempMax))
            add(Candidate("Overnight low", prev.tempMin, day.tempMin, 1.0, prev.tempMin, day.tempMin))
            if (t.useApparentTemp) {
                add(Candidate("Daytime feels-like", prev.apparentMax, day.apparentMax, m, prev.tempMax, day.tempMax))
                add(Candidate("Overnight feels-like", prev.apparentMin, day.apparentMin, m, prev.tempMin, day.tempMin))
            }
        }
        // Normalised by each candidate's own bar, so a raw change and a feels-like change compete
        // on how unusual they are rather than on raw magnitude.
        val worst = candidates.maxByOrNull { abs(it.to - it.from) / it.scale } ?: return null
        val delta = worst.to - worst.from
        val mag = abs(delta)
        if (mag < t.tempNotableC * worst.scale) return null

        val major = mag >= t.tempMajorC * worst.scale
        val rising = delta > 0
        val sign = if (rising) "+" else "-"
        return TempHit(
            kind = if (rising) AlertKind.TEMP_RISE else AlertKind.TEMP_DROP,
            severity = if (major) Severity.MAJOR else Severity.NOTABLE,
            headline = when {
                major && rising -> "Sharp warm-up"
                major -> "Sharp temperature drop"
                rising -> "Warming up"
                else -> "Turning colder"
            },
            // When feels-like is what tripped it, the real thermometer reading goes in too —
            // otherwise "turning colder, -4" on a night that only fell 2 reads as a wrong forecast.
            detail = buildString {
                append("${worst.label} ${deg(worst.from)} to ${deg(worst.to)} ")
                append("($sign${String.format(Locale.US, "%.0f", mag)}°)")
                if (worst.apparent) append(", actual ${deg(worst.rawFrom)} to ${deg(worst.rawTo)}")
            },
            short = (if (rising) (if (major) "much warmer" else "warmer") else (if (major) "much colder" else "colder")) +
                ", ${worst.label.lowercase(Locale.US)} ${deg(worst.from)}→${deg(worst.to)}",
        )
    }

    /**
     * Wind is a ratio-ish quantity, so a bare delta is not enough: 5 to 20 km/h is technically a
     * 15 km/h jump and is not news. Requiring the windier day to clear an absolute floor keeps
     * this to days when the wind is actually worth knowing about.
     */
    private fun windChange(prev: DayWeather, day: DayWeather, t: Thresholds): TempHit? {
        val delta = day.gustMaxKmh - prev.gustMaxKmh
        val mag = abs(delta)
        val peak = maxOf(day.gustMaxKmh, prev.gustMaxKmh)
        if (mag < t.windNotableKmh || peak < t.windMinKmh) return null

        val major = mag >= t.windMajorKmh
        val rising = delta > 0
        return TempHit(
            kind = if (rising) AlertKind.WIND_RISE else AlertKind.WIND_DROP,
            severity = if (major) Severity.MAJOR else Severity.NOTABLE,
            headline = when {
                major && rising -> "Strong winds arriving"
                rising -> "Wind picking up"
                major -> "Wind dropping away"
                else -> "Wind easing"
            },
            detail = "Gusts ${prev.gustMaxKmh.roundToInt()} to ${day.gustMaxKmh.roundToInt()} km/h",
            short = (if (rising) (if (major) "strong winds" else "windier") else "calmer") +
                ", gusts ${prev.gustMaxKmh.roundToInt()}→${day.gustMaxKmh.roundToInt()} km/h",
        )
    }

    private fun deg(v: Double) = "${v.roundToInt()}°"
}
