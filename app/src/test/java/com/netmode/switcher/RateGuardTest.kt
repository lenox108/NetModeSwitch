package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Test

class RateGuardTest {

    @Test fun countsWithinTheWindowOnly() {
        val g = RateGuard(windowMs = 10_000L, keep = 60)
        assertEquals(1, g.hit(0L))
        assertEquals(2, g.hit(5_000L))
        assertEquals(3, g.hit(10_000L))
        // The first one has left the window.
        assertEquals(3, g.hit(10_001L))
    }

    @Test fun aLoopIsSeenAndTheCountStaysBounded() {
        val g = RateGuard(windowMs = 10_000L, keep = 60)
        var last = 0
        // 32 a second, as in the incident of 19.09.2026.
        for (i in 0 until 320) last = g.hit(i * 31L)
        assertEquals(240, last)
    }
}
