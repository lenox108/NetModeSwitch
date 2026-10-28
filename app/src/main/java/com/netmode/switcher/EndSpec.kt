package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject

/** How an ending treats what the rule changed. */
enum class EndMode {
    /** Everything the rule changed goes back. */
    RESTORE,
    /** Set by hand: the ending's own values; stopped apps still come back. */
    CUSTOM,
    /** Nothing at all: everything stays as the rule left it (only for unlocking, see [AppRule.unlockEnd]). */
    NOTHING,
}

/**
 * One ending of a rule — what is done and in what order. The rule's main end lives in its own
 * fields ([AppRule.endSpec]); this carries the second one, unlocking inside a lock rule's time.
 */
data class EndSpec(
    val mode: EndMode = EndMode.RESTORE,
    /** The apps stopped at the start stay stopped until the end of the time. */
    val keepApps: Boolean = false,
    val net: NetMode? = null,
    val data: Toggle? = null,
    val wifi: Toggle? = null,
    val hotspot: Toggle? = null,
    val hotspotVia: TetherMode? = null,
    val hotspotBand: String? = null,
    val bt: Toggle? = null,
    val loc: Toggle? = null,
    val vpn: Toggle? = null,
    /** Null keeps the legacy "disconnect any VPN" action. */
    val vpnOffApp: String? = null,
    val airplane: Toggle? = null,
    val powerSave: Toggle? = null,
    val dataSim: Int? = null,
    val tiles: Map<String, Toggle> = emptyMap(),
    val stopApps: List<String> = emptyList(),
    val appMethods: Map<String, FreezeMethod> = emptyMap(),
    val launchApps: List<String> = emptyList(),
    val unload: Boolean = false,
    val unloadAll: Boolean = false,
    val steps: List<String> = emptyList(),
    val sys: Map<String, String> = emptyMap(),
    val note: String? = null,
    val http: String? = null,
    val shell: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name).put("keepApps", keepApps)
        .put("net", net?.name ?: JSONObject.NULL).put("data", data?.name ?: JSONObject.NULL)
        .put("wifi", wifi?.name ?: JSONObject.NULL).put("ap", hotspot?.name ?: JSONObject.NULL)
        .put("apBand", hotspotBand ?: JSONObject.NULL)
        .put("apVia", hotspotVia?.name ?: JSONObject.NULL).put("bt", bt?.name ?: JSONObject.NULL)
        .put("loc", loc?.name ?: JSONObject.NULL).put("vpn", vpn?.name ?: JSONObject.NULL)
        .put("vpnOffApp", vpnOffApp ?: JSONObject.NULL)
        .put("air", airplane?.name ?: JSONObject.NULL).put("ps", powerSave?.name ?: JSONObject.NULL)
        .put("dSim", dataSim ?: JSONObject.NULL)
        .put("tiles", JSONObject().apply { tiles.forEach { (c, t) -> put(c, t.name) } })
        .put("stop", JSONArray(stopApps))
        .put("methods", JSONObject().apply { appMethods.forEach { (p, m) -> put(p, m.name) } })
        .put("launch", JSONArray(launchApps))
        .put("unl", unload).put("unlAll", unloadAll)
        .put("steps", JSONArray(steps))
        .put("sys", JSONObject(sys as Map<*, *>))
        .put("note", note ?: JSONObject.NULL).put("http", http ?: JSONObject.NULL).put("shell", shell ?: JSONObject.NULL)

    companion object {
        private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T? =
            if (isNull(key)) null else runCatching { enumValueOf<T>(getString(key)) }.getOrNull()

        private fun JSONObject.strings(key: String): List<String> =
            optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

        fun fromJson(o: JSONObject) = EndSpec(
            mode = o.enum<EndMode>("mode") ?: EndMode.RESTORE,
            keepApps = o.optBoolean("keepApps", false),
            net = o.enum<NetMode>("net"), data = o.enum<Toggle>("data"), wifi = o.enum<Toggle>("wifi"),
            hotspot = o.enum<Toggle>("ap"), hotspotVia = o.enum<TetherMode>("apVia"),
            hotspotBand = HotspotCondition.band(o.optString("apBand")),
            bt = o.enum<Toggle>("bt"), loc = o.enum<Toggle>("loc"), vpn = o.enum<Toggle>("vpn"),
            vpnOffApp = o.optString("vpnOffApp").takeIf { !o.isNull("vpnOffApp") && it.isNotBlank() },
            airplane = o.enum<Toggle>("air"), powerSave = o.enum<Toggle>("ps"),
            dataSim = if (o.isNull("dSim") || !o.has("dSim")) null else o.optInt("dSim"),
            tiles = o.optJSONObject("tiles")?.let { m ->
                m.keys().asSequence().mapNotNull { c -> runCatching { Toggle.valueOf(m.getString(c)) }.getOrNull()?.let { c to it } }.toMap()
            }.orEmpty(),
            stopApps = o.strings("stop"),
            appMethods = o.optJSONObject("methods")?.let { m ->
                m.keys().asSequence().mapNotNull { p -> runCatching { FreezeMethod.valueOf(m.getString(p)) }.getOrNull()?.let { p to it } }.toMap()
            }.orEmpty(),
            launchApps = o.strings("launch"),
            unload = o.optBoolean("unl", false), unloadAll = o.optBoolean("unlAll", false),
            steps = o.strings("steps"),
            sys = o.optJSONObject("sys")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
            note = o.optString("note").takeIf { !o.isNull("note") && it.isNotEmpty() },
            http = o.optString("http").takeIf { !o.isNull("http") && it.isNotEmpty() },
            shell = o.optString("shell").takeIf { !o.isNull("shell") && it.isNotEmpty() },
        )
    }
}
