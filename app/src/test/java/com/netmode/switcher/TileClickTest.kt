package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class TileClickTest {
    @Test fun matchingIsExactAndAcceptsOnlyTheTitlePrefixOfAnAccessibilityDescription() {
        assertTrue(TileClickWatch.matches("Proxy", listOf(" Proxy, включено ")))
        assertTrue(TileClickWatch.matches("VPN 5 ГГц", listOf("vpn  5 ггц")))
        assertFalse(TileClickWatch.matches("VPN", listOf("VPN 5 ГГц")))
        assertFalse(TileClickWatch.matches("VPN", listOf("Уведомление, VPN")))
        assertFalse(TileClickWatch.matches("", listOf("")))
    }

    @Test fun tilePressIsAnEventWithNoImplicitReturnOrDelayedStart() {
        val rule = AppRule(1, true, 0, 0, null, null, 0, emptyList(), FreezeMethod.STOP,
            trigger = Trigger.TILE_CLICK, triggerTile = "example.app/.Tile", triggerTileLabel = "Example",
            note = "Click")
        assertFalse(Trigger.TILE_CLICK.isCondition)
        assertFalse(rule.restores)
        assertTrue(rule.hasActions)
        assertEquals("нажатие плитки «Example»", rule.timeLabel)
    }
}
