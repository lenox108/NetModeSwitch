package com.netmode.switcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TetherLeaseTest {
    @Test fun repeatedEventsAndNewAppProcessAdoptSameHealthyTunnel() = runBlocking {
        var disk: TetherRecord? = null
        var writes = 0
        fun process() = TetherLease({ disk }) { disk = it; writes++; true }
        val first = process()
        assertNull(first.adopt("vpn-1") { error("no saved route should be probed") })
        first.remember("vpn-1", "ready") { true }
        repeat(3) { assertEquals("ready", first.adopt("vpn-1") { true }) }
        assertEquals("ready", process().adopt("vpn-1") { true })
        assertEquals("adoption must not trigger a restart/state rewrite", 1, writes)
    }

    @Test fun changedNetworkOrBrokenRulesCannotBeAdopted() = runBlocking {
        var disk: TetherRecord? = TetherRecord("old-net", "ready")
        val lease = TetherLease({ disk }) { disk = it; true }
        assertNull(lease.adopt("new-net") { error("identity mismatch must not reuse the old process") })
        assertNull(lease.adopt("old-net") { false })
        lease.forget()
        try { lease.remember("new-net", "ready") { false }; fail("must not persist an unverified route") }
        catch (_: IllegalStateException) { }
        assertNull(disk)
    }

    @Test fun identityCoversCredentialsAndDoesNotExposeThemOrAmbiguousSeparators() {
        val values = arrayOf("69", "boot", "wlan2", "103", "proxy:1080", "user", "password")
        val key = tetherIdentity(*values)
        assertEquals(64, key.length)
        assertEquals(key, tetherIdentity(*values))
        for (i in values.indices) {
            val changed = values.clone().also { it[i] += "changed" }
            assertNotEquals(key, tetherIdentity(*changed))
        }
        assertNotEquals(tetherIdentity("a\nb", "c"), tetherIdentity("a", "b\nc"))
        assertFalse(key.contains("password"))
    }
}
