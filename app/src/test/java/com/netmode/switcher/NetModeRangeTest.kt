package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NetModeRangeTest {
    @Test
    fun everyRangeIsOneMode() {
        val ranges = NetMode.entries.map { it.top to it.bottom }
        assertEquals(ranges.size, ranges.toSet().size)
    }

    @Test
    fun topKeepsTheFallback() {
        assertEquals(NetMode.FULL, NetMode.withTop(4, NetMode.FIVE_G))
        assertEquals(NetMode.THREE_G, NetMode.withTop(3, NetMode.FULL))
        assertEquals(NetMode.FIVE_G, NetMode.withTop(5, NetMode.THREE_G))
    }

    @Test
    fun topKeepsHolding() {
        assertEquals(NetMode.WCDMA_ONLY, NetMode.withTop(3, NetMode.LTE_ONLY))
        assertEquals(NetMode.NR_LTE, NetMode.withTop(5, NetMode.WCDMA_ONLY))
        assertEquals(NetMode.LTE_ONLY, NetMode.withTop(4, NetMode.NR_LTE))
    }

    @Test
    fun bottomMovesTopOnlyWhenItMust() {
        assertEquals(NetMode.NR_LTE, NetMode.withBottom(4, NetMode.FIVE_G))
        assertEquals(NetMode.WCDMA_ONLY, NetMode.withBottom(3, NetMode.FIVE_G))
        assertEquals(NetMode.FULL, NetMode.withBottom(2, NetMode.LTE_ONLY))
        assertEquals(NetMode.FIVE_G, NetMode.withBottom(2, null))
    }

    @Test
    fun unavailableModesAreSkipped() {
        val among = NetMode.entries - NetMode.NR_LTE
        assertEquals(NetMode.LTE_ONLY, NetMode.withBottom(4, NetMode.FIVE_G, among))
        assertEquals(null, NetMode.withTop(5, NetMode.FIVE_G, among - NetMode.FIVE_G))
    }
}
