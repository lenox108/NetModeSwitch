package com.netmode.switcher

import android.content.Context
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SharedPreferences rather than DataStore on purpose: the alarm receiver, the Wi-Fi receiver
 * and the QS tile all need this synchronously, before they are allowed to suspend.
 */
object Prefs {

    private const val FILE = "netmode"
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_WIFI = "wifi_rules"
    private const val KEY_CATCH_UP = "catch_up_on_boot"
    private const val KEY_RELIABLE_WIFI = "reliable_wifi"
    private const val KEY_EVENTS = "events"
    private const val KEY_LAST_SSID = "last_ssid"
    private const val KEY_IGNORE_WIFI_OFF = "ignore_wifi_off"
    private const val KEY_BACKEND = "backend"
    private const val KEY_APP_RULES = "app_rules"
    private const val KEY_FROZEN = "frozen_apps"
    private const val KEY_APP_HANDLED = "app_rules_handled"
    private const val KEY_HIDE_SYSTEM = "hide_system_apps"
    private const val MAX_EVENTS = 300

    private val sp by lazy { App.ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    var schedules: List<ScheduleRule>
        get() = decodeList(sp.getString(KEY_SCHEDULES, "[]")!!, ScheduleRule::fromJson)
        set(value) = sp.edit().putString(KEY_SCHEDULES, encodeList(value) { it.toJson() }).apply()

    var wifiRules: List<WifiRule>
        get() = decodeList(sp.getString(KEY_WIFI, "[]")!!, WifiRule::fromJson)
        set(value) = sp.edit().putString(KEY_WIFI, encodeList(value) { it.toJson() }).apply()

    var appRules: List<AppRule>
        get() = decodeList(sp.getString(KEY_APP_RULES, "[]")!!, AppRule::fromJson)
        set(value) = sp.edit().putString(KEY_APP_RULES, encodeList(value) { it.toJson() }).apply()

    /** Apps currently held down, with what to put back; survives reboots on purpose. */
    var frozen: List<FrozenApp>
        get() = decodeList(sp.getString(KEY_FROZEN, "[]")!!, FrozenApp::fromJson)
        set(value) = sp.edit().putString(KEY_FROZEN, encodeList(value) { it.toJson() }).commit().let { }

    /**
     * Rule id → the start (epoch ms) that has already been acted on. Stops a late or repeated
     * alarm from stopping the same apps twice, and a freshly saved rule from firing at once.
     */
    var appHandled: Map<Long, Long>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString(KEY_APP_HANDLED, "{}")!!)
            o.keys().asSequence().associate { it.toLong() to o.getLong(it) }
        }.getOrDefault(emptyMap())
        set(value) = sp.edit().putString(
            KEY_APP_HANDLED,
            org.json.JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), v) } }.toString(),
        ).apply()

    /** When the app last switched Wi-Fi or the hotspot itself; see AppFreezer.changedWifiRecently. */
    var selfWifiAt: Long
        get() = sp.getLong("self_wifi_at", 0L)
        set(value) = sp.edit().putLong("self_wifi_at", value).commit().let { }

    /** When a rule last switched Bluetooth off; the disconnects that follow are its own doing. */
    var selfBtAt: Long
        get() = sp.getLong("self_bt_at", 0L)
        set(value) = sp.edit().putLong("self_bt_at", value).commit().let { }

    /**
     * Devices connected, as the ACL broadcasts told; see BtReceiver. Tagged with the boot count,
     * so a reboot empties it without depending on the order of boot-time broadcasts.
     */
    var btConnected: Set<String>
        get() = if (sp.getInt("bt_connected_boot", -1) != bootCount()) emptySet()
        else sp.getStringSet("bt_connected", emptySet())!!
        set(value) = sp.edit().putStringSet("bt_connected", value).putInt("bt_connected_boot", bootCount()).commit().let { }

    private fun bootCount(): Int =
        android.provider.Settings.Global.getInt(App.ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    /** Rules whose window could not be closed for lack of root/Shizuku; retried by reconcile. */
    var pendingClose: Set<Long>
        get() = sp.getStringSet("pending_close", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("pending_close", value.map { it.toString() }.toSet()).commit().let { }

    /** Apps "Выгрузить" never touches, in every rule and on the widget (app keys, clones too). */
    var unloadExclude: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString("unload_exclude", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        set(value) = sp.edit().putString("unload_exclude", JSONArray(value).toString()).apply()

    /** Bluetooth devices seen connecting; see BtDevices. */
    var knownBt: List<BtDevice>
        get() = decodeList(sp.getString("known_bt", "[]")!!) { BtDevice(it.getString("a"), it.getString("n")) }
        set(value) = sp.edit().putString(
            "known_bt",
            encodeList(value) { org.json.JSONObject().put("a", it.address).put("n", it.name) },
        ).apply()

    /**
     * Rule id → when its window opened. A window is closed exactly once, and closing is what
     * applies the rule's end: put back, or its own end state.
     */
    var openWindows: Map<Long, Long>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("open_windows", "{}")!!)
            o.keys().asSequence().associate { it.toLong() to o.getLong(it) }
        }.getOrDefault(emptyMap())
        set(value) = sp.edit().putString(
            "open_windows",
            org.json.JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), v) } }.toString(),
        ).commit().let { }

    /** Old schedule and Wi-Fi network-mode rules have been moved into [appRules]. */
    var migratedRules: Boolean
        get() = sp.getBoolean("migrated_rules_v3", false)
        set(value) = sp.edit().putBoolean("migrated_rules_v3", value).commit().let { }

    /** Last mobile-data state seen by DataWatch; null before the first look. */
    var lastDataOn: Boolean?
        get() = if (sp.contains("last_data_on")) sp.getBoolean("last_data_on", false) else null
        set(value) = sp.edit().apply {
            if (value == null) remove("last_data_on") else putBoolean("last_data_on", value)
        }.commit().let { }

    /** Where hotspot clients' traffic goes; see TetherRoute. */
    var tetherMode: TetherMode
        get() = runCatching { TetherMode.valueOf(sp.getString("tether_mode", null) ?: "DIRECT") }
            .getOrDefault(TetherMode.DIRECT)
        set(value) = sp.edit().putString("tether_mode", value.name).commit().let { }

    var tetherTtlEnabled: Boolean
        get() = sp.getBoolean("tether_ttl_enabled", false)
        set(value) = sp.edit().putBoolean("tether_ttl_enabled", value).commit().let { }

    /** Zero selects the phone's IPv4 TTL; this does not change ip_default_ttl itself. */
    var tetherTtlValue: Int
        get() = sp.getInt("tether_ttl_value", 0).takeIf { it in 0..255 } ?: 0
        set(value) {
            require(value in 0..255)
            sp.edit().putInt("tether_ttl_value", value).commit()
        }

    /** The 3.13 choice is preserved, with its scope narrowed to tethered clients. */
    var disableTetherIpv6: Boolean
        get() = sp.getBoolean("disable_tether_ipv6", sp.getBoolean("disable_ipv6", false))
        set(value) = sp.edit().putBoolean("disable_tether_ipv6", value).remove("disable_ipv6").commit().let { }

    /** Original system settings, not a portable user setting: never include in backups. */
    internal var networkTweaksState: String?
        get() = sp.getString("network_tweaks_state", null)
        set(value) = sp.edit().putString("network_tweaks_state", value).commit().let { }

    /** SOCKS5 proxy for [tetherMode] PROXY, "host:port". */
    var tetherProxy: String
        get() = sp.getString("tether_proxy", "127.0.0.1:10808")!!
        set(value) = sp.edit().putString("tether_proxy", value).apply()

    /** Optional SOCKS5 login for [tetherProxy]. */
    var tetherProxyUser: String
        get() = sp.getString("tether_proxy_user", "")!!
        set(value) = sp.edit().putString("tether_proxy_user", value).apply()

    var tetherProxyPass: String
        get() = sp.getString("tether_proxy_pass", "")!!
        set(value) = sp.edit().putString("tether_proxy_pass", value).apply()

    var dualSimEnabled: Boolean
        get() = sp.getBoolean("dual_sim", false)
        set(value) = sp.edit().putBoolean("dual_sim", value).apply()

    var dualPrimary: NetMode
        get() = runCatching { NetMode.valueOf(sp.getString("dual_primary", null) ?: "FULL") }.getOrDefault(NetMode.FULL)
        set(value) = sp.edit().putString("dual_primary", value.name).apply()

    var dualSecondary: NetMode
        get() = runCatching { NetMode.valueOf(sp.getString("dual_secondary", null) ?: "TWO_G") }.getOrDefault(NetMode.TWO_G)
        set(value) = sp.edit().putString("dual_secondary", value.name).apply()

    /** The 4G-or-better mode a slot was last in, which the toggle goes back up to. */
    fun lastHigh(slot: Int): NetMode =
        runCatching { NetMode.valueOf(sp.getString("last_high_$slot", null)!!) }.getOrNull()?.takeIf { it.high } ?: NetMode.FULL

    /**
     * The two modes the quick switch of [slot] goes between — the button, its tile, the widget,
     * the notification — the first being where it goes from anything else. Null: the faster
     * one last used ⇄ 3G.
     */
    fun togglePair(slot: Int): Pair<NetMode, NetMode>? = runCatching {
        val (a, b) = sp.getString("toggle_pair_$slot", null)!!.split(',').map { NetMode.valueOf(it) }
        a to b
    }.getOrNull()

    fun setTogglePair(slot: Int, pair: Pair<NetMode, NetMode>?) =
        sp.edit().apply {
            if (pair == null) remove("toggle_pair_$slot") else putString("toggle_pair_$slot", "${pair.first.name},${pair.second.name}")
        }.apply()

    /** Whether [slot]'s tile is in the shade, as last told by the tile itself. */
    fun tileShown(slot: Int): Boolean = sp.getBoolean("tile_shown_$slot", false)

    fun setTileShown(slot: Int, shown: Boolean) {
        if (tileShown(slot) != shown) sp.edit().putBoolean("tile_shown_$slot", shown).apply()
    }

    fun setLastHigh(slot: Int, mode: NetMode) {
        if (sp.getString("last_high_$slot", null) != mode.name) sp.edit().putString("last_high_$slot", mode.name).apply()
    }

    /**
     * Set when a rule switched Wi-Fi off on leaving its network, cleared once Wi-Fi is back:
     * only then is Wi-Fi woken near the network — never when the user switched it off.
     */
    var wakeArmed: Boolean
        get() = sp.getBoolean("wake_armed", false)
        set(value) = sp.edit().putBoolean("wake_armed", value).apply()

    /** Checks in a row that found nothing near the network; sets the backoff. See WifiWake. */
    var wakeFails: Int
        get() = sp.getInt("wake_fails", 0)
        set(value) = sp.edit().putInt("wake_fails", value).apply()

    /** "ruleId|tower" → [checks that found the network, checks that did not], as JSON. */
    var wakeScores: String
        get() = sp.getString("wake_scores", "{}")!!
        set(value) = sp.edit().putString("wake_scores", value).apply()

    /** When Wi-Fi was last woken near a network; see WifiWake. */
    var wakeAt: Long
        get() = sp.getLong("wake_at", 0L)
        set(value) = sp.edit().putLong("wake_at", value).apply()

    /** The phone check's result as JSON; see Profile. */
    var deviceProfile: String?
        get() = sp.getString("device_profile", null)
        set(value) = sp.edit().putString("device_profile", value).apply()

    /** Whether the first start has offered the phone check already. */
    var checkOffered: Boolean
        get() = sp.getBoolean("check_offered", false)
        set(value) = sp.edit().putBoolean("check_offered", value).apply()

    /** Last tether routing result logged; the same line is not logged again. */
    var lastTetherLine: String?
        get() = sp.getString("last_tether_line", null)
        set(value) = sp.edit().putString("last_tether_line", value).apply()

    /**
     * Rule id → what of its start is still waiting for the phone to be locked: "all", or
     * "apps" when only stopping apps waits. See AppFreezer.runPending.
     */
    var pendingStarts: Map<Long, String>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("pending_starts", "{}")!!)
            o.keys().asSequence().associate { it.toLong() to o.getString(it) }
        }.getOrDefault(emptyMap())
        set(value) = sp.edit().putString(
            "pending_starts",
            org.json.JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), v) } }.toString(),
        ).commit().let { }

    /**
     * Lock rules unlocked inside their time ([AppRule.splitEnd]): their unlock block has run,
     * the night goes on until the time is over or the phone is locked again.
     */
    /** Rules whose start waits for an answer to "Выполнить?" (see [RunNotices]). */
    var askedStarts: Set<Long>
        get() = sp.getStringSet("asked_starts", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("asked_starts", value.map { it.toString() }.toSet()).commit().let { }

    var lockPaused: Set<Long>
        get() = sp.getStringSet("lock_paused", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("lock_paused", value.map { it.toString() }.toSet()).commit().let { }

    /**
     * "Раздача через VPN" through the app's own SOCKS server (see [LocalSocks]) rather than by
     * forwarding packets into the VPN: as fast as the proxy route. On unless switched off.
     */
    var vpnFast: Boolean
        get() = sp.getBoolean("vpn_fast", true)
        set(value) = sp.edit().putBoolean("vpn_fast", value).apply()

    /** Rules waiting out a pause between their steps; see [StepQueue]. */
    var pendingSteps: List<PendingSteps>
        get() = runCatching {
            val a = org.json.JSONArray(sp.getString("pending_steps", "[]")!!)
            (0 until a.length()).map { PendingSteps.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(value) = sp.edit().putString(
            "pending_steps",
            org.json.JSONArray().apply { value.forEach { put(it.toJson()) } }.toString(),
        ).commit().let { }

    /** Keep a notification with the SIM modes and a switch button. */
    var statusNotification: Boolean
        get() = sp.getBoolean("status_notification", false)
        set(value) = sp.edit().putBoolean("status_notification", value).apply()

    var hideSystemApps: Boolean
        get() = sp.getBoolean(KEY_HIDE_SYSTEM, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_SYSTEM, value).apply()

    var catchUpOnBoot: Boolean
        get() = sp.getBoolean(KEY_CATCH_UP, true)
        set(value) = sp.edit().putBoolean(KEY_CATCH_UP, value).apply()

    /**
     * On: a persistent watcher service, which is the only arrangement that survives OxygenOS
     * dropping the PendingIntent registration. Off: notification-free but occasionally misses
     * events, and the disconnect half cannot start its service from the background at all.
     */
    var reliableWifi: Boolean
        get() = sp.getBoolean(KEY_RELIABLE_WIFI, true)
        set(value) = sp.edit().putBoolean(KEY_RELIABLE_WIFI, value).apply()

    /** SSID we last acted on; persisted so a disconnect is still attributable after a restart. */
    var lastSsid: String?
        get() = sp.getString(KEY_LAST_SSID, null)
        set(value) = sp.edit().putString(KEY_LAST_SSID, value).apply()

    /**
     * Legacy global switch; now only the default for rules saved before it became per-rule.
     * Losing the network because the adapter was switched off is not the same as walking out
     * of range, and night-time power saving does exactly the former.
     */
    var ignoreWifiOff: Boolean
        get() = sp.getBoolean(KEY_IGNORE_WIFI_OFF, true)
        set(value) = sp.edit().putBoolean(KEY_IGNORE_WIFI_OFF, value).apply()

    /** Which privileged channel to use; AUTO prefers root, then Shizuku. */
    var backend: Backend
        get() = runCatching { Backend.valueOf(sp.getString(KEY_BACKEND, null) ?: "AUTO") }
            .getOrDefault(Backend.AUTO)
        set(value) = sp.edit().putString(KEY_BACKEND, value.name).apply()

    val events: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString(KEY_EVENTS, "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())

    fun log(line: String) {
        val stamp = LocalDateTime.now().format(TIME)
        val next = (listOf("$stamp  $line") + events).take(MAX_EVENTS)
        sp.edit().putString(KEY_EVENTS, JSONArray(next).toString()).apply()
    }

    fun clearLog() = sp.edit().remove(KEY_EVENTS).apply()

    /** What a home-screen widget was set up to show; null until it is configured. */
    fun widgetConfig(id: Int): String? = sp.getString("widget_$id", null)

    fun setWidgetConfig(id: Int, value: String?) =
        sp.edit().apply { if (value == null) remove("widget_$id") else putString("widget_$id", value) }.apply()

    /** How opaque a widget's backing is, 0 (none) to 100; solid until chosen. */
    fun widgetAlpha(id: Int): Int = sp.getInt("widget_alpha_$id", 100)

    fun setWidgetAlpha(id: Int, value: Int?) =
        sp.edit().apply { if (value == null) remove("widget_alpha_$id") else putInt("widget_alpha_$id", value.coerceIn(0, 100)) }.apply()

    /** A widget's text size, % of the designed one. */
    fun widgetScale(id: Int): Int = sp.getInt("widget_scale_$id", 100)

    fun setWidgetScale(id: Int, value: Int?) =
        sp.edit().apply { if (value == null) remove("widget_scale_$id") else putInt("widget_scale_$id", value.coerceIn(40, 120)) }.apply()

    /** A widget's text colour: "auto" (by the wallpaper when the backing is thin), "light", "dark" or "theme". */
    fun widgetText(id: Int): String = sp.getString("widget_text_$id", "auto")!!

    fun setWidgetText(id: Int, value: String?) =
        sp.edit().apply { if (value == null) remove("widget_text_$id") else putString("widget_text_$id", value) }.apply()

    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /** True the first time it is asked for [key], false ever after. */
    fun once(key: String): Boolean {
        if (sp.getBoolean("once_$key", false)) return false
        sp.edit().putBoolean("once_$key", true).apply()
        return true
    }

    fun newId(): Long = System.currentTimeMillis()

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
}
