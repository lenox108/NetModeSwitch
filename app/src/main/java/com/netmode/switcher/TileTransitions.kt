package com.netmode.switcher

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A tile is a toggle. An uncertain observation or a timeout must never cause a blind click. */
internal class TileTransitions {
    enum class Outcome { ALREADY, CHANGED, UNKNOWN, UNCONFIRMED }
    data class Result(val component: String, val outcome: Outcome, val added: Boolean = false)
    private val mutex = Mutex()

    suspend fun set(
        wanted: Map<String, Boolean>,
        read: suspend (String) -> Boolean?,
        prepare: suspend (String) -> Boolean,
        click: suspend (String) -> Unit,
        await: suspend (String, Boolean) -> Boolean,
        before: (String, Boolean) -> Unit = { _, _ -> },
    ): List<Result> = mutex.withLock {
        wanted.map { (component, target) ->
            val initial = read(component)
            if (initial == null) return@map Result(component, Outcome.UNKNOWN)
            if (initial == target) {
                before(component, initial)
                return@map Result(component, Outcome.ALREADY)
            }
            val added = prepare(component)
            // Adding/binding a tile takes time. A manual action may have changed its state.
            val current = read(component) ?: return@map Result(component, Outcome.UNKNOWN, added)
            before(component, current)
            if (current == target) return@map Result(component, Outcome.ALREADY, added)
            click(component)
            Result(component, if (await(component, target)) Outcome.CHANGED else Outcome.UNCONFIRMED, added)
        }
    }
}
