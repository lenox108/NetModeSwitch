package com.netmode.switcher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** QSLog identifies the actual component; labels may be duplicated or change while running. */
internal class TileClickLog(private val startedAt: Long) {
    private val seen = linkedSetOf<String>()
    private val click = Regex("(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}).*?QSLog: \\[custom\\(([^)]+)\\)](?:\\[\\d+])? Tile clicked\\.")

    fun read(dump: String, now: Long): List<String> = buildList {
        for (line in dump.lineSequence()) {
            val m = click.find(line) ?: continue
            if (!seen.add(m.value)) continue
            val component = m.groupValues[2]
            if (!Tiles.valid(component)) continue
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).apply { isLenient = false }
            var at = runCatching { parser.parse("${calendar.get(Calendar.YEAR)}-${m.groupValues[1]}")?.time }.getOrNull() ?: continue
            // A ring buffer may still contain Dec 31 after the year rolls over.
            if (at > now + 86_400_000L) {
                at = runCatching { parser.parse("${calendar.get(Calendar.YEAR) - 1}-${m.groupValues[1]}")?.time }.getOrNull() ?: continue
            }
            if (at >= startedAt && at <= now + 2_000 && now - at <= 10_000) add(component)
        }
        while (seen.size > 512) seen.remove(seen.first())
    }
}
