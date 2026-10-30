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
            .put("hideRules", JSONArray(Prefs.hideRules.map { it.copy(channelWas = null, pausedUntil = 0).toJson() }))
            .put("freezer", JSONObject()
                // Which apps and how, not whether they are frozen right now.
                .put("apps", JSONArray(Freezer.apps.map { a ->
                    JSONObject().put("key", a.key).put("mode", a.mode.name)
                        .put("delay", a.delay ?: JSONObject.NULL).put("group", a.group ?: JSONObject.NULL)
                        .put("favorite", a.favorite).put("addedAt", a.addedAt)
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
     * What a rule would run or call on its own, unlocking block included, each exactly as it
     * stands in the file. A shell command runs as root, so a file from elsewhere must not bring
     * one in unseen.
     */
    internal fun outsideActions(rule: AppRule): List<String> = listOfNotNull(
        rule.shell?.let { "команда при старте: $it" },
        rule.http?.let { "HTTP при старте: $it" },
        rule.endShell?.let { "команда в конце: $it" },
        rule.endHttp?.let { "HTTP в конце: $it" },
        rule.unlockEnd?.shell?.let { "команда при разблокировке: $it" },
        rule.unlockEnd?.http?.let { "HTTP при разблокировке: $it" },
    )

    /** The rules with [outsideActions] come in switched off, for the user to look at first. */
    internal fun held(rules: List<AppRule>): List<AppRule> =
        rules.map { if (outsideActions(it).isEmpty()) it else it.copy(enabled = false) }

    /** Every outside action of [rules] for the confirmation; empty when there are none. */
    internal fun outsideSummary(rules: List<AppRule>): String = rules.mapNotNull { rule ->
        outsideActions(rule).takeIf { it.isNotEmpty() }?.let { actions ->
            "«${rule.name ?: rule.timeLabel}»\n" + actions.joinToString("\n") { "• $it" }
        }
    }.joinToString("\n\n")

    /**
     * A file read and checked through, with nothing changed yet: [changes] only set what has
     * already been parsed, so a bad value refuses the whole file instead of leaving a mix.
     */
    internal class Plan(val rules: List<AppRule>, val hideRules: List<HideRule>?, val changes: List<() -> Unit>)

    /** A file from a newer version may mean something else by the same names; none is old. */
    internal fun checkVersion(version: Any?) {
        if (version == null) return
        val n = (version as? Number)?.toInt()
        require(n != null && n >= 1) { "неверная версия файла" }
        require(n <= VERSION) { "файл создан более новой версией приложения, обновите её" }
    }

    private inline fun <T> JSONObject.choice(key: String, parse: (String) -> T): T =
        try { parse(getString(key)) } catch (e: Exception) { throw IllegalArgumentException("неверное значение «$key»", e) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /**
     * The freezer's apps and settings. An app already in the freezer keeps its state; a new one
     * comes in thawed and freezes by its delay, like any app thawed.
     */
    private fun parseFreezer(f: JSONObject, out: MutableList<() -> Unit>) {
        fun <T> on(key: String, read: () -> T, set: (T) -> Unit) { if (f.has(key)) { val v = read(); out += { set(v) } } }
        on("mode", { f.choice("mode") { IceMode.valueOf(it) } }) { Prefs.iceDefaultMode = it }
        on("delay", { f.getInt("delay") }) { Prefs.iceDefaultDelay = it }
        on("screenOff", { f.getBoolean("screenOff") }) { Prefs.iceScreenOff = it }
        on("keepBusy", { f.getBoolean("keepBusy") }) { Prefs.iceKeepBusy = it }
        on("noConfirm", { f.getBoolean("noConfirm") }) { Prefs.iceNoConfirm = it }
        on("badge", { f.getBoolean("badge") }) { Prefs.iceBadge = it }
        on("view", { f.getString("view") }) { Prefs.iceView = it }
        on("columns", { f.getInt("columns") }) { Prefs.iceColumns = it }
        on("rows", { f.getInt("rows") }) { Prefs.iceRows = it }
        on("iconSize", { f.getString("iconSize") }) { Prefs.iceIconSize = it }
        on("labels", { f.getBoolean("labels") }) { Prefs.iceLabels = it }
        on("sort", { f.choice("sort") { IceSort.valueOf(it) } }) { Prefs.iceSort = it }
        on("order", { f.getJSONArray("order").strings() }) { Prefs.iceOrder = it }
        on("respectRules", { f.getBoolean("respectRules") }) { Prefs.iceRespectRules = it }
        on("bootCheck", { f.getBoolean("bootCheck") }) { Prefs.iceBootCheck = it }
        on("groups", { f.getJSONArray("groups").strings() }) { Prefs.iceGroups = it }
        val arr = f.optJSONArray("apps") ?: return
        val now = System.currentTimeMillis()
        // Installed here only; one the old phone had and this one lacks is left out, as is one
        // this version cannot read. Neither says anything about the rest of the file.
        val pm = App.ctx.packageManager
        val read = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val key = o.getString("key").takeIf { AppKey.valid(it) } ?: return@runCatching null
                val mode = runCatching { IceMode.valueOf(o.getString("mode")) }.getOrDefault(IceMode.DEFAULT)
                val delay = if (o.isNull("delay")) null else o.getInt("delay")
                val group = o.optString("group").takeIf { !o.isNull("group") && it.isNotEmpty() }
                val favorite = if (o.has("favorite")) o.getBoolean("favorite") else null
                val addedAt = o.optLong("addedAt", 0L).coerceAtLeast(0L)
                val make: (IceApp?) -> IceApp = { have ->
                    (have ?: IceApp(key, thawedAt = now, addedAt = addedAt)).copy(mode = mode, delay = delay, group = group,
                        favorite = favorite ?: have?.favorite ?: false)
                }
                key to make
            }.getOrNull()
        }
        val readKeys = read.map { it.first }.toSet()
        val installed = read.filter { (key, _) -> AppKey.isClone(key) || Freezer.info(pm, key) != null }
        out += {
            val have = Prefs.iceApps
            val byKey = have.associateBy { it.key }
            Prefs.iceApps = installed.map { (key, make) -> make(byKey[key]) } + have.filter { it.key !in readKeys }
        }
    }

    private fun parseSettings(s: JSONObject, out: MutableList<() -> Unit>) {
        fun <T> on(key: String, read: () -> T, set: (T) -> Unit) { if (s.has(key)) { val v = read(); out += { set(v) } } }
        on("backend", { s.choice("backend") { Backend.valueOf(it) } }) { Prefs.backend = it }
        on("catchUp", { s.getBoolean("catchUp") }) { Prefs.catchUpOnBoot = it }
        on("reliableWifi", { s.getBoolean("reliableWifi") }) { Prefs.reliableWifi = it }
        on("statusNotification", { s.getBoolean("statusNotification") }) { Prefs.statusNotification = it }
        on("dualSim", { s.getBoolean("dualSim") }) { Prefs.dualSimEnabled = it }
        on("dualPrimary", { s.choice("dualPrimary") { NetMode.valueOf(it) } }) { Prefs.dualPrimary = it }
        on("dualSecondary", { s.choice("dualSecondary") { NetMode.valueOf(it) } }) { Prefs.dualSecondary = it }
        s.optString("tetherProxy").takeIf { it.isNotEmpty() }?.let { v -> out += { Prefs.tetherProxy = v } }
        on("tetherProxyUser", { s.getString("tetherProxyUser") }) { Prefs.tetherProxyUser = it }
        on("hotspotPresets", { HotspotPreset.decode(s.getString("hotspotPresets")) }) { Prefs.hotspotPresets = it }
        on("tetherTtlEnabled", { s.getBoolean("tetherTtlEnabled") }) { Prefs.tetherTtlEnabled = it }
        on("tetherTtlValue", { s.choice("tetherTtlValue") { v -> v.toInt().also { require(it in 0..255) } } }) { Prefs.tetherTtlValue = it }
        if (s.has("disableTetherIpv6")) on("disableTetherIpv6", { s.getBoolean("disableTetherIpv6") }) { Prefs.disableTetherIpv6 = it }
        else on("disableIpv6", { s.getBoolean("disableIpv6") }) { Prefs.disableTetherIpv6 = it }
        on("hideSystem", { s.getBoolean("hideSystem") }) { Prefs.hideSystemApps = it }
        on("unloadExclude", { s.getJSONArray("unloadExclude").strings() }) { Prefs.unloadExclude = it }
        on("ruleSort", { s.choice("ruleSort") { RuleSort.valueOf(it) } }) { Prefs.ruleSort = it }
        on("ruleOrder", { s.getJSONArray("ruleOrder").let { a -> (0 until a.length()).map { a.getLong(it) } } }) { Prefs.ruleOrder = it }
        s.optJSONObject("freezer")?.let { parseFreezer(it, out) }
        s.optJSONObject("togglePairs")?.let { p ->
            p.keys().forEach { slot ->
                val pair = p.choice(slot) { v -> v.split(',').map { NetMode.valueOf(it) }.let { (a, b) -> slot.toInt() to (a to b) } }
                out += { Prefs.setTogglePair(pair.first, pair.second) }
            }
        }
    }

    /** Reads all of [text] and changes nothing; throws on anything this version cannot take in. */
    internal fun parse(text: String): Plan {
        val o = JSONObject(text)
        require(o.optString("app") == "NetModeSwitch") { "это не файл NetModeSwitch" }
        checkVersion(o.opt("version"))
        val arr = o.getJSONArray("rules")
        val rules = held((0 until arr.length()).map { AppRule.fromJson(arr.getJSONObject(it)) })
        val settings = o.optJSONObject("settings")
        val hide = settings?.optJSONArray("hideRules")?.let { a -> (0 until a.length()).map { HideRule.fromJson(a.getJSONObject(it)) } }
        require(hide == null || NotifRules.importable(hide)) { "Некорректные правила скрытия в резервной копии" }
        val changes = mutableListOf<() -> Unit>()
        settings?.let { parseSettings(it, changes) }
        return Plan(rules, hide, changes)
    }

    /** Applies what [parse] read and returns the number of rules. */
    internal suspend fun apply(plan: Plan): Int {
        // The one step that can still fail goes first, with everything else untouched.
        plan.hideRules?.let { NotifRules.replace(it).getOrThrow() }
        plan.changes.forEach { it() }
        // Whatever the old rules were holding goes back first: reconcile sees them as gone.
        Prefs.appRules = plan.rules
        Prefs.appHandled = emptyMap()
        plan.rules.forEach { AppFreezer.markSaved(it) }
        RuleStore.changed()
        App.scope.launch { runCatching { NetworkTweaks.apply() }; TetherWatch.sync() }
        return plan.rules.size
    }
}
