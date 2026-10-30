package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class NotifRulePolicyTest {
    @Test fun anUntitledNotificationMatchesTheTextTheUserSelected() {
        assertTrue(NotifRulePolicy.matches("подключено", false, emptyList(), "", "VPN подключено", true))
    }

    @Test fun aTitledNotificationDoesNotUnexpectedlyMatchWordsFromItsBody() {
        assertFalse(NotifRulePolicy.matches("VPN", false, emptyList(), "Новое сообщение", "Отключи VPN", false))
    }

    @Test fun matchingIsCaseInsensitiveForRussianText() {
        assertTrue(NotifRulePolicy.matches("ПОДКЛЮЧЕНО", false, emptyList(), "VPN подключено", "", true))
    }

    @Test fun ongoingFilterLeavesOrdinaryMessagesVisible() {
        assertFalse(NotifRulePolicy.matches(null, true, emptyList(), "Сообщение", "", false))
        assertTrue(NotifRulePolicy.matches(null, true, emptyList(), "VPN", "", true))
    }

    @Test fun exceptionsProtectBothTitleAndMessageBody() {
        assertFalse(NotifRulePolicy.matches(null, false, listOf("важно"), "ВАЖНО", "", false))
        assertFalse(NotifRulePolicy.matches(null, false, listOf("код"), "Банк", "Ваш КОД: 1234", false))
        assertTrue(NotifRulePolicy.matches(null, false, listOf("код"), "Банк", "Акция", false))
    }

    @Test fun onlyAnOrdinaryNotificationCanBeClosedForGood() {
        assertTrue(NotifRulePolicy.dismissable(0))
        assertFalse(NotifRulePolicy.dismissable(android.app.Notification.FLAG_ONGOING_EVENT))
        assertFalse(NotifRulePolicy.dismissable(android.app.Notification.FLAG_FOREGROUND_SERVICE))
        assertFalse(NotifRulePolicy.dismissable(android.app.Notification.FLAG_NO_CLEAR))
    }

    @Test fun disabledRulesAndPausesRespectTheExactDeadline() {
        assertFalse(NotifRulePolicy.active(false, 0, 100))
        assertFalse(NotifRulePolicy.active(true, 101, 100))
        assertTrue(NotifRulePolicy.active(true, 100, 100))
        assertTrue(NotifRulePolicy.active(true, 0, 100))
    }

    @Test fun blankExceptionsCannotDisableEveryRuleAndDuplicatesAreMerged() {
        assertEquals(listOf("Код", "важно"), NotifRulePolicy.exclusions("\n Код \nкод\n\nважно\n"))
        assertTrue(NotifRulePolicy.matches(null, false, listOf(" "), "VPN", "", true))
    }

    @Test fun aForegroundServiceCountsAsOngoingWithoutTheOngoingFlag() {
        assertTrue(NotifRulePolicy.ongoing(android.app.Notification.FLAG_FOREGROUND_SERVICE))
        assertTrue(NotifRulePolicy.ongoing(android.app.Notification.FLAG_ONGOING_EVENT))
        assertFalse(NotifRulePolicy.ongoing(android.app.Notification.FLAG_GROUP_SUMMARY))
    }

    @Test fun aSummaryIsNotHiddenWhileASiblingHasToStayVisible() {
        assertFalse(NotifRulePolicy.mayHide(true, listOf(true, false)))
        assertTrue(NotifRulePolicy.mayHide(true, listOf(true, true)))
        assertTrue(NotifRulePolicy.mayHide(true, emptyList()))
        assertTrue(NotifRulePolicy.mayHide(false, listOf(false)))
    }

    @Test fun onlyAMissingChannelCountsAsGone() {
        assertTrue(NotifRulePolicy.channelGone("error=IllegalStateException: нет канала vpn"))
        assertFalse(NotifRulePolicy.channelGone("error=SecurityException: denied"))
    }

    @Test fun aKeyNotSnoozedNowStaysPendingUntilItsTimeRunsOut() {
        val pending = mapOf("waiting" to 90L, "old" to 10L, "shown" to 90L, "kept" to 90L, "done" to 90L, "failed" to 90L)
        val next = NotifRulePolicy.nextPending(pending, setOf("kept"), setOf("done", "failed"), setOf("failed"),
            setOf("shown"), now = 100, ttl = 50, max = 10)
        assertEquals(setOf("waiting", "failed"), next.keys)
    }

    @Test fun pendingKeysAreBoundedKeepingTheNewest() {
        val next = NotifRulePolicy.nextPending(mapOf("a" to 1L, "b" to 2L, "c" to 3L), emptySet(), emptySet(), emptySet(),
            emptySet(), now = 4, ttl = 100, max = 2)
        assertEquals(setOf("b", "c"), next.keys)
    }
}
