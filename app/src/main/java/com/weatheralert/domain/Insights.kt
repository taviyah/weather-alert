package com.weatheralert.domain

import com.weatheralert.model.AqiScale
import com.weatheralert.model.DayAir
import com.weatheralert.model.HourAir
import com.weatheralert.model.HourWeather
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Plain-language readings of the forecast for the detail screen. The alert and advice engines
 * decide what is worth interrupting someone for; this answers the questions a person asks when
 * they open a place on purpose — how bad is the air, when will it rain, how sticky will it feel.
 *
 * Pure and side-effect free, like the engines, so every band edge is unit-testable.
 */
object Insights {

    // ---------------------------------------------------------------- air quality

    /**
     * [level] runs 0-5 on both scales, so one six-step meter can draw either. The two scales do
     * not line up band for band — European "Poor" (60-80) is milder than US "Unhealthy" — so the
     * label and meaning always come from the scale the user picked.
     */
    data class AqiBand(val level: Int, val label: String, val meaning: String)

    private val EU_BANDS = listOf(
        "Good" to "Clean air. Nothing to adjust.",
        "Fair" to "Fine for everyone, including people with asthma or heart conditions.",
        "Moderate" to "Fine for most people. With asthma or a heart or lung condition, ease off " +
            "long, hard outdoor exercise if you notice symptoms.",
        "Poor" to "Sensitive groups — asthma, heart or lung conditions, children, older people — " +
            "should cut down on hard outdoor exercise.",
        "Very poor" to "Sensitive groups should avoid hard outdoor exercise, and everyone else " +
            "should reduce it, especially if eyes or throat feel irritated.",
        "Extremely poor" to "Everyone should avoid hard outdoor exercise. Sensitive groups are " +
            "better off indoors.",
    )

    private val US_BANDS = listOf(
        "Good" to "Clean air. Nothing to adjust.",
        "Moderate" to "Acceptable. Unusually sensitive people may want to shorten long, hard " +
            "outdoor exercise.",
        "Unhealthy for sensitive groups" to "Heart or lung conditions, children and older " +
            "people should reduce long or hard outdoor exertion.",
        "Unhealthy" to "Everyone may start to feel it. Sensitive groups should avoid long or " +
            "hard outdoor exertion.",
        "Very unhealthy" to "Health alert: everyone should avoid long or hard outdoor exertion.",
        "Hazardous" to "Stay indoors and keep activity low.",
    )

    /** European bands are half-open, 0-20, 20-40…, so 80 already reads "Very poor" — matching the
     *  alert bar, which starts at 80. US bands are the EPA's inclusive 0-50, 51-100… */
    fun aqiBand(value: Int, scale: AqiScale): AqiBand {
        val level = when (scale) {
            AqiScale.EUROPEAN -> (value / 20).coerceIn(0, 5)
            AqiScale.US -> when {
                value <= 50 -> 0
                value <= 100 -> 1
                value <= 150 -> 2
                value <= 200 -> 3
                value <= 300 -> 4
                else -> 5
            }
        }
        val (label, meaning) = (if (scale == AqiScale.EUROPEAN) EU_BANDS else US_BANDS)[level]
        return AqiBand(level, label, meaning)
    }

    /** [phrase] is the name as it reads mid-sentence. Gases are the ones a mask cannot stop. */
    data class Pollutant(val name: String, val phrase: String, val subIndex: Int, val isGas: Boolean)

    /**
     * European sub-indices, worst first. The headline AQI is the *maximum* of these, so the first
     * entry is what is actually making the air bad.
     */
    fun pollutants(air: DayAir): List<Pollutant> = listOfNotNull(
        air.subO3?.let { Pollutant("Ozone", "ozone", it, isGas = true) },
        air.subPm25?.let { Pollutant("Fine particles (PM2.5)", "fine particles (PM2.5)", it, isGas = false) },
        air.subPm10?.let { Pollutant("Coarse particles (PM10)", "coarse particles (PM10)", it, isGas = false) },
        air.subNo2?.let { Pollutant("Nitrogen dioxide", "nitrogen dioxide", it, isGas = true) },
    ).sortedByDescending { it.subIndex }

