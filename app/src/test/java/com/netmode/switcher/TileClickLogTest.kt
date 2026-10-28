package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TileClickLogTest {
    private fun time(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).parse(value)!!.time
    private val start = time("2026-10-28 12:00:00.000")
    private fun click(at: String, seq: Int) = "$at D QSLog: [custom(example.app/.Tile)][$seq] Tile clicked. StatusBarState=SHADE. TileState=active"

    @Test fun ignoresHistoryHandlingLongPressesAndSecondaryClicksAndNeverReplaysADump() {
        val reader = TileClickLog(start)
        val lines = listOf(click("10-28 11:59:59.000", 0), click("10-28 12:00:00.500", 1),
            click("10-28 12:00:00.500", 1).replace("clicked.", "handling click."),
            click("10-28 12:00:00.600", 2).replace("clicked.", "long clicked."),
            click("10-28 12:00:00.700", 3).replace("clicked.", "secondary clicked."))
        assertEquals(listOf("example.app/.Tile"), reader.read(lines.joinToString("\n"), start + 1_000))
        assertTrue(reader.read(lines.joinToString("\n"), start + 2_000).isEmpty())
        assertEquals(listOf("example.app/.Tile", "example.app/.Tile"), reader.read(
            click("10-28 12:00:02.000", 4) + "\n" + click("10-28 12:00:02.100", 5), start + 3_000))
    }

    @Test fun oldEventsCannotRunAfterProcessRestartOrAClockChange() {
        val reader = TileClickLog(start)
        assertTrue(reader.read(click("10-28 12:00:00.500", 1), start + 20_000).isEmpty())
        assertTrue(reader.read(click("10-28 12:30:00.000", 2), start + 1_000).isEmpty())
    }
}
