package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class IceGroupsTest {
    private val work = IceApp("app.work", group = "Работа", frozenAs = IceMode.SOFT)
    private val workAwake = IceApp("app.work.awake", group = "Работа")
    private val personal = IceApp("app.personal", group = "Личное", frozenAs = IceMode.DEEP)
    private val ungrouped = IceApp("app.other")
    private val apps = listOf(work, personal, ungrouped, workAwake)

    @Test fun selectedGroupIncludesBothFrozenAndThawedAppsAndExcludesOtherGroups() {
        assertEquals(listOf(work, workAwake), IceGroupSelection("Работа").apps(apps))
        assertEquals(apps, IceGroupSelection().apps(apps))
        assertEquals(listOf(ungrouped), IceGroupSelection(ungrouped = true).apps(apps))
    }

    @Test fun ordinaryGroupNamesCannotCollideWithSpecialFilterLabels() {
        val named = ungrouped.copy(key = "app.named", group = "Без группы")
        assertEquals(listOf(named), IceGroupSelection("Без группы").apps(apps + named))
        assertEquals(listOf(ungrouped), IceGroupSelection(ungrouped = true).apps(apps + named))
    }

    @Test fun shortcutSelectionUsesCurrentMembersInsteadOfASavedAppSnapshot() {
        val selection = IceGroupSelection("Работа")
        val moved = personal.copy(group = "Работа")
        assertEquals(listOf(workAwake, moved), selection.apps(listOf(work.copy(group = null), workAwake, moved)))
        assertTrue(selection.apps(emptyList()).isEmpty())
    }

    @Test fun thawedKeysAreOnlyThawedMembersOfTheSelection() {
        assertEquals(listOf("app.work.awake"), IceGroups.thawedKeys(IceGroupSelection("Работа"), apps))
        assertEquals(listOf("app.other"), IceGroups.thawedKeys(IceGroupSelection(ungrouped = true), apps))
        assertEquals(listOf("app.other", "app.work.awake"), IceGroups.thawedKeys(IceGroupSelection(), apps))
    }

    @Test fun thawedKeysAreEmptyWhenNothingIsThawedOrTheGroupIsUnknown() {
        assertTrue(IceGroups.thawedKeys(IceGroupSelection("Личное"), apps).isEmpty())
        assertTrue(IceGroups.thawedKeys(IceGroupSelection("Нет такой"), apps).isEmpty())
        assertTrue(IceGroups.thawedKeys(IceGroupSelection("Работа"), emptyList()).isEmpty())
    }

    @Test fun thawedKeysSkipWhatTheFreezerWouldRefuse() {
        val clone = workAwake.copy(key = "app.work.awake@10")
        val broken = workAwake.copy(key = "not a key")
        val list = listOf(workAwake, clone, broken)
        // A guarded package covers its clones too, matching freezeLocked.
        assertTrue(IceGroups.thawedKeys(IceGroupSelection("Работа"), list, setOf("app.work.awake")).isEmpty())
        assertEquals(listOf("app.work.awake", "app.work.awake@10"), IceGroups.thawedKeys(IceGroupSelection("Работа"), list))
    }

    @Test fun savedOrderKeepsEmptyGroupsAndAppOnlyLegacyGroupsFollowIt() {
        assertEquals(listOf("Пустая", "Личное", "Работа"), IceGroups.ordered(listOf("Пустая", "Личное", "Личное"), apps))
        assertEquals(listOf("Работа", "Личное"), IceGroups.ordered(emptyList(), apps))
    }

    @Test fun savingOrderDoesNotReviveDeletedGroupsOrLoseNewGroups() {
        assertEquals(listOf("Личное", "Работа", "Новая"),
            IceGroups.reconcile(listOf("Личное", "Удалена", "Работа"), listOf("Работа", "Личное", "Новая")))
    }

    @Test fun reorderingChipsDoesNotReorderAppsOrChangeTheMembership() {
        val order = IceOrder.move(listOf("Работа", "Личное"), 1, 0)
        assertEquals(listOf("Личное", "Работа"), IceGroups.ordered(order, apps))
        assertEquals(listOf(work, workAwake), IceGroupSelection("Работа").apps(apps))
    }
}