    data class AirTiming(val worst: HourAir, val cleanest: HourAir?)

    /**
     * The cleanest hour is looked for between 06:00 and 21:00 only. "Cleanest at 04:00" is true
     * and useless; the point is when to go for a run.
     */
    fun airTiming(hours: List<HourAir>, scale: AqiScale): AirTiming? {
        val rated = hours.filter { it.aqi(scale) != null }
        val worst = rated.maxByOrNull { it.aqi(scale)!! } ?: return null
        val cleanest = rated.filter { it.hour in 6..21 }.minByOrNull { it.aqi(scale)!! }
        return AirTiming(worst, cleanest?.takeIf { it.aqi(scale)!! < worst.aqi(scale)!! })
    }

    // ---------------------------------------------------------------- rain

    /** Rain in the hour starting at [hour], local time. */
    data class RainHour(val hour: Int, val mm: Double, val probPct: Int)

    /**
     * [date]'s 24 rain hours, each keyed by the hour it *starts*.
     *
     * Open-Meteo stamps each hourly precipitation value at the *end* of the hour it covers, so
     * the 14:00 figure is rain between 13:00 and 14:00. Ignoring that shifts every window an hour
     * late, and puts the 00:00 value — yesterday's last hour — on the wrong day.
     */
    fun rainHours(hours: List<HourWeather>, date: LocalDate): List<RainHour> = hours.mapNotNull { h ->
        val covers = LocalDateTime.parse(h.time).minusHours(1)
        if (covers.toLocalDate() != date) null
        else RainHour(covers.hour, h.precipMm, h.precipProbPct ?: 0)
    }.sortedBy { it.hour }

    /** [startHour] inclusive, [endHour] exclusive, both 0-24, in local time on that day. */
    data class RainWindow(
        val startHour: Int,
        val endHour: Int,
        val totalMm: Double,
        val maxProbPct: Int,
        val peakMmPerHour: Double,
    ) {
        /** Standard meteorological rates: light < 2.5 mm/h, heavy > 7.6 mm/h. */
        val intensity: String
            get() = when {
                peakMmPerHour >= 7.6 -> "heavy"
                peakMmPerHour >= 2.5 -> "moderate"
                peakMmPerHour > 0.0 -> "light"
                else -> "possible"
            }
    }

    /**
     * Hours on [date] when rain is likely, merged into windows. See [rainHours] for the timing.
     *
     * An hour counts as wet at [minProbPct] chance (the same bar the laundry and clothing advice
     * use) or [minMm] of forecast rain. One dry hour inside a shower does not split it in two.
     */
    fun rainWindows(
        hours: List<HourWeather>,
        date: LocalDate,
        minProbPct: Int = 40,
        minMm: Double = 0.2,
    ): List<RainWindow> {
        val slots = rainHours(hours, date)
        val wet = slots.filter { it.probPct >= minProbPct || it.mm >= minMm }
        if (wet.isEmpty()) return emptyList()

        val groups = mutableListOf(mutableListOf(wet.first()))
        for (slot in wet.drop(1)) {
            if (slot.hour - groups.last().last().hour <= 2) groups.last().add(slot)
            else groups.add(mutableListOf(slot))
        }
        return groups.map { g ->
            val inside = slots.filter { it.hour in g.first().hour..g.last().hour }
            RainWindow(
                startHour = g.first().hour,
                endHour = g.last().hour + 1,
                totalMm = inside.sumOf { it.mm },
                maxProbPct = inside.maxOf { it.probPct },
                peakMmPerHour = inside.maxOf { it.mm },
            )
        }
    }

