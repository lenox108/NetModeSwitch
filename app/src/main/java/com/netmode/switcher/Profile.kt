package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject

/** How the allowed network types are written and read on this phone. */
enum class WriteMethod(val label: String) {
    /** `cmd phone set/get-allowed-network-types-for-users` — AOSP, what OnePlus takes. */
    CMD("команда cmd phone"),

    /** The phone service's own method, through [PhoneCli]. */
    BINDER("системная служба телефонии"),
}

enum class CheckState(val mark: String) { OK("✅"), WARN("⚠️"), FAIL("❌"), SKIP("➖") }

/** What one preset did on one SIM: the mask that held, or why none did. */
data class ModeResult(val state: CheckState, val mask: Long, val note: String)

data class CheckItem(val title: String, val state: CheckState, val note: String)

/**
 * What the phone check found (see [Calibration]): the device, the way to switch modes, the
 * presets that hold on each SIM — with the exact mask that held — and every other feature.
 * Read by the mode switching and the mode lists; absent until the check has run once, and
 * then everything behaves as on the phone the app was built on.
 */
data class DeviceProfile(
    val at: Long,
    val device: String,
    val rom: String,
    val android: String,
    val backend: String,
    val method: WriteMethod?,
    /** Slot → preset → result. */
    val modes: Map<Int, Map<NetMode, ModeResult>>,
    val checks: List<CheckItem>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("device", device).put("rom", rom).put("android", android).put("backend", backend)
        .put("method", method?.name ?: JSONObject.NULL)
        .put("modes", JSONObject().apply {
            modes.forEach { (slot, m) ->
                put(slot.toString(), JSONObject().apply {
                    m.forEach { (mode, r) -> put(mode.name, JSONObject().put("s", r.state.name).put("m", r.mask).put("n", r.note)) }
                })
            }
        })
        .put("checks", JSONArray(checks.map { JSONObject().put("t", it.title).put("s", it.state.name).put("n", it.note) }))

    /** Plain text for sending: what was checked and how it went. */
    fun report(): String = buildString {
        appendLine("NetModeSwitch — проверка телефона")
        appendLine("$device · $rom · Android $android · доступ: $backend")
        appendLine("Способ переключения: ${method?.label ?: "не найден"}")
        for ((slot, m) in modes.toSortedMap()) {
            appendLine()
            appendLine("SIM ${slot + 1}:")
            for ((mode, r) in m) appendLine("  ${r.state.mark} ${mode.label}: ${r.note}" + if (r.state == CheckState.OK) " (0x%X)".format(r.mask) else "")
        }
        appendLine()
        for (c in checks) appendLine("${c.state.mark} ${c.title}: ${c.note}")
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceProfile(
            at = o.optLong("at"),
            device = o.optString("device"),
            rom = o.optString("rom"),
            android = o.optString("android"),
            backend = o.optString("backend"),
            method = runCatching { WriteMethod.valueOf(o.getString("method")) }.getOrNull(),
            modes = o.optJSONObject("modes")?.let { ms ->
                ms.keys().asSequence().associate { slot ->
                    val m = ms.getJSONObject(slot)
                    slot.toInt() to m.keys().asSequence().mapNotNull { name ->
                        val mode = runCatching { NetMode.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                        val r = m.getJSONObject(name)
                        mode to ModeResult(runCatching { CheckState.valueOf(r.getString("s")) }.getOrDefault(CheckState.FAIL),
                            r.optLong("m"), r.optString("n"))
                    }.toMap()
                }
            }.orEmpty(),
            checks = o.optJSONArray("checks")?.let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    CheckItem(c.getString("t"), runCatching { CheckState.valueOf(c.getString("s")) }.getOrDefault(CheckState.SKIP), c.optString("n"))
                }
            }.orEmpty(),
        )
    }
}

/** The saved profile and what the rest of the app asks of it. */
object Profile {

    @Volatile private var cache: DeviceProfile? = null
    @Volatile private var loaded = false

    val current: DeviceProfile?
        get() {
            if (!loaded) {
                cache = Prefs.deviceProfile?.let { runCatching { DeviceProfile.fromJson(JSONObject(it)) }.getOrNull() }
                loaded = true
            }
            return cache
        }

    fun save(p: DeviceProfile) {
        Prefs.deviceProfile = p.toJson().toString()
        cache = p
        loaded = true
    }

    /**
     * How to write and read the network mode until a check says otherwise: the AOSP command,
     * which exists from Android 12; before it, the phone service itself (see PhoneCli).
     */
    val method: WriteMethod get() = current?.method ?: if (Compat.SDK >= 31) WriteMethod.CMD else WriteMethod.BINDER

    /** The mask to write for [mode] on [slot]: the one that held in the check, else the stock one. */
    fun maskFor(mode: NetMode, slot: Int): Long =
        result(mode, slot)?.takeIf { it.state == CheckState.OK }?.mask ?: mode.mask

    private fun result(mode: NetMode, slot: Int): ModeResult? =
        current?.modes?.let { it[slot]?.get(mode) ?: it.values.firstOrNull()?.get(mode) }

    /** Every mask the check found working for [mode], on any SIM. */
    fun masksOf(mode: NetMode): Set<Long> =
        current?.modes?.values?.mapNotNull { it[mode]?.takeIf { r -> r.state == CheckState.OK }?.mask }?.toSet().orEmpty()

    /**
     * The presets to offer for [slot]: all of them until the check has run; after it, those
     * that did not fail there. A SIM never checked is judged by the others.
     */
    fun available(slot: Int? = null): List<NetMode> {
        val modes = current?.modes ?: return NetMode.entries
        if (modes.isEmpty()) return NetMode.entries
        val forSlot = slot?.let { modes[it] } ?: modes.values.first()
        return NetMode.entries.filter { forSlot[it]?.state != CheckState.FAIL }
    }
}
