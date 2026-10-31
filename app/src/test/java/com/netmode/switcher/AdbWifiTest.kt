package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class AdbWifiTest {
    @Test fun onlyTheStoredOneMeansOn() {
        assertTrue(AdbWifi.isOn("1"))
        for (value in listOf("0", "", null, "true")) assertFalse(AdbWifi.isOn(value))
    }

    @Test fun commandSetsTheSystemSetting() {
        assertEquals("settings put global adb_wifi_enabled 1", AdbWifi.command(true))
        assertEquals("settings put global adb_wifi_enabled 0", AdbWifi.command(false))
    }

    @Test fun aFailedOrRefusedSwitchIsAJournalError() {
        val refused = Journal.parse("30.10 10:11  отладка по Wi-Fi: система не включила — нужен Wi-Fi")
        assertEquals(JournalEntry.Severity.ERROR, refused.severity)
        assertEquals(JournalEntry.Severity.ERROR, Journal.parse("30.10 10:11  отладка по Wi-Fi: ошибка нет доступа").severity)
    }
}
