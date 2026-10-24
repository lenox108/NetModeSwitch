package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NetModeRangeTest {
    @Test
    fun everyRangeIsOneMode() {
        val ranges = NetMode.entries.map { it.generations }
        assertEquals(ranges.size, ranges.toSet().size)
    }

    @Test
    fun maskReadsBackAsItsMode() {
        for (mode in NetMode.entries) assertEquals(mode, NetMode.of(mode.mask))
    }

    @Test
    fun untickingTwoGKeepsTheRest() {
        assertEquals(NetMode.LTE_WCDMA, NetMode.ticked(NetMode.FULL, 2, false))
        assertEquals(NetMode.NR_LTE_WCDMA, NetMode.ticked(NetMode.FIVE_G, 2, false))
    }

    @Test
    fun tickPastAGapFillsIt() {
        assertEquals(NetMode.LTE_WCDMA, NetMode.ticked(NetMode.LTE_ONLY, 3, true))
        assertEquals(NetMode.FULL, NetMode.ticked(NetMode.LTE_ONLY, 2, true))
        assertEquals(NetMode.NR_LTE_WCDMA, NetMode.ticked(NetMode.WCDMA_ONLY, 5, true))
    }

    @Test
    fun untickInsideKeepsThePartAbove() {
        assertEquals(NetMode.NR_LTE, NetMode.ticked(NetMode.FIVE_G, 3, false))
        assertEquals(NetMode.WCDMA_ONLY, NetMode.ticked(NetMode.LTE_WCDMA, 4, false))
    }

    @Test
    fun theLastTickStays() {
        assertEquals(NetMode.TWO_G, NetMode.ticked(NetMode.TWO_G, 2, false))
    }

    @Test
    fun missingModeFallsToTheNearest() {
        // 5G alone does not exist.
        assertEquals(NetMode.NR_LTE, NetMode.ticked(NetMode.NR_LTE, 4, false))
        // The phone check failed 3G alone.
        assertEquals(NetMode.THREE_G, NetMode.ticked(NetMode.LTE_WCDMA, 4, false, NetMode.entries - NetMode.WCDMA_ONLY))
    }
}
