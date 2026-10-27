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
}
