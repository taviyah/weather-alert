package com.weatheralert.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** A place the user watches. [id] is stable across renames so the alert ledger keeps working. */
@Serializable
data class WatchedLocation(
    val id: String,
    val name: String,
    val admin: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
) {
    val subtitle: String get() = listOfNotNull(admin, country).joinToString(", ")

    companion object {
        /** Coordinates rounded to ~1 km: re-adding the same town twice must not duplicate it. */
        fun idFor(lat: Double, lon: Double): String =
            "%.2f_%.2f".format(java.util.Locale.US, lat, lon)
    }
}

@Serializable
data class DayWeather(
    val date: String,
    val weatherCode: Int,
    val tempMax: Double,
    val tempMin: Double,
    val apparentMax: Double,
    val apparentMin: Double,
    val precipMm: Double,
    val precipProbPct: Int,
    val windMaxKmh: Double,
    val gustMaxKmh: Double,
    /** Mean relative humidity, %. Drives the "it will not dry" half of the laundry signal. */
    val humidityMeanPct: Int? = null,
    // Detail-screen only. Nullable so a forecast cached by an older version still decodes.
    val humidityMinPct: Int? = null,
    val humidityMaxPct: Int? = null,
    /** Daily max dew point, °C — a far better measure of how sticky the air feels than humidity. */
    val dewPointMaxC: Double? = null,
    val uvIndexMax: Double? = null,
    /** Local ISO times, e.g. "2026-10-01T06:34". */
    val sunrise: String? = null,
    val sunset: String? = null,
    /** Direction the wind blows *from*, degrees. */
    val windDirectionDeg: Int? = null,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

/**
 * One forecast hour. Daily totals say *whether* it rains; only the hourly series says *when*,
 * which is the question that decides whether to take an umbrella to work or just to the gym.
 */
@Serializable
data class HourWeather(
    /** Local ISO time, e.g. "2026-10-01T14:00". */
    val time: String,
    val precipMm: Double = 0.0,
    val precipProbPct: Int? = null,
    val humidityPct: Int? = null,
) {
    val localDate: LocalDate get() = LocalDate.parse(time.substringBefore('T'))
    val hour: Int get() = time.substringAfter('T').substringBefore(':').toInt()
}

/** One air-quality hour, kept so the detail screen can say *when* the air is worst and cleanest. */
@Serializable
data class HourAir(
    val time: String,
    val euAqi: Int? = null,
    val usAqi: Int? = null,
) {
    val localDate: LocalDate get() = LocalDate.parse(time.substringBefore('T'))
    val hour: Int get() = time.substringAfter('T').substringBefore(':').toInt()

    fun aqi(scale: AqiScale): Int? = if (scale == AqiScale.EUROPEAN) euAqi else usAqi
}

@Serializable
data class DayAir(
    val date: String,
    val pm10Max: Double? = null,
    val pm25Max: Double? = null,
    val dustMax: Double? = null,
    val euAqiMax: Int? = null,
    val usAqiMax: Int? = null,
    /** Hour of day (0-23) at which pm10 peaks — turns "dusty Thursday" into "worst around 14:00". */
    val peakHour: Int? = null,
    /**
     * European AQI sub-indices. The headline AQI is the *maximum* of these, not a blend, so these
     * are the only way to tell whether a bad-air day is particulate or gaseous — which decides
     * whether a mask is useful advice or useless advice.
     */
    val subPm25: Int? = null,
    val subPm10: Int? = null,
    val subO3: Int? = null,
    val subNo2: Int? = null,
    /** Hour at which ozone peaks. Ozone is strongly diurnal, so "worst at 15:00" is actionable. */
    val o3PeakHour: Int? = null,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)

    /** True when the air is bad because of gases rather than particles a mask could stop. */
    fun gasDriven(threshold: Int): Boolean {
        val gas = maxOf(subO3 ?: 0, subNo2 ?: 0)
        val particle = maxOf(subPm25 ?: 0, subPm10 ?: 0)
        return gas >= threshold && gas > particle
    }
}

/**
 * One location's data as fetched. [days] starts at *yesterday* — the day-over-day temperature
 * rule needs a baseline for the first day it reports on, and yesterday's actual is a better
 * baseline than today's forecast.
 */
@Serializable
data class LocationForecast(
    val locationId: String,
    val fetchedAtEpochMs: Long,
    val days: List<DayWeather>,
    val air: List<DayAir>,
    val hours: List<HourWeather> = emptyList(),
    val airHours: List<HourAir> = emptyList(),
    /**
     * The Open-Meteo model the rain numbers came from: "ecmwf_ifs" normally, "best_match" if the
     * ECMWF request failed or the forecast was cached by a version before the switch.
     */
    val rainSource: String = "best_match",
) {
    fun airFor(date: LocalDate): DayAir? = air.firstOrNull { it.date == date.toString() }

    fun hoursFor(date: LocalDate): List<HourWeather> = hours.filter { it.localDate == date }

    fun airHoursFor(date: LocalDate): List<HourAir> = airHours.filter { it.localDate == date }
}

