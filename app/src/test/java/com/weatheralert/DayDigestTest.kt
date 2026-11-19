package com.weatheralert

import com.weatheralert.domain.DayDigest
import com.weatheralert.model.AdviceKind
import com.weatheralert.model.AdviceLevel
import com.weatheralert.model.AlertKind
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.Severity
import com.weatheralert.model.WeatherAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayDigestTest {

    private val today = LocalDate.of(2026, 1, 15)
    private val d0 = today.toString()
    private val d1 = today.plusDays(1).toString()

    private fun alert(date: String, kind: AlertKind, sev: Severity = Severity.NOTABLE, short: String = "x") =
        WeatherAlert("tlv", "Tel Aviv", date, kind, sev, "Headline", "detail", short)

    private fun tip(date: String, kind: AdviceKind, level: AdviceLevel, headline: String, reason: String) =
        DayAdvice(date, kind, level, headline, reason)

    @Test
    fun `a wet day is one entry whose tips lose the rain reason`() {
        val digest = DayDigest.build(
            alerts = listOf(alert(d1, AlertKind.RAIN, short = "rain 4.2 mm (70%)")),
            advice = listOf(
                tip(d1, AdviceKind.LAUNDRY, AdviceLevel.BAD, "Do not hang laundry", "70% chance of rain"),
                tip(d1, AdviceKind.CLOTHING, AdviceLevel.CAUTION, "Take a raincoat", "70% chance of rain"),
            ),
        )
        assertEquals(1, digest.size)
        val day = digest.single()
        assertEquals(listOf("rain 4.2 mm (70%)"), day.facts.map { it.text })
        assertEquals(listOf("Do not hang laundry", "Take a raincoat"), day.tips.map { it.action })
        assertTrue(day.tips.all { it.reason == null })
    }

    @Test
    fun `a reason no alert covers is kept`() {
        val digest = DayDigest.build(
            alerts = listOf(alert(d1, AlertKind.RAIN)),
            advice = listOf(
                tip(d1, AdviceKind.LAUNDRY, AdviceLevel.BAD, "Do not hang laundry", "70% chance of rain, humidity 88%"),
            ),
        )
        assertEquals("humidity 88%", digest.single().tips.single().reason)
    }

    @Test
    fun `tips on a day without alerts keep their whole reason`() {
        val digest = DayDigest.build(
            alerts = listOf(alert(d1, AlertKind.RAIN)),
            advice = listOf(tip(d0, AdviceKind.CLOTHING, AdviceLevel.CAUTION, "Take a raincoat", "45% chance of rain")),
        )
        assertEquals(listOf(today, today.plusDays(1)), digest.map { it.date })
        assertEquals("45% chance of rain", digest.first().tips.single().reason)
        assertTrue(digest.first().facts.isEmpty())
    }

    @Test
    fun `a mask tip under a dust alert has nothing left to explain`() {
        val day = DayDigest.build(
            alerts = listOf(alert(d0, AlertKind.DUST, Severity.MAJOR)),
            advice = listOf(
                tip(d0, AdviceKind.MASK, AdviceLevel.BAD, "Wear an N95 outside", "PM2.5 60, PM10 400, worst around 14:00"),
                tip(d0, AdviceKind.LAUNDRY, AdviceLevel.BAD, "Do not hang laundry", "dusty air, PM10 400"),
            ),
        ).single()
        assertTrue(day.major)
        assertTrue(day.tips.all { it.reason == null })
    }

    @Test
    fun `worst around qualifies only a dropped clause`() {
        val reason = DayDigest.uncoveredReason(
            tip(d0, AdviceKind.LAUNDRY, AdviceLevel.CAUTION, "Peg it down", "gusts 55 km/h"),
            setOf(AlertKind.RAIN),
        )
        assertEquals("gusts 55 km/h", reason)
        assertNull(
            DayDigest.uncoveredReason(
                tip(d0, AdviceKind.LAUNDRY, AdviceLevel.CAUTION, "Peg it down", "gusts 55 km/h"),
                setOf(AlertKind.WIND_RISE),
            ),
        )
    }

    @Test
    fun `alerts cached without a short phrase fall back to the headline`() {
        val old = WeatherAlert("tlv", "Tel Aviv", d0, AlertKind.RAIN, Severity.NOTABLE, "Rain expected", "1.0 mm")
        assertEquals("rain expected", DayDigest.build(listOf(old), emptyList()).single().facts.single().text)
    }
}
