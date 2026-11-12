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
}
