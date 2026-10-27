package com.netmode.switcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HotspotPresetTest {
    private class Device : HotspotPresetStart.Device {
        var settings: ApSettings? = ApSettings("Home", "password", "wpa3", "2", 6, true, 4, true, 10, true, true, "1:6")
        var on = true
        var actual = setOf("2")
        var validationError: String? = null
        var writeError: String? = null
        var canStop = true
        var ignoreWrite = false
        val events = mutableListOf<String>()
        override suspend fun read() = settings
        override suspend fun validate(next: ApSettings, old: ApSettings): String? {
            events += "validate"
            return validationError
        }
        override suspend fun write(next: ApSettings, old: ApSettings): String? {
            events += "write"
            if (writeError == null && !ignoreWrite) settings = next
            return writeError
        }
        override suspend fun isOn() = on
        override suspend fun activeBands() = actual
        override suspend fun stop(): Boolean {
            events += "stop"
            if (canStop) on = false
            return canStop
        }
        override suspend fun ensureOn(mode: TetherMode): String {
            events += "launch:$mode"
            on = true
            return "on"
        }
    }

    @Test fun corruptButtonsAreIgnoredAndRemovingAllDoesNotRestoreDefaults() {
        assertEquals(6, HotspotPreset.decode(null).size)
        assertTrue(HotspotPreset.decode("").isEmpty())
        val preset = HotspotPreset("6", TetherMode.PROXY)
        assertEquals(listOf(preset), HotspotPreset.decode("INVALID:2;PROXY:6;PROXY:6;VPN:invalid;VPN:2:5"))
        assertEquals(listOf(preset), HotspotPreset.decode(HotspotPreset.encode(listOf(preset))))
    }

    @Test fun aDifferentBandIsValidatedBeforeStopAndOnlyRadioChanges() = runBlocking {
        val device = Device()
        val original = device.settings!!
        HotspotPresetStart(device).run(HotspotPreset("5", TetherMode.VPN))
        assertEquals(listOf("validate", "stop", "write", "launch:VPN"), device.events)
        assertEquals(mapOf("radio" to "2:0"), HotspotConfig.changes(device.settings!!, original))
        assertTrue(device.on)
    }

    @Test fun sameBandKeepsManualChannelAndDoesNotStopAnActiveHotspot() = runBlocking {
        val device = Device()
        repeat(2) { HotspotPresetStart(device).run(HotspotPreset("2", TetherMode.PROXY)) }
        assertEquals(listOf("launch:PROXY", "launch:PROXY"), device.events)
        assertEquals("1:6", device.settings!!.radio)
        assertTrue(device.on)
    }

    @Test fun pendingSavedBandIsAppliedByRestartingTheOldActiveBand() = runBlocking {
        val device = Device().apply { settings = settings!!.copy(band = "5", radio = "2:149", channel = 149) }
        HotspotPresetStart(device).run(HotspotPreset("5", TetherMode.DIRECT))
        assertEquals(listOf("stop", "launch:DIRECT"), device.events)
        assertEquals("2:149", device.settings!!.radio)
    }

    @Test fun anIdleBridgedRadioDoesNotCauseARestart() = runBlocking {
        val device = Device().apply { settings = settings!!.copy(radio = "1:0,2:0") }
        HotspotPresetStart(device).run(HotspotPreset("dual25", TetherMode.VPN))
        assertEquals(listOf("launch:VPN"), device.events)
    }

    @Test fun invalidConfigAndFailedStopLeaveTheExistingRouteAndRadioAlone() = runBlocking {
        val invalid = Device().apply { validationError = "unsupported" }
        HotspotPresetStart(invalid).run(HotspotPreset("6", TetherMode.DIRECT))
        assertEquals(listOf("validate"), invalid.events)
        assertTrue(invalid.on)
        val running = Device().apply { canStop = false }
        HotspotPresetStart(running).run(HotspotPreset("5", TetherMode.DIRECT))
        assertEquals(listOf("validate", "stop"), running.events)
        assertEquals("1:6", running.settings!!.radio)
    }

    @Test fun aRejectedOrIgnoredWriteNeverStartsWithTheWrongBand() = runBlocking {
        for (device in listOf(Device().apply { writeError = "rejected" }, Device().apply { ignoreWrite = true })) {
            HotspotPresetStart(device).run(HotspotPreset("5", TetherMode.DIRECT))
            assertEquals(listOf("validate", "stop", "write"), device.events)
            assertFalse(device.on)
        }
    }

    @Test fun unreadableSettingsPreventAnyMutation() = runBlocking {
        val device = Device().apply { settings = null }
        HotspotPresetStart(device).run(HotspotPreset("2", TetherMode.DIRECT))
        assertTrue(device.events.isEmpty())
    }
}
