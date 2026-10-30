package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class BackupReviewTest {
    private fun rule(id: Long = 1, shell: String? = null, http: String? = null, endShell: String? = null,
                     endHttp: String? = null, unlock: EndSpec? = null) = AppRule(
        id = id, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, name = "r$id",
        shell = shell, http = http, endShell = endShell, endHttp = endHttp, unlockEnd = unlock,
    )

    @Test fun everyCommandAndRequestIsShownInFull() {
        val text = Backup.outsideSummary(listOf(rule(
            shell = "rm -rf /sdcard", http = "POST https://a.example", endShell = "id; whoami",
            endHttp = "https://b.example", unlock = EndSpec(shell = "reboot", http = "https://c.example"),
        )))
        listOf("rm -rf /sdcard", "POST https://a.example", "id; whoami", "https://b.example", "reboot", "https://c.example")
            .forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun aRuleWithNothingOutsideIsNotListed() {
        assertEquals("", Backup.outsideSummary(listOf(rule())))
    }

    @Test fun onlyRulesWithOutsideActionsComeInOff() {
        val held = Backup.held(listOf(rule(1), rule(2, endHttp = "https://x.example"), rule(3, unlock = EndSpec(shell = "id"))))
        assertEquals(listOf(true, false, false), held.map { it.enabled })
    }
}
