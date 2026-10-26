package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class FreezerControlsTest {
    private val soft = IceApp("app.soft", frozenAs = IceMode.SOFT)
    private val deep = IceApp("app.deep", frozenAs = IceMode.DEEP)
    private val thawed = IceApp("app.awake")

    @Test fun emptyFreezerIsNotAnActiveFrozenTile() {
        assertEquals(IceTileState.EMPTY, IceTileState.of(emptyList()))
    }

    @Test fun softAndDeepFreezeTogetherCountAsFullyFrozen() {
        val state = IceTileState.of(listOf(soft, deep))
        assertEquals(IceTileState.FROZEN, state)
        assertFalse(state.freezeOnClick)
        assertEquals(IceTileState.THAWED, IceTileState.of(listOf(soft, deep).map { it.copy(frozenAs = null) }))
    }

    @Test fun mixedStateFinishesFreezingInsteadOfThawingTheOtherApps() {
        val list = listOf(soft, deep, thawed.copy(heldBy = 123L))
        val state = IceTileState.of(list)
        assertEquals(IceTileState.MIXED, state)
        assertTrue(state.freezeOnClick)
        assertEquals(listOf("app.awake"), list.filter { it.frozen != state.freezeOnClick }.map { it.key })
    }

    @Test fun failedThawLeavesAnHonestMixedState() {
        val state = IceTileState.of(listOf(soft.copy(frozenAs = null), deep))
        assertEquals(IceTileState.MIXED, state)
        assertTrue(state.freezeOnClick)
    }

    @Test fun requestedRowsFitWithTheirSpacing() {
        val h = IceGridGeometry.rowHeight(700, 8, 4, 48)
        assertTrue(h * 8 + 4 * 7 <= 700)
        assertTrue(700 - (h * 8 + 4 * 7) < 8)
    }

    @Test fun SmallViewportOrLargeTextKeepsCellsReadableAndScrollable() {
        assertEquals(48, IceGridGeometry.rowHeight(240, 12, 4, 48))
        assertEquals(96, IceGridGeometry.rowHeight(600, 10, 0, 96))
        assertEquals(64, IceGridGeometry.rowHeight(0, 8, 0, 64))
    }
}
