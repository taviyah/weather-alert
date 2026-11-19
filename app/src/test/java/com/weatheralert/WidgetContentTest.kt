package com.weatheralert

import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import com.weatheralert.widget.WidgetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class WidgetContentTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15) // a Thursday
    private val now = 1_800_000_000_000L
    private val t = Thresholds()

    private val hadera = WatchedLocation("a", "Hadera", latitude = 32.43, longitude = 34.92)
    private val telAviv = WatchedLocation("b", "Tel Aviv", latitude = 32.08, longitude = 34.78)

    private fun day(
        offset: Long,
        max: Double = 20.0,
        min: Double = 12.0,
        precip: Double = 0.0,
        prob: Int = 0,
        humidity: Int? = 55,
    ) = DayWeather(
        date = today.plusDays(offset).toString(),
        weatherCode = 0,
        tempMax = max, tempMin = min,
        apparentMax = max, apparentMin = min,
        precipMm = precip, precipProbPct = prob,
        windMaxKmh = 10.0, gustMaxKmh = 20.0,
        humidityMeanPct = humidity,
    )

    private fun air(offset: Long, pm10: Double = 25.0, pm25: Double = 12.0) = DayAir(
        date = today.plusDays(offset).toString(),
        pm10Max = pm10, pm25Max = pm25, dustMax = 2.0,
        euAqiMax = 40, peakHour = 14,
        subPm25 = 30, subPm10 = 24, subO3 = 40, subNo2 = 35,
    )

    private fun forecast(
        id: String,
        days: List<DayWeather>,
        airs: List<DayAir> = emptyList(),
    ) = LocationForecast(id, 0L, days, airs)

    private fun build(
        locations: List<WatchedLocation>,
        forecasts: Map<String, LocationForecast>,
        settings: Thresholds = t,
        lastRun: Long = now - TimeUnit.HOURS.toMillis(2),
    ) = WidgetContent.from(locations, forecasts, settings, lastRun, today, now)

    // ------------------------------------------------------------------ quiet

    /**
     * The state the widget is in almost every day. It has to be informative enough to be worth
     * the space and static enough that an e-paper panel never repaints it.
     */
    @Test
    fun `a quiet week says how far the quiet extends`() {
        val c = build(
            listOf(hadera),
            mapOf("a" to forecast("a", (-1L..3L).map { day(it) }, (0L..3L).map { air(it) })),
        )
        assertEquals("Hadera", c.place)
        assertEquals("All clear through Sunday", c.headline)
        assertNull(c.mark)
        assertNull(c.detail)
        assertNull(c.advice)
    }

    @Test
    fun `the horizon end follows the setting`() {
        val c = build(
            listOf(hadera),
            mapOf("a" to forecast("a", (-1L..6L).map { day(it) })),
            settings = t.copy(horizonDays = 3),
        )
        assertEquals("All clear through Saturday", c.headline)
    }

    // ----------------------------------------------------------------- alerts

    @Test
    fun `the soonest alert becomes the headline`() {
        val c = build(
            listOf(hadera),
            mapOf(
                "a" to forecast(
                    "a",
                    listOf(day(-1), day(0), day(1, precip = 18.0, prob = 90)),
                ),
            ),
        )
        assertEquals("Tomorrow · Heavy rain", c.headline)
        assertEquals("!!", c.mark)
        assertTrue(c.detail!!, c.detail!!.contains("18.0 mm"))
    }

    @Test
    fun `a notable alert gets the hollow mark`() {
        val c = build(
            listOf(hadera),
            mapOf("a" to forecast("a", listOf(day(-1), day(0, precip = 3.0, prob = 70)))),
        )
        assertEquals("!", c.mark)
        assertTrue(c.headline, c.headline.startsWith("Today · "))
    }

    // ----------------------------------------------------------------- advice

    @Test
    fun `only advice that needs acting on appears`() {
        val c = build(
            listOf(hadera),
            mapOf(
                "a" to forecast(
                    "a",
                    listOf(day(-1), day(0, precip = 4.0, prob = 80)),
                    listOf(air(0)),
                ),
            ),
        )
        assertTrue(c.advice!!, c.advice!!.contains("No laundry out"))
    }

    @Test
    fun `a day needing nothing shows no advice line at all`() {
        val c = build(
            listOf(hadera),
            mapOf("a" to forecast("a", listOf(day(-1), day(0)), listOf(air(0)))),
        )
        assertNull(c.advice)
    }

    /**
     * Ozone days are the common bad-air case here, and "wear a mask" would be wrong advice, so
     * the widget's one line has to carry the correct one.
     */
    @Test
    fun `an ozone day says stay in rather than mask up`() {
        val c = build(
            listOf(hadera),
            mapOf(
                "a" to forecast(
                    "a",
                    listOf(day(-1), day(0)),
                    listOf(air(0, pm10 = 22.0, pm25 = 9.0).copy(subO3 = 95, subPm25 = 20, subPm10 = 18)),
                ),
            ),
        )
        assertTrue(c.advice!!, c.advice!!.contains("Ozone"))
    }

    // -------------------------------------------------------------- spillover

    @Test
    fun `a major alert somewhere else earns one line`() {
        val c = build(
            listOf(hadera, telAviv),
            mapOf(
                "a" to forecast("a", listOf(day(-1), day(0))),
                "b" to forecast("b", listOf(day(-1), day(0, precip = 20.0, prob = 90))),
            ),
        )
        assertEquals("+1", c.morePlaces)
        assertTrue(c.spillover!!, c.spillover!!.contains("Tel Aviv"))
        assertTrue(c.spillover!!, c.spillover!!.contains("heavy rain"))
    }

    @Test
    fun `a merely notable alert elsewhere is not worth the line`() {
        val c = build(
            listOf(hadera, telAviv),
            mapOf(
                "a" to forecast("a", listOf(day(-1), day(0))),
                "b" to forecast("b", listOf(day(-1), day(0, precip = 3.0, prob = 70))),
            ),
        )
        assertNull(c.spillover)
    }

    @Test
    fun `the top place is the one the widget speaks for`() {
        val forecasts = mapOf(
            "a" to forecast("a", listOf(day(-1), day(0))),
            "b" to forecast("b", listOf(day(-1), day(0, precip = 20.0, prob = 90))),
        )
        assertEquals("Hadera", build(listOf(hadera, telAviv), forecasts).place)
        // Dragging Tel Aviv to the top changes who the widget reports on.
        assertEquals("Tel Aviv", build(listOf(telAviv, hadera), forecasts).place)
    }

    // ------------------------------------------------------------ housekeeping

    @Test
    fun `no places watched is stated plainly`() {
        val c = build(emptyList(), emptyMap())
        assertEquals("No places watched", c.headline)
    }

    @Test
    fun `a place with no forecast yet says so instead of claiming all clear`() {
        val c = build(listOf(hadera), emptyMap())
        assertEquals("Waiting for the first forecast", c.headline)
    }

    @Test
    fun `the checked label makes a stalled app visible`() {
        assertEquals("2h ago", build(listOf(hadera), emptyMap()).checked)
        assertEquals(
            "3d ago",
            build(listOf(hadera), emptyMap(), lastRun = now - TimeUnit.DAYS.toMillis(3)).checked,
        )
        assertEquals("", build(listOf(hadera), emptyMap(), lastRun = 0L).checked)
    }
}
