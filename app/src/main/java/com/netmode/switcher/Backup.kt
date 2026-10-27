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
            .put("hotspotPresets", HotspotPreset.encode(Prefs.hotspotPresets))
            .put("tetherProxyUser", Prefs.tetherProxyUser)
            .put("tetherTtlEnabled", Prefs.tetherTtlEnabled)
            .put("tetherTtlValue", Prefs.tetherTtlValue)
            .put("disableTetherIpv6", Prefs.disableTetherIpv6)
            .put("hideSystem", Prefs.hideSystemApps)
            .put("unloadExclude", JSONArray(Prefs.unloadExclude))
            .put("ruleSort", Prefs.ruleSort.name)
            .put("ruleOrder", JSONArray(Prefs.ruleOrder))
            .put("hideRules", JSONArray(Prefs.hideRules.map { it.toJson() }))
            .put("freezer", JSONObject()
                // Which apps and how, not whether they are frozen right now.
                .put("apps", JSONArray(Freezer.apps.map { a ->
                    JSONObject().put("key", a.key).put("mode", a.mode.name)
                        .put("delay", a.delay ?: JSONObject.NULL).put("group", a.group ?: JSONObject.NULL)
                        .put("favorite", a.favorite)
                }))
                .put("groups", JSONArray(Prefs.iceGroups))
                .put("mode", Prefs.iceDefaultMode.name).put("delay", Prefs.iceDefaultDelay)
                .put("screenOff", Prefs.iceScreenOff).put("keepBusy", Prefs.iceKeepBusy)
                .put("noConfirm", Prefs.iceNoConfirm).put("badge", Prefs.iceBadge)
                .put("view", Prefs.iceView).put("columns", Prefs.iceColumns).put("rows", Prefs.iceRows)
                .put("iconSize", Prefs.iceIconSize).put("labels", Prefs.iceLabels)
                .put("sort", Prefs.iceSort.name).put("order", JSONArray(Prefs.iceOrder))
                .put("respectRules", Prefs.iceRespectRules).put("bootCheck", Prefs.iceBootCheck))
            .put("togglePairs", JSONObject().apply {
                Sims.slots().forEach { slot -> Prefs.togglePair(slot)?.let { put(slot.toString(), "${it.first.name},${it.second.name}") } }
            }))
        .toString(2)

    /**
     * The freezer's apps and settings. An app already in the freezer keeps its state; a new one
     * comes in thawed and freezes by its delay, like any app thawed.
     */
    private fun importFreezer(f: JSONObject) {
        runCatching { Prefs.iceDefaultMode = IceMode.valueOf(f.getString("mode")) }
        if (f.has("delay")) Prefs.iceDefaultDelay = f.getInt("delay")
        if (f.has("screenOff")) Prefs.iceScreenOff = f.getBoolean("screenOff")
        if (f.has("keepBusy")) Prefs.iceKeepBusy = f.getBoolean("keepBusy")
        if (f.has("noConfirm")) Prefs.iceNoConfirm = f.getBoolean("noConfirm")
        if (f.has("badge")) Prefs.iceBadge = f.getBoolean("badge")
        if (f.has("view")) Prefs.iceView = f.getString("view")
        if (f.has("columns")) Prefs.iceColumns = f.getInt("columns")
        if (f.has("rows")) Prefs.iceRows = f.getInt("rows")
        if (f.has("iconSize")) Prefs.iceIconSize = f.getString("iconSize")
        if (f.has("labels")) Prefs.iceLabels = f.getBoolean("labels")
        if (f.has("sort")) runCatching { Prefs.iceSort = IceSort.valueOf(f.getString("sort")) }
        f.optJSONArray("order")?.let { a -> Prefs.iceOrder = (0 until a.length()).map { a.getString(it) } }
        if (f.has("respectRules")) Prefs.iceRespectRules = f.getBoolean("respectRules")
        if (f.has("bootCheck")) Prefs.iceBootCheck = f.getBoolean("bootCheck")
        f.optJSONArray("groups")?.let { a -> Prefs.iceGroups = (0 until a.length()).map { a.getString(it) } }
        val arr = f.optJSONArray("apps") ?: return
        val have = Prefs.iceApps.associateBy { it.key }
        val now = System.currentTimeMillis()
        val read = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val key = o.getString("key").takeIf { AppKey.valid(it) } ?: return@runCatching null
                val mode = runCatching { IceMode.valueOf(o.getString("mode")) }.getOrDefault(IceMode.DEFAULT)
                val delay = if (o.isNull("delay")) null else o.getInt("delay")
                val group = o.optString("group").takeIf { !o.isNull("group") && it.isNotEmpty() }
                (have[key] ?: IceApp(key, thawedAt = now)).copy(mode = mode, delay = delay, group = group,
                    favorite = if (o.has("favorite")) o.getBoolean("favorite") else have[key]?.favorite ?: false)
            }.getOrNull()
        }
        val readKeys = read.map { it.key }.toSet()
        // Installed here only; one the old phone had and this one lacks is left out.
        val pm = App.ctx.packageManager
        Prefs.iceApps = read.filter { AppKey.isClone(it.key) || Freezer.info(pm, it.key) != null } +
            Prefs.iceApps.filter { it.key !in readKeys }
    }

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
            if (s.has("hotspotPresets")) Prefs.hotspotPresets = HotspotPreset.decode(s.getString("hotspotPresets"))
            if (s.has("tetherTtlEnabled")) Prefs.tetherTtlEnabled = s.getBoolean("tetherTtlEnabled")
            if (s.has("tetherTtlValue")) Prefs.tetherTtlValue = s.getInt("tetherTtlValue").takeIf { it in 0..255 } ?: 0
            if (s.has("disableTetherIpv6")) Prefs.disableTetherIpv6 = s.getBoolean("disableTetherIpv6")
            else if (s.has("disableIpv6")) Prefs.disableTetherIpv6 = s.getBoolean("disableIpv6")
            if (s.has("hideSystem")) Prefs.hideSystemApps = s.getBoolean("hideSystem")
            s.optJSONArray("unloadExclude")?.let { a -> Prefs.unloadExclude = (0 until a.length()).map { a.getString(it) } }
            runCatching { Prefs.ruleSort = RuleSort.valueOf(s.getString("ruleSort")) }
            s.optJSONArray("ruleOrder")?.let { a -> Prefs.ruleOrder = (0 until a.length()).map { a.getLong(it) } }
            s.optJSONArray("hideRules")?.let { a -> Prefs.hideRules = (0 until a.length()).mapNotNull { runCatching { HideRule.fromJson(a.getJSONObject(it)) }.getOrNull() } }
            s.optJSONObject("freezer")?.let { importFreezer(it) }
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
