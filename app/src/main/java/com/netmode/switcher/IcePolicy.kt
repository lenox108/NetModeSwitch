package com.netmode.switcher

/** Pure scheduling decisions shared by the alarm, screen-off handler and status display. */
object IcePolicy {
    fun protected(a: IceApp, now: Long): Boolean = !a.frozen && (a.keepUntil == -1L || a.keepUntil > now)

    fun deadline(a: IceApp, defaultDelay: Int, lastSeen: Long = 0L): Long? {
        if (a.frozen || a.heldBy != 0L || a.keepUntil == -1L) return null
        if (a.keepUntil > 0L) return a.keepUntil
        val minutes = a.delay ?: defaultDelay
        if (minutes < 0) return null
        return maxOf(a.thawedAt, lastSeen) + minutes * 60_000L
    }

    fun sameState(a: IceApp, b: IceApp): Boolean =
        a.frozenAs == b.frozenAs && a.thawedAt == b.thawedAt && a.heldBy == b.heldBy &&
            a.keepUntil == b.keepUntil && a.launchedAt == b.launchedAt && a.mode == b.mode
}

enum class IceSort(val title: Int) {
    NAME(R.string.sort_name), NEWEST(R.string.ice_sort_newest), OLDEST(R.string.ice_sort_oldest),
    LAUNCHED(R.string.ice_sort_launched),
    STATE(R.string.ice_sort_state), MANUAL(R.string.sort_manual)
}

object IceOrder {
    fun sort(apps: List<IceApp>, sort: IceSort, order: List<String>, label: (String) -> String): List<IceApp> {
        val positions = order.withIndex().associate { it.value to it.index }
        // Older versions kept insertion order but did not record the date. Use that order
        // for legacy apps and apps added in the same batch; freezing never changes it.
        val addedPositions = apps.withIndex().associate { it.value.key to it.index }
        val base = compareBy<IceApp> { !it.favorite }
        val comparator = when (sort) {
            IceSort.NAME -> base.thenBy { label(it.key).lowercase() }
            IceSort.NEWEST -> base.thenByDescending { it.addedAt }.thenByDescending { addedPositions[it.key] }
            IceSort.OLDEST -> base.thenBy { it.addedAt }.thenBy { addedPositions[it.key] }
            IceSort.LAUNCHED -> base.thenByDescending { it.launchedAt }.thenBy { label(it.key).lowercase() }
            IceSort.STATE -> base.thenBy { it.frozen }.thenBy { label(it.key).lowercase() }
            IceSort.MANUAL -> base.thenBy { positions[it.key] ?: Int.MAX_VALUE }
        }
        return apps.sortedWith(comparator)
    }

    /** Only apps with a deadline, nearest first. This order replaces the chosen sort, favorites included. */
    fun soonest(apps: List<IceApp>, deadline: (IceApp) -> Long?, label: (String) -> String): List<IceApp> =
        apps.mapNotNull { a -> deadline(a)?.let { a to it } }
            .sortedWith(compareBy({ it.second }, { label(it.first.key).lowercase() }))
            .map { it.first }

    /** Keeps the order seen when the folder opened, so icons stay put; favorites stay first, new apps go last. */
    fun settled(sorted: List<IceApp>, order: List<String>): List<IceApp> {
        val positions = order.withIndex().associate { it.value to it.index }
        return sorted.sortedWith(compareBy<IceApp> { !it.favorite }.thenBy { positions[it.key] ?: Int.MAX_VALUE })
    }

    fun move(keys: List<String>, from: Int, to: Int): List<String> =
        keys.toMutableList().apply { add(to, removeAt(from)) }
}
