package com.netmode.switcher

import com.netmode.switcher.JournalEntry.Severity
import org.junit.Assert.*
import org.junit.Test

class JournalTest {
    @Test fun aRuleLineGivesItsTimeNameAndText() {
        val e = Journal.parse("01.10 01:00  («Ночь» расписание 01:00–07:00): SIM 1 → 3G, Bluetooth выключен")
        assertEquals("01.10 01:00", e.time)
        assertEquals("Ночь", e.group)
        assertEquals("SIM 1 → 3G, Bluetooth выключен", e.text)
        assertEquals(Severity.DONE, e.severity)
    }

    @Test fun anUnnamedRuleIsItsConditionAtStartAndEnd() {
        val start = Journal.parse("30.10 10:11  (точка доступа включена): Happ выкл")
        val end = Journal.parse("30.10 10:11  (точка доступа включена — больше нет): возвращено как было (1)")
        assertEquals("точка доступа включена", start.group)
        assertEquals(start.group, end.group)
    }

    @Test fun appsLineAndNestedBracketsKeepTheWholeLabel() {
        val apps = Journal.parse("01.10 01:00  приложения («Ночь» расписание): остановить — 1")
        assertEquals("Ночь", apps.group)
        assertEquals("остановить — 1", apps.text)
        val nested = Journal.parse("01.10 01:00  (Wi-Fi (дом) найден): готово")
        assertEquals("Wi-Fi (дом) найден", nested.group)
    }

    @Test fun theSkippedTileFromTheRealLogIsAnError() {
        val e = Journal.parse("30.10 10:11  (точка доступа включена · через VPN): Happ · Переключить выкл, " +
            "VPN AmneziaVPN: состояние неизвестно (VPN-сеть без владельца (OwnerUid нет, uid 10409)), нажатие пропущено")
        assertEquals(Severity.ERROR, e.severity)
        assertTrue(e.problem)
    }

    @Test fun aSkipIsAWarningAndPlainLinesAreInfo() {
        assertEquals(Severity.WARNING, Journal.parse("30.10 10:11  (Машина): в конце — VPN не подключён — пропущено").severity)
        assertEquals(Severity.INFO, Journal.parse("30.10 10:11  раздача через VPN (tun0)").severity)
        assertEquals(Severity.ERROR, Journal.parse("30.10 10:11  раздача через VPN: VPN не подключён — раздача заблокирована").severity)
    }

    @Test fun aRuleOrNetworkNameCannotFakeAProblem() {
        assertEquals(Severity.DONE, Journal.parse("30.10 10:11  («Ошибка» расписание): SIM 1 → 3G").severity)
        assertEquals(Severity.INFO, Journal.parse("30.10 10:11  подключено к «не удалось»").severity)
    }

    @Test fun unusualLinesSurviveWhole() {
        for (raw in listOf("", "без времени", "01.10 01:00  (нет закрывающей", "01.10 01:00  (не метка) и текст")) {
            val e = Journal.parse(raw)
            assertNull(raw, e.group)
            assertTrue(raw, raw.endsWith(e.text))
        }
    }

    @Test fun filtersOfferRulesNewestFirstOnce() {
        val entries = listOf("(«Б»): x", "(«А»): y", "(«Б»): z", "раздача").map { Journal.parse("01.10 01:00  $it") }
        assertEquals(listOf("Б", "А"), Journal.groups(entries))
    }

    @Test fun onlyErrorsNewerThanTheSeenLineCount() {
        val entries = listOf("новая ошибка", "(«А»): готово", "старая ошибка").map { Journal.parse("01.10 01:00  $it") }
        assertEquals(1, Journal.unseenErrors(entries, entries[2].raw).size)
        assertEquals(2, Journal.unseenErrors(entries, null).size)
        assertTrue(Journal.unseenErrors(entries, entries[0].raw).isEmpty())
    }
}
