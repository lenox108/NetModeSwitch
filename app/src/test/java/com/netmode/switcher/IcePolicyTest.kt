package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class IcePolicyTest {
    private val now = 1_000_000L
    private val thawed = IceApp("app.test", thawedAt = now)

    @Test fun timedThawOverridesImmediateAndDisabledAutoFreeze() {
        for (delay in listOf(0, -1, 5)) {
            val a = thawed.copy(delay = delay, keepUntil = now + 900_000L)
            assertTrue(IcePolicy.protected(a, now + 899_999L))
            assertFalse(IcePolicy.protected(a, now + 900_000L))
            assertEquals(now + 900_000L, IcePolicy.deadline(a, 0, now + 400_000L))
            assertEquals(now + 900_000L, IcePolicy.deadline(a, -1, now + 1_000_000L))
        }
    }

    @Test fun manualHoldHasNoDeadlineEvenWithImmediateDefault() {
        val a = thawed.copy(keepUntil = -1)
        assertTrue(IcePolicy.protected(a, Long.MAX_VALUE))
        assertNull(IcePolicy.deadline(a, 0))
    }

    @Test fun ruleAndUserHoldsCoexist() {
        val a = thawed.copy(heldBy = 42, keepUntil = now + 900_000L)
        assertNull(IcePolicy.deadline(a, 0))
        assertTrue(IcePolicy.protected(a, now))
        assertEquals(now + 900_000L, IcePolicy.deadline(a.copy(heldBy = 0), 0))
        assertNull(IcePolicy.deadline(a.copy(keepUntil = 0), 0))
    }

    @Test fun restartingTheProcessDoesNotExtendTheSavedTimer() {
        val a = thawed.copy(keepUntil = now + 60_000L)
        assertEquals(IcePolicy.deadline(a, 5, now + 20_000L), IcePolicy.deadline(a, 5, 0))
        assertFalse(IcePolicy.protected(a, now + 60_001L))
        assertEquals(now + 60_000L, IcePolicy.deadline(a, 5))
    }

    @Test fun normalDelayStartsFromMostRecentUseAndExplicitThaw() {
        assertEquals(now + 300_000L, IcePolicy.deadline(thawed, 5, now - 60_000L))
        assertEquals(now + 360_000L, IcePolicy.deadline(thawed, 5, now + 60_000L))
        assertNull(IcePolicy.deadline(thawed.copy(delay = -1), 5))
    }

    @Test fun frozenAppNeverSchedulesOrClaimsProtection() {
        val a = thawed.copy(frozenAs = IceMode.DEEP, keepUntil = -1)
        assertNull(IcePolicy.deadline(a, 0))
        assertFalse(IcePolicy.protected(a, now))
    }

    @Test fun undoSkipsApplicationsOpenedOrChangedSinceTheOperation() {
        val after = thawed.copy(keepUntil = now + 60_000L, revision = 3)
        val entry = IceUndoEntry(thawed.copy(frozenAs = IceMode.SOFT), after)
        assertTrue(entry.eligible(after, now, now - 1))
        assertFalse(entry.eligible(after, now, now + 1))
        assertFalse(entry.eligible(after.copy(heldBy = 99), now, 0))
        assertFalse(entry.eligible(after.copy(revision = 5), now, 0))
        assertFalse(entry.eligible(after.copy(keepUntil = -1), now, 0))
        assertFalse(entry.eligible(after.copy(mode = IceMode.DEEP), now, 0))
    }

    @Test fun changingFavoriteOrGroupDoesNotPreventUndoOfRuntimeState() {
        val after = thawed.copy(revision = 2)
        val entry = IceUndoEntry(thawed.copy(frozenAs = IceMode.SOFT), after)
        assertTrue(entry.eligible(after.copy(favorite = true, group = "Work"), now, 0))
    }

    @Test fun undoExpiresAndRejectsABackwardsClock() {
        val undo = IceUndo(now, emptyList())
        assertTrue(undo.usable(now + 599_999L))
        assertFalse(undo.usable(now + 600_001L))
        assertFalse(undo.usable(now - 1))
    }

    @Test fun alphabeticalOrderDoesNotJumpWhenAnAppFreezes() {
        val a = IceApp("Alpha")
        val b = IceApp("Beta", favorite = true)
        val c = IceApp("Charlie")
        val before = IceOrder.sort(listOf(c, a, b), IceSort.NAME, emptyList()) { it }.map { it.key }
        val after = IceOrder.sort(listOf(c, a.copy(frozenAs = IceMode.DEEP), b), IceSort.NAME, emptyList()) { it }.map { it.key }
        assertEquals(listOf("Beta", "Alpha", "Charlie"), before)
        assertEquals(before, after)
    }

    @Test fun manualOrderKeepsFavoritesFirstAndNewAppsLastWithinTheirSection() {
        val apps = listOf(IceApp("new"), IceApp("a"), IceApp("b"), IceApp("star", favorite = true))
        assertEquals(listOf("star", "b", "a", "new"),
            IceOrder.sort(apps, IceSort.MANUAL, listOf("removed", "b", "a")) { it }.map { it.key })
        assertEquals(listOf("b", "c", "a"), IceOrder.move(listOf("a", "b", "c"), 0, 2))
    }

    @Test fun additionDateUsesSavedDatesRatherThanNamesOrRuntimeState() {
        val apps = listOf(
            IceApp("Alpha", addedAt = 300, thawedAt = 1),
            IceApp("Charlie", addedAt = 100, launchedAt = 999),
            IceApp("Bravo", addedAt = 200, frozenAs = IceMode.DEEP),
        )
        assertEquals(listOf("Alpha", "Bravo", "Charlie"),
            IceOrder.sort(apps, IceSort.NEWEST, listOf("Charlie", "Bravo", "Alpha")) { it }.map { it.key })
        assertEquals(listOf("Charlie", "Bravo", "Alpha"),
            IceOrder.sort(apps, IceSort.OLDEST, emptyList()) { it }.map { it.key })
    }

    @Test fun legacyEntriesAndEqualDatesKeepTheirInsertionOrder() {
        val apps = listOf(IceApp("z"), IceApp("a"),
            IceApp("y", addedAt = 100), IceApp("b", addedAt = 100))
        assertEquals(listOf("b", "y", "a", "z"),
            IceOrder.sort(apps, IceSort.NEWEST, emptyList()) { it }.map { it.key })
        assertEquals(listOf("z", "a", "y", "b"),
            IceOrder.sort(apps, IceSort.OLDEST, emptyList()) { it }.map { it.key })
        assertEquals(listOf("b", "z"),
            IceOrder.sort(apps.filter { it.key in setOf("z", "b") }, IceSort.NEWEST, emptyList()) { it }.map { it.key })
    }

    @Test fun dateSortKeepsFavoritesFirstAndSortsEachSection() {
        val apps = listOf(IceApp("new", addedAt = 300), IceApp("old", addedAt = 100),
            IceApp("star-old", favorite = true, addedAt = 50),
            IceApp("star-new", favorite = true, addedAt = 200))
        assertEquals(listOf("star-new", "star-old", "new", "old"),
            IceOrder.sort(apps, IceSort.NEWEST, emptyList()) { it }.map { it.key })
        assertEquals(listOf("star-old", "star-new", "old", "new"),
            IceOrder.sort(apps, IceSort.OLDEST, emptyList()) { it }.map { it.key })
    }
}
