package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class HotspotPanelTest {
    private val settings = ApSettings("Home", "password", "wpa2", "2", 6, false, 0, true, 0, true, true, "1:6,2:149")

    @Test fun renamingDoesNotCollapseConcurrentApsOrResetTheirChannels() {
        assertEquals(mapOf("ssid" to "Work"), HotspotConfig.changes(settings.copy(ssid = "Work"), settings))
        assertEquals(emptyMap<String, String>(), HotspotConfig.changes(settings, settings))
        assertEquals(mapOf("radio" to "1:0,2:0"), HotspotConfig.changes(settings.copy(radio = "1:0,2:0"), settings))
    }

    @Test fun autoSelectionAndSimultaneousBandsAreDifferentConfigurations() {
        assertEquals("auto25", ApRadio.key("3:0"))
        assertEquals("dual25", ApRadio.key("1:0,2:0"))
        assertEquals("3:0", ApRadio.value("auto25"))
        assertEquals("1:0,2:0", ApRadio.value("dual25"))
        assertEquals("1:6,8:37", ApRadio.value("keep", original = "1:6,8:37"))
        for (bad in listOf("", "1:0,1:1", "1:-1", "4:0", "3:6", "1:0;id", "1:0,2:0,8:0")) assertTrue(bad, ApRadio.parse(bad).isEmpty())
    }

    @Test fun trafficUsesClientDirectionAndResetsOnNewInterfaceOrCounterReset() {
        val tracker = HotspotTelemetry()
        val first = tracker.sample(1000, ApCounters("ap:4", 100, 300))!!
        assertNull(first.downRate)
        assertEquals(0L, first.download)
        val next = tracker.sample(3000, ApCounters("ap:4", 300, 900))!!
        assertEquals(100L, next.upRate)
        assertEquals(300L, next.downRate)
        assertEquals(600L, next.download)
        assertNull(tracker.sample(4000, ApCounters("ap:4", 0, 0))!!.downRate)
        assertEquals(5000L, tracker.sample(5000, ApCounters("ap:5", 20, 20))!!.since)
        assertNull(tracker.sample(6000, null))
        assertEquals(7000L, tracker.sample(7000, ApCounters("ap:5", 50, 50))!!.since)
    }

    @Test fun staleNeighboursAndOtherInterfacesDoNotBecomeConnectedClients() {
        val peer = "aa:bb:cc:dd:ee:02"
        val neighbourText = """
            192.168.1.2 dev wlan2 lladdr $peer REACHABLE
            192.168.1.3 dev wlan2 lladdr aa:bb:cc:dd:ee:03 STALE
            10.0.0.2 dev wlan0 lladdr $peer REACHABLE
            192.168.1.4 dev wlan2 lladdr $peer FAILED
        """.trimIndent()
        val actual = HotspotParsing.peers(listOf(peer, peer.uppercase(), "02:00:00:00:00:00"), neighbourText, setOf("wlan2"))
        assertEquals(listOf(ApPeer(peer, listOf("192.168.1.2"))), actual)
        assertTrue(HotspotParsing.peers(emptyList(), neighbourText, setOf("wlan2")).isEmpty())
    }

    @Test fun reportsStripCredentialsAndHardwareAddresses() {
        val text = HotspotParsing.redact("user secretpass secret aa:bb:cc:dd:ee:ff", listOf("secret", "secretpass", "user", ""))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("user"))
        assertFalse(text.contains("aa:bb"))
        assertTrue(text.contains("[MAC]"))
    }
}