enum class AlertKind {
    RAIN, DUST, AIR_QUALITY, TEMP_DROP, TEMP_RISE, WIND_RISE, WIND_DROP, HEAT, COLD, FROST,
}

enum class Severity { NOTABLE, MAJOR }

@Serializable
data class WeatherAlert(
    val locationId: String,
    val locationName: String,
    val date: String,
    val kind: AlertKind,
    val severity: Severity,
    val headline: String,
    val detail: String,
    /**
     * The alert as a fragment of a sentence ("heavy rain 12 mm (70%)", "gusts 25→60 km/h"), so
     * the home card can put a whole day on one line. Empty in alerts cached by older versions.
     */
    val short: String = "",
) {
    val localDate: LocalDate get() = LocalDate.parse(date)

    /**
     * Dedupe identity. Severity is deliberately part of it: a NOTABLE alert that later escalates
     * to MAJOR is worth interrupting the user a second time; one that quietly fades is not.
     */
    val key: String get() = "$locationId|$date|$kind|$severity"
}

/**
 * The three practical questions the raw alerts are really being asked to answer. Advice is
 * derived from the same forecast but with its own thresholds, because the level that matters
 * differs per question: dust that is harmless to breathe still greys a white sheet.
 */
enum class AdviceKind { LAUNDRY, MASK, CLOTHING }

enum class AdviceLevel { GOOD, CAUTION, BAD }

@Serializable
data class DayAdvice(
    val date: String,
    val kind: AdviceKind,
    val level: AdviceLevel,
    val headline: String,
    val reason: String,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)

    val key: String get() = "$date|$kind|$level"
}

enum class AqiScale { EUROPEAN, US }

enum class TempUnit { CELSIUS, FAHRENHEIT }

/**
 * Every number the alert engine consults. Temperatures are always stored in Celsius; [tempUnit]
 * only affects display, so switching units never silently changes what triggers an alert.
 *
 * Defaults are tuned for Israel / the eastern Mediterranean: rain is a notable event for most of
 * the year so its bar sits low, and the dust bar assumes khamsin-type events where PM10 routinely
 * runs into the hundreds.
 */
