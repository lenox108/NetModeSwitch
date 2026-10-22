package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class SunTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val lat = 55.7558
    private val lon = 37.6173

    private fun minutes(t: ZonedDateTime) = t.hour * 60 + t.minute

    private fun near(expected: String, actual: ZonedDateTime) {
        val (h, m) = expected.split(":").map { it.toInt() }
        assertTrue("$expected vs $actual", abs(minutes(actual) - (h * 60 + m)) <= 5)
    }

    @Test fun moscowMidsummer() {
        val d = Sun.day(LocalDate.of(2026, 6, 21), lat, lon, moscow) as Sun.Day.Normal
        near("03:44", d.rise)
        near("21:18", d.set)
    }

    @Test fun moscowMidwinter() {
        val d = Sun.day(LocalDate.of(2026, 12, 21), lat, lon, moscow) as Sun.Day.Normal
        near("08:59", d.rise)
        near("15:57", d.set)
    }

    @Test fun darkness() {
        assertTrue(Sun.dark(ZonedDateTime.of(2026, 12, 21, 18, 0, 0, 0, moscow), lat, lon))
        assertFalse(Sun.dark(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, moscow), lat, lon))
        assertTrue(Sun.dark(ZonedDateTime.of(2026, 12, 21, 7, 0, 0, 0, moscow), lat, lon))
    }

    @Test fun polarNightAndDay() {
        val murmanskish = 69.0
        assertEquals(Sun.Day.AlwaysDown, Sun.day(LocalDate.of(2026, 12, 21), murmanskish, 33.0, moscow))
        assertEquals(Sun.Day.AlwaysUp, Sun.day(LocalDate.of(2026, 6, 21), murmanskish, 33.0, moscow))
    }

    @Test fun nextEdgeIsTheComingSunset() {
        val noon = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, moscow)
        near("15:57", Sun.nextEdge(noon, lat, lon)!!)
    }
}