    /** One line of the hour-by-hour rain table. */
    sealed interface RainRow {
        data class Hour(val hour: Int, val probPct: Int, val mm: Double) : RainRow
        /** [startHour] inclusive, [endHour] exclusive: a run of hours with no rain to speak of. */
        data class Dry(val startHour: Int, val endHour: Int) : RainRow
    }

    /**
     * [date]'s hours from [fromHour] on, as a table: every hour with any real chance or amount
     * gets its own line, and runs of quiet hours fold into one "dry" line, so a day with one
     * shower is four lines rather than twenty-four. Empty when no hour reaches the bar at all.
     *
     * The bar is deliberately low (10%, 0.1 mm): this table is where the actual numbers live,
     * including the hours too unlikely to count as a rain window.
     */
    fun rainTable(
        hours: List<HourWeather>,
        date: LocalDate,
        fromHour: Int = 0,
        minProbPct: Int = 10,
        minMm: Double = 0.1,
    ): List<RainRow> {
        val slots = rainHours(hours, date).filter { it.hour >= fromHour }
        if (slots.none { it.probPct >= minProbPct || it.mm >= minMm }) return emptyList()
        val rows = mutableListOf<RainRow>()
        var dryStart: Int? = null
        for (s in slots) {
            val quiet = s.probPct < minProbPct && s.mm < minMm
            if (quiet) {
                if (dryStart == null) dryStart = s.hour
            } else {
                dryStart?.let { rows += RainRow.Dry(it, s.hour) }
                dryStart = null
                rows += RainRow.Hour(s.hour, s.probPct, s.mm)
            }
        }
        dryStart?.let { rows += RainRow.Dry(it, slots.last().hour + 1) }
        return rows
    }

    // ---------------------------------------------------------------- humidity

    data class Mugginess(val label: String, val meaning: String)

    /**
     * Judged on dew point, not relative humidity. 80% at dawn in October is normal and feels
     * fine; 60% at 33 °C is miserable. Dew point is the absolute amount of water in the air, which
     * is what decides whether sweat can evaporate, so it tracks "sticky" far better.
     */
    fun mugginess(dewPointC: Double): Mugginess = when {
        dewPointC < 10 -> Mugginess("Dry", "Crisp air. Skin and lips may feel dry.")
        dewPointC < 16 -> Mugginess("Comfortable", "Pleasant, not noticeably humid.")
        dewPointC < 18 -> Mugginess("Slightly humid", "Noticeable, but comfortable for most people.")
        dewPointC < 21 -> Mugginess("Muggy", "Sticky. Sweat dries slowly.")
        dewPointC < 24 -> Mugginess("Oppressive", "Heavy, uncomfortable air; exercise feels much harder.")
        else -> Mugginess("Extremely oppressive", "Sweat barely evaporates. Take it easy outdoors.")
    }

    /** Most and least humid hours of [date], for "damp mornings, dry afternoons". */
    fun humidityTiming(hours: List<HourWeather>, date: LocalDate): Pair<HourWeather, HourWeather>? {
        val day = hours.filter { it.localDate == date && it.humidityPct != null }
        val wettest = day.maxByOrNull { it.humidityPct!! } ?: return null
        val driest = day.minByOrNull { it.humidityPct!! } ?: return null
        return wettest to driest
    }

    // ---------------------------------------------------------------- sun and wind

    data class UvBand(val label: String, val advice: String)

    fun uvBand(uv: Double): UvBand = when {
        uv < 3 -> UvBand("Low", "No protection needed.")
        uv < 6 -> UvBand("Moderate", "Sunscreen and a hat if you are out long around midday.")
        uv < 8 -> UvBand("High", "Sunscreen, hat and shade between 11:00 and 15:00.")
        uv < 11 -> UvBand("Very high", "Avoid the midday sun; sunscreen, hat and sunglasses.")
        else -> UvBand("Extreme", "Stay out of the midday sun.")
    }

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compass(degrees: Int): String = COMPASS[(((degrees % 360) + 360) % 360 * 16 + 180) / 360 % 16]
}
