package com.weatheralert.data

import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.HourAir
import com.weatheralert.model.HourWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.WatchedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import android.util.Log
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo is the only provider that covers forecast, Saharan dust and both AQI scales with no
 * API key and no signup, which is why the app has no backend and no account system at all.
 *
 * Free for non-commercial use, rate-limited by IP rather than by key. Three checks a day across a
 * handful of locations sits far inside that.
 */
object OpenMeteo {

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ---------------------------------------------------------------- forecast

    suspend fun fetchForecast(loc: WatchedLocation, horizonDays: Int): LocationForecast =
        withContext(Dispatchers.IO) {
            val days = horizonDays.coerceIn(1, 14)
            val daily = listOf(
                "weather_code",
                "temperature_2m_max",
                "temperature_2m_min",
                "apparent_temperature_max",
                "apparent_temperature_min",
                "precipitation_sum",
                "precipitation_probability_max",
                "wind_speed_10m_max",
                "wind_gusts_10m_max",
                "relative_humidity_2m_mean",
                "relative_humidity_2m_min",
                "relative_humidity_2m_max",
                "dew_point_2m_max",
                "uv_index_max",
                "sunrise",
                "sunset",
                "wind_direction_10m_dominant",
            ).joinToString(",")
            // Hourly only for what the detail screen needs to say *when*: rain timing and the
            // humidity cycle. A few KB per location.
            val hourlyWeather = "precipitation,precipitation_probability,relative_humidity_2m"

            // past_days=1 buys yesterday's *observed* numbers, which is the baseline the
            // day-over-day temperature rule needs for the very first day it reports on.
            val fUrl = "$FORECAST?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&daily=$daily&hourly=$hourlyWeather&timezone=auto&past_days=1&forecast_days=$days"

            // The sub-indices are what let the mask advice tell a dust day from an ozone day.
            val hourly = "pm10,pm2_5,dust,european_aqi,us_aqi," +
                "european_aqi_pm2_5,european_aqi_pm10,european_aqi_o3,european_aqi_no2"
            val aUrl = "$AIR?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&hourly=$hourly&timezone=auto&forecast_days=${days.coerceAtMost(7)}"

            // Rain comes from ECMWF IFS, everything else from best_match. In Israel best_match is
            // DWD ICON-EU, and the rain backtest (research/rain-backtest/, 4 gauges, Jan 2024 -
            // Feb 2026) put it last: it caught 62% of rain days and forecast about half the rain,
            // where ECMWF IFS caught 93% with amounts right on average. With this model the
            // probability is ECMWF's 51-member ensemble share. Temperature, wind and the rest
            // stay on best_match because their thresholds were tuned on it.
            val rUrl = "$FORECAST?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&daily=precipitation_sum,precipitation_probability_max" +
                "&hourly=precipitation,precipitation_probability" +
                "&models=$RAIN_MODEL&timezone=auto&past_days=1&forecast_days=$days"

            val forecast = try {
                json.decodeFromString<ForecastDto>(get(fUrl))
            } catch (e: Exception) {
                // A parse failure looks identical to a network failure from the outside, so name it.
                if (e is ApiException) throw e
                Log.w(TAG, "forecast parse failed: ${e.message}")
                throw ApiException("Bad forecast response: ${e.message}", e)
            }
            // A failure here falls back to best_match's rain rather than failing the check:
            // a weaker rain forecast beats none.
            val rain = runCatching { json.decodeFromString<ForecastDto>(get(rUrl)) }
                .onFailure { Log.w(TAG, "ECMWF rain unavailable, using best_match: ${it.message}") }
                .getOrNull()
            // Air quality is a separate service and a separate model run. If it is down the
            // rain and temperature alerts are still perfectly good, so it degrades to empty
            // rather than failing the whole check.
            val airDto = runCatching { json.decodeFromString<AirDto>(get(aUrl)) }
                .onFailure { Log.w(TAG, "air quality unavailable: ${it.message}") }
                .getOrNull()

            val dayList = forecast.toDays()
            val hourList = forecast.toHours()
            LocationForecast(
                locationId = loc.id,
                fetchedAtEpochMs = System.currentTimeMillis(),
                days = rain?.let { withRainDays(dayList, it) } ?: dayList,
                air = airDto?.let(::aggregateAir).orEmpty(),
                hours = rain?.let { withRainHours(hourList, it) } ?: hourList,
                airHours = airDto?.toHours().orEmpty(),
                rainSource = if (rain != null) RAIN_MODEL else "best_match",
            )
        }

    const val TAG = "WeatherAlert"

    const val RAIN_MODEL = "ecmwf_ifs"

