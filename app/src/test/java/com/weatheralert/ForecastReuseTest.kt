package com.weatheralert

import com.weatheralert.data.ForecastReuse
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ForecastReuseTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)
    private val now = 1_800_000_000_000L

    private fun day(offset: Long) = DayWeather(
        date = today.plusDays(offset).toString(),
        weatherCode = 0,
        tempMax = 20.0,
        tempMin = 12.0,
        apparentMax = 20.0,
        apparentMin = 12.0,
        precipMm = 0.0,
        precipProbPct = 0,
        windMaxKmh = 10.0,
        gustMaxKmh = 20.0,
    )

    /** Yesterday plus [horizon] days, fetched [ageMs] ago. */
    private fun forecast(ageMs: Long, horizon: Int = 4) =
        LocationForecast("tlv", now - ageMs, (-1L until horizon.toLong()).map { day(it) }, emptyList())

    @Test
    fun `a forecast fetched a minute ago is reused`() {
        assertTrue(ForecastReuse.canReuse(forecast(60_000), 4, today, now))
    }

    @Test
    fun `an older forecast is fetched again`() {
        assertFalse(ForecastReuse.canReuse(forecast(ForecastReuse.WINDOW_MS + 1), 4, today, now))
    }

    @Test
    fun `nothing cached means fetch`() {
        assertFalse(ForecastReuse.canReuse(null, 4, today, now))
    }

    @Test
    fun `a widened horizon is fetched again even when fresh`() {
        assertFalse(ForecastReuse.canReuse(forecast(60_000, horizon = 4), 6, today, now))
    }

    @Test
    fun `a narrowed horizon is still covered`() {
        assertTrue(ForecastReuse.canReuse(forecast(60_000, horizon = 6), 3, today, now))
    }

    @Test
    fun `a fetch time in the future (clock changed) is not trusted`() {
        assertFalse(ForecastReuse.canReuse(forecast(-60_000), 4, today, now))
    }
}
