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

    /** Preserve additions made while the order dialog was open, without reviving deleted groups. */
    fun reconcile(order: List<String>, current: List<String>): List<String> =
        (order.filter { it in current } + current).distinct()
}

enum class IceGroupShortcutAction { OPEN, FREEZE, THAW }
