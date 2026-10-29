package com.netmode.switcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TileTransitionsTest {
    private val component = "org.amnezia.vpn/.AmneziaTileService"

    @Test fun disconnectedAmneziaWithAForegroundServiceIsNotToggledOnByAnOffAction() = runBlocking {
        val networkDump = "Current Networks:\n\nNetwork Requests:\n"
        val serviceDump = "ACTIVITY MANAGER SERVICES\n  isForeground=true\n"
        val result = TileTransitions().set(mapOf(component to false), read = { c ->
            if (TileStateProbe.usesVpn(c, emptySet())) TileStateProbe.vpn(networkDump, c.substringBefore('/'), 10123)
            else TileStateProbe.foreground(serviceDump)
        }, prepare = { error("already disconnected") }, click = { error("would start VPN") },
            await = { _, _ -> error("no change") })
        assertEquals(TileTransitions.Outcome.ALREADY, result.single().outcome)
    }

    @Test fun repeatedOffForAnAlreadyDisconnectedAmneziaNeverClicksItsTile() = runBlocking {
        val transitions = TileTransitions()
        repeat(2) {
            val result = transitions.set(mapOf(component to false),
                read = { false }, prepare = { error("must not add an already off tile") },
                click = { error("would turn Amnezia on") }, await = { _, _ -> error("no change to await") })
            assertEquals(TileTransitions.Outcome.ALREADY, result.single().outcome)
        }
    }

    @Test fun onAndOffAreIdempotentAfterASuccessfulChange() = runBlocking {
        for (target in listOf(true, false)) {
            val transitions = TileTransitions()
            var actual = !target
            var clicks = 0
            val previous = mutableListOf<Boolean>()
            val outcomes = (1..2).map {
                transitions.set(mapOf(component to target), read = { actual }, prepare = { false },
                    click = { clicks++; actual = !actual }, await = { _, want -> actual == want },
                    before = { _, was -> previous += was }).single().outcome
            }
            assertEquals(listOf(TileTransitions.Outcome.CHANGED, TileTransitions.Outcome.ALREADY), outcomes)
            assertEquals(1, clicks)
            assertEquals(target, actual)
            assertEquals(listOf(!target, target), previous)
        }
    }

    @Test fun timeoutNeverTriggersASecondToggle() = runBlocking {
        var clicks = 0
        val result = TileTransitions().set(mapOf(component to false), read = { true }, prepare = { false },
            click = { clicks++ }, await = { _, _ -> false })
        assertEquals(1, clicks)
        assertEquals(TileTransitions.Outcome.UNCONFIRMED, result.single().outcome)
    }

    @Test fun unknownStateDoesNotClickOrRecordAFabricatedPreviousState() = runBlocking {
        val result = TileTransitions().set(mapOf(component to true), read = { null },
            prepare = { error("must not add an unknown tile") }, click = { error("blind toggle") },
            await = { _, _ -> error("no change") }, before = { _, _ -> error("unknown previous state") })
        assertEquals(TileTransitions.Outcome.UNKNOWN, result.single().outcome)
    }

    @Test fun manualChangeWhileAddingATileIsRecheckedBeforeClicking() = runBlocking {
        var actual = true
        val previous = mutableListOf<Boolean>()
        val result = TileTransitions().set(mapOf(component to false), read = { actual },
            prepare = { actual = false; true }, click = { error("manual off must not turn it back on") },
            await = { _, _ -> error("no change") }, before = { _, was -> previous += was })
        assertEquals(TileTransitions.Outcome.ALREADY, result.single().outcome)
        assertTrue(result.single().added)
        assertEquals(listOf(false), previous)
    }

    @Test fun losingAccessDuringPreparationDoesNotClick() = runBlocking {
        var readable = true
        val result = TileTransitions().set(mapOf(component to false), read = { if (readable) true else null },
            prepare = { readable = false; false }, click = { error("blind toggle") },
            await = { _, _ -> error("no change") }, before = { _, _ -> error("unknown previous state") })
        assertEquals(TileTransitions.Outcome.UNKNOWN, result.single().outcome)
    }

    @Test fun concurrentRulesForTheSameTargetCannotBothToggleIt() = runBlocking {
        val transitions = TileTransitions()
        val clicked = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var actual = true
        var clicks = 0
        suspend fun off() = transitions.set(mapOf(component to false), read = { actual }, prepare = { false },
            click = { clicks++; clicked.complete(Unit) },
            await = { _, _ -> complete.await(); actual = false; true }).single().outcome
        val first = async(start = CoroutineStart.UNDISPATCHED) { off() }
        clicked.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { off() }
        assertFalse(second.isCompleted)
        complete.complete(Unit)
        assertEquals(TileTransitions.Outcome.CHANGED, first.await())
        assertEquals(TileTransitions.Outcome.ALREADY, second.await())
        assertEquals(1, clicks)
    }
}
