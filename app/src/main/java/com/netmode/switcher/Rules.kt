package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A time-of-day rule. [days] is a bitmask over DayOfWeek.value-1 (Mon = bit 0); zero means
 * every day, which keeps the common case free of bookkeeping.
 */
data class ScheduleRule(
    val id: Long,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val mode: NetMode,
    /** Slot to act on, or [SLOT_ALL]. Absent in rules written before dual-SIM support. */
    val slot: Int = SLOT_ALL,
) {
    fun runsOn(day: DayOfWeek): Boolean = daysAllow(days, day)

    /** First firing strictly after [from]. */
    fun nextAfter(from: ZonedDateTime): ZonedDateTime = nextOccurrence(from, hour, minute, ::runsOn)

    /** Most recent firing at or before [at]; used for boot catch-up. */
    fun lastAtOrBefore(at: ZonedDateTime): ZonedDateTime? = lastOccurrence(at, hour, minute, ::runsOn)

    val timeLabel: String get() = "%02d:%02d".format(hour, minute)

    val slotLabel: String get() = if (slot == SLOT_ALL) "все SIM" else "SIM ${slot + 1}"

    val daysLabel: String get() = formatDays(days)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("on", enabled).put("h", hour).put("m", minute)
        .put("days", days).put("mode", mode.name).put("slot", slot)

    companion object {
        val DAY_NAMES = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

        fun fromJson(o: JSONObject) = ScheduleRule(
            id = o.getLong("id"),
            enabled = o.optBoolean("on", true),
            hour = o.getInt("h"),
            minute = o.getInt("m"),
            days = o.optInt("days", 0),
            mode = NetMode.valueOf(o.getString("mode")),
            slot = o.optInt("slot", SLOT_ALL),
        )
    }
}

/**
 * A Wi-Fi rule. Either action may be null, meaning "do nothing" — and a null [onDisconnect]
 * is what lets the app avoid running any foreground service for this rule at all.
 */
data class WifiRule(
    val id: Long,
    val enabled: Boolean,
    val ssid: String,
    val onConnect: NetMode?,
    val onDisconnect: NetMode?,
    /** Slot to act on, or [SLOT_ALL]. Absent in rules written before dual-SIM support. */
    val slot: Int = SLOT_ALL,
    /**
     * Whether switching the Wi-Fi adapter off counts as leaving. Off by default because night
     * power saving does exactly that; rules written before this was per-rule inherit the old
     * global setting.
     */
    val leaveOnWifiOff: Boolean = false,
) {
    fun matches(other: String?): Boolean =
        other != null && other.trim('"').equals(ssid.trim('"'), ignoreCase = true)

    val slotLabel: String get() = if (slot == SLOT_ALL) "все SIM" else "SIM ${slot + 1}"

    val actionLabel: String
        get() = buildList {
            onConnect?.let { add("при подключении → ${it.label}") }
            onDisconnect?.let { add("при отключении → ${it.label}") }
        }.joinToString(", ").ifEmpty { "нет действий" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("on", enabled).put("ssid", ssid).put("slot", slot)
        .put("wifiOff", leaveOnWifiOff)
        .put("conn", onConnect?.name ?: JSONObject.NULL)
        .put("disc", onDisconnect?.name ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = WifiRule(
            id = o.getLong("id"),
            enabled = o.optBoolean("on", true),
            ssid = o.getString("ssid"),
            onConnect = o.optString("conn").takeIf { it.isNotEmpty() && it != "null" }?.let { NetMode.valueOf(it) },
            onDisconnect = o.optString("disc").takeIf { it.isNotEmpty() && it != "null" }?.let { NetMode.valueOf(it) },
            slot = o.optInt("slot", SLOT_ALL),
            leaveOnWifiOff = if (o.has("wifiOff")) o.getBoolean("wifiOff") else !Prefs.ignoreWifiOff,
        )
    }
}

/** Day-of-week filter shared by every time-based rule; zero means every day. */
internal fun daysAllow(days: Int, day: DayOfWeek): Boolean =
    days == 0 || days and (1 shl (day.value - 1)) != 0

internal fun formatDays(days: Int): String =
    if (days == 0) "ежедневно" else DayOfWeek.entries
        .filter { daysAllow(days, it) }
        .joinToString(" ") { ScheduleRule.DAY_NAMES[it.value - 1] }

/** First hour:minute strictly after [from] on a day that [runsOn] accepts. */
internal fun nextOccurrence(
    from: ZonedDateTime, hour: Int, minute: Int, runsOn: (DayOfWeek) -> Boolean,
): ZonedDateTime {
    var at = from.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
    if (!at.isAfter(from)) at = at.plusDays(1)
    var guard = 0
    while (!runsOn(at.dayOfWeek) && guard++ < 8) at = at.plusDays(1)
    return at
}

/** Most recent hour:minute at or before [at] on an accepted day, or null if none in a week. */
internal fun lastOccurrence(
    at: ZonedDateTime, hour: Int, minute: Int, runsOn: (DayOfWeek) -> Boolean,
): ZonedDateTime? {
    var t = at.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
    if (t.isAfter(at)) t = t.minusDays(1)
    var guard = 0
    while (!runsOn(t.dayOfWeek)) {
        t = t.minusDays(1)
        if (guard++ > 8) return null
    }
    return t
}

internal fun <T> decodeList(raw: String, item: (JSONObject) -> T): List<T> = decodeChecked(raw, item).items

/** What could be read of a stored list; [bad] counts what could not, the whole list being one. */
internal data class Decoded<T>(val items: List<T>, val bad: Int)

internal fun <T> decodeChecked(raw: String, item: (JSONObject) -> T): Decoded<T> {
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return Decoded(emptyList(), 1)
    return decodeItems(array.length()) { item(array.getJSONObject(it)) }
}

/** One unreadable item costs that item alone: the next save writes back all the others. */
internal fun <T> decodeItems(count: Int, item: (Int) -> T): Decoded<T> {
    val read = (0 until count).map { i -> runCatching { item(i) }.getOrNull() }
    val items = read.filterNotNull()
    return Decoded(items, count - items.size)
}

/**
 * Copies of stored lists with something unreadable in them. The next save overwrites the
 * original, and these stay for a manual rescue. Same content, same name: a list read again and
 * again makes one copy.
 */
internal object UnreadableCopies {
    const val KEEP = 10

    fun name(key: String, raw: String): String = "$key-%08x.json".format(raw.hashCode())

    /** The oldest of [byAge] (oldest first) beyond [KEEP]. */
    fun excess(byAge: List<String>): List<String> = byAge.dropLast(KEEP)

    /** The new copy's name; null when this content was copied before. */
    fun keep(dir: java.io.File, key: String, raw: String): String? {
        val file = java.io.File(dir, name(key, raw))
        if (file.exists()) return null
        dir.mkdirs()
        file.writeText(raw)
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        val gone = excess(files.map { it.name }).toSet()
        files.filter { it.name in gone }.forEach { it.delete() }
        return file.name
    }
}

internal fun <T> encodeList(list: List<T>, item: (T) -> JSONObject): String =
    JSONArray().apply { list.forEach { put(item(it)) } }.toString()
