package com.weatheralert.domain

import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.AlertKind
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WeatherAlert
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the forecast into the three practical questions the user actually asks it:
 * can I hang laundry out, do I need a mask, and what should I wear.
 *
 * These are not a re-skin of [AlertEngine]. Each reads different variables at different levels,
 * because the physics differ: laundry is about particles landing on wet fabric and about water
 * arriving, masks are about what gets inhaled, clothing is about how the day will feel compared
 * with the last one.
 *
 * Pure and side-effect free, same as [AlertEngine], and covered by unit tests for the same reason.
 */
object AdviceEngine {

    /**
     * [alerts] lets clothing advice stand down on a day whose temperature alert already says the
     * same thing. Without it the card stacks "Dress warmer - feels 4 colder than yesterday"
     * directly above "Turning colder - ... (-4)", which is one fact printed twice.
     */
    fun evaluate(
        forecast: LocationForecast,
        t: Thresholds,
        today: LocalDate,
        alerts: List<WeatherAlert> = emptyList(),
    ): List<DayAdvice> {
        val daysWithTempAlert = alerts
            .filter { it.kind == AlertKind.TEMP_DROP || it.kind == AlertKind.TEMP_RISE }
            .map { it.date }
            .toSet()
        val days = forecast.days.sortedBy { it.date }
        val lastDay = today.plusDays((t.horizonDays - 1).toLong())
        val out = mutableListOf<DayAdvice>()

        days.forEachIndexed { i, day ->
            val date = day.localDate
            if (date.isBefore(today) || date.isAfter(lastDay)) return@forEachIndexed
            val air = forecast.airFor(date)
            // Only the calendar day before counts as "yesterday" (see AlertEngine).
            val prev = days.getOrNull(i - 1)?.takeIf { it.localDate == date.minusDays(1) }

            if (t.enableLaundry) out += laundry(day, air, t)
            if (t.enableMask) out += mask(day, air, t)
            if (t.enableClothing && prev != null) {
                val advice = clothing(prev, day, t)
                // A thermal-only verdict on a day the alert already covers is pure duplication;
                // one that also mentions rain or wind still earns its place.
                val duplicate = day.date in daysWithTempAlert &&
                    advice.reason.contains("than yesterday") &&
                    !advice.reason.contains("rain") &&
                    !advice.reason.contains("gusts") &&
                    !advice.reason.contains("after dark")
                if (!duplicate) out += advice
            }
        }
        return out
    }

    // ------------------------------------------------------------------ laundry

    /**
     * Two independent ways for an outdoor line to be a bad idea — it gets rained on, or it gets
     * dusted — plus a third, humidity, that merely means it will still be wet at sundown.
     */
    private fun laundry(day: DayWeather, air: DayAir?, t: Thresholds): DayAdvice {
        val reasons = mutableListOf<String>()
        var level = AdviceLevel.GOOD
        var headline = "Fine for laundry"

        fun raise(to: AdviceLevel, text: String) {
            if (to.ordinal > level.ordinal) { level = to; headline = text }
        }

        val rainLikely = day.precipMm >= 0.2 && day.precipProbPct >= t.laundryRainProbPct
        if (rainLikely) {
            raise(AdviceLevel.BAD, "Do not hang laundry")
            reasons += "${day.precipProbPct}% chance of rain"
        } else if (day.precipProbPct >= t.laundryRainProbPct - 10 && day.precipMm > 0.0) {
            raise(AdviceLevel.CAUTION, "Risky for laundry")
            reasons += "${day.precipProbPct}% chance of rain"
        }

        val pm10 = air?.pm10Max
        val dust = air?.dustMax
        if (pm10 != null && pm10 >= t.laundryDustBadPm10) {
            raise(AdviceLevel.BAD, "Do not hang laundry")
            reasons += "dusty air, PM10 ${pm10.roundToInt()}"
        } else if (pm10 != null && pm10 >= t.laundryDustCautionPm10) {
            raise(AdviceLevel.CAUTION, "Whites may dull")
            reasons += "hazy, PM10 ${pm10.roundToInt()}"
        } else if (dust != null && dust >= 25.0) {
            raise(AdviceLevel.CAUTION, "Whites may dull")
            reasons += "airborne dust ${dust.roundToInt()}"
        }

        day.humidityMeanPct?.let { rh ->
            if (rh >= t.laundryHumidityPct) {
                raise(AdviceLevel.CAUTION, "Slow to dry")
                reasons += "humidity $rh%"
            }
        }

        if (day.gustMaxKmh >= t.laundryWindGustKmh) {
            raise(AdviceLevel.CAUTION, "Peg it down")
            reasons += "gusts ${day.gustMaxKmh.roundToInt()} km/h"
        }

        if (level == AdviceLevel.GOOD) {
            reasons += buildString {
                append("dry")
                day.humidityMeanPct?.let { append(", humidity $it%") }
                if (day.windMaxKmh in 8.0..35.0) append(", a good breeze")
            }
        }

        return DayAdvice(day.date, AdviceKind.LAUNDRY, level, headline, reasons.joinToString(", "))
    }

    // --------------------------------------------------------------------- mask

