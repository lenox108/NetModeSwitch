package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleOrderTest {

    private fun rule(id: Long, name: String, on: Boolean = true, trigger: Trigger = Trigger.WIFI) = AppRule(
        id = id, enabled = on, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = trigger, name = name,
    )

    private val night = rule(1, "Ночь", trigger = Trigger.LOCKED)
    private val home = rule(2, "дом", on = false)
    private val car = rule(3, "Машина", trigger = Trigger.BLUETOOTH)
    private val all = listOf(night, home, car)

    private fun ids(l: List<AppRule>) = l.map { it.id }

    @Test fun byNameIgnoresCase() {
        assertEquals(listOf(2L, 3L, 1L), ids(RuleOrder.sort(all, RuleSort.NAME, emptyList()) { false }))
    }

    @Test fun activeFirstThenSwitchedOn() {
        assertEquals(listOf(3L, 1L, 2L), ids(RuleOrder.sort(all, RuleSort.ACTIVE, emptyList()) { it.id == 3L }))
    }

    @Test fun manualKeepsTheUsersOrderAndPutsNewOnesLast() {
        val withNew = all + rule(4, "Новое")
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(RuleOrder.sort(withNew, RuleSort.MANUAL, listOf(3L, 1L, 2L)) { false }))
    }

    @Test fun manualForgetsDeletedRules() {
        assertEquals(listOf(3L, 1L), ids(RuleOrder.sort(listOf(night, car), RuleSort.MANUAL, listOf(3L, 2L, 1L)) { false }))
    }

    @Test fun moveShiftsTheOthers() {
        assertEquals(listOf(2L, 3L, 1L), RuleOrder.move(listOf(1L, 2L, 3L), 0, 2))
        assertEquals(listOf(3L, 1L, 2L), RuleOrder.move(listOf(1L, 2L, 3L), 2, 0))
    }
}
