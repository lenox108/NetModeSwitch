package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NewFeaturesTest {
    private val day = LocalDate.of(2026, 11, 1)

    @Test fun theFirstSampleCountsNothingAndGrowthAdds() {
        val first = TrafficBook().add("wlan2:9", 5_000, day)
        assertEquals(0, first.dayBytes)
        val next = first.add("wlan2:9", 7_000, day)
        assertEquals(2_000, next.dayBytes)
        assertEquals(2_000, next.monthBytes)
    }

    @Test fun aNewOrResetInterfaceCountsAllItShows() {
        val book = TrafficBook().add("wlan2:9", 5_000, day)
        assertEquals(300, book.add("wlan2:10", 300, day).dayBytes)
        assertEquals(100, book.add("wlan2:9", 100, day).dayBytes)
    }

    @Test fun dayAndMonthRollOver() {
        val book = TrafficBook().add("a", 0, day).add("a", 100, day)
        val nextDay = book.add("a", 150, day.plusDays(1))
        assertEquals(50, nextDay.dayBytes)
        assertEquals(150, nextDay.monthBytes)
        val nextMonth = nextDay.add("a", 160, day.plusMonths(1))
        assertEquals(10, nextMonth.monthBytes)
        assertEquals(0, book.today(day.plusDays(1)))
        assertEquals(0, book.thisMonth(day.plusMonths(1)))
    }

    @Test fun theLimitWarnsOnlyOnTheCrossingSample() {
        val book = TrafficBook(month = "2026-11", monthBytes = 120)
        assertTrue(book.crossed(before = 90, limit = 100))
        assertFalse(book.crossed(before = 110, limit = 100))
        assertFalse(book.crossed(before = 90, limit = 0))
    }

    @Test fun countersSumAcrossInterfacesAndRejectPartialOutput() {
        val two = "@IF=wlan2\n9\n100\n200\n@IF=ap_br0\n12\n1\n2\n"
        val parsed = TetherTraffic.parse(two, 2)!!
        assertEquals(101, parsed.received)
        assertEquals(202, parsed.sent)
        assertEquals("wlan2:9,ap_br0:12", parsed.identity)
        assertNull(TetherTraffic.parse(two, 3))
        assertNull(TetherTraffic.parse("@IF=wlan2\n9\nerror\n", 1))
    }

    @Test fun aTunnelIsBlamedOnlyWhenThereIsInternetWithoutIt() {
        assertEquals(0, VpnFallback.nextStrikes(3, tunnelValidated = true, internetWithoutTunnel = true))
        assertEquals(4, VpnFallback.nextStrikes(3, tunnelValidated = false, internetWithoutTunnel = true))
        assertEquals(3, VpnFallback.nextStrikes(3, tunnelValidated = false, internetWithoutTunnel = false))
        assertFalse(VpnFallback.shouldSwitch(3))
        assertTrue(VpnFallback.shouldSwitch(4))
    }

    @Test fun anotherVpnDoesNotPassACheckForAChosenOne() {
        assertTrue(StepChecks.vpnUp("org.amnezia.vpn", "org.amnezia.vpn", null))
        assertTrue(StepChecks.vpnUp("su.happ", "org.amnezia.vpn", "su.happ"))
        assertFalse(StepChecks.vpnUp("com.adguard.android", "org.amnezia.vpn", "su.happ"))
        assertFalse(StepChecks.vpnUp(null, "org.amnezia.vpn", null))
        assertTrue(StepChecks.vpnUp("com.adguard.android", null, null))
    }

    @Test fun checkStepsParseAndAreKept() {
        val step = Steps.check(Steps.CHECK_VPN, 30)
        assertEquals(Steps.CHECK_VPN to 30, Steps.checkOf(step))
        assertNull(Steps.checkOf("VPN"))
        assertNull(Steps.checkOf("P30"))
        assertNull(Steps.checkOf("?VPN:abc"))
        assertNull(Steps.pauseOf(step))
        assertTrue(Steps.isMark(step))
        assertTrue(Steps.isMark(Steps.pause(5)))
        assertFalse(Steps.isMark("WIFI"))
        // A check first must not read as "nothing beyond the fixed order".
        assertEquals(listOf(step, "WIFI"), Steps.toSave(listOf(step, "WIFI"), Steps.START_ORDER))
    }

    @Test fun blockedMacsAreCleanedBeforeReachingTheShell() {
        assertEquals("aa:bb:cc:dd:ee:01,aa:bb:cc:dd:ee:02",
            HotspotConfig.blockedArg(listOf("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:01", "x; rm -rf /")))
        assertEquals(listOf("aa:bb:cc:dd:ee:01"), HotspotConfig.parseBlocked("ssid=a\nblocked=AA:BB:CC:DD:EE:01,bad\n"))
        assertTrue(HotspotConfig.parseBlocked("ssid=a\n").isEmpty())
    }
}
