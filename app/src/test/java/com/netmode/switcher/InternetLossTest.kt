package com.netmode.switcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetLossTest {

    @Test fun networkThatPassesNothingIsALoss() {
        assertTrue(InternetLoss.lost(hasNetwork = true, validated = false, dataWanted = false, airplane = false))
        assertFalse(InternetLoss.lost(hasNetwork = true, validated = true, dataWanted = true, airplane = false))
    }

    @Test fun noNetworkCountsOnlyWhenMobileDataIsOn() {
        assertTrue(InternetLoss.lost(hasNetwork = false, validated = false, dataWanted = true, airplane = false))
        assertFalse(InternetLoss.lost(hasNetwork = false, validated = false, dataWanted = false, airplane = false))
    }

    @Test fun airplaneModeIsNeverALoss() {
        assertFalse(InternetLoss.lost(hasNetwork = false, validated = false, dataWanted = true, airplane = true))
        assertFalse(InternetLoss.lost(hasNetwork = true, validated = false, dataWanted = true, airplane = true))
    }

    @Test fun holdsOnceTheLossHasLasted() {
        assertFalse(InternetLoss.holds(lostSince = 0L, now = 500_000L, afterMs = 30_000L))
        assertFalse(InternetLoss.holds(lostSince = 480_000L, now = 500_000L, afterMs = 30_000L))
        assertTrue(InternetLoss.holds(lostSince = 470_000L, now = 500_000L, afterMs = 30_000L))
    }
}
