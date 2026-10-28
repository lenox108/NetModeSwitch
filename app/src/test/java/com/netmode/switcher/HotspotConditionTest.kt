package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class HotspotConditionTest {
    @Test fun missingStateStaysUnknownAndAnEmptySnapshotMeansOff() {
        assertNull(HotspotCondition.enabled(null))
        assertEquals(false, HotspotCondition.enabled(emptyList()))
        for (mode in TetherMode.entries) {
            assertNull(HotspotCondition.holds(null, mode, mode))
            assertEquals(false, HotspotCondition.holds(false, mode, mode))
            assertEquals(false, HotspotCondition.holds(false, mode, null))
        }
    }

    @Test fun onlyWifiSharingCountsIncludingWhenUsbIsAlsoActive() {
        assertEquals(false, HotspotCondition.enabled(listOf("rndis0", "ncm0", "bt-pan", "tun0", "eth0")))
        for (iface in listOf("wlan0", "wlan2", "softap0", "ap_br_wlan2", "swlan0")) {
            assertEquals(true, HotspotCondition.enabled(listOf("rndis0", iface)))
        }
    }

    @Test fun eachRouteMatchesOnlyItsOwnModeAndAnyAcceptsAll() {
        for (current in TetherMode.entries) {
            assertEquals(true, HotspotCondition.holds(true, current, null))
            for (wanted in TetherMode.entries) {
                assertEquals(current == wanted, HotspotCondition.holds(true, current, wanted))
            }
        }
    }

    @Test fun switchingModesWhileSharingEndsOldConditionAndStartsNewOne() {
        for (before in TetherMode.entries) for (after in TetherMode.entries.filter { it != before }) {
            assertEquals(true, HotspotCondition.holds(true, before, before))
            assertEquals(false, HotspotCondition.holds(true, after, before))
            assertEquals(true, HotspotCondition.holds(true, after, after))
            assertEquals(true, HotspotCondition.holds(true, after, null))
        }
    }

    private fun rule(via: TetherMode?) = AppRule(
        id = 1, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.HOTSPOT,
        triggerHotspotVia = via, note = "Раздача включена",
    )

    @Test fun ruleLabelsDistinguishRoutesAndConditionHasAnEnd() {
        assertEquals("точка доступа включена", rule(null).timeLabel)
        assertEquals("точка доступа включена · через VPN", rule(TetherMode.VPN).timeLabel)
        assertEquals("точка доступа включена · через прокси", rule(TetherMode.PROXY).timeLabel)
        assertEquals("точка доступа включена · напрямую", rule(TetherMode.DIRECT).timeLabel)
        assertTrue(Trigger.HOTSPOT.isCondition)
        assertTrue(rule(TetherMode.VPN).restores)
        assertFalse(rule(TetherMode.VPN).copy(releaseOnLeave = false).restores)
    }

    @Test fun triggerRouteAndActionRouteStayIndependent() {
        val r = rule(TetherMode.VPN).copy(endCustom = true, endHotspot = Toggle.ON, endHotspotVia = TetherMode.DIRECT)
        assertEquals(TetherMode.VPN, r.triggerHotspotVia)
        assertEquals(TetherMode.DIRECT, r.endSpec.hotspotVia)
        assertTrue(r.hasActions)
    }
}