    /**
     * Driven by particulate concentration only — deliberately *not* by the headline AQI.
     *
     * A mask filters particles. It does essentially nothing for ozone or nitrogen dioxide, and
     * those are what drive almost every high-AQI day in Israel: over 30 sampled days the AQI peak
     * was ozone-driven on 100% of days in Jerusalem and Beersheba. Saying "wear a mask" on those
     * days would be confidently wrong, so when the air is bad for gaseous reasons this returns
     * the advice that actually helps instead.
     */
    private fun mask(day: DayWeather, air: DayAir?, t: Thresholds): DayAdvice {
        if (air == null) {
            return DayAdvice(
                day.date, AdviceKind.MASK, AdviceLevel.GOOD,
                "No mask needed", "no air-quality data",
            )
        }
        val pm25 = air.pm25Max
        val pm10 = air.pm10Max
        val at = air.peakHour?.let { String.format(java.util.Locale.US, ", worst around %02d:00", it) }.orEmpty()

        val bad = (pm25 != null && pm25 >= t.maskPm25Bad) || (pm10 != null && pm10 >= t.maskPm10Bad)
        val caution =
            (pm25 != null && pm25 >= t.maskPm25Caution) || (pm10 != null && pm10 >= t.maskPm10Caution)

        val particulate = listOfNotNull(
            pm25?.let { "PM2.5 ${it.roundToInt()}" },
            pm10?.let { "PM10 ${it.roundToInt()}" },
        ).joinToString(", ")

        return when {
            bad -> DayAdvice(
                day.date, AdviceKind.MASK, AdviceLevel.BAD,
                "Wear an N95 outside", particulate + at,
            )
            caution -> DayAdvice(
                day.date, AdviceKind.MASK, AdviceLevel.CAUTION,
                "Mask if you are sensitive", particulate + at,
            )
            // Particles are fine, so a mask is pointless — but the air may still be bad to breathe.
            air.gasDriven(t.euAqiPoor) -> {
                val hour = air.o3PeakHour?.let {
                    String.format(java.util.Locale.US, ", peaks around %02d:00", it)
                }.orEmpty()
                val gas = if ((air.subO3 ?: 0) >= (air.subNo2 ?: 0)) "Ozone" else "Nitrogen dioxide"
                DayAdvice(
                    day.date, AdviceKind.MASK, AdviceLevel.CAUTION,
                    "A mask will not help today",
                    "$gas is high$hour — masks do not filter gases. Keep hard exercise indoors.",
                )
            }
            else -> DayAdvice(
                day.date, AdviceKind.MASK, AdviceLevel.GOOD,
                "No mask needed", particulate.ifEmpty { "particulates low" },
            )
        }
    }

    // ----------------------------------------------------------------- clothing

    /**
     * Answers "what do I put on relative to what I wore yesterday", which is how people actually
     * dress. Uses apparent temperature rather than the raw reading throughout, because that is
     * the quantity a coat is chosen against.
     */
    private fun clothing(prev: DayWeather, day: DayWeather, t: Thresholds): DayAdvice {
        val dayDelta = day.apparentMax - prev.apparentMax
        val nightDelta = day.apparentMin - prev.apparentMin
        val worst = if (abs(dayDelta) >= abs(nightDelta)) dayDelta else nightDelta
        val mag = abs(worst)
        // Clothing is judged purely on feels-like, which is the right quantity for choosing a
        // coat but the more volatile one, so it takes the same amplification-scaled bar the
        // temperature alert uses.
        val notable = t.tempNotableC * t.apparentMultiplier
        val major = t.tempMajorC * t.apparentMultiplier

        var level = AdviceLevel.GOOD
        var headline = "Dress as yesterday"
        val reasons = mutableListOf<String>()

        fun raise(to: AdviceLevel, text: String) {
            if (to.ordinal > level.ordinal) { level = to; headline = text }
        }

        if (mag >= major) {
            raise(
                AdviceLevel.BAD,
                if (worst < 0) "Dress much warmer" else "Dress much lighter",
            )
            reasons += "feels ${t.tempUnit.delta(mag)} ${if (worst < 0) "colder" else "warmer"} than yesterday"
        } else if (mag >= notable) {
            raise(
                AdviceLevel.CAUTION,
                if (worst < 0) "Dress warmer" else "Dress lighter",
            )
            reasons += "feels ${t.tempUnit.delta(mag)} ${if (worst < 0) "colder" else "warmer"} than yesterday"
        }

        if (day.precipMm >= 0.2 && day.precipProbPct >= t.clothingRainProbPct) {
            raise(AdviceLevel.CAUTION, "Take a raincoat")
            reasons += "${day.precipProbPct}% chance of rain"
        }

        if (day.gustMaxKmh >= t.clothingWindGustKmh) {
            raise(AdviceLevel.CAUTION, "Windproof layer")
            reasons += "gusts ${day.gustMaxKmh.roundToInt()} km/h"
        }

        if (day.apparentMin <= t.clothingColdEveningC && day.apparentMax >= 20.0) {
            raise(AdviceLevel.CAUTION, "Layer for the evening")
            reasons += "drops to ${t.tempUnit.temp(day.apparentMin)} after dark"
        }

        if (level == AdviceLevel.GOOD) {
            reasons += "feels ${t.tempUnit.temp(day.apparentMin)} to ${t.tempUnit.temp(day.apparentMax)}"
        }

        return DayAdvice(day.date, AdviceKind.CLOTHING, level, headline, reasons.joinToString(", "))
    }
}
