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

    /** After the [n]th refused freeze in a row: 1, 5, 15, 30 min, then hourly. */
    fun failRetryMs(n: Int): Long = when {
        n <= 1 -> 60_000L
        n == 2 -> 5 * 60_000L
        n == 3 -> 15 * 60_000L
        n == 4 -> 30 * 60_000L
        else -> 60 * 60_000L
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

/**
 * Whether a freezer clone is still installed in its user. Clones are out of this app's own
 * PackageManager, so the shell lists each user's packages and Kotlin compares them; a miss is
 * then confirmed with `pm path`, so one odd answer never takes a clone off the list.
 */
object IceGone {
    /** "@U user", that user's `pm list packages` lines, "@E user" — per user of [keys]. */
    fun listScript(keys: List<String>): String = buildString {
        for (u in keys.map { AppKey.user(it) }.distinct()) {
            appendLine("echo '@U $u'")
            appendLine("pm list packages --user $u 2>/dev/null")
            appendLine("echo '@E $u'")
        }
    }

    /**
     * Keys whose package is missing from their user's list. A user whose list was not read
     * through to its end marker, or came back empty, says nothing.
     */
    fun suspects(out: String, keys: List<String>): List<String> {
        val lists = mutableMapOf<Int, MutableSet<String>>()
        val complete = mutableSetOf<Int>()
        var current: Int? = null
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("@U ") -> current = line.removePrefix("@U ").trim().toIntOrNull()?.also { lists.getOrPut(it) { mutableSetOf() } }
                line.startsWith("@E ") -> {
                    if (current != null && line.removePrefix("@E ").trim().toIntOrNull() == current) complete += current
                    current = null
                }
                current != null && line.startsWith("package:") ->
                    lists.getValue(current) += line.removePrefix("package:").substringBefore(' ').substringBefore('=').trim()
            }
        }
        return keys.filter { k ->
            val u = AppKey.user(k)
            val have = lists[u]
            u in complete && !have.isNullOrEmpty() && AppKey.pkg(k) !in have
        }
    }

    /** "@P key" for each of [keys] the system still has an APK path for in its user. */
    fun confirmScript(keys: List<String>): String = buildString {
        val d = "$"
        for (k in keys) {
            appendLine("case \"$d(pm path --user ${AppKey.user(k)} ${AppKey.pkg(k)} 2>/dev/null)\" in *package:*) echo '@P $k' ;; *) echo '@N $k' ;; esac")
        }
    }

    /** A clone's freeze as `dumpsys package` shows it in its user. */
    data class State(val suspended: Boolean, val disabled: Boolean) {
        /** Disabled wins: a disabled app is frozen the deep way whatever else it has. */
        val mode: IceMode? get() = when {
            disabled -> IceMode.DEEP
            suspended -> IceMode.SOFT
            else -> null
        }

        fun frozenAs(want: IceMode): Boolean = if (want == IceMode.DEEP) disabled else suspended
    }

    /** "@S key <that user's line of dumpsys package>" for each of [keys]. */
    fun stateScript(keys: List<String>): String = buildString {
        val d = "$"
        for (k in keys) {
            appendLine("echo \"@S $k $d(dumpsys package ${AppKey.pkg(k)} 2>/dev/null | grep -m1 -E '^ *User ${AppKey.user(k)}: .*installed=')\"")
        }
    }

    /**
     * Key to state, for each key whose user line was found ("User 999: … installed=true …
     * suspended=true … enabled=3 …"). enabled 2 and 3 are disabled and disabled-user.
     */
    fun states(out: String): Map<String, State> = out.lineSequence().map { it.trim() }
        .filter { it.startsWith("@S ") && "installed=" in it }
        .associate { line ->
            val key = line.removePrefix("@S ").substringBefore(' ')
            val enabled = Regex("\\benabled=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            key to State(suspended = "suspended=true" in line, disabled = enabled == 2 || enabled == 3)
        }

    /** One more try through pm alone: "@O key what pm said" for each. */
    fun retryScript(targets: List<Pair<String, IceMode>>): String = buildString {
        val d = "$"
        for ((k, mode) in targets) {
            val cmd = if (mode == IceMode.DEEP) "pm disable-user" else "pm suspend"
            appendLine("echo \"@O $k $d($cmd --user ${AppKey.user(k)} ${AppKey.pkg(k)} 2>&1 | tr '\\n' ' ')\"")
            appendLine("am force-stop --user ${AppKey.user(k)} ${AppKey.pkg(k)} >/dev/null 2>&1")
        }
    }

    fun said(out: String): Map<String, String> = out.lineSequence().map { it.trim() }
        .filter { it.startsWith("@O ") }
        .associate { it.removePrefix("@O ").substringBefore(' ') to it.substringAfter(' ').substringAfter(' ', "").trim() }

    /** Keys the confirmation answered for and found no path for. */
    fun gone(out: String, keys: List<String>): Set<String> {
        val lines = out.lineSequence().map { it.trim() }.toSet()
        return keys.filter { "@N $it" in lines && "@P $it" !in lines }.toSet()
    }
}
