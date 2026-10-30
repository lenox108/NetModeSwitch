package com.netmode.switcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VpnOffTest {
    private val amnezia = "org.amnezia.vpn"
    private val happ = "su.happ.proxyutility"

    private fun disconnect(target: String?, current: String?): Pair<String, List<String?>> = runBlocking {
        val mutations = mutableListOf<String?>()
        val result = VpnControl.off(target, readOwner = { current }, name = { it }) { owner ->
            mutations += owner
            "disconnected $owner"
        }
        result to mutations
    }

    @Test fun anUnsafeTargetRunsNothing() {
        var read = false
        val line = runBlocking {
            VpnControl.off("x; reboot", readOwner = { read = true; null }, name = { it }) { "disconnected" }
        }
        assertTrue(line.contains("неверное имя"))
        assertFalse(read)
    }

    @Test fun onlyPlainPackageNamesAreValid() {
        assertTrue(VpnControl.validPkg(amnezia))
        listOf("x; reboot", "\$(id)", "com.a b", "com.a@10", "nodots").forEach { assertFalse(it, VpnControl.validPkg(it)) }
    }

    @Test fun hotspotEndingForAmneziaDoesNotTouchHappOrClaimItsState() {
        val (message, mutations) = disconnect(amnezia, happ)
        assertTrue(message.contains("пропущено"))
        assertTrue(mutations.isEmpty())
    }

    @Test fun disconnectedTargetDoesNotRestoreAnAlwaysOnSettingOrClickATile() {
        val (message, mutations) = disconnect(amnezia, null)
        assertTrue(message.contains("пропущено"))
        assertTrue(mutations.isEmpty())
    }

    @Test fun matchingOwnerIsPassedToTheDisconnectBackend() {
        assertEquals(listOf(amnezia), disconnect(amnezia, amnezia).second)
    }

    @Test fun legacyAnyTargetStillReachesTheBackendIncludingAlwaysOnCleanup() {
        assertEquals(listOf(happ), disconnect(null, happ).second)
        assertEquals(listOf<String?>(null), disconnect(null, null).second)
    }

    private fun rule() = AppRule(
        id = 1, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null,
        days = 0, packages = emptyList(), method = FreezeMethod.STOP,
        trigger = Trigger.HOTSPOT, endCustom = true, endVpn = Toggle.OFF,
    )

    @Test fun startEndAndUnlockKeepIndependentDisconnectTargets() {
        val r = rule().copy(vpn = Toggle.OFF, vpnOffApp = happ, endVpnOffApp = amnezia,
            unlockEnd = EndSpec(mode = EndMode.CUSTOM, vpn = Toggle.OFF, vpnOffApp = "com.example.third"))
        assertEquals(amnezia, r.endSpec.vpnOffApp)
        val unlocked = r.withEnd(r.unlockEnd!!)
        assertEquals("com.example.third", unlocked.endVpnOffApp)
        assertEquals(happ, unlocked.vpnOffApp)
        assertEquals(amnezia, r.endVpnOffApp)
        assertTrue(r.hasActions)
    }

    @Test fun oldOnAppNeverBecomesAnImplicitOffTarget() {
        val r = rule().copy(vpnApp = amnezia, vpn = Toggle.OFF)
        assertNull(r.vpnOffApp)
        assertNull(r.endSpec.vpnOffApp)
        assertEquals("VPN выкл", r.actionLabel)
    }
}
