package com.weatheralert

import com.weatheralert.domain.Insights
import com.weatheralert.model.AqiScale
import com.weatheralert.model.DayAir
import com.weatheralert.model.HourAir
import com.weatheralert.model.HourWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {

    private val day = LocalDate.of(2026, 10, 2)

    /** Hourly series for [day] and the first hour of the next, with rain at the given stamps. */
    private fun hours(vararg wet: Pair<String, Pair<Double, Int>>): List<HourWeather> {
        val byTime = wet.toMap()
        val stamps = (0..23).map { "${day}T%02d:00".format(it) } + "${day.plusDays(1)}T00:00"
        return stamps.map { t ->
            val (mm, prob) = byTime[t] ?: (0.0 to 0)
            HourWeather(t, precipMm = mm, precipProbPct = prob, humidityPct = 60)
        }
    }

    // ---------------------------------------------------------------- AQI bands

    @Test
    fun `european bands are half-open so 80 is already very poor`() {
        assertEquals("Good", Insights.aqiBand(0, AqiScale.EUROPEAN).label)
        assertEquals("Good", Insights.aqiBand(19, AqiScale.EUROPEAN).label)
        assertEquals("Fair", Insights.aqiBand(20, AqiScale.EUROPEAN).label)
        assertEquals("Poor", Insights.aqiBand(79, AqiScale.EUROPEAN).label)
        assertEquals("Very poor", Insights.aqiBand(80, AqiScale.EUROPEAN).label)
        assertEquals("Extremely poor", Insights.aqiBand(100, AqiScale.EUROPEAN).label)
        assertEquals(5, Insights.aqiBand(400, AqiScale.EUROPEAN).level)
    }

    @Test
    fun `us bands follow the EPA inclusive edges`() {
        assertEquals("Good", Insights.aqiBand(50, AqiScale.US).label)
        assertEquals("Moderate", Insights.aqiBand(51, AqiScale.US).label)
        assertEquals("Unhealthy for sensitive groups", Insights.aqiBand(101, AqiScale.US).label)
        assertEquals("Unhealthy", Insights.aqiBand(151, AqiScale.US).label)
        assertEquals("Hazardous", Insights.aqiBand(301, AqiScale.US).label)
    }

    @Test
    fun `pollutants are ranked worst first`() {
        val air = DayAir("$day", subPm25 = 30, subPm10 = 22, subO3 = 64, subNo2 = 12)
        assertEquals(listOf("Ozone", "Fine particles (PM2.5)"), Insights.pollutants(air).take(2).map { it.name })
    }

    @Test
    fun `cleanest hour ignores the middle of the night`() {
        val air = (0..23).map { HourAir("${day}T%02d:00".format(it), euAqi = if (it < 5) 10 else 30 + it) }
        val timing = Insights.airTiming(air, AqiScale.EUROPEAN)!!
        assertEquals(23, timing.worst.hour)
        assertEquals(6, timing.cleanest!!.hour)
    }

    // ---------------------------------------------------------------- rain

    @Test
    fun `a dry day has no rain windows`() {
        assertTrue(Insights.rainWindows(hours(), day).isEmpty())
    }

    /** Open-Meteo stamps each hour at its end: the 15:00 value is rain from 14:00 to 15:00. */
    @Test
    fun `windows are shifted to the hour the rain actually falls`() {
        val w = Insights.rainWindows(
            hours("${day}T15:00" to (1.0 to 70), "${day}T16:00" to (3.0 to 80)),
            day,
        ).single()
        assertEquals(14, w.startHour)
        assertEquals(16, w.endHour)
        assertEquals(4.0, w.totalMm, 1e-9)
        assertEquals(80, w.maxProbPct)
        assertEquals("moderate", w.intensity)
    }

    @Test
    fun `one dry hour does not split a shower in two`() {
        val w = Insights.rainWindows(
            hours("${day}T10:00" to (0.5 to 60), "${day}T12:00" to (0.5 to 60)),
            day,
        )
        assertEquals(1, w.size)
        assertEquals(9, w[0].startHour)
        assertEquals(12, w[0].endHour)
    }

    @Test
    fun `a longer gap makes two windows`() {
        val w = Insights.rainWindows(
            hours("${day}T08:00" to (0.5 to 60), "${day}T18:00" to (0.5 to 60)),
            day,
        )
        assertEquals(listOf(7, 17), w.map { it.startHour })
    }

    /** The 00:00 stamp is the last hour of the *previous* day; the next 00:00 closes this one. */
    @Test
    fun `midnight stamps land on the day the rain falls`() {
        val w = Insights.rainWindows(
            hours("${day}T00:00" to (2.0 to 90), "${day.plusDays(1)}T00:00" to (1.0 to 70)),
            day,
        ).single()
        assertEquals(23, w.startHour)
        assertEquals(24, w.endHour)
    }

    @Test
    fun `a high chance with no forecast amount still counts`() {
        val w = Insights.rainWindows(hours("${day}T09:00" to (0.0 to 45)), day).single()
        assertEquals("possible", w.intensity)
    }

    @Test
    fun `a low chance of a trace does not`() {
        assertTrue(Insights.rainWindows(hours("${day}T09:00" to (0.1 to 20)), day).isEmpty())
    }

    // ---------------------------------------------------------------- rain table

    @Test
    fun `rain table folds quiet hours and keeps every hour with a number`() {
        val rows = Insights.rainTable(
            hours("${day}T15:00" to (1.2 to 70), "${day}T16:00" to (0.0 to 15), "${day}T19:00" to (0.3 to 5)),
            day,
        )
        assertEquals(
            listOf(
                Insights.RainRow.Dry(0, 14),
                Insights.RainRow.Hour(14, 70, 1.2),
                Insights.RainRow.Hour(15, 15, 0.0),
                Insights.RainRow.Dry(16, 18),
                Insights.RainRow.Hour(18, 5, 0.3),
                Insights.RainRow.Dry(19, 24),
            ),
            rows,
        )
    }

    @Test
    fun `rain table starts at the given hour and is empty on a dry day`() {
        val rows = Insights.rainTable(hours("${day}T09:00" to (2.0 to 90), "${day}T15:00" to (1.0 to 60)), day, fromHour = 12)
        assertEquals(listOf(Insights.RainRow.Dry(12, 14), Insights.RainRow.Hour(14, 60, 1.0), Insights.RainRow.Dry(15, 24)), rows)
        assertTrue(Insights.rainTable(hours("${day}T09:00" to (0.0 to 5)), day).isEmpty())
        assertTrue(Insights.rainTable(hours("${day}T09:00" to (2.0 to 90)), day, fromHour = 12).isEmpty())
    }

    // ---------------------------------------------------------------- humidity, uv, wind

    @Test
    fun `mugginess follows dew point bands`() {
        assertEquals("Dry", Insights.mugginess(8.0).label)
        assertEquals("Comfortable", Insights.mugginess(14.0).label)
        assertEquals("Muggy", Insights.mugginess(19.6).label)
        assertEquals("Oppressive", Insights.mugginess(22.0).label)
    }

    @Test
    fun `humidity timing finds the dampest and driest hours`() {
        val h = (0..23).map { HourWeather("${day}T%02d:00".format(it), humidityPct = 90 - it * 2) }
        val (wet, dry) = Insights.humidityTiming(h, day)!!
        assertEquals(0, wet.hour)
        assertEquals(23, dry.hour)
        assertNull(Insights.humidityTiming(emptyList(), day))
    }

    @Test
    fun `uv bands`() {
        assertEquals("Low", Insights.uvBand(2.9).label)
        assertEquals("High", Insights.uvBand(6.25).label)
        assertEquals("Extreme", Insights.uvBand(11.0).label)
    }

    @Test
    fun `compass rounds to the nearest of 16 points`() {
        assertEquals("N", Insights.compass(0))
        assertEquals("N", Insights.compass(355))
        assertEquals("W", Insights.compass(270))
        assertEquals("WSW", Insights.compass(249))
        assertEquals("NW", Insights.compass(325))
    }
}
