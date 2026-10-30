package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class TetherHealthTest {
    @Test fun checksWhileTheHotspotIsUp() {
        assertTrue(TetherHealth.shouldApply(up = true, wasUp = false))
        assertTrue(TetherHealth.shouldApply(up = true, wasUp = true))
    }

    @Test fun clearsOnceAsItGoesThenStaysQuiet() {
        assertTrue(TetherHealth.shouldApply(up = false, wasUp = true))
        assertFalse(TetherHealth.shouldApply(up = false, wasUp = false))
    }
}