@Serializable
data class Thresholds(
    /** Days of forecast to watch, counting today. */
    val horizonDays: Int = 4,
    /**
     * The forecast models behind this data are re-run about every 3 hours, but the alerts are
     * about days 1-4 out: picking a change up three hours later than it was published makes no
     * practical difference to "it will rain on Thursday", and halves the wakeups and traffic.
     */
    val checkEveryHours: Int = 6,

    val enableRain: Boolean = true,
    val rainProbPct: Int = 50,
    val rainMinMm: Double = 0.5,
    val rainHeavyMm: Double = 10.0,

    val enableDust: Boolean = true,
    val dustUgm3: Double = 50.0,
    val dustSevereUgm3: Double = 150.0,
    val pm10Ugm3: Double = 100.0,
    val pm10SevereUgm3: Double = 180.0,

    val enableAirQuality: Boolean = true,
    val aqiScale: AqiScale = AqiScale.EUROPEAN,
    /**
     * European AQI bands put "Poor" at 60, but 17 days of sampling in September 2026 found a
     * daily-max median of 69 in Jerusalem, 62 in Beersheba and 51 in Tel Aviv — background ozone
     * and dust keep the whole eastern Mediterranean sitting inside that band. Alerting at 60 would
     * fire in Jerusalem every single day, so the bar starts at the next band up.
     * US AQI keeps the EPA bands: 101 = unhealthy for sensitive groups, 151 = unhealthy.
     */
    val euAqiPoor: Int = 80,
    val euAqiVeryPoor: Int = 100,
    val usAqiPoor: Int = 101,
    val usAqiVeryPoor: Int = 151,

    val enableTempChange: Boolean = true,
    /**
     * Day-over-day change in daily max or min, in °C.
     *
     * Set deliberately below the meteorological "surge" levels: 5/8 catches only weather-service
     * grade events, which over a quiet 60-day stretch in Israel fired 0.1-0.2 times a week and
     * felt like nothing was happening. 3/5.5 raises that to roughly 0.5-1.8 a week, which is a
     * change you would actually notice walking outside.
     */
    val tempNotableC: Double = 3.0,
    val tempMajorC: Double = 5.5,
    /** Also test feels-like: a 5 °C drop under a 40 km/h wind lands like a 10 °C one. */
    val useApparentTemp: Boolean = true,
    /**
     * How much bigger a feels-like swing has to be before it counts as the same news as a raw one.
     *
     * Apparent temperature is a non-linear function of temperature *and* humidity, and in warm
     * humid air that curve is steep: measured over 60 days across six Israeli cities, day-over-day
     * feels-like deltas ran a median 1.37x the raw ones (p90 2.73x). Testing both against the same
     * 3 deg bar therefore let feels-like do 78% of all the alerting on its own — Hadera reported
     * "turning colder, -4 deg" on a night whose actual low moved 2.2 deg. Scaling by the measured
     * amplification puts the two tests back on equal footing.
     */
    val apparentMultiplier: Double = 1.4,

    /**
     * Measured on daily peak *gusts*, not mean wind: over 90 days across five Israeli cities the
     * mean-wind day-over-day delta never once reached 15 km/h (p98 = 10), so a rule built on it
     * would be permanently silent. Gust deltas run median 3, p90 10, p95 13, max 26 — which also
     * means the 20-30 km/h bar that felt right by intuition would have fired on 0.6% of days.
     *
     * [windMinKmh] is a floor on the windier of the two days, so a jump from dead calm to a light
     * breeze does not count as weather.
     */
    val enableWindChange: Boolean = true,
    val windNotableKmh: Double = 12.0,
    val windMajorKmh: Double = 18.0,
    val windMinKmh: Double = 35.0,

    val enableExtremes: Boolean = true,
    val heatC: Double = 35.0,
    val heatSevereC: Double = 38.0,
    val coldC: Double = 8.0,
    val frostC: Double = 0.0,
    /**
     * Hysteresis for the crossing rules. Without it a climate whose daily max oscillates right at
     * the threshold alerts every other day: backtesting 60 days of Beersheba highs against a bare
     * 35 °C line produced 7 heat alerts from a 34/36/34/36 sawtooth. Requiring the previous day to
     * sit a clear margin below the line cuts that to the real transitions.
     */
    val extremeDeadbandC: Double = 2.0,

    val tempUnit: TempUnit = TempUnit.CELSIUS,

    /**
     * Render for electronic paper: pure black on white, no shadows, no animation, and severity
     * carried by shape and text rather than by colour.
     *
     * Defaulted once from [com.weatheralert.ui.EInkDetect] on first launch and then left alone,
     * so a user who overrides it does not get overridden back on the next start.
     */
    val eInkMode: Boolean = false,
    val eInkDefaultApplied: Boolean = false,

    /**
     * Run [com.weatheralert.work.KeepAliveService], the always-on "Watching the forecast"
     * service. Only phones whose maker kills background apps (DuraSpeed on MediaTek builds, such
     * as the Bigme HiBreak) need it; elsewhere it only keeps the app in memory for nothing.
     * Defaulted once from [com.weatheralert.work.KeepAliveService.likelyNeeded] on first launch,
     * then left to the user, like [eInkMode].
     */
    val keepAlive: Boolean = false,
    val keepAliveDefaultApplied: Boolean = false,

    // ---------------------------------------------------------------- advice signals

    /**
     * Laundry cares about *deposition and wetting*, not about breathing. Its dust bar therefore
     * sits well below the health one: measured clean-air PM10 in Israel runs 24-28 µg/m³, and
     * wet fabric is an efficient deposition surface, so 60 is roughly where whites start to dull
     * even though nobody would call the air unhealthy.
     */
    val enableLaundry: Boolean = true,
    val laundryDustCautionPm10: Double = 60.0,
    val laundryDustBadPm10: Double = 100.0,
    /** Lower than the rain alert's bar: being surprised by drizzle is cheap, re-washing is not. */
    val laundryRainProbPct: Int = 40,
    val laundryHumidityPct: Int = 80,
    /** Above this, pegs start losing. Set higher than the clothing gust bar because a windbreaker
     *  is wanted well before a shirt actually leaves the line. */
    val laundryWindGustKmh: Double = 60.0,

    /**
     * Mask advice is driven by particulates only, never by the headline AQI. A mask filters
     * particles; it does essentially nothing for ozone or NO2, which is what actually drives
     * almost every high-AQI day in Israel.
     */
    val enableMask: Boolean = true,
    val maskPm25Caution: Double = 25.0,
    val maskPm25Bad: Double = 50.0,
    val maskPm10Caution: Double = 60.0,
    val maskPm10Bad: Double = 100.0,

    /** Clothing reuses [tempNotableC] / [tempMajorC] for the thermal half. */
    val enableClothing: Boolean = true,
    val clothingRainProbPct: Int = 40,
    /**
     * 45 km/h looked like a lot of wind and was not: measured over 60 days, Jerusalem's *median*
     * daily gust is 49 km/h and Haifa's is 42, so a 45 bar flagged 77% of Jerusalem days and made
     * the clothing signal meaningless. 55 sits near the top of the normal range everywhere
     * sampled — genuinely "you want a windproof layer" wind.
     */
    val clothingWindGustKmh: Double = 55.0,
    val clothingColdEveningC: Double = 10.0,
) {
    val aqiPoor: Int get() = if (aqiScale == AqiScale.EUROPEAN) euAqiPoor else usAqiPoor
    val aqiVeryPoor: Int get() = if (aqiScale == AqiScale.EUROPEAN) euAqiVeryPoor else usAqiVeryPoor
}