    /** Per value, so a day or hour ECMWF has no number for keeps best_match's instead of zero. */
    private fun withRainDays(days: List<DayWeather>, rain: ForecastDto): List<DayWeather> {
        val d = rain.daily ?: return days
        val at = d.time.withIndex().associate { (i, t) -> t to i }
        return days.map { day ->
            val i = at[day.date] ?: return@map day
            day.copy(
                precipMm = d.precipSum?.getOrNull(i) ?: day.precipMm,
                precipProbPct = d.precipProbMax?.getOrNull(i) ?: day.precipProbPct,
            )
        }
    }

    private fun withRainHours(hours: List<HourWeather>, rain: ForecastDto): List<HourWeather> {
        val h = rain.hourly ?: return hours
        val at = h.time.withIndex().associate { (i, t) -> t to i }
        return hours.map { hour ->
            val i = at[hour.time] ?: return@map hour
            hour.copy(
                precipMm = h.precip?.getOrNull(i) ?: hour.precipMm,
                precipProbPct = h.precipProb?.getOrNull(i) ?: hour.precipProbPct,
            )
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "WeatherAlert/0.1").build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: ${body.take(200)}")
                Log.d(TAG, "GET ok (${body.length} bytes) $url")
                body
            }
        } catch (e: ApiException) {
            Log.w(TAG, "GET failed: ${e.message} — $url")
            throw e
        } catch (e: Exception) {
            // Without this the app fails silently and neither the user nor a log can say why.
            Log.w(TAG, "GET failed: ${e.javaClass.simpleName}: ${e.message} — $url")
            throw ApiException("${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------- geocoding

    suspend fun search(query: String): List<WatchedLocation> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "$GEOCODE?name=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&count=10&language=en&format=json"
        val dto = json.decodeFromString<GeocodeDto>(get(url))
        dto.results.orEmpty().map {
            WatchedLocation(
                id = WatchedLocation.idFor(it.latitude, it.longitude),
                name = it.name,
                admin = it.admin1,
                country = it.country,
                latitude = it.latitude,
                longitude = it.longitude,
                timezone = it.timezone,
            )
        }
    }

    // ---------------------------------------------------------------- DTOs

    @Serializable
    private data class ForecastDto(val daily: DailyDto? = null, val hourly: HourlyDto? = null) {
        fun toHours(): List<HourWeather> {
            val h = hourly ?: return emptyList()
            return h.time.indices.map { i ->
                HourWeather(
                    time = h.time[i],
                    precipMm = h.precip?.getOrNull(i) ?: 0.0,
                    precipProbPct = h.precipProb?.getOrNull(i),
                    humidityPct = h.humidity?.getOrNull(i),
                )
            }
        }

        fun toDays(): List<DayWeather> {
            val d = daily ?: return emptyList()
            return d.time.indices.map { i ->
                DayWeather(
                    date = d.time[i],
                    weatherCode = d.weatherCode?.getOrNull(i) ?: 0,
                    tempMax = d.tempMax?.getOrNull(i) ?: 0.0,
                    tempMin = d.tempMin?.getOrNull(i) ?: 0.0,
                    // Apparent temperature is occasionally null at the edge of a model run;
                    // falling back to the real temperature makes the feels-like rule a no-op
                    // for that day rather than throwing the whole location away.
                    apparentMax = d.apparentMax?.getOrNull(i) ?: d.tempMax?.getOrNull(i) ?: 0.0,
                    apparentMin = d.apparentMin?.getOrNull(i) ?: d.tempMin?.getOrNull(i) ?: 0.0,
                    precipMm = d.precipSum?.getOrNull(i) ?: 0.0,
                    precipProbPct = d.precipProbMax?.getOrNull(i) ?: 0,
                    windMaxKmh = d.windMax?.getOrNull(i) ?: 0.0,
                    gustMaxKmh = d.gustMax?.getOrNull(i) ?: 0.0,
                    humidityMeanPct = d.humidityMean?.getOrNull(i),
                    humidityMinPct = d.humidityMin?.getOrNull(i),
                    humidityMaxPct = d.humidityMax?.getOrNull(i),
                    dewPointMaxC = d.dewPointMax?.getOrNull(i),
                    uvIndexMax = d.uvMax?.getOrNull(i),
                    sunrise = d.sunrise?.getOrNull(i),
                    sunset = d.sunset?.getOrNull(i),
                    windDirectionDeg = d.windDirection?.getOrNull(i),
                )
            }
        }
    }

    @Serializable
    private data class DailyDto(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?>? = null,
        @SerialName("temperature_2m_max") val tempMax: List<Double?>? = null,
        @SerialName("temperature_2m_min") val tempMin: List<Double?>? = null,
        @SerialName("apparent_temperature_max") val apparentMax: List<Double?>? = null,
        @SerialName("apparent_temperature_min") val apparentMin: List<Double?>? = null,
        @SerialName("precipitation_sum") val precipSum: List<Double?>? = null,
        @SerialName("precipitation_probability_max") val precipProbMax: List<Int?>? = null,
        @SerialName("wind_speed_10m_max") val windMax: List<Double?>? = null,
        @SerialName("wind_gusts_10m_max") val gustMax: List<Double?>? = null,
        @SerialName("relative_humidity_2m_mean") val humidityMean: List<Int?>? = null,
        @SerialName("relative_humidity_2m_min") val humidityMin: List<Int?>? = null,
        @SerialName("relative_humidity_2m_max") val humidityMax: List<Int?>? = null,
        @SerialName("dew_point_2m_max") val dewPointMax: List<Double?>? = null,
        @SerialName("uv_index_max") val uvMax: List<Double?>? = null,
        val sunrise: List<String?>? = null,
        val sunset: List<String?>? = null,
        @SerialName("wind_direction_10m_dominant") val windDirection: List<Int?>? = null,
    )

    @Serializable
    private data class HourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("precipitation") val precip: List<Double?>? = null,
        @SerialName("precipitation_probability") val precipProb: List<Int?>? = null,
        @SerialName("relative_humidity_2m") val humidity: List<Int?>? = null,
    )

    @Serializable
    private data class AirDto(val hourly: AirHourlyDto? = null) {
        fun toHours(): List<HourAir> {
            val h = hourly ?: return emptyList()
            return h.time.indices.map { i ->
                HourAir(h.time[i], euAqi = h.euAqi?.getOrNull(i), usAqi = h.usAqi?.getOrNull(i))
            }
        }
    }

    @Serializable
    private data class AirHourlyDto(
        val time: List<String> = emptyList(),
        val pm10: List<Double?>? = null,
        @SerialName("pm2_5") val pm25: List<Double?>? = null,
        val dust: List<Double?>? = null,
        @SerialName("european_aqi") val euAqi: List<Int?>? = null,
        @SerialName("us_aqi") val usAqi: List<Int?>? = null,
        @SerialName("european_aqi_pm2_5") val subPm25: List<Int?>? = null,
        @SerialName("european_aqi_pm10") val subPm10: List<Int?>? = null,
        @SerialName("european_aqi_o3") val subO3: List<Int?>? = null,
        @SerialName("european_aqi_no2") val subNo2: List<Int?>? = null,
    )

    /**
     * The air-quality service is hourly-only, so daily figures are built here. A day is
     * represented by its *peak*, not its mean: a six-hour dust plume that doubles PM10 is the
     * thing worth a notification, and averaging it across 24 hours hides exactly that.
     */
    private fun aggregateAir(dto: AirDto): List<DayAir> {
        val h = dto.hourly ?: return emptyList()
        val byDate = LinkedHashMap<String, MutableList<Int>>()
        h.time.forEachIndexed { i, t ->
            byDate.getOrPut(t.substringBefore('T')) { mutableListOf() }.add(i)
        }
        return byDate.map { (date, idx) ->
            val pm10 = idx.mapNotNull { h.pm10?.getOrNull(it) }
            val peakIdx = idx.maxByOrNull { h.pm10?.getOrNull(it) ?: Double.NEGATIVE_INFINITY }
            DayAir(
                date = date,
                pm10Max = pm10.maxOrNull(),
                pm25Max = idx.mapNotNull { h.pm25?.getOrNull(it) }.maxOrNull(),
                dustMax = idx.mapNotNull { h.dust?.getOrNull(it) }.maxOrNull(),
                euAqiMax = idx.mapNotNull { h.euAqi?.getOrNull(it) }.maxOrNull(),
                usAqiMax = idx.mapNotNull { h.usAqi?.getOrNull(it) }.maxOrNull(),
                peakHour = hourOf(h, peakIdx?.takeIf { pm10.isNotEmpty() }),
                subPm25 = idx.mapNotNull { h.subPm25?.getOrNull(it) }.maxOrNull(),
                subPm10 = idx.mapNotNull { h.subPm10?.getOrNull(it) }.maxOrNull(),
                subO3 = idx.mapNotNull { h.subO3?.getOrNull(it) }.maxOrNull(),
                subNo2 = idx.mapNotNull { h.subNo2?.getOrNull(it) }.maxOrNull(),
                o3PeakHour = hourOf(
                    h,
                    idx.maxByOrNull { h.subO3?.getOrNull(it) ?: Int.MIN_VALUE }
                        ?.takeIf { h.subO3?.getOrNull(it) != null },
                ),
            )
        }
    }

    private fun hourOf(h: AirHourlyDto, index: Int?): Int? = index
        ?.let { h.time.getOrNull(it) }
        ?.substringAfter('T')
        ?.substringBefore(':')
        ?.toIntOrNull()

    @Serializable
    private data class GeocodeDto(val results: List<GeoResult>? = null)

    @Serializable
    private data class GeoResult(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
        val timezone: String? = null,
    )
}
