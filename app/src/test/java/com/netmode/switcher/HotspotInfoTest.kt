package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class HotspotInfoTest {
    @Test fun widthIsDecodedFromSoftApEnumRatherThanDisplayedAsRawNumber() {
        for ((code, width) in mapOf(1 to "20", 2 to "20", 3 to "40", 4 to "80", 5 to "80+80", 6 to "160", 11 to "320")) {
            assertEquals("5 ГГц · канал 149 · $width МГц", HotspotConfig.softApSummary("SoftApInfo{bandwidth= $code, frequency= 5745"))
        }
        assertEquals("2,4 ГГц · канал 14", HotspotConfig.softApSummary("bandwidth= 160, frequency=2484"))
        assertEquals("6 ГГц · канал 2 · 20 МГц", HotspotConfig.softApSummary("bandwidth=2, frequency=5935"))
        assertNull(HotspotConfig.softApSummary("frequency=0"))
    }
}
