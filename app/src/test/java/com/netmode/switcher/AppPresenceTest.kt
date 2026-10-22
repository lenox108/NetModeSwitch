package com.netmode.switcher

import com.netmode.switcher.AppPresence.Look
import com.netmode.switcher.AppPresence.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPresenceTest {

    private fun look(seen: Seen, open: Boolean = true, away: Long? = null, now: Long = 100_000L, delay: Long = 15_000L, screenStays: Boolean = false) =
        AppPresence.look(seen, open, away, now, delay, screenStays)

    @Test fun appOnScreenHoldsAndForgetsTheTripOut() {
        assertEquals(Look(true, null), look(Seen.APP, open = false))
        assertEquals(Look(true, null), look(Seen.APP, away = 90_000L))
    }

    @Test fun closedRuleDoesNotOpenOnAnythingElse() {
        for (s in listOf(Seen.PASS, Seen.OTHER, Seen.SCREEN_OFF, Seen.UNKNOWN)) {
            assertEquals(Look(false, null), look(s, open = false, away = 90_000L))
        }
    }

    @Test fun leavingStartsTheDelayAndEndsAfterIt() {
        assertEquals(Look(true, 100_000L), look(Seen.OTHER))
        assertEquals(Look(true, 90_000L), look(Seen.OTHER, away = 90_000L))
        assertEquals(Look(false, null), look(Seen.OTHER, away = 85_000L))
    }

    @Test fun noDelayLeavesAtOnce() {
        assertEquals(Look(false, null), look(Seen.OTHER, delay = 0L))
        assertEquals(Look(false, null), look(Seen.SCREEN_OFF, delay = 0L))
    }

    @Test fun passThroughKeepsTheRuleUntilTheAppHasLeft() {
        assertEquals(Look(true, null), look(Seen.PASS, delay = 0L))
        // Left for the launcher first: a dialog after that does not stop the countdown.
        assertEquals(Look(false, null), look(Seen.PASS, away = 80_000L))
    }

    @Test fun screenOffIsLeavingUnlessTheRuleSaysOtherwise() {
        assertEquals(Look(false, null), look(Seen.SCREEN_OFF, delay = 0L))
        assertEquals(Look(true, null), look(Seen.SCREEN_OFF, delay = 0L, screenStays = true))
        assertEquals(Look(true, 100_000L), look(Seen.SCREEN_OFF))
    }

    @Test fun unknownKeepsAsIs() {
        assertEquals(Look(null, 95_000L), look(Seen.UNKNOWN, away = 95_000L))
    }

    @Test fun systemScreensAndBrowserTabsPassThrough() {
        assertTrue(AppPresence.passes("com.android.intentresolver", "com.android.intentresolver.ChooserActivity"))
        assertTrue(AppPresence.passes("android", "com.android.internal.app.ResolverActivity"))
        assertTrue(AppPresence.passes("com.android.chrome", "org.chromium.chrome.browser.customtabs.CustomTabActivity"))
        assertTrue(AppPresence.passes("org.mozilla.firefox", "org.mozilla.fenix.customtabs.ExternalAppBrowserActivity"))
        assertFalse(AppPresence.passes("com.android.chrome", "com.google.android.apps.chrome.Main"))
        assertFalse(AppPresence.passes("org.telegram.messenger", null))
    }
}
