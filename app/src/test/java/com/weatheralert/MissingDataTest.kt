package com.weatheralert

import com.weatheralert.data.OpenMeteo
import com.weatheralert.domain.AlertEngine
import com.weatheralert.model.AlertKind
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A forecast day with a missing temperature or gust used to be stored as 0 °C / 0 km/h, which
 * read as real weather and fired a false "Frost" and "Sharp temperature drop".
 */
class MissingDataTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)
    private val loc = WatchedLocation("tlv", "Tel Aviv", latitude = 32.08, longitude = 34.78)

    /** Five days, yesterday to three days out. [nullDay] is the index whose figures are null. */
    private fun response(nullDay: Int?, field: String): String {
        val dates = (-1L..3L).map { "\"${today.plusDays(it)}\"" }
        fun series(value: String, name: String) = (0 until 5).joinToString(",", "[", "]") {
            if (it == nullDay && name == field) "null" else value
        }
        return """
            {"daily": {
              "time": [${dates.joinToString(",")}],
              "temperature_2m_max": ${series("22.0", "temperature_2m_max")},
              "temperature_2m_min": ${series("18.0", "temperature_2m_min")},
              "wind_gusts_10m_max": ${series("30.0", "wind_gusts_10m_max")},
              "wind_speed_10m_max": ${series("12.0", "wind_speed_10m_max")}
            }}
        """.trimIndent()
    }

    private fun alerts(body: String) = AlertEngine.evaluate(
        loc,
        LocationForecast("tlv", 0L, OpenMeteo.parseDays(body), emptyList()),
        Thresholds(),
        today,
    )

    @Test
    fun `a day with a missing low is dropped, not read as 0 degrees`() {
        val days = OpenMeteo.parseDays(response(nullDay = 4, field = "temperature_2m_min"))
        assertEquals(4, days.size)
        assertTrue(days.none { it.tempMin == 0.0 })
    }

    @Test
    fun `a missing low at the edge of the forecast raises no frost or drop alert`() {
        val found = alerts(response(nullDay = 4, field = "temperature_2m_min"))
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun `a missing high in the middle does not compare across the gap`() {
        val found = alerts(response(nullDay = 2, field = "temperature_2m_max"))
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun `a missing gust raises no wind alert`() {
        val found = alerts(response(nullDay = 3, field = "wind_gusts_10m_max"))
        assertTrue(found.toString(), found.none { it.kind == AlertKind.WIND_DROP || it.kind == AlertKind.WIND_RISE })
    }

    @Test
    fun `a two-day gap is not treated as a one-day change`() {
        // Day 1 is missing; days 0 and 2 differ by 10 degrees. Without the calendar-day check
        // that would be a "Sharp temperature drop" from day 0 straight to day 2.
        val body = """
            {"daily": {
              "time": ["${today.minusDays(1)}", "$today", "${today.plusDays(1)}", "${today.plusDays(2)}"],
              "temperature_2m_max": [22.0, 22.0, null, 12.0],
              "temperature_2m_min": [15.0, 15.0, 15.0, 5.0],
              "wind_gusts_10m_max": [30.0, 30.0, 30.0, 30.0]
            }}
        """.trimIndent()
        val found = alerts(body)
        assertTrue(found.toString(), found.none { it.kind == AlertKind.TEMP_DROP })
    }
}
