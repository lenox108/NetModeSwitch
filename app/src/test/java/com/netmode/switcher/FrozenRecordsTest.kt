package com.netmode.switcher

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FrozenRecordsTest {
    private fun entry(pkg: String, prev: String, rule: Long = 1) = FrozenApp(pkg, rule, 0, null, null, false, prev)

    @Test fun eachRecordKeepsTheOnesBeforeIt() {
        // What a process death after the second change finds on disk.
        var disk = emptyList<FrozenApp>()
        disk = FrozenRecords.put(disk, entry(FrozenApp.WIFI, "on"))
        disk = FrozenRecords.put(disk, entry(FrozenApp.DATA, "off"))
        assertEquals(listOf(FrozenApp.WIFI, FrozenApp.DATA), disk.map { it.pkg })
    }

    @Test fun aNewRecordReplacesTheOldOneOfThatPackage() {
        val disk = FrozenRecords.put(listOf(entry(FrozenApp.WIFI, "on", rule = 1)), entry(FrozenApp.WIFI, "on", rule = 2))
        assertEquals(listOf(2L), disk.map { it.ruleId })
    }

    @Test fun undoBringsBackTheEarlierRecordOrNone() {
        val earlier = entry(FrozenApp.NET + 0, "FULL", rule = 1)
        val mine = FrozenRecords.put(listOf(earlier), entry(FrozenApp.NET + 0, "FULL", rule = 2))
        assertEquals(listOf(earlier), FrozenRecords.undo(mine, FrozenApp.NET + 0, earlier))
        assertEquals(emptyList<FrozenApp>(), FrozenRecords.undo(mine, FrozenApp.NET + 0, null))
    }

    @Test fun cancellationIsRethrownAndOtherFailuresAreNot() {
        assertThrows(CancellationException::class.java) {
            runCatching<Unit> { throw CancellationException("stop") }.rethrowCancel()
        }
        assertTrue(runCatching<Unit> { error("boom") }.rethrowCancel().isFailure)
    }
}
