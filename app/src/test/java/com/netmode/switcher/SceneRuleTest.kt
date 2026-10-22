package com.netmode.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SceneRuleTest {

    private fun scene(minutes: Int = 0, toggle: Boolean = true) = AppRule(
        id = 1, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.MANUAL,
        releaseOnLeave = toggle, level = minutes, vpn = Toggle.ON, vpnApp = "com.example.vpn",
    )

    private val start = ZonedDateTime.of(2026, 10, 22, 23, 30, 0, 0, ZoneId.of("Europe/Samara"))

    @Test fun timedSceneEndsAfterItsMinutes() {
        assertEquals(start.plusHours(1), scene(minutes = 60).manualEndAfter(start))
    }

    @Test fun untimedOrOneShotSceneHasNoEnd() {
        assertNull(scene(minutes = 0).manualEndAfter(start))
        assertNull(scene(minutes = 60, toggle = false).manualEndAfter(start))
    }

    @Test fun otherTriggersIgnoreTheSceneTimer() {
        assertNull(scene(minutes = 60).copy(trigger = Trigger.BATTERY).manualEndAfter(start))
    }

    @Test fun labels() {
        assertEquals("сцена на 1 ч", scene(minutes = 60).timeLabel)
        assertEquals("сцена · вкл/выкл", scene().timeLabel)
        assertEquals("сцена", scene(toggle = false).timeLabel)
    }

    @Test fun reconnectAloneIsAnActionAndListedInOrder() {
        val r = scene(toggle = false).copy(vpn = null, reconnect = true)
        assertTrue(r.hasActions)
        assertEquals("переподключить моб. сеть", r.actionLabel)
        assertTrue("RECONNECT" in Steps.START_ORDER)
    }

    @Test fun sceneOfOnlyAnEndIsARule() {
        val endOnly = scene().copy(vpn = null, endCustom = true, endVpn = Toggle.OFF)
        assertTrue(endOnly.hasActions)
        assertFalse(endOnly.copy(endCustom = false).hasActions)
    }
}

class ConflictTest {
    private fun rule(id: Long, net: NetMode?, wifi: Toggle? = null) = AppRule(
        id = id, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null, days = 0,
        packages = emptyList(), method = FreezeMethod.STOP, netMode = net, wifi = wifi,
    )

    @Test fun differentValuesClashSameValuesDoNot() {
        val a = rule(1, NetMode.THREE_G, Toggle.OFF)
        val c = a.conflicts(listOf(rule(2, NetMode.FULL, Toggle.OFF), rule(3, NetMode.THREE_G)))
        assertEquals(1, c.size)
        assertEquals(2L, c[0].first.id)
        assertEquals(listOf("режим сети"), c[0].second)
    }
}

class SysSettingsTest {
    @Test fun labelsAndSummary() {
        assertEquals("вибрация", SysKey.RINGER.optionLabel("vibrate"))
        assertEquals("2 мин", SysKey.TIMEOUT.optionLabel("120"))
        assertEquals("как было", SysKey.BRIGHT.optionLabel("m:120"))
        assertEquals("звонок: без звука, яркость: авто", SysKey.summary(linkedMapOf("RINGER" to "silent", "BRIGHT" to "auto")))
    }

    @Test fun settingsClashByName() {
        fun r(id: Long, v: String) = AppRule(id = id, enabled = true, hour = 0, minute = 0, endHour = null, endMinute = null,
            days = 0, packages = emptyList(), method = FreezeMethod.STOP, sys = mapOf("BRIGHT" to v))
        assertEquals(listOf("яркость"), r(1, "auto").conflicts(listOf(r(2, "50")))[0].second)
    }
}

class NewTriggerLabelTest {
    private fun r(t: Trigger, level: Int = 0, month: Boolean = false) = AppRule(id = 1, enabled = true, hour = 0, minute = 0,
        endHour = null, endMinute = null, days = 0, packages = emptyList(), method = FreezeMethod.STOP,
        trigger = t, level = level, trafficMonth = month)

    @Test fun labels() {
        assertEquals("моб. трафик за месяц ≥ 5 ГБ", r(Trigger.TRAFFIC, 5120, month = true).timeLabel)
        assertEquals("моб. трафик за день ≥ 500 МБ", r(Trigger.TRAFFIC, 500).timeLabel)
        assertEquals("за 30 мин до будильника", r(Trigger.ALARM, 30).timeLabel)
        assertEquals("темно (от заката до восхода)", r(Trigger.SUN).timeLabel)
    }
}
