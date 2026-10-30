package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class EventLogTest {
    private fun log(max: Int = 1000): EventLog {
        var stored = emptyList<String>()
        return EventLog(max, read = {
            // A wide gap between read and write, where unlocked adds would overwrite each other.
            stored.also { Thread.yield() }
        }, write = { stored = it })
    }

    @Test fun noLineIsLostAcrossThreads() {
        val log = log()
        (0 until 8).map { t -> thread { repeat(100) { log.add("$t-$it") } } }.forEach { it.join() }
        assertEquals(800, log.lines().toSet().size)
    }

    @Test fun newestFirstAndCapped() {
        val log = log(max = 2)
        listOf("a", "b", "c").forEach { log.add(it) }
        assertEquals(listOf("c", "b"), log.lines())
    }

    @Test fun clearEmpties() {
        val log = log()
        log.add("a")
        log.clear()
        assertEquals(emptyList<String>(), log.lines())
    }
}
