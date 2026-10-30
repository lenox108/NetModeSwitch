package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test

class SysCommandsTest {
    private fun refresh(value: String) = SysCommands.refresh(value, "peak", "min") { "120.0" }

    @Test fun brightness() {
        assertEquals("settings put system screen_brightness_mode 1", SysCommands.brightness("auto"))
        assertTrue(SysCommands.brightness("m:120")!!.endsWith("screen_brightness 120"))
        assertTrue(SysCommands.brightness("50")!!.endsWith("screen_brightness 127"))
        assertNull(SysCommands.brightness("m:1; reboot"))
        assertNull(SysCommands.brightness("m:-1"))
        assertNull(SysCommands.brightness("m:"))
        assertNull(SysCommands.brightness("\$(id)"))
    }

    @Test fun timeout() {
        assertEquals("settings put system screen_off_timeout 30000", SysCommands.timeout("ms:30000"))
        assertEquals("settings put system screen_off_timeout 60000", SysCommands.timeout("60"))
        assertNull(SysCommands.timeout("ms:30000;id"))
        assertNull(SysCommands.timeout("ms:`id`"))
    }

    @Test fun refreshTakesWhatReadCanReturn() {
        assertEquals("settings put system peak 60.0; settings put system min 60.0", refresh("60"))
        assertEquals("settings put system peak 120.0; settings put system min 0.0", refresh("max"))
        assertEquals("settings put system peak 90.0; settings delete system min", refresh("p:90.0|"))
        assertEquals("settings delete system peak; settings delete system min", refresh("p:|"))
        assertEquals("settings put system peak Infinity; settings put system min 0.0", refresh("p:Infinity|0.0"))
    }

    @Test fun refreshRefusesAnythingElse() {
        assertNull(refresh("p:90;reboot|"))
        assertNull(refresh("p:90.0|\$(id)"))
        assertNull(refresh("72"))
    }
}
