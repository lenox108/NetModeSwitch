package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class HotspotAvailabilityTest {
    @Test fun unknownExtraBandsAreHiddenUntilSupportIsConfirmed() {
        val caps = ApCapability(32, emptyMap())
        assertEquals(setOf("2", "5", "auto25"), HotspotBands.available(caps, 34))
        assertFalse(HotspotBands.known(caps))
    }

    @Test fun explicitUnsupportedFlagsOverrideStaleChannelLists() {
        val caps = ApCapability(32, mapOf("6" to listOf(37)), true,
            mapOf("2" to true, "5" to false, "6" to false))
        assertEquals(setOf("2"), HotspotBands.available(caps, 34))
        assertTrue(HotspotBands.known(caps))
    }

    @Test fun emptyChannelListDoesNotHideAnExplicitlySupportedBand() {
        val caps = ApCapability(32, emptyMap(), false, mapOf("6" to true))
        assertEquals(setOf("2", "5", "6", "auto25"), HotspotBands.available(caps, 34))
    }

    @Test fun bridgedModeNeedsBothBandsAndTheRequiredApi() {
        val caps = ApCapability(32, emptyMap(), true, mapOf("2" to true, "5" to true, "6" to false))
        assertTrue("dual25" in HotspotBands.available(caps, 31))
        assertFalse("dual25" in HotspotBands.available(caps, 30))
        assertTrue(HotspotBands.available(caps, 29).isEmpty())
        assertFalse("dual25" in HotspotBands.available(caps.copy(bridged = false), 34))
    }

    @Test fun aPartialResponseDoesNotRestoreUnsupportedOrForgetSupportedBands() {
        val partial = ApCapability(32, emptyMap(), false)
        assertEquals(setOf("2"), HotspotBands.resolve(partial, 34, setOf("2")))
        assertEquals(setOf("2", "5", "6", "auto25"),
            HotspotBands.resolve(partial, 34, setOf("2", "5", "6", "auto25", "dual25")))
        assertEquals(setOf("2", "5", "auto25"), HotspotBands.resolve(partial.copy(supported = mapOf("5" to true)), 34, setOf("2")))
    }

    @Test fun tilesDistinguishTheRouteAndActualBandFromPendingSavedSettings() {
        val preset = HotspotPreset("5", TetherMode.PROXY)
        val state = HotspotPresetState(true, TetherMode.PROXY, "2:149", setOf("5"))
        assertTrue(state.matches(preset))
        assertFalse(state.copy(on = false).matches(preset))
        assertFalse(state.copy(route = TetherMode.VPN).matches(preset))
        assertFalse(state.copy(activeBands = setOf("2")).matches(preset))
        assertFalse(state.copy(radio = "1:0").matches(preset))
        assertTrue(HotspotPresetState(true, TetherMode.VPN, "1:0,2:0", setOf("5"))
            .matches(HotspotPreset("dual25", TetherMode.VPN)))
        assertEquals(setOf("2", "5", "6"), HotspotBands.fromFrequencies(listOf(2412, 5745, 5935, 0)))
    }
}
