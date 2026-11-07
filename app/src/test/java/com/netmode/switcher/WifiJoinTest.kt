package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiJoinTest {

    private fun rule(id: Long = 1, join: WifiTarget? = null, wifi: Toggle? = null) = AppRule(
        id = id, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.MANUAL,
        releaseOnLeave = false, join = join, wifi = wifi,
    )

    @Test fun bssidIsNormalized() {
        assertEquals("aa:bb:cc:dd:ee:0f", WifiTarget.normalizeBssid(" AA-BB-CC-DD-EE-0F "))
        assertEquals("aa:bb:cc:dd:ee:0f", WifiTarget.normalizeBssid("aabbccddee0f"))
        assertNull(WifiTarget.normalizeBssid("aa:bb:cc:dd:ee"))
        assertNull(WifiTarget.normalizeBssid("gg:bb:cc:dd:ee:ff"))
    }

    @Test fun joinAloneIsAnActionAndListedInOrder() {
        val r = rule(join = WifiTarget("Дом", "aa:bb:cc:dd:ee:ff"))
        assertTrue(r.hasActions)
        assertEquals("Wi-Fi → «Дом» (aa:bb:cc:dd:ee:ff)", r.actionLabel)
        assertTrue(Steps.START_ORDER.indexOf("JOIN") > Steps.START_ORDER.indexOf("WIFI"))
    }

    @Test fun joinClashesWithWifiOffAndWithAnotherNetwork() {
        val home = rule(id = 1, join = WifiTarget("Home"))
        val off = rule(id = 2, wifi = Toggle.OFF)
        val work = rule(id = 3, join = WifiTarget("Work"))
        val sameHome = rule(id = 4, join = WifiTarget("home"))
        assertEquals(listOf(off, work), home.conflicts(listOf(off, work, sameHome)).map { it.first })
    }

    @Test fun answerIgnoresTheShellsOwnLines() {
        assertEquals("ok", WifiJoin.answer("Decrypt success\nok\n"))
        assertEquals("ok", WifiJoin.answer("ok\nDecrypt success\n"))
        assertEquals("error=not saved", WifiJoin.answer("Decrypt success\r\nerror=not saved\r\n"))
        assertEquals("error=нет ответа: Decrypt success", WifiJoin.answer("Decrypt success\n"))
        assertEquals("error=нет ответа: пусто", WifiJoin.answer(""))
    }
}
