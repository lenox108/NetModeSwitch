package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class TileStateProbeTest {
    private val pkg = "org.amnezia.vpn"
    private val uid = 10123
    private fun current(vararg networks: String) = "Active default network: 100\nCurrent Networks:\n" +
        networks.joinToString("\n") { "  $it" } + "\n\nNetwork Requests:\n  historical data\n"
    private fun vpn(owner: String, state: String = "CONNECTED") =
        "NetworkAgentInfo{network{101} ni{VPN $state extra: VPN:$owner} nc{[ Transports: VPN ]}}"

    @Test fun bothAmneziaClientsUseVpnEvenWhenChosenInTheGenericTileRow() {
        assertTrue(TileStateProbe.usesVpn("$pkg/.AmneziaTileService", emptySet()))
        assertTrue(TileStateProbe.usesVpn("org.amnezia.awg/.QuickTileService", emptySet()))
        assertFalse(TileStateProbe.usesVpn("su.happ.proxyutility/.QSTileService", emptySet()))
        val happ = "su.happ.proxyutility/.QSTileService"
        assertTrue(TileStateProbe.usesVpn(happ, setOf(happ)))
    }

    @Test fun disconnectedAmneziaIsOffEvenWithAVpnInTheHistoricalDump() {
        val dump = current("NetworkAgentInfo{network{100} ni{WIFI CONNECTED} nc{[ Transports: WIFI ]}}") +
            "Previous networks:\n  ${vpn(pkg)}\n"
        assertEquals(false, TileStateProbe.vpn(dump, pkg, uid))
    }

    @Test fun onlyTheExactConnectedPackageIsOn() {
        assertEquals(true, TileStateProbe.vpn(current(vpn(pkg)), pkg, uid))
        assertEquals(false, TileStateProbe.vpn(current(vpn("$pkg.other")), pkg, uid))
        assertEquals(false, TileStateProbe.vpn(current(vpn("su.happ.proxyutility")), pkg, uid))
    }

    @Test fun android10NetworkInfoFormatUsesTheSameActualConnection() {
        val network = "NetworkAgentInfo [VPN () - 101] NetworkInfo: [type: VPN[], state: CONNECTED/CONNECTED, extra: VPN:$pkg] NetworkCapabilities: [ Transports: VPN ]"
        assertEquals(true, TileStateProbe.vpn(current(network), pkg, uid))
    }

    @Test fun modernDumpCanIdentifyTheOwnerByUidWhenItOmitsThePackage() {
        val network = "NetworkAgentInfo{network{101} ni{VPN CONNECTED} nc{[ Transports: WIFI|VPN OwnerUid: $uid ]}}"
        assertEquals(true, TileStateProbe.vpn(current(network), pkg, uid))
        assertEquals(false, TileStateProbe.vpn(current(network), pkg, uid + 1))
        assertNull(TileStateProbe.vpn(current(network), pkg, null))
    }

    @Test fun connectingOrUnidentifiedVpnIsUnknownRatherThanOff() {
        assertNull(TileStateProbe.vpn(current(vpn(pkg, "CONNECTING")), pkg, uid))
        assertNull(TileStateProbe.vpn(current(vpn(pkg, "DISCONNECTING")), pkg, uid))
        assertNull(TileStateProbe.vpn(current("NetworkAgentInfo{ni{VPN CONNECTED} nc{[ Transports: VPN ]}}"), pkg, uid))
    }

    @Test fun aValidEmptyCurrentNetworkListMeansOff() {
        assertEquals(false, TileStateProbe.vpn(current(), pkg, uid))
    }

    @Test fun networkIndentationDoesNotTurnAConnectedVpnIntoAnEmptyList() {
        val flat = "Current Networks:\n${vpn(pkg)}\nNetwork Requests:\n"
        val indented = "  Current Networks:\n    ${vpn(pkg)}\n  Network Requests:\n    ${vpn("other.pkg")}\n"
        assertEquals(true, TileStateProbe.vpn(flat, pkg, uid))
        assertEquals(true, TileStateProbe.vpn(indented, pkg, uid))
    }

    @Test fun failedAndUnsupportedVpnReadsNeverMasqueradeAsOff() {
        for (dump in listOf("", "Permission Denial: can't dump ConnectivityService", "Can't find service: connectivity",
            "VPN CONNECTED extra: VPN:$pkg", "Current Networks:\n", "Current Networks:\nunsupported format\n",
            "Current Networks:\n  unsupported format\n", current("NetworkAgentInfo{unsupported}"))) {
            assertNull(dump, TileStateProbe.vpn(dump, pkg, uid))
        }
    }

    @Test fun errorsQuotedInTheLogHistoryDoNotMakeAValidDumpUnknown() {
        val dump = current("NetworkAgentInfo{network{100} ni{MOBILE[NR] CONNECTED extra: internet} nc{[ Transports: CELLULAR ]}}") +
            "Tethering log:\n  java.lang.SecurityException: Permission denied\n"
        assertEquals(false, TileStateProbe.vpn(dump, pkg, uid))
        assertEquals(true, TileStateProbe.vpn(current(vpn(pkg)) + "  Error dumping NetworkStack\n", pkg, uid))
    }

    @Test fun aVpnNamedOnlyByRequestsOrCapabilitiesIsNoVpnNetwork() {
        val mobile = "NetworkAgentInfo{network{163} ni{MOBILE[LTE] CONNECTED extra: internet} nc{[ Transports: CELLULAR Capabilities: INTERNET&NOT_VPN ]}}\n" +
            "    Requests: REQUEST:1 LISTEN:3\n" +
            "      NetworkRequest [ LISTEN id=7, [ Transports: VPN OwnerUid: 10001 ] ]"
        assertEquals(false, TileStateProbe.vpn(current(mobile), pkg, uid))
        assertEquals(true, TileStateProbe.vpn(current(mobile, vpn(pkg)), pkg, uid))
        val vpnFirst = "NetworkAgentInfo{network{101} ni{VPN CONNECTED} nc{[ Transports: VPN|USB OwnerUid: $uid ]}}"
        assertEquals(true, TileStateProbe.vpn(current(vpnFirst), pkg, uid))
    }

    @Test fun foregroundAdapterStillSupportsHappProxyButRejectsUnreadableDumps() {
        assertEquals(true, TileStateProbe.foreground("ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  isForeground=true"))
        assertEquals(false, TileStateProbe.foreground("ACTIVITY MANAGER SERVICES\n  isForeground=false"))
        assertEquals(false, TileStateProbe.foreground("No services match: su.happ.proxyutility"))
        assertNull(TileStateProbe.foreground("Permission Denial: can't dump ActivityManager"))
        assertNull(TileStateProbe.foreground(""))
    }
}
