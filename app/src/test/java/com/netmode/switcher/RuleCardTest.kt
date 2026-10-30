package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RuleCardTest {
    private val now = ZonedDateTime.of(2026, 10, 30, 22, 0, 0, 0, ZoneId.of("Europe/Moscow"))

    @Test fun nextRunIsSaidInPlainWords() {
        assertEquals("сегодня в 23:30", RuleCard.nextLabel(now.withHour(23).withMinute(30), now))
        assertEquals("завтра в 01:00", RuleCard.nextLabel(now.plusDays(1).withHour(1).withMinute(0), now))
        assertTrue(RuleCard.nextLabel(now.plusDays(3).withHour(1).withMinute(0), now).contains("02.11 в 01:00"))
    }

    @Test fun theLastEntryOfARuleIsItsNewest() {
        val entries = listOf("(«Б»): ошибка", "(«А»): готово", "(«Б»): готово").map { Journal.parse("01.10 01:00  $it") }
        assertEquals(entries[0], RuleCard.lastOf(entries, "Б"))
        assertNull(RuleCard.lastOf(entries, "В"))
    }

    @Test fun groupsIgnoreCaseAndBlanks() {
        assertEquals(listOf("Дом", "Машина"), RuleCard.groups(listOf(" Дом", "дом", null, "", "Машина")))
        assertTrue(RuleCard.inGroup("дом ", "Дом"))
        assertFalse(RuleCard.inGroup(null, "Дом"))
        assertTrue(RuleCard.inGroup(null, null))
    }

    @Test fun everyTriggerIsInExactlyOneSection() {
        val offered = Trigger.entries.filter { it != Trigger.WIFI_LEFT }
        val shown = RuleCard.sections(offered, "") { it.name }.flatMap { it.second }
        assertEquals(offered.size, shown.size)
        assertEquals(offered.toSet(), shown.toSet())
    }

    @Test fun searchKeepsOnlyMatchesAndEmptySectionsGo() {
        val offered = Trigger.entries.filter { it != Trigger.WIFI_LEFT }
        val found = RuleCard.sections(offered, " wifi ") { it.name.replace("_", "") }
        assertEquals(1, found.size)
        assertEquals(listOf(Trigger.WIFI, Trigger.WIFI_OTHER), found.single().second)
    }

    private fun rule(vpn: Toggle? = null, shell: String? = null, http: String? = null) = AppRule(
        id = 1, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.MANUAL, vpn = vpn, shell = shell, http = http,
    )

    @Test fun actionIconsFollowWhatTheRuleDoes() {
        assertTrue(RuleCard.actionIcons(rule()).isEmpty())
        assertEquals(listOf(R.drawable.ic_a_vpn), RuleCard.actionIcons(rule(vpn = Toggle.ON)))
    }

    @Test fun dayPresetsCoverTheWeekOnce() {
        assertEquals(0b1111111, RuleCard.WEEKDAYS or RuleCard.WEEKEND)
        assertEquals(0, RuleCard.WEEKDAYS and RuleCard.WEEKEND)
    }

    @Test fun commandsAndRequestsOfASharedRuleAreSpelledOut() {
        assertTrue(RuleShare.risks(rule()).isEmpty())
        val risks = RuleShare.risks(rule(shell = "rm -rf /sdcard", http = "https://example.com"))
        assertEquals(2, risks.size)
        assertTrue(risks[0].contains("rm -rf /sdcard"))
        assertTrue(risks[1].contains("https://example.com"))
    }

    @Test fun todayCountsOnlyThatDate() {
        val entries = listOf("30.10 09:00  («А»): готово", "30.10 08:00  («А»): ошибка", "29.10 09:00  («А»): готово")
            .map(Journal::parse)
        assertEquals(1 to 1, Journal.today(entries, "30.10"))
    }
}
