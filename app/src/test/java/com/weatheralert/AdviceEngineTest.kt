package com.weatheralert

import com.weatheralert.domain.AdviceEngine
import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.DayAir
import com.weatheralert.model.DayWeather
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AdviceEngineTest {

    private val today: LocalDate = LocalDate.of(2026, 1, 15)
    private val t = Thresholds()

    private fun day(
        offset: Long,
        max: Double = 22.0,
        min: Double = 14.0,
        appMax: Double = max,
        appMin: Double = min,
        precip: Double = 0.0,
        prob: Int = 0,
        gust: Double = 20.0,
        wind: Double = 12.0,
        humidity: Int? = 55,
    ) = DayWeather(
        date = today.plusDays(offset).toString(),
        weatherCode = 0,
        tempMax = max, tempMin = min,
        apparentMax = appMax, apparentMin = appMin,
        precipMm = precip, precipProbPct = prob,
        windMaxKmh = wind, gustMaxKmh = gust,
        humidityMeanPct = humidity,
    )

    private fun air(
        offset: Long,
        pm10: Double? = 25.0,
        pm25: Double? = 12.0,
        dust: Double? = 2.0,
        subPm25: Int? = 30,
        subPm10: Int? = 24,
        subO3: Int? = 40,
        subNo2: Int? = 35,
    ) = DayAir(
        date = today.plusDays(offset).toString(),
        pm10Max = pm10, pm25Max = pm25, dustMax = dust,
        euAqiMax = listOfNotNull(subPm25, subPm10, subO3, subNo2).maxOrNull(),
        peakHour = 14,
        subPm25 = subPm25, subPm10 = subPm10, subO3 = subO3, subNo2 = subNo2,
        o3PeakHour = 15,
    )

    private fun run(
        days: List<DayWeather>,
        airs: List<DayAir> = emptyList(),
        th: Thresholds = t,
    ): List<DayAdvice> = AdviceEngine.evaluate(LocationForecast("x", 0L, days, airs), th, today)

    private fun one(advice: List<DayAdvice>, kind: AdviceKind, offset: Long = 0) =
        advice.single { it.kind == kind && it.date == today.plusDays(offset).toString() }

    // ------------------------------------------------------------------ laundry

    @Test
    fun `a clear dry day is fine for laundry`() {
        val a = one(run(listOf(day(-1), day(0)), listOf(air(0))), AdviceKind.LAUNDRY)
        assertEquals(AdviceLevel.GOOD, a.level)
    }

    @Test
    fun `likely rain means do not hang laundry`() {
        val a = one(
            run(listOf(day(-1), day(0, precip = 4.0, prob = 70)), listOf(air(0))),
            AdviceKind.LAUNDRY,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertTrue(a.reason, a.reason.contains("rain"))
    }

    @Test
    fun `heavy dust means do not hang laundry`() {
        val a = one(
            run(listOf(day(-1), day(0)), listOf(air(0, pm10 = 160.0, dust = 90.0))),
            AdviceKind.LAUNDRY,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertTrue(a.reason, a.reason.contains("PM10"))
    }

    /**
     * The whole point of a separate laundry threshold: PM10 70 is not remotely a health concern
     * and raises no dust alert, but it is enough to grey a white sheet.
     */
    @Test
    fun `mildly hazy air warns about laundry while staying below the health bar`() {
        val a = one(run(listOf(day(-1), day(0)), listOf(air(0, pm10 = 70.0))), AdviceKind.LAUNDRY)
        assertEquals(AdviceLevel.CAUTION, a.level)
        assertTrue(a.headline, a.headline.contains("dull"))
        // and the health-facing mask signal stays quiet at the same level
        assertEquals(
            AdviceLevel.GOOD,
            one(run(listOf(day(-1), day(0)), listOf(air(0, pm10 = 55.0))), AdviceKind.MASK).level,
        )
    }

    @Test
    fun `muggy air is flagged as slow to dry`() {
        val a = one(
            run(listOf(day(-1), day(0, humidity = 88)), listOf(air(0))),
            AdviceKind.LAUNDRY,
        )
        assertEquals(AdviceLevel.CAUTION, a.level)
        assertTrue(a.reason, a.reason.contains("humidity"))
    }

    @Test
    fun `rain outranks a merely humid day`() {
        val a = one(
            run(listOf(day(-1), day(0, precip = 5.0, prob = 80, humidity = 90)), listOf(air(0))),
            AdviceKind.LAUNDRY,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertEquals("Do not hang laundry", a.headline)
    }

    @Test
    fun `laundry advice still works with no air-quality data`() {
        val a = one(run(listOf(day(-1), day(0, precip = 6.0, prob = 80))), AdviceKind.LAUNDRY)
        assertEquals(AdviceLevel.BAD, a.level)
    }

    // --------------------------------------------------------------------- mask

    @Test
    fun `clean air needs no mask`() {
        val a = one(run(listOf(day(-1), day(0)), listOf(air(0))), AdviceKind.MASK)
        assertEquals(AdviceLevel.GOOD, a.level)
    }

    @Test
    fun `a dust storm calls for an N95`() {
        val a = one(
            run(listOf(day(-1), day(0)), listOf(air(0, pm10 = 240.0, pm25 = 60.0, dust = 180.0))),
            AdviceKind.MASK,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertTrue(a.headline, a.headline.contains("N95"))
    }

    /**
     * The distinction that matters most in Israel: over 30 sampled days the AQI peak was
     * ozone-driven 100% of the time in Jerusalem and Beersheba. A mask does nothing for ozone,
     * so recommending one on those days would be confidently wrong advice.
     */
    @Test
    fun `high ozone with clean particulates says a mask will not help`() {
        val a = one(
            run(
                listOf(day(-1), day(0)),
                listOf(air(0, pm10 = 22.0, pm25 = 9.0, subPm25 = 20, subPm10 = 18, subO3 = 95)),
            ),
            AdviceKind.MASK,
        )
        assertEquals(AdviceLevel.CAUTION, a.level)
        assertTrue(a.headline, a.headline.contains("not help"))
        assertTrue(a.reason, a.reason.contains("Ozone"))
        assertTrue(a.reason, a.reason.contains("do not filter gases"))
    }

    @Test
    fun `particulates win over gases when both are high`() {
        val a = one(
            run(
                listOf(day(-1), day(0)),
                listOf(air(0, pm10 = 200.0, pm25 = 70.0, subPm25 = 95, subPm10 = 90, subO3 = 92)),
            ),
            AdviceKind.MASK,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertTrue(a.headline, a.headline.contains("N95"))
    }

    @Test
    fun `the ordinary Israeli ozone baseline does not trigger mask advice`() {
        // Measured typical sub-index levels: O3 around 40-55, well under the 80 bar.
        val a = one(
            run(listOf(day(-1), day(0)), listOf(air(0, subO3 = 55, subNo2 = 50))),
            AdviceKind.MASK,
        )
        assertEquals(AdviceLevel.GOOD, a.level)
    }

    // ----------------------------------------------------------------- clothing

    @Test
    fun `a steady day needs no change of outfit`() {
        val a = one(run(listOf(day(-1), day(0)), listOf(air(0))), AdviceKind.CLOTHING)
        assertEquals(AdviceLevel.GOOD, a.level)
    }

    @Test
    fun `a sharp cooldown says dress much warmer`() {
        val a = one(
            run(listOf(day(-1, max = 30.0, appMax = 30.0), day(0, max = 18.0, appMax = 18.0))),
            AdviceKind.CLOTHING,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertEquals("Dress much warmer", a.headline)
        assertTrue(a.reason, a.reason.contains("colder"))
    }

    @Test
    fun `a moderate warm-up says dress lighter`() {
        val a = one(
            run(listOf(day(-1, max = 18.0, appMax = 18.0), day(0, max = 23.0, appMax = 23.0))),
            AdviceKind.CLOTHING,
        )
        assertEquals(AdviceLevel.CAUTION, a.level)
        assertEquals("Dress lighter", a.headline)
    }

    @Test
    fun `rain earns a raincoat even with no temperature change`() {
        val a = one(
            run(listOf(day(-1), day(0, precip = 3.0, prob = 65))),
            AdviceKind.CLOTHING,
        )
        assertEquals(AdviceLevel.CAUTION, a.level)
        assertTrue(a.headline, a.headline.contains("raincoat"))
    }

    @Test
    fun `a warm day with a cold evening suggests layers`() {
        val a = one(
            run(listOf(day(-1, appMin = 15.0), day(0, max = 26.0, appMax = 26.0, appMin = 7.0))),
            AdviceKind.CLOTHING,
        )
        assertTrue(a.level != AdviceLevel.GOOD)
        assertTrue(a.reason, a.reason.contains("after dark"))
    }

    @Test
    fun `clothing uses feels-like, not the raw reading`() {
        // Raw highs move 2°, but the wind makes it feel 9° colder.
        val a = one(
            run(
                listOf(
                    day(-1, max = 20.0, appMax = 20.0),
                    day(0, max = 18.0, appMax = 10.0, gust = 30.0),
                ),
            ),
            AdviceKind.CLOTHING,
        )
        assertEquals(AdviceLevel.BAD, a.level)
        assertEquals("Dress much warmer", a.headline)
    }

    // ------------------------------------------------------------ plumbing

    @Test
    fun `each enabled signal produces exactly one verdict per day`() {
        val advice = run(
            (-1L..3L).map { day(it) },
            (0L..3L).map { air(it) },
        )
        (0L..3L).forEach { off ->
            val date = today.plusDays(off).toString()
            assertEquals(
                "day $date",
                setOf(AdviceKind.LAUNDRY, AdviceKind.MASK, AdviceKind.CLOTHING),
                advice.filter { it.date == date }.map { it.kind }.toSet(),
            )
        }
    }

    @Test
    fun `a disabled signal produces nothing`() {
        val advice = run(
            listOf(day(-1), day(0)),
            listOf(air(0)),
            t.copy(enableLaundry = false, enableMask = false),
        )
        assertTrue(advice.none { it.kind == AdviceKind.LAUNDRY || it.kind == AdviceKind.MASK })
        assertTrue(advice.any { it.kind == AdviceKind.CLOTHING })
    }

    @Test
    fun `advice respects the horizon and never covers yesterday`() {
        val advice = run((-1L..6L).map { day(it) }, (0L..6L).map { air(it) }, t.copy(horizonDays = 3))
        val dates = advice.map { it.date }.distinct().sorted()
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)).map { it.toString() }, dates)
    }

    @Test
    fun `the dedupe key separates kind, level and date`() {
        val bad = one(
            run(listOf(day(-1), day(0, precip = 5.0, prob = 80))),
            AdviceKind.LAUNDRY,
        )
        val fine = one(run(listOf(day(-1), day(0))), AdviceKind.LAUNDRY)
        assertTrue(bad.key != fine.key)
        assertTrue(bad.key.contains("LAUNDRY"))
    }
}
