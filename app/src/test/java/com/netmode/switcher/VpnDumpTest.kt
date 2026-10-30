package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs the on-device filter through this machine's awk, the same one-true-awk Android ships. */
class VpnDumpTest {
    private val pkg = "org.amnezia.vpn"
    private val vpnLine = "NetworkAgentInfo{network{101} ni{VPN CONNECTED extra: VPN:$pkg} nc{[ Transports: VPN ]}}"
    private val wifiLine = "NetworkAgentInfo{network{100} ni{WIFI CONNECTED} nc{[ Transports: WIFI ]}}"

    private fun trimmed(dump: String): String {
        assumeTrue(File("/usr/bin/awk").exists())
        val program = TileStateProbe.VPN_DUMP.substringAfter("awk '").removeSuffix("'")
        val p = ProcessBuilder("/usr/bin/awk", program).redirectErrorStream(true).start()
        p.outputStream.use { it.write(dump.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return out
    }

    private fun full(vararg networks: String) = "Active default network: 100\n\nCurrent Networks:\n" +
        networks.joinToString("") { "  $it\n    Requests: REQUEST:1 LISTEN:2\n" } +
        "\nStatus for known UIDs:\n  UID 1000\n\nPrevious networks:\n  $vpnLine\nlog: SecurityException quoted\n"

    @Test fun keepsTheSectionAndTheNextHeaderAndNothingAfter() {
        val out = trimmed(full(wifiLine, vpnLine))
        assertTrue(out, out.contains("Current Networks:"))
        assertTrue(out, out.contains(wifiLine))
        assertTrue(out, out.contains("Status for known UIDs:"))
        assertFalse(out, out.contains("Previous networks"))
        assertFalse(out, out.contains("SecurityException"))
    }

    @Test fun answersMatchTheFullDump() {
        val on = full(wifiLine, vpnLine)
        assertEquals(TileStateProbe.vpn(trimmed(on), pkg, 10123), true)
        assertEquals(false, TileStateProbe.vpn(trimmed(full(wifiLine)), pkg, 10123))
        assertEquals(false, TileStateProbe.vpn(trimmed(full()), pkg, 10123))
    }

    @Test fun failuresStayDetectable() {
        assertNull(TileStateProbe.vpn(trimmed("Permission Denial: can't dump connectivity\n"), pkg, 10123))
        assertNull(TileStateProbe.vpn(trimmed("Can't find service: connectivity\n"), pkg, 10123))
        assertNull(TileStateProbe.vpn(trimmed("unexpected\noutput\n"), pkg, 10123))
        assertNull(TileStateProbe.vpn(trimmed(""), pkg, 10123))
    }

    @Test fun anErrorBeforeTheSectionIsKept() {
        val out = trimmed("Error dumping service info\n" + full(vpnLine))
        assertTrue(out, out.contains("Error dumping"))
        assertNull(TileStateProbe.vpn(out, pkg, 10123))
    }
}
