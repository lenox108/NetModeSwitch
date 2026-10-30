package com.netmode.switcher

/** A null name means all groups; ungrouped is a separate choice, never a magic group name. */
data class IceGroupSelection(val name: String? = null, val ungrouped: Boolean = false) {
    val isAll: Boolean get() = name == null && !ungrouped
    fun contains(app: IceApp): Boolean = if (ungrouped) app.group == null else name == null || app.group == name
    fun apps(all: List<IceApp>): List<IceApp> = all.filter(::contains)
}

object IceGroups {
    fun ordered(saved: List<String>, apps: List<IceApp>): List<String> =
        (saved + apps.mapNotNull { it.group }).distinct()

    /** What a group freeze can act on: thawed members minus the same exclusions freezeLocked applies. */
    fun thawedKeys(selection: IceGroupSelection, all: List<IceApp>, guarded: Set<String> = emptySet()): List<String> =
        selection.apps(all).filter { !it.frozen && AppKey.valid(it.key) && AppKey.pkg(it.key) !in guarded }.map { it.key }

    /** Preserve additions made while the order dialog was open, without reviving deleted groups. */
    fun reconcile(order: List<String>, current: List<String>): List<String> =
        (order.filter { it in current } + current).distinct()
}

enum class IceGroupShortcutAction { OPEN, FREEZE, THAW }
