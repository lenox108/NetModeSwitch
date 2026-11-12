package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class IceGoneTest {
    private val tg = "org.telegram.messenger@999"
    private val wa = "com.whatsapp@999"

    @Test fun installedCloneIsNotASuspect() {
        val out = "@U 999\npackage:org.telegram.messenger\r\npackage:com.whatsapp \n@E 999\n"
        assertEquals(emptyList<String>(), IceGone.suspects(out, listOf(tg, wa)))
    }

    @Test fun missingPackageIsASuspect() {
        val out = "@U 999\npackage:com.android.settings\npackage:org.telegram.messenger\n@E 999\n"
        assertEquals(listOf(wa), IceGone.suspects(out, listOf(tg, wa)))
    }

    @Test fun unreadOrCutListSaysNothing() {
        assertEquals(emptyList<String>(), IceGone.suspects("@U 999\n@E 999\n", listOf(wa)))
        assertEquals(emptyList<String>(), IceGone.suspects("@U 999\npackage:com.android.settings\n", listOf(wa)))
        assertEquals(emptyList<String>(), IceGone.suspects("", listOf(wa)))
    }

    @Test fun onlyAConfirmedMissIsGone() {
        assertEquals(setOf(wa), IceGone.gone("@N $wa\n@P $tg\n", listOf(tg, wa)))
        assertEquals(emptySet<String>(), IceGone.gone("", listOf(wa)))
    }

    @Test fun scriptsUseTheCloneUser() {
        assertTrue(IceGone.listScript(listOf(tg, wa)).contains("pm list packages --user 999"))
        assertTrue(IceGone.confirmScript(listOf(wa)).contains("pm path --user 999 com.whatsapp"))
    }

    @Test fun cloneStateIsReadFromItsUserLine() {
        val out = "@S $tg User 999: ceDataInode=1 installed=true hidden=false suspended=true distractionFlags=0 stopped=true notLaunched=false enabled=0 instant=false\n" +
            "@S $wa User 999: ceDataInode=2 installed=true hidden=false suspended=false stopped=true enabled=3 instant=false\n" +
            "@S com.vk.im@999 \n"
        val s = IceGone.states(out)
        assertEquals(IceMode.SOFT, s.getValue(tg).mode)
        assertTrue(s.getValue(tg).frozenAs(IceMode.SOFT))
        assertFalse(s.getValue(tg).frozenAs(IceMode.DEEP))
        assertEquals(IceMode.DEEP, s.getValue(wa).mode)
        assertFalse("unread state is unknown, not thawed", "com.vk.im@999" in s)
    }

    @Test fun thawedCloneHasNoMode() {
        val s = IceGone.states("@S $tg User 999: installed=true suspended=false enabled=0 instant=false")
        assertNull(s.getValue(tg).mode)
    }

    @Test fun retrySaysWhatPmAnswered() {
        assertTrue(IceGone.retryScript(listOf(tg to IceMode.SOFT)).contains("pm suspend --user 999 org.telegram.messenger"))
        assertTrue(IceGone.retryScript(listOf(wa to IceMode.DEEP)).contains("pm disable-user --user 999 com.whatsapp"))
        assertEquals(mapOf(tg to "Error: not allowed"), IceGone.said("@O $tg Error: not allowed \n"))
    }
}
