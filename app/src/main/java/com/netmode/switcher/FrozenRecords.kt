package com.netmode.switcher

import kotlinx.coroutines.CancellationException

/** Editing the restore records one change at a time; see AppFreezer.sideActions. */
internal object FrozenRecords {
    /** [entry] replaces whatever was on record for its package. */
    fun put(list: List<FrozenApp>, entry: FrozenApp): List<FrozenApp> =
        list.filterNot { it.pkg == entry.pkg } + entry

    /** Takes back a [put] of [pkg]: [earlier] was on record before it, if anything was. */
    fun undo(list: List<FrozenApp>, pkg: String, earlier: FrozenApp?): List<FrozenApp> =
        list.filterNot { it.pkg == pkg } + listOfNotNull(earlier)
}

/** For a runCatching around suspending work: cancellation is not a failure to carry on from. */
internal fun <T> Result<T>.rethrowCancel(): Result<T> = onFailure { if (it is CancellationException) throw it }
