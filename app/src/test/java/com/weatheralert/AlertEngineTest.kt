package com.weatheralert

import com.weatheralert.domain.AlertEngine
import com.weatheralert.model.AlertKind
import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Severity
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlertEngineTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)
    private val loc = WatchedLocation("tlv", "Tel Aviv", latitude = 32.08, longitude = 34.78)
    private val t = Thresholds()

    private fun day(
        offset: Long,
        max: Double = 20.0,
        min: Double = 12.0,
        appMax: Double = max,
        appMin: Double = min,
        precip: Double = 0.0,
        prob: Int = 0,
        code: Int = 0,
        gust: Double = 20.0,
    ) = DayWeather(
        date = today.plusDays(offset).toString(),
        weatherCode = code,
        tempMax = max,
        tempMin = min,
        apparentMax = appMax,
        apparentMin = appMin,
        precipMm = precip,
        precipProbPct = prob,
        windMaxKmh = 10.0,
        gustMaxKmh = gust,
    )

    private fun forecast(days: List<DayWeather>, air: List<DayAir> = emptyList()) =
        LocationForecast("tlv", 0L, days, air)

    private fun air(offset: Long, pm10: Double? = null, dust: Double? = null, eu: Int? = null) =
        DayAir(
            date = today.plusDays(offset).toString(),
            pm10Max = pm10,
            dustMax = dust,
            euAqiMax = eu,
            peakHour = 14,
        )

    private fun run(f: LocationForecast, th: Thresholds = t) =
        AlertEngine.evaluate(loc, f, th, today)

    // ------------------------------------------------------------------ quiet

    @Test
    fun `a settled week produces no alerts`() {
        val alerts = run(forecast((-1L..4L).map { day(it) }))
        assertTrue(alerts.toString(), alerts.isEmpty())
    }

    // ------------------------------------------------------------------ rain

    @Test
    fun `light rain below the probability bar is ignored`() {
        val alerts = run(forecast(listOf(day(-1), day(0, precip = 1.0, prob = 20))))
        assertNull(alerts.firstOrNull { it.kind == AlertKind.RAIN })
    }

    @Test
    fun `likely rain is a notable alert`() {
        val alerts = run(forecast(listOf(day(-1), day(0, precip = 3.0, prob = 70))))
        val rain = alerts.single { it.kind == AlertKind.RAIN }
        assertEquals(Severity.NOTABLE, rain.severity)
    }

    @Test
    fun `heavy rain is major regardless of the stated probability`() {
        val alerts = run(forecast(listOf(day(-1), day(0, precip = 18.0, prob = 0))))
        val rain = alerts.single { it.kind == AlertKind.RAIN }
        assertEquals(Severity.MAJOR, rain.severity)
    }

    // ------------------------------------------------------- temperature change

    @Test
    fun `a two degree drift is inside the noise floor`() {
        val alerts = run(forecast(listOf(day(-1, max = 22.0), day(0, max = 20.0))))
        assertTrue(alerts.none { it.kind == AlertKind.TEMP_DROP })
    }

    @Test
    fun `a four degree drop is notable`() {
        val alerts = run(forecast(listOf(day(-1, max = 24.0), day(0, max = 20.0))))
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertEquals(Severity.NOTABLE, hit.severity)
    }

    @Test
    fun `a six degree drop is major`() {
        val alerts = run(forecast(listOf(day(-1, max = 26.0), day(0, max = 20.0))))
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertEquals(Severity.MAJOR, hit.severity)
    }

    @Test
    fun `warming is reported as a rise, not a drop`() {
        val alerts = run(forecast(listOf(day(-1, max = 18.0), day(0, max = 27.0))))
        assertEquals(AlertKind.TEMP_RISE, alerts.single { it.kind == AlertKind.TEMP_RISE }.kind)
    }

    @Test
    fun `the daily heating cycle alone never triggers an alert`() {
        // 12 degrees between each day's own low and high, but identical from day to day.
        val alerts = run(forecast((-1L..3L).map { day(it, max = 30.0, min = 18.0) }))
        assertTrue(alerts.none { it.kind == AlertKind.TEMP_DROP || it.kind == AlertKind.TEMP_RISE })
    }

    @Test
    fun `a wind-driven feels-like drop counts even when the real temperature barely moves`() {
        // 9 deg of apparent movement clears the scaled bar (3 x 1.4 = 4.2) on its own.
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 20.0, appMax = 20.0),
                    day(0, max = 18.0, appMax = 11.0),
                ),
            ),
        )
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertTrue(hit.detail, hit.detail.contains("feels-like"))
        // The real thermometer reading rides along, so the alert cannot read as a wrong forecast.
        assertTrue(hit.detail, hit.detail.contains("actual"))
    }

    /**
     * The Hadera case: overnight low moved 2.2 deg but felt like 3.6 deg because the air was
     * muggier. Judged against the same bar, feels-like did 78% of all alerting on its own.
     */
    @Test
    fun `a humidity-amplified swing below the scaled bar stays quiet`() {
        val alerts = run(
            forecast(
                listOf(
                    day(-1, min = 22.0, appMin = 25.7),
                    day(0, min = 19.8, appMin = 22.1),
                ),
            ),
        )
        assertTrue(alerts.toString(), alerts.none { it.kind == AlertKind.TEMP_DROP })
    }

    @Test
    fun `raw temperature still fires at the unscaled threshold`() {
        // 3.5 deg of real movement, with feels-like moving less. The raw bar is untouched.
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 24.0, appMax = 24.0),
                    day(0, max = 20.5, appMax = 22.0),
                ),
            ),
        )
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertEquals(Severity.NOTABLE, hit.severity)
        assertTrue(hit.detail, hit.detail.contains("Daytime high"))
    }

    @Test
    fun `the feels-like multiplier is configurable`() {
        val f = forecast(
            listOf(day(-1, min = 22.0, appMin = 25.7), day(0, min = 19.8, appMin = 22.1)),
        )
        // At 1.0 the old behaviour returns and the humidity-driven swing fires again.
        assertTrue(run(f, t.copy(apparentMultiplier = 1.0)).any { it.kind == AlertKind.TEMP_DROP })
    }

    @Test
    fun `feels-like can be switched off`() {
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 20.0, appMax = 20.0),
                    day(0, max = 18.0, appMax = 11.0),
                ),
            ),
            t.copy(useApparentTemp = false),
        )
        assertTrue(alerts.none { it.kind == AlertKind.TEMP_DROP })
    }

    @Test
    fun `overnight lows are compared against overnight lows`() {
        val alerts = run(forecast(listOf(day(-1, min = 16.0), day(0, min = 9.0))))
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertTrue(hit.detail, hit.detail.contains("Overnight"))
    }

    @Test
    fun `feels-like never wins on a day the raw change is more unusual`() {
        // Raw moves 5 (1.67x its bar); apparent moves 6 (1.43x its scaled bar). Raw is the
        // stronger signal once both are normalised, so that is what gets reported.
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 25.0, appMax = 26.0),
                    day(0, max = 20.0, appMax = 20.0),
                ),
            ),
        )
        val hit = alerts.single { it.kind == AlertKind.TEMP_DROP }
        assertTrue(hit.detail, hit.detail.contains("Daytime high"))
    }

    // ------------------------------------------------------------------ dust

    @Test
    fun `a dust plume is a major alert and suppresses the duplicate air-quality one`() {
        val alerts = run(
            forecast(
                listOf(day(-1), day(0)),
                listOf(air(0, pm10 = 320.0, dust = 240.0, eu = 140)),
            ),
        )
        assertEquals(Severity.MAJOR, alerts.single { it.kind == AlertKind.DUST }.severity)
        assertTrue(alerts.none { it.kind == AlertKind.AIR_QUALITY })
    }

    @Test
    fun `poor air with no dust still raises an air-quality alert`() {
        val alerts = run(
            forecast(listOf(day(-1), day(0)), listOf(air(0, pm10 = 30.0, dust = 2.0, eu = 85))),
        )
        val aqi = alerts.single { it.kind == AlertKind.AIR_QUALITY }
        assertEquals(Severity.NOTABLE, aqi.severity)
    }

    @Test
    fun `the ordinary Israeli air-quality baseline stays quiet`() {
        // Measured daily-max EU AQI over 17 days in September 2026: Tel Aviv median 51, Beersheba
        // 62, Jerusalem 69, worst day anywhere 81. None of that is news, and a default that fired
        // on it would have the user muting the app inside a week.
        listOf(51, 62, 69, 79).forEach { baseline ->
            val alerts = run(
                forecast(listOf(day(-1), day(0)), listOf(air(0, pm10 = 28.0, dust = 5.0, eu = baseline))),
            )
            assertTrue(
                "EU AQI $baseline should not alert",
                alerts.none { it.kind == AlertKind.AIR_QUALITY },
            )
        }
    }

    @Test
    fun `missing air-quality data never blocks the weather alerts`() {
        val alerts = run(forecast(listOf(day(-1, max = 30.0), day(0, max = 20.0)), emptyList()))
        assertEquals(1, alerts.count { it.kind == AlertKind.TEMP_DROP })
    }

    // ------------------------------------------------------------- extremes

    @Test
    fun `heat fires on the crossing and stays quiet for the rest of the wave`() {
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 30.0),
                    day(0, max = 36.0),
                    day(1, max = 37.0),
                    day(2, max = 36.5),
                ),
            ),
        )
        val heat = alerts.filter { it.kind == AlertKind.HEAT }
        assertEquals(heat.toString(), 1, heat.size)
        assertEquals(today.toString(), heat.first().date)
    }

    @Test
    fun `a sawtooth around the heat threshold does not alert every other day`() {
        // Real Beersheba behaviour: highs oscillate right at 35 for weeks. Against a bare
        // crossing test this produced an alert on every upswing.
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 34.0), day(0, max = 36.0), day(1, max = 34.0),
                    day(2, max = 36.0), day(3, max = 34.5),
                ),
            ),
            t.copy(horizonDays = 5),
        )
        assertTrue(alerts.toString(), alerts.none { it.kind == AlertKind.HEAT })
    }

    @Test
    fun `a genuine climb into the heat band still alerts once`() {
        val alerts = run(
            forecast(
                listOf(
                    day(-1, max = 28.0), day(0, max = 36.0), day(1, max = 37.0),
                    day(2, max = 36.0),
                ),
            ),
        )
        assertEquals(1, alerts.count { it.kind == AlertKind.HEAT })
    }

    @Test
    fun `frost outranks the cold-night alert on the same day`() {
        val alerts = run(forecast(listOf(day(-1, min = 6.0), day(0, min = -2.0))))
        assertEquals(1, alerts.count { it.kind == AlertKind.FROST })
        assertTrue(alerts.none { it.kind == AlertKind.COLD })
    }

    // ------------------------------------------------------------------- wind

    @Test
    fun `an ordinary gust wobble is ignored`() {
        // Measured median day-over-day gust change across five Israeli cities is 3 km/h.
        val alerts = run(forecast(listOf(day(-1, gust = 38.0), day(0, gust = 42.0))))
        assertTrue(alerts.none { it.kind == AlertKind.WIND_RISE || it.kind == AlertKind.WIND_DROP })
    }

    @Test
    fun `a thirteen km per hour gust jump is notable`() {
        val alerts = run(forecast(listOf(day(-1, gust = 30.0), day(0, gust = 43.0))))
        val hit = alerts.single { it.kind == AlertKind.WIND_RISE }
        assertEquals(Severity.NOTABLE, hit.severity)
        assertTrue(hit.detail, hit.detail.contains("30 to 43"))
    }

    @Test
    fun `a twenty km per hour gust jump is major`() {
        val alerts = run(forecast(listOf(day(-1, gust = 30.0), day(0, gust = 50.0))))
        assertEquals(Severity.MAJOR, alerts.single { it.kind == AlertKind.WIND_RISE }.severity)
    }

    @Test
    fun `wind dropping away is reported separately from wind picking up`() {
        val alerts = run(forecast(listOf(day(-1, gust = 55.0), day(0, gust = 38.0))))
        val hit = alerts.single { it.kind == AlertKind.WIND_DROP }
        assertTrue(hit.headline, hit.headline.contains("Wind"))
    }

    /**
     * Wind is ratio-ish: 5 to 20 km/h is a 15 km/h delta and is not weather. The absolute floor
     * is what stops a calm day turning into a light breeze from counting.
     */
    @Test
    fun `a big jump that stays calm does not alert`() {
        val alerts = run(forecast(listOf(day(-1, gust = 5.0), day(0, gust = 22.0))))
        assertTrue(alerts.none { it.kind == AlertKind.WIND_RISE })
    }

    @Test
    fun `wind change can be switched off`() {
        val alerts = run(
            forecast(listOf(day(-1, gust = 30.0), day(0, gust = 55.0))),
            t.copy(enableWindChange = false),
        )
        assertTrue(alerts.none { it.kind == AlertKind.WIND_RISE })
    }

    // ------------------------------------------------------------- horizon

    @Test
    fun `days beyond the horizon are not reported`() {
        val alerts = run(
            forecast((-1L..7L).map { day(it, precip = 20.0, prob = 90) }),
            t.copy(horizonDays = 4),
        )
        val dates = alerts.map { it.date }.distinct()
        assertEquals(4, dates.size)
        assertEquals(today.toString(), dates.first())
        assertEquals(today.plusDays(3).toString(), dates.last())
    }

    @Test
    fun `yesterday is used as a baseline but never alerted on`() {
        val alerts = run(forecast(listOf(day(-1, max = 40.0, precip = 30.0, prob = 99), day(0))))
        assertTrue(alerts.none { it.date == today.minusDays(1).toString() })
    }

    // ------------------------------------------------------------- dedupe key

    @Test
    fun `escalating severity produces a different dedupe key`() {
        val mild = run(forecast(listOf(day(-1, max = 24.0), day(0, max = 20.0))))
            .single { it.kind == AlertKind.TEMP_DROP }
        val sharp = run(forecast(listOf(day(-1, max = 30.0), day(0, max = 20.0))))
            .single { it.kind == AlertKind.TEMP_DROP }
        assertEquals(Severity.NOTABLE, mild.severity)
        assertEquals(Severity.MAJOR, sharp.severity)
        assertTrue(mild.key != sharp.key)
    }

    @Test
    fun `the same forecast twice produces identical keys`() {
        val f = forecast(listOf(day(-1, max = 24.0), day(0, max = 20.0, precip = 4.0, prob = 80)))
        assertEquals(run(f).map { it.key }, run(f).map { it.key })
    }
}
