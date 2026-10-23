package com.netmode.switcher

import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rules and settings as one JSON file, for moving to a new phone or keeping a copy — Android's
 * own backup is off, since it would also carry the proxy password. The password is left out
 * here too; what is running right now (held apps, open windows) is not a setting and stays.
 */
object Backup {

    private const val VERSION = 1

    fun export(): String = JSONObject()
        .put("app", "NetModeSwitch")
        .put("version", VERSION)
        .put("rules", JSONArray(Prefs.appRules.map { it.toJson() }))
        .put("settings", JSONObject()
            .put("backend", Prefs.backend.name)
            .put("catchUp", Prefs.catchUpOnBoot)
            .put("reliableWifi", Prefs.reliableWifi)
            .put("statusNotification", Prefs.statusNotification)
            .put("dualSim", Prefs.dualSimEnabled)
            .put("dualPrimary", Prefs.dualPrimary.name)
            .put("dualSecondary", Prefs.dualSecondary.name)
            .put("tetherProxy", Prefs.tetherProxy)
            .put("tetherProxyUser", Prefs.tetherProxyUser)
            .put("tetherTtlEnabled", Prefs.tetherTtlEnabled)
            .put("tetherTtlValue", Prefs.tetherTtlValue)
            .put("disableTetherIpv6", Prefs.disableTetherIpv6)
            .put("hideSystem", Prefs.hideSystemApps)
            .put("unloadExclude", JSONArray(Prefs.unloadExclude))
            .put("ruleSort", Prefs.ruleSort.name)
            .put("ruleOrder", JSONArray(Prefs.ruleOrder))
            .put("togglePairs", JSONObject().apply {
                Sims.slots().forEach { slot -> Prefs.togglePair(slot)?.let { put(slot.toString(), "${it.first.name},${it.second.name}") } }
            }))
        .toString(2)

    /** Returns the number of rules read; throws on a file that is not a backup of this app. */
    fun import(text: String): Int {
        val o = JSONObject(text)
        require(o.optString("app") == "NetModeSwitch") { "это не файл NetModeSwitch" }
        val arr = o.getJSONArray("rules")
        val rules = (0 until arr.length()).map { AppRule.fromJson(arr.getJSONObject(it)) }
        o.optJSONObject("settings")?.let { s ->
            runCatching { Prefs.backend = Backend.valueOf(s.getString("backend")) }
            if (s.has("catchUp")) Prefs.catchUpOnBoot = s.getBoolean("catchUp")
            if (s.has("reliableWifi")) Prefs.reliableWifi = s.getBoolean("reliableWifi")
            if (s.has("statusNotification")) Prefs.statusNotification = s.getBoolean("statusNotification")
            if (s.has("dualSim")) Prefs.dualSimEnabled = s.getBoolean("dualSim")
            runCatching { Prefs.dualPrimary = NetMode.valueOf(s.getString("dualPrimary")) }
            runCatching { Prefs.dualSecondary = NetMode.valueOf(s.getString("dualSecondary")) }
            s.optString("tetherProxy").takeIf { it.isNotEmpty() }?.let { Prefs.tetherProxy = it }
            if (s.has("tetherProxyUser")) Prefs.tetherProxyUser = s.getString("tetherProxyUser")
            if (s.has("tetherTtlEnabled")) Prefs.tetherTtlEnabled = s.getBoolean("tetherTtlEnabled")
            if (s.has("tetherTtlValue")) Prefs.tetherTtlValue = s.getInt("tetherTtlValue").takeIf { it in 0..255 } ?: 0
            if (s.has("disableTetherIpv6")) Prefs.disableTetherIpv6 = s.getBoolean("disableTetherIpv6")
            else if (s.has("disableIpv6")) Prefs.disableTetherIpv6 = s.getBoolean("disableIpv6")
            if (s.has("hideSystem")) Prefs.hideSystemApps = s.getBoolean("hideSystem")
            s.optJSONArray("unloadExclude")?.let { a -> Prefs.unloadExclude = (0 until a.length()).map { a.getString(it) } }
            runCatching { Prefs.ruleSort = RuleSort.valueOf(s.getString("ruleSort")) }
            s.optJSONArray("ruleOrder")?.let { a -> Prefs.ruleOrder = (0 until a.length()).map { a.getLong(it) } }
            s.optJSONObject("togglePairs")?.let { p ->
                p.keys().forEach { slot ->
                    runCatching {
                        val (a, b) = p.getString(slot).split(',').map { NetMode.valueOf(it) }
                        Prefs.setTogglePair(slot.toInt(), a to b)
                    }
                }
            }
        }
        // Whatever the old rules were holding goes back first: reconcile sees them as gone.
        Prefs.appRules = rules
        Prefs.appHandled = emptyMap()
        rules.forEach { AppFreezer.markSaved(it) }
        RuleStore.changed()
        App.scope.launch { runCatching { NetworkTweaks.apply() }; TetherWatch.sync() }
        return rules.size
    }
}
