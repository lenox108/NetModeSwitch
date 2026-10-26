package com.netmode.switcher

/** A mixed freezer has a definite next action: finish freezing it. Empty is not "all frozen". */
enum class IceTileState {
    EMPTY, FROZEN, THAWED, MIXED;

    val freezeOnClick: Boolean get() = this != FROZEN

    companion object {
        fun of(apps: List<IceApp>): IceTileState = when {
            apps.isEmpty() -> EMPTY
            apps.all { it.frozen } -> FROZEN
            apps.none { it.frozen } -> THAWED
            else -> MIXED
        }
    }
}
