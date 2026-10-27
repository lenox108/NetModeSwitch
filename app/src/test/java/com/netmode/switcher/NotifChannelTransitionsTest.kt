package com.netmode.switcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotifChannelTransitionsTest {
    private val channel = "org.example.vpn" to "connection"

    @Test fun aSharedChannelStaysOffUntilItsLastActiveRuleIsRemoved() = runBlocking {
        val calls = mutableListOf<String>()
        val owners = NotifChannelTransitions.update(mapOf(channel to 3), setOf(channel),
            restore = { _, _ -> calls += "restore"; 3 }, disable = { calls += "disable"; 3 })
        assertEquals(mapOf(channel to 3), owners)
        assertTrue(calls.isEmpty())
        val restored = NotifChannelTransitions.update(owners, emptySet(),
            restore = { _, was -> calls += "restore:$was"; was }, disable = { error("unexpected") })
        assertTrue(restored.isEmpty())
        assertEquals(listOf("restore:3"), calls)
    }

    @Test fun rejectedRestorationDoesNotDiscardTheOriginalImportanceOrDisableAnotherChannel() = runBlocking {
        val owners = mapOf(channel to 4)
        var disabled = false
        val result = runCatching { NotifChannelTransitions.update(owners, setOf("org.example.other" to "news"),
            restore = { _, _ -> 0 }, disable = { disabled = true; 3 }) }
        assertTrue(result.isFailure)
        assertFalse(disabled)
        assertEquals(mapOf(channel to 4), owners)
    }

    @Test fun aChannelAlreadyDisabledOutsideTheAppIsNeverClaimedForRestoration() = runBlocking {
        val owners = NotifChannelTransitions.update(emptyMap(), setOf(channel),
            restore = { _, _ -> error("unexpected") }, disable = { null })
        assertTrue(owners.isEmpty())
    }

    @Test fun changingToAConditionalRuleRestoresTheSystemChannelFirst() = runBlocking {
        val calls = mutableListOf<Int>()
        val owners = NotifChannelTransitions.update(mapOf(channel to 2), emptySet(),
            restore = { _, was -> calls += was; was }, disable = { error("unexpected") })
        assertEquals(listOf(2), calls)
        assertTrue(owners.isEmpty())
    }
}
