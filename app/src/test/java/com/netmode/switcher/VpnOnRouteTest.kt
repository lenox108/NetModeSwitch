package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class VpnOnRouteTest {
    private val amneziaTile = "org.amnezia.vpn/org.amnezia.vpn.VpnTile"
    private val adguardTile = "com.adguard.android/.service.TileService"

    @Test fun rootStillUsesAmneziaTileSoAlwaysOnIsNotLeftBehind() {
        assertTrue(VpnControl.viaTile(amneziaTile, root = true))
    }

    @Test fun rootKeepsAlwaysOnForAppsWhoseTileStateIsNotTheVpn() {
        assertFalse(VpnControl.viaTile(adguardTile, root = true))
        assertFalse(VpnControl.viaTile(null, root = true))
    }

    @Test fun shellUsesAnyTileAndNothingWithoutOne() {
        assertTrue(VpnControl.viaTile(adguardTile, root = false))
        assertFalse(VpnControl.viaTile(null, root = false))
    }
}
