package com.netmode.switcher

import com.netmode.switcher.IceSwipeRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceSwipeRulesTest {
    private val slop = 24f

    @Test
    fun smallMovementStaysUndecided() {
        assertEquals(Decision.PENDING, IceSwipeRules.decide(0f, 0f, slop))
        assertEquals(Decision.PENDING, IceSwipeRules.decide(23f, 10f, slop))
    }

    @Test
    fun clearlySidewaysIsClaimedInBothDirections() {
        assertEquals(Decision.CLAIM, IceSwipeRules.decide(24f, 0f, slop))
        assertEquals(Decision.CLAIM, IceSwipeRules.decide(-40f, 10f, slop))
        assertEquals(Decision.CLAIM, IceSwipeRules.decide(40f, -20f, slop))
    }

    @Test
    fun diagonalIsNotClaimedBeforeItDominates() {
        assertEquals(Decision.PENDING, IceSwipeRules.decide(30f, 20f, slop))
    }

    @Test
    fun verticalPastSlopBelongsToTheList() {
        assertEquals(Decision.REJECT, IceSwipeRules.decide(0f, 24f, slop))
        assertEquals(Decision.REJECT, IceSwipeRules.decide(0f, -60f, slop))
        // Even when sideways is larger: the list has already started to scroll.
        assertEquals(Decision.REJECT, IceSwipeRules.decide(200f, 30f, slop))
    }

    @Test
    fun thresholdIsAThirdOfAWideRow() {
        assertEquals(330f, IceSwipeRules.threshold(1000f, 200f), 0.01f)
    }

    @Test
    fun thresholdHasAMinimumButNeverPassesHalfTheRow() {
        assertEquals(200f, IceSwipeRules.threshold(500f, 200f), 0.01f)
        assertEquals(150f, IceSwipeRules.threshold(300f, 200f), 0.01f)
    }

    @Test
    fun commitNeedsTheThresholdInEitherDirection() {
        assertTrue(IceSwipeRules.commits(100f, 100f))
        assertTrue(IceSwipeRules.commits(-100f, 100f))
        assertFalse(IceSwipeRules.commits(99f, 100f))
        assertFalse(IceSwipeRules.commits(-99f, 100f))
    }

    @Test
    fun aRowWithoutWidthNeverCommits() {
        assertFalse(IceSwipeRules.commits(0f, IceSwipeRules.threshold(0f, 200f)))
    }
}
