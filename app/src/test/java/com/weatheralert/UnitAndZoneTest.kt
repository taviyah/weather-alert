package com.weatheralert

import com.weatheralert.model.TempUnit
import com.weatheralert.model.WatchedLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Alert and advice text used to print Celsius whatever the setting, and "today" was always the
 * phone's date even for a place in another time zone.
 */
class UnitAndZoneTest {

    @Test
    fun `temperatures follow the chosen unit`() {
        assertEquals("0°", TempUnit.CELSIUS.temp(0.0))
        assertEquals("32°", TempUnit.FAHRENHEIT.temp(0.0))
        assertEquals("-4°", TempUnit.FAHRENHEIT.temp(-20.0))
    }

    @Test
    fun `a temperature change has no 32 degree offset`() {
        assertEquals("10°", TempUnit.CELSIUS.delta(10.0))
        assertEquals("18°", TempUnit.FAHRENHEIT.delta(10.0))
    }

    @Test
    fun `today is the place's own date`() {
        val zone = "Pacific/Kiritimati" // UTC+14: often already tomorrow compared with the phone
        val loc = WatchedLocation("x", "X", latitude = 0.0, longitude = 0.0, timezone = zone)
        assertEquals(LocalDate.now(ZoneId.of(zone)), loc.today())
    }

    @Test
    fun `an unknown or broken zone falls back to the phone's date`() {
        val none = WatchedLocation("x", "X", latitude = 0.0, longitude = 0.0)
        val broken = none.copy(timezone = "Not/AZone")
        assertEquals(LocalDate.now(), none.today())
        assertEquals(LocalDate.now(), broken.today())
    }
}
