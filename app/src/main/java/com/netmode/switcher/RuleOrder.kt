package com.netmode.switcher

/** How the list of rules is ordered (see [AppRulesActivity]). */
enum class RuleSort(val label: Int) {
    /** Switched-off ones last, then by kind and time — as the list always was. */
    TYPE(R.string.sort_type),
    NAME(R.string.sort_name),
    /** Working right now first, then the switched-on ones. */
    ACTIVE(R.string.sort_active),
    /** The user's own order, dragged by hand; rules not placed yet go last, newest last. */
    MANUAL(R.string.sort_manual),
}

object RuleOrder {

    fun title(rule: AppRule): String = rule.name ?: rule.timeLabel

    fun sort(rules: List<AppRule>, by: RuleSort, order: List<Long>, active: (AppRule) -> Boolean): List<AppRule> = when (by) {
        RuleSort.TYPE -> rules.sortedWith(compareBy({ !it.enabled }, { it.trigger.ordinal }, { it.hour }, { it.minute }))
        RuleSort.NAME -> rules.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { title(it) })
        RuleSort.ACTIVE -> rules.sortedWith(compareBy({ !active(it) }, { !it.enabled }, { title(it).lowercase() }))
        RuleSort.MANUAL -> {
            val place = order.withIndex().associate { it.value to it.index }
            rules.withIndex().sortedWith(compareBy({ place[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        }
    }

    /** [ids] with the one at [from] moved to [to]. */
    fun move(ids: List<Long>, from: Int, to: Int): List<Long> =
        ids.toMutableList().apply { add(to, removeAt(from)) }
}
