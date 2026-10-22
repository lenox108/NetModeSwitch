package com.netmode.switcher

import com.netmode.switcher.ExtraGate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ExtrasTest {

    private fun window(from: String, to: String): Extra {
        val f = LocalTime.parse(from); val t = LocalTime.parse(to)
        return Extra(ExtraKind.TIME, from = f.hour * 60 + f.minute, to = t.hour * 60 + t.minute)
    }

    @Test fun dayWindow() {
        val w = window("09:00", "18:00")
        assertTrue(w.timeHolds(LocalTime.parse("09:00")))
        assertTrue(w.timeHolds(LocalTime.parse("17:59")))
        assertFalse(w.timeHolds(LocalTime.parse("18:00")))
        assertFalse(w.timeHolds(LocalTime.parse("08:59")))
    }

    @Test fun nightWindowRunsPastMidnight() {
        val w = window("23:00", "07:00")
        assertTrue(w.timeHolds(LocalTime.parse("23:30")))
        assertTrue(w.timeHolds(LocalTime.parse("03:00")))
        assertFalse(w.timeHolds(LocalTime.parse("07:00")))
        assertFalse(w.timeHolds(LocalTime.parse("12:00")))
    }

    @Test fun sameStartAndEndIsAllDay() {
        assertTrue(window("10:00", "10:00").timeHolds(LocalTime.parse("03:00")))
    }

    @Test fun labels() {
        assertEquals("с 23:00 до 07:00", window("23:00", "07:00").label)
        assertEquals("не в сети «Home»", Extra(ExtraKind.WIFI, negate = true, ssid = "Home").label)
        assertEquals("заряд ≤ 20%", Extra(ExtraKind.BATTERY, level = 20).label)
    }

    @Test fun gate() {
        // Trigger holds, the last extra comes: start.
        assertEquals(Step.START, ExtraGate.step(open = false, primary = true, extras = true))
        // An extra goes while open: end; while closed: nothing.
        assertEquals(Step.END, ExtraGate.step(open = true, primary = true, extras = false))
        assertEquals(Step.NONE, ExtraGate.step(open = false, primary = true, extras = false))
        // Trigger is not there, or cannot be told: wait for it.
        assertEquals(Step.NONE, ExtraGate.step(open = false, primary = false, extras = true))
        assertEquals(Step.NONE, ExtraGate.step(open = false, primary = null, extras = true))
        // An extra cannot be told: keep as is.
        assertEquals(Step.NONE, ExtraGate.step(open = true, primary = true, extras = null))
        assertEquals(Step.NONE, ExtraGate.step(open = true, primary = true, extras = true))
    }
}
