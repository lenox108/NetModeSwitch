package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** How hard an app is held down for the length of the window. */
enum class FreezeMethod(val label: String) {
    /** force-stop only: the app is unloaded but may come back on its own. */
    STOP("Остановить"),

    /**
     * force-stop plus a background ban until the window ends: RUN_ANY_IN_BACKGROUND=ignore
     * where the channel allows it (root — OxygenOS strips MANAGE_APP_OPS_MODES from shell)
     * and the "restricted" standby bucket, which shell can set too.
     */
    STOP_RESTRICT("Остановить + запрет фона"),

    /** pm suspend: greyed-out icon, cannot be opened or run at all until the window ends. */
    SUSPEND("Заморозить"),
}

/** "точка доступа вкл · через Proxy" */
internal fun apLabel(t: Toggle, via: TetherMode?, band: String? = null): String =
    if (t == Toggle.OFF) "точка доступа выкл"
    else "точка доступа вкл" + when (via) {
        TetherMode.VPN -> " · через VPN"
        TetherMode.PROXY -> " · через Proxy"
        else -> ""
    } + (band?.let { " · ${ApRadio.label(it)}" } ?: "")

internal fun vpnLabel(toggle: Toggle?, offApp: String?): String? = toggle?.let {
    if (it == Toggle.ON) "VPN вкл"
    else "VPN" + (offApp?.let { p -> " ${AppPickerActivity.labels(listOf(p)).first()}" } ?: "") + " выкл"
}

/** What starts a rule's window. */
enum class Trigger {
    /**
     * A scene: started by hand — a home-screen shortcut, the app icon's menu, the rules widget.
     * With an end it is a switch, the next press ends it, or [AppRule.level] minutes do.
     */
    MANUAL,

    /** hour:minute on the chosen days, until the optional end time. */
    TIME,

    /** While connected to [AppRule.ssid]. */
    WIFI,

    /** From leaving [AppRule.ssid] until the end time, a Bluetooth disconnect or coming back. */
    WIFI_LEFT,

    /** While the Bluetooth device [AppRule.btAddress] is connected. */
    BLUETOOTH,

    /** While mobile data is switched on. */
    MOBILE_DATA,

    /** While SIM [AppRule.triggerSlot] is in network mode [AppRule.triggerMode]. */
    NET_MODE,

    /** While the battery is at or below [AppRule.level] %, or battery saver is on (level 0). */
    BATTERY,

    /** While the phone is charging. */
    CHARGING,

    /** While the screen has been off for at least [AppRule.level] minutes. */
    SCREEN_OFF,

    /**
     * While the phone is locked, from [AppRule.level] minutes after the screen went off (0: at
     * once) until it is unlocked. Waking the screen on the lock screen is still locked.
     */
    LOCKED,

    /** While one of [AppRule.triggerApps] is on screen. */
    APP_OPEN,

    /** While the phone is on one of the cell towers of [AppRule.cells] — a place without GPS. */
    PLACE,

    /** While a SIM is roaming. */
    ROAMING,

    /** While SIM [AppRule.triggerSlot]'s signal is at or below [AppRule.level] bars. */
    WEAK_SIGNAL,

    /** During a call, a messenger call included. */
    CALL,

    /** While headphones are connected, wired or Bluetooth. */
    HEADPHONES,

    /** While Do Not Disturb is on. */
    DND,

    /** While there is no internet for longer than [AppRule.level] seconds (see [InternetLoss]). */
    NO_INTERNET,

    /**
     * While one of [AppRule.triggerApps] has a notification up; with [AppRule.level] 1 only a
     * lasting one — the app works in the background: music, a download, navigation.
     */
    NOTIFY_APP,

    /** While a computer is connected over USB. */
    USB,

    /** While the phone is in car mode — Android Auto. */
    CAR,

    /** From [AppRule.level] minutes before the next alarm clock until it rings. */
    ALARM,

    /** While it is dark — after sunset, before sunrise — or, with [AppRule.level] 1, light. */
    SUN,

    /** While mobile traffic of the day (or the month, [AppRule.trafficMonth]) is at least [AppRule.level] MB. */
    TRAFFIC,

    /** While connected to a Wi-Fi network not in [AppRule.knownSsids] — a café, a hotel. */
    WIFI_OTHER,

    /** While a VPN is connected: any, or one of [AppRule.triggerApps]'s. */
    VPN,

    /** While Wi-Fi sharing is on, optionally in [AppRule.triggerHotspotVia]'s route mode. */
    HOTSPOT,

    /** Each press of a selected app tile in the shade; a one-shot event. */
    TILE_CLICK;

    /** Triggers that hold for a while and then stop: their rules can put things back. */
    val isCondition: Boolean get() = this != TIME && this != WIFI_LEFT && this != TILE_CLICK
}

/** What the user hears of a rule starting by itself; never asked when started by hand. */
enum class RunNotice {
    SILENT,

    /** A notification of what was done, with a button to undo it. */
    NOTIFY,

    /** A notification asking first: the start waits for "Выполнить". */
    ASK,
}

/** "500 МБ", "5 ГБ" */
internal fun trafficLabel(mb: Int): String = if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} ГБ" else "$mb МБ"

private val CONFLICT_NAMES = mapOf(
    "NET" to "режим сети", "DATA" to "моб. интернет", "WIFI" to "Wi-Fi", "HOTSPOT" to "точка доступа",
    "VPN" to "VPN", "AIRPLANE" to "режим полёта", "POWERSAVE" to "энергосбережение",
    "DATASIM" to "SIM для интернета", "BT" to "Bluetooth", "LOC" to "геолокация", "TILE" to "плитки",
)

/** Turn something on or off for the window; null in a rule means "leave it alone". */
enum class Toggle { ON, OFF }

/**
 * A window with a set of actions, and everything put back as it was when the window closes.
 *
 * For [Trigger.TIME] the window runs from [hour]:[minute] (filtered by [days]) to
 * [endHour]:[endMinute]; the end belongs to the start it follows, so a 23:00–07:00 rule on
 * Friday ends on Saturday morning. [Trigger.WIFI_LEFT] reuses the end time — "until 19:00" —
 * and may also end on [endBtAddress] disconnecting, i.e. arriving somewhere by car.
 */
data class AppRule(
    val id: Long,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val endHour: Int?,
    val endMinute: Int?,
    val days: Int,
    val packages: List<String>,
    val method: FreezeMethod,
    val ssid: String? = null,
    /** Put everything back when the triggering connection ends. */
    val releaseOnLeave: Boolean = true,
    /** Switch Bluetooth off for the window, back on afterwards if it was on. */
    val bluetoothOff: Boolean = false,
    /** Same for location. */
    val locationOff: Boolean = false,
    /** Wi-Fi rules: whether switching the adapter off counts as leaving the network. */
    val leaveOnWifiOff: Boolean = false,
    val trigger: Trigger = if (ssid != null) Trigger.WIFI else Trigger.TIME,
    val btAddress: String? = null,
    val btName: String? = null,
    val endBtAddress: String? = null,
    val endBtName: String? = null,
    val wifi: Toggle? = null,
    val hotspot: Toggle? = null,
    /** With [hotspot] ON: where the hotspot's traffic goes; null leaves the current mode. */
    val hotspotVia: TetherMode? = null,
    val mobileData: Toggle? = null,
    /**
     * What happens when the window closes: false puts everything back as it was, true applies
     * the end* values below instead (stopped apps are always put back either way).
     */
    val endCustom: Boolean = false,
    val endNet: NetMode? = null,
    val endData: Toggle? = null,
    val endWifi: Toggle? = null,
    val endHotspot: Toggle? = null,
    val endHotspotVia: TetherMode? = null,
    val endBt: Toggle? = null,
    val endLoc: Toggle? = null,
    /**
     * Apps left stopped at the end. Every other app of the rule is taken out of the "stopped"
     * state (pm unstop) so push messages and system events reach it again without the user
     * opening it. Nothing is launched.
     */
    val keepStopped: Set<String> = emptySet(),
    /** Other apps switched through their Quick Settings tile: component → wanted state. */
    val tiles: Map<String, Toggle> = emptyMap(),
    val endTiles: Map<String, Toggle> = emptyMap(),
    /**
     * The system VPN: [vpn] off disconnects [vpnOffApp] (null: any), on starts [vpnApp].
     * [vpnTile] is the older, tile-based form of the same choice, read only to find the app.
     */
    val vpnApp: String? = null,
    val vpnTile: String? = null,
    val vpn: Toggle? = null,
    val endVpn: Toggle? = null,
    /** Separate off targets keep old rules (including a stale [vpnApp]) unscoped. */
    val vpnOffApp: String? = null,
    val endVpnOffApp: String? = null,
    /** For [Trigger.NET_MODE]: which SIM is watched and for which mode. */
    val triggerSlot: Int = 0,
    val triggerMode: NetMode? = null,
    /** The user's own name for the rule; null shows the generated description. */
    val name: String? = null,
    /** Per-app method; apps not listed use [method]. */
    val appMethods: Map<String, FreezeMethod> = emptyMap(),
    val netMode: NetMode? = null,
    val netSlot: Int = SLOT_ALL,
    /**
     * Threshold of the trigger: battery %, minutes of screen off, signal bars, seconds without
     * internet; a scene's minutes until it ends by itself.
     */
    val level: Int = 0,
    /** APP_OPEN, NOTIFY_APP: the apps watched. VPN: the VPN apps that count; empty, any VPN. */
    val triggerApps: List<String> = emptyList(),
    /** For [Trigger.PLACE]: "tac:ci" of the towers seen there, and the user's name for it. */
    val cells: Set<String> = emptySet(),
    val placeName: String? = null,
    val airplane: Toggle? = null,
    val endAirplane: Toggle? = null,
    val powerSave: Toggle? = null,
    val endPowerSave: Toggle? = null,
    /** Slot of the SIM to use for mobile data, or null. */
    val dataSim: Int? = null,
    val endDataSim: Int? = null,
    /**
     * Not while the phone is in use (screen on and unlocked): the start waits until a minute
     * after it is locked. The end is never held back.
     */
    val waitLocked: Boolean = false,
    /** With [waitLocked]: everything else applies at once, only stopping apps waits. */
    val waitAppsOnly: Boolean = false,
    /**
     * Wi-Fi rules: switch Wi-Fi on near this network when it is off. The towers seen while
     * connected are learned into [cells]; see WifiWake.
     */
    val wakeWifi: Boolean = false,
    /** Apps opened when the window opens. */
    val launchApps: List<String> = emptyList(),
    /**
     * At the end, whichever way it ends (put back or set by hand): apps to stop — "left the
     * car, the navigator closes" — and apps to open.
     */
    val endStopApps: List<String> = emptyList(),
    /** Per-app method for [endStopApps], like [appMethods]; held until the next start. */
    val endAppMethods: Map<String, FreezeMethod> = emptyMap(),
    val endLaunchApps: List<String> = emptyList(),
    /** Background processes of every app unloaded when the window opens, and at its end. */
    val unload: Boolean = false,
    val endUnload: Boolean = false,
    /** "Выгрузить всё": music, navigation and the like too (see AppFreezer.unloadBackground). */
    val unloadAll: Boolean = false,
    val endUnloadAll: Boolean = false,
    /**
     * LOCKED: only within [hour]:[minute]–[endHour]:[endMinute] on [days] (0 = every day). A
     * window that starts with the phone locked starts the rule; one that ends closes it.
     */
    val lockWindow: Boolean = false,
    /**
     * The order of "Что сделать" and of the end, with pauses (see [Steps]); empty for the old
     * fixed order.
     */
    val steps: List<String> = emptyList(),
    val endSteps: List<String> = emptyList(),
    /**
     * LOCKED with a time: what unlocking inside the time does, apart from the end of the time
     * (the end fields above). Null: unlocking ends the night, as before this existed.
     */
    val unlockEnd: EndSpec? = null,
    /**
     * APP_OPEN: leaving counts once the app has been away this many seconds — a link opened in
     * a browser, a glance at a message — so a short trip out does not end the rule and start it
     * again a moment later. Zero: at once, as before this existed. CAR: the same for the car
     * going — a wireless Android Auto drop of a few seconds is not leaving it. VPN: a reconnect
     * on a change of network is not the VPN going.
     */
    val appLeaveDelay: Int = 0,
    /**
     * APP_OPEN: apps that do not count as leaving while they are on top of the rule's app — a
     * bank's 3-D Secure, a browser. System dialogs and browser tabs are let through anyway
     * (see [AppPresence]).
     */
    val appPassThrough: List<String> = emptyList(),
    /** APP_OPEN: the screen going off does not count as leaving — a navigator, a video. */
    val appScreenOffStays: Boolean = false,
    /**
     * At the start: airplane mode for a moment and back, so the modem registers with the
     * network afresh — what brings a stuck mobile connection back. Nothing to put back.
     */
    val reconnect: Boolean = false,
    /** At the start: the freezer thaws or freezes apps (see [Freezer.ruleStart]). */
    val ice: IceAction? = null,
    val notice: RunNotice = RunNotice.SILENT,
    /** "И ещё если": states the rule needs besides its trigger (see [Extra]). */
    val extras: List<Extra> = emptyList(),
    /** "Системные настройки": [SysKey] name → option code, at the start and in a custom end. */
    val sys: Map<String, String> = emptyMap(),
    val endSys: Map<String, String> = emptyMap(),
    /** One-offs at the start and at the end, whichever way it ends: a notification, a request, a command. */
    val note: String? = null,
    val endNote: String? = null,
    val http: String? = null,
    val endHttp: String? = null,
    val shell: String? = null,
    val endShell: String? = null,
    /** [Trigger.TRAFFIC]: count the month rather than the day. */
    val trafficMonth: Boolean = false,
    /** [Trigger.WIFI_OTHER]: the user's own networks. */
    val knownSsids: List<String> = emptyList(),
    /** [Trigger.HOTSPOT]: null accepts any route; independent of the hotspot action's mode. */
    val triggerHotspotVia: TetherMode? = null,
    val triggerHotspotBand: String? = null,
    val hotspotBand: String? = null,
    val endHotspotBand: String? = null,
    /** Stable tile component, plus its label as shown in the shade. */
    val triggerTile: String? = null,
    val triggerTileLabel: String? = null,
) {
    /** What the start sets, key → value, to tell rules that pull the same thing different ways. */
    val sets: Map<String, String>
        get() = buildMap {
            netMode?.let { m -> (if (netSlot == SLOT_ALL) listOf(0, 1) else listOf(netSlot)).forEach { put("NET$it", m.name) } }
            mobileData?.let { put("DATA", it.name) }
            wifi?.let { put("WIFI", it.name) }
            hotspot?.let { put("HOTSPOT", it.name) }
            if (hotspot == Toggle.ON) {
                hotspotBand?.let { put("HOTSPOT:BAND", it) }
                hotspotVia?.let { put("HOTSPOT:ROUTE", it.name) }
            }
            vpn?.let { put("VPN", it.name) }
            airplane?.let { put("AIRPLANE", it.name) }
            powerSave?.let { put("POWERSAVE", it.name) }
            dataSim?.let { put("DATASIM", it.toString()) }
            if (bluetoothOff) put("BT", "OFF")
            if (locationOff) put("LOC", "OFF")
            tiles.forEach { (c, t) -> put("TILE:$c", t.name) }
            sys.forEach { (k, v) -> put("SYS:$k", v) }
        }

    /** Other enabled rules that set something this one sets, to another value: rule → what. */
    fun conflicts(others: List<AppRule>): List<Pair<AppRule, List<String>>> {
        val mine = sets
        return others.filter { it.enabled && it.id != id }.mapNotNull { o ->
            val theirs = o.sets
            val clash = mine.filter { (k, v) -> theirs[k] != null && theirs[k] != v }.keys
                .map { k -> SysKey.of(k.removePrefix("SYS:"))?.title?.lowercase()?.takeIf { k.startsWith("SYS:") }
                    ?: CONFLICT_NAMES[k.substringBefore(':').trimEnd('0', '1')] ?: k }
                .distinct()
            if (clash.isEmpty()) null else o to clash
        }
    }

    /** The end fields, as one ending. */
    val endSpec: EndSpec
        get() = EndSpec(
            mode = if (endCustom) EndMode.CUSTOM else EndMode.RESTORE,
            net = endNet, data = endData, wifi = endWifi, hotspot = endHotspot, hotspotVia = endHotspotVia, hotspotBand = endHotspotBand,
            bt = endBt, loc = endLoc, vpn = endVpn, vpnOffApp = endVpnOffApp, airplane = endAirplane, powerSave = endPowerSave,
            dataSim = endDataSim, tiles = endTiles, stopApps = endStopApps, appMethods = endAppMethods,
            launchApps = endLaunchApps, unload = endUnload, unloadAll = endUnloadAll, steps = endSteps,
            sys = endSys, note = endNote, http = endHttp, shell = endShell,
        )

    /** This rule with [e] as its end: the unlock block runs through the end's own code. */
    fun withEnd(e: EndSpec): AppRule = copy(
        endCustom = e.mode == EndMode.CUSTOM,
        endNet = e.net, endData = e.data, endWifi = e.wifi, endHotspot = e.hotspot, endHotspotVia = e.hotspotVia, endHotspotBand = e.hotspotBand,
        endBt = e.bt, endLoc = e.loc, endVpn = e.vpn, endVpnOffApp = e.vpnOffApp, endAirplane = e.airplane, endPowerSave = e.powerSave,
        endDataSim = e.dataSim, endTiles = e.tiles, endStopApps = e.stopApps, endAppMethods = e.appMethods,
        endLaunchApps = e.launchApps, endUnload = e.unload, endUnloadAll = e.unloadAll, endSteps = e.steps,
        endSys = e.sys, endNote = e.note, endHttp = e.http, endShell = e.shell,
    )

    /** Unlocking inside the time has its own block: the night goes on until the time is over. */
    val splitEnd: Boolean
        get() = unlockEnd != null && trigger == Trigger.LOCKED && lockWindow && releaseOnLeave &&
            endHour != null && endMinute != null

    fun endMethodOf(pkg: String): FreezeMethod = endAppMethods[pkg] ?: FreezeMethod.STOP_RESTRICT

    val hasActions: Boolean
        get() = packages.isNotEmpty() || bluetoothOff || locationOff ||
            wifi != null || hotspot != null || netMode != null || mobileData != null ||
            tiles.isNotEmpty() || vpn != null || airplane != null || powerSave != null || dataSim != null ||
            launchApps.isNotEmpty() || unload || reconnect || ice != null || sys.isNotEmpty() || note != null || http != null || shell != null ||
            (restores && (endStopApps.isNotEmpty() || endLaunchApps.isNotEmpty() || endUnload || endSets ||
                endNote != null || endHttp != null || endShell != null))

    /**
     * The end sets something of its own ("Задать вручную"), which is enough for a rule: "when
     * the app is closed — VPN off" needs nothing done at the start.
     */
    private val endSets: Boolean
        get() = endCustom && (endNet != null || endData != null || endWifi != null || endHotspot != null ||
            endBt != null || endLoc != null || endVpn != null || endAirplane != null || endPowerSave != null ||
            endDataSim != null || endTiles.isNotEmpty() || endSys.isNotEmpty())

    /** Whether an entry in the frozen list still belongs to this rule as it now stands. */
    fun covers(entry: String): Boolean = when {
        entry == FrozenApp.BLUETOOTH -> bluetoothOff
        entry == FrozenApp.LOCATION -> locationOff
        entry == FrozenApp.WIFI -> wifi != null
        entry == FrozenApp.HOTSPOT -> hotspot != null
        entry == FrozenApp.TETHER_MODE -> hotspot == Toggle.ON && (hotspotVia != null || hotspotBand != null)
        entry == FrozenApp.HOTSPOT_RADIO -> hotspot == Toggle.ON && hotspotBand != null
        entry == FrozenApp.DATA -> mobileData != null
        entry.startsWith(FrozenApp.NET) -> netMode != null &&
            (netSlot == SLOT_ALL || entry == FrozenApp.NET + netSlot)
        entry.startsWith(FrozenApp.TILE) -> entry.removePrefix(FrozenApp.TILE) in tiles
        entry == FrozenApp.SYSVPN -> vpn != null
        entry == FrozenApp.AIRPLANE -> airplane != null
        entry == FrozenApp.POWERSAVE -> powerSave != null
        entry == FrozenApp.DATASIM -> dataSim != null
        entry.startsWith(FrozenApp.SYS) -> entry.removePrefix(FrozenApp.SYS) in sys
        entry.startsWith(FrozenApp.VPN_BACK) -> entry.removePrefix(FrozenApp.VPN_BACK) in packages
        else -> entry in packages
    }

    /** "Остановить + запрет фона · BT · гео" — what the rule does, in one line. */
    val actionLabel: String
        get() = ordered(steps, linkedMapOf(
            "APPS" to appsLabel,
            "NET" to netMode?.let { (if (netSlot == SLOT_ALL) "все SIM" else "SIM ${netSlot + 1}") + " → ${it.short}" },
            "DATA" to mobileData?.let { if (it == Toggle.ON) "моб. интернет вкл" else "моб. интернет выкл" },
            "WIFI" to wifi?.let { if (it == Toggle.ON) "Wi-Fi вкл" else "Wi-Fi выкл" },
            "HOTSPOT" to hotspot?.let { apLabel(it, hotspotVia, hotspotBand) },
            "VPN" to vpnLabel(vpn, vpnOffApp),
            "TILES" to tiles.size.takeIf { it > 0 }?.let { "плитки: $it" },
            "BT" to "Bluetooth выкл".takeIf { bluetoothOff },
            "LOC" to "геолокация выкл".takeIf { locationOff },
            "AIRPLANE" to airplane?.let { if (it == Toggle.ON) "полёт вкл" else "полёт выкл" },
            "POWERSAVE" to powerSave?.let { if (it == Toggle.ON) "энергосбережение вкл" else "энергосбережение выкл" },
            "DATASIM" to dataSim?.let { "интернет через SIM ${it + 1}" },
            "RECONNECT" to "переподключить моб. сеть".takeIf { reconnect },
            "ICE" to ice?.label,
            "SYS" to sys.takeIf { it.isNotEmpty() }?.let { SysKey.summary(it) },
            "NOTE" to note?.let { "уведомление «$it»" },
            "HTTP" to http?.let { "HTTP-запрос" },
            "SHELL" to shell?.let { "команда" },
            "UNLOAD" to (if (unloadAll) "выгрузить всё" else "выгрузить фон").takeIf { unload },
            "LAUNCH" to launchApps.takeIf { it.isNotEmpty() }?.let { "открыть: " + AppPickerActivity.labels(it).joinToString(", ") },
        ))

    /**
     * The labels in [steps] order, "→" between them and the pauses written out; with no steps,
     * in the order given, " · " between them.
     */
    private fun ordered(steps: List<String>, labels: LinkedHashMap<String, String?>): String {
        if (steps.isEmpty()) return labels.values.filterNotNull().joinToString(" · ")
        return steps.mapNotNull { s -> Steps.pauseOf(s)?.let { "⏳ " + Steps.words(it) } ?: labels[s] }
            .joinToString(" → ")
    }

    val isTime: Boolean get() = trigger == Trigger.TIME

    fun methodOf(pkg: String): FreezeMethod = appMethods[pkg] ?: method



    /** Apps that get push and background work back at the end. */
    val unstopApps: List<String> get() = packages.filter { it !in keepStopped }

    /** "«Умная сеть» " prefix for log lines, empty for an unnamed rule. */
    val logPrefix: String get() = name?.let { "«$it» " }.orEmpty()

    /** "при разблокировке: только 4G/3G/2G" for a rule whose unlock has its own block. */
    val unlockLabel: String?
        get() {
            val u = unlockEnd?.takeIf { splitEnd } ?: return null
            val apps = if (u.keepApps && packages.isNotEmpty() && u.mode != EndMode.NOTHING) " · приложения — до конца времени" else ""
            return "при разблокировке: " + when (u.mode) {
                EndMode.NOTHING -> "ничего не менять"
                EndMode.RESTORE -> (withEnd(u).endLabel?.removePrefix("в конце: ") ?: "вернуть как было") + apps
                EndMode.CUSTOM -> withEnd(u).endLabel!!.removePrefix("в конце: ") + apps
            }
        }

    /** "в конце: сеть 4G · Wi-Fi вкл", or null when the rule simply puts things back. */
    val endLabel: String?
        get() {
            if (!restores) return null
            val apps = linkedMapOf(
                "APPS" to endStopApps.takeIf { it.isNotEmpty() }?.let { apps ->
                    apps.groupBy { endMethodOf(it) }.entries.joinToString(" · ") { (m, ps) ->
                        m.label.lowercase() + ": " + AppPickerActivity.labels(ps).joinToString(", ")
                    }
                },
                "UNLOAD" to (if (endUnloadAll) "выгрузить всё" else "выгрузить фон").takeIf { endUnload },
                "LAUNCH" to endLaunchApps.takeIf { it.isNotEmpty() }?.let { "открыть: " + AppPickerActivity.labels(it).joinToString(", ") },
                "NOTE" to endNote?.let { "уведомление «$it»" },
                "HTTP" to endHttp?.let { "HTTP-запрос" },
                "SHELL" to endShell?.let { "команда" },
            )
            if (!endCustom) {
                val text = ordered(endSteps, apps)
                return if (text.isEmpty()) null else "в конце: вернуть как было · $text"
            }
            fun t(name: String, v: Toggle?) = v?.let { "$name ${if (it == Toggle.ON) "вкл" else "выкл"}" }
            val parts = apps + linkedMapOf(
                "NET" to endNet?.let { (if (netSlot == SLOT_ALL) "все SIM" else "SIM ${netSlot + 1}") + " → ${it.short}" },
                "DATA" to t("моб. интернет", endData), "WIFI" to t("Wi-Fi", endWifi),
                "HOTSPOT" to endHotspot?.let { apLabel(it, endHotspotVia, endHotspotBand) },
                "BT" to t("Bluetooth", endBt), "LOC" to t("геолокация", endLoc),
                "VPN" to vpnLabel(endVpn, endVpnOffApp), "AIRPLANE" to t("полёт", endAirplane), "POWERSAVE" to t("энергосбережение", endPowerSave),
                "DATASIM" to endDataSim?.let { "интернет через SIM ${it + 1}" },
                "TILES" to endTiles.size.takeIf { it > 0 }?.let { "плитки: $it" },
                "SYS" to endSys.takeIf { it.isNotEmpty() }?.let { SysKey.summary(it) },
            )
            return "в конце: " + ordered(endSteps, LinkedHashMap(parts)).ifEmpty { "ничего не менять" }
        }

    /** "Остановить" when every app shares one method, "приложения: 3" otherwise. */
    val appsLabel: String?
        get() {
            if (packages.isEmpty()) return null
            val methods = packages.map { methodOf(it) }.toSet()
            return if (methods.size == 1) methods.first().label else "приложения: ${packages.size}"
        }

    /**
     * Whether the window ever closes. A rule that never does — "at 21:00 → 3G" — sets state
     * for good, so there is nothing to remember and put back.
     */
    val restores: Boolean
        get() = when (trigger) {
            Trigger.TIME -> hasEnd
            Trigger.WIFI_LEFT -> true
            Trigger.TILE_CLICK -> false
            else -> releaseOnLeave
        }

    /** "While connected to [ssid]" — the rule kind Wi-Fi connect events apply. */
    val isWifi: Boolean get() = trigger == Trigger.WIFI

    val hasEnd: Boolean get() = isTime && endHour != null && endMinute != null

    /** MANUAL: when a scene started at [since] ends by itself; null when it waits to be switched off. */
    fun manualEndAfter(since: ZonedDateTime): ZonedDateTime? =
        if (trigger == Trigger.MANUAL && restores && level > 0) since.plusMinutes(level.toLong()) else null

    /** WIFI_LEFT: the time that closes the window, if any. */
    val hasLeftEndTime: Boolean get() = trigger == Trigger.WIFI_LEFT && endHour != null && endMinute != null

    fun matchesSsid(other: String?): Boolean =
        ssid != null && other != null && other.trim('"').equals(ssid.trim('"'), ignoreCase = true)

    /** LOCKED with a window: whether [at] falls inside it (the days count for its start). */
    fun inLockWindow(at: ZonedDateTime): Boolean {
        if (!lockWindow || endHour == null || endMinute == null) return true
        val start = lastStart(at) ?: return false
        return at.isBefore(lockWindowEnd(start)!!)
    }

    /** LOCKED: the end of the window opened at [start] — [endAfter] is for time rules only. */
    fun lockWindowEnd(start: ZonedDateTime): ZonedDateTime? {
        if (endHour == null || endMinute == null) return null
        var end = start.withHour(endHour).withMinute(endMinute)
        if (!end.isAfter(start)) end = end.plusDays(1)
        return end
    }

    fun lastStart(at: ZonedDateTime): ZonedDateTime? =
        lastOccurrence(at, hour, minute) { daysAllow(days, it) }

    fun nextStart(from: ZonedDateTime): ZonedDateTime =
        nextOccurrence(from, hour, minute) { daysAllow(days, it) }

    /** The end that closes the window opened at [start]; null for a start-only rule. */
    fun endAfter(start: ZonedDateTime): ZonedDateTime? {
        if (!hasEnd) return null
        var end = start.withHour(endHour!!).withMinute(endMinute!!)
        if (!end.isAfter(start)) end = end.plusDays(1)
        return end
    }

    /** WIFI_LEFT: the end time following the moment the window opened. */
    fun leftEndAfter(since: ZonedDateTime): ZonedDateTime? =
        if (!hasLeftEndTime) null else nextOccurrence(since, endHour!!, endMinute!!) { true }

    /** Most recent end boundary at or before [at]. */
    fun lastEnd(at: ZonedDateTime): ZonedDateTime? {
        if (!hasEnd) return null
        val s1 = lastStart(at) ?: return null
        val e1 = endAfter(s1)!!
        if (!e1.isAfter(at)) return e1
        return lastStart(s1.minusMinutes(1))?.let { endAfter(it) }
    }

    /** Next start or end strictly after [from] — what the shared alarm has to wake for. */
    fun nextEvent(from: ZonedDateTime): ZonedDateTime {
        val next = nextStart(from)
        if (!hasEnd) return next
        val current = lastStart(from)?.let { endAfter(it) }?.takeIf { it.isAfter(from) }
        return listOfNotNull(next, current, endAfter(next)).min()
    }

    /** "Wi-Fi «Дом» · если с 23:00 до 07:00 и на зарядке" */
    val timeLabel: String
        get() = triggerLabel + if (extras.isEmpty()) "" else " · если " + extras.joinToString(" и ") { it.label }

    private val triggerLabel: String
        get() = when (trigger) {
            Trigger.MANUAL -> "сцена" + when {
                !restores -> ""
                level > 0 -> " на " + Steps.words(level * 60)
                else -> " · вкл/выкл"
            }
            Trigger.TIME -> "%02d:%02d".format(hour, minute) +
                if (hasEnd) "–%02d:%02d".format(endHour, endMinute) else ""
            Trigger.WIFI -> "Wi-Fi «$ssid»"
            Trigger.WIFI_LEFT -> "уход из «$ssid»" +
                (if (hasLeftEndTime) " до %02d:%02d".format(endHour, endMinute) else "") +
                (endBtName?.let { " / до откл. «$it»" } ?: "")
            Trigger.BLUETOOTH -> "Bluetooth «${btName ?: btAddress}»"
            Trigger.MOBILE_DATA -> "моб. интернет включён"
            Trigger.NET_MODE -> "SIM ${triggerSlot + 1} в режиме ${triggerMode?.short ?: "?"}"
            Trigger.BATTERY -> if (level <= 0) "энергосбережение включено" else "заряд ≤ $level%"
            Trigger.CHARGING -> "на зарядке"
            Trigger.SCREEN_OFF -> "экран выключен ≥ $level мин"
            Trigger.APP_OPEN -> "открыто: " + AppPickerActivity.labels(triggerApps).joinToString(", ").ifEmpty { "?" } +
                (if (appLeaveDelay > 0) " · выход через " + Steps.words(appLeaveDelay) else "")
            Trigger.PLACE -> "место «${placeName ?: "?"}»"
            Trigger.ROAMING -> "роуминг"
            Trigger.WEAK_SIGNAL -> "слабый сигнал SIM ${triggerSlot + 1}"
            Trigger.CALL -> "идёт звонок"
            Trigger.HEADPHONES -> "подключены наушники"
            Trigger.LOCKED -> (if (level <= 0) "телефон заблокирован" else "заблокирован ≥ $level мин") +
                if (lockWindow && endHour != null && endMinute != null)
                    " · %02d:%02d–%02d:%02d".format(hour, minute, endHour, endMinute) + if (days != 0) " · " + formatDays(days) else ""
                else ""
            Trigger.DND -> "включён «Не беспокоить»"
            Trigger.NO_INTERNET -> "нет интернета дольше " + Steps.words(level)
            Trigger.NOTIFY_APP -> (if (level == 1) "работает в фоне: " else "уведомление: ") +
                AppPickerActivity.labels(triggerApps).joinToString(", ").ifEmpty { "?" }
            Trigger.USB -> "USB подключён к компьютеру"
            Trigger.CAR -> "в машине (Android Auto)" +
                (if (appLeaveDelay > 0) " · выход через " + Steps.words(appLeaveDelay) else "")
            Trigger.ALARM -> "за " + Steps.words(level * 60) + " до будильника"
            Trigger.SUN -> if (level == 1) "светло (от восхода до заката)" else "темно (от заката до восхода)"
            Trigger.TRAFFIC -> "моб. трафик за ${if (trafficMonth) "месяц" else "день"} ≥ " + trafficLabel(level)
            Trigger.WIFI_OTHER -> "Wi-Fi не из своих"
            Trigger.HOTSPOT -> HotspotCondition.label(triggerHotspotVia, triggerHotspotBand)
            Trigger.TILE_CLICK -> "нажатие плитки «${triggerTileLabel ?: triggerTile?.let { Tiles.label(it) } ?: "не выбрана"}»"
            Trigger.VPN -> "VPN подключён" +
                (if (triggerApps.isEmpty()) "" else ": " + AppPickerActivity.labels(triggerApps).joinToString(", ")) +
                (if (appLeaveDelay > 0) " · выход через " + Steps.words(appLeaveDelay) else "")
        }

    val daysLabel: String get() = formatDays(days)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("on", enabled).put("h", hour).put("m", minute)
        .put("eh", endHour ?: JSONObject.NULL).put("em", endMinute ?: JSONObject.NULL)
        .put("days", days).put("method", method.name)
        .put("pkgs", JSONArray(packages))
        .put("ssid", ssid ?: JSONObject.NULL).put("leave", releaseOnLeave)
        .put("bt", bluetoothOff).put("loc", locationOff).put("wifiOff", leaveOnWifiOff)
        .put("trigger", trigger.name)
        .put("btAddr", btAddress ?: JSONObject.NULL).put("btName", btName ?: JSONObject.NULL)
        .put("endBtAddr", endBtAddress ?: JSONObject.NULL).put("endBtName", endBtName ?: JSONObject.NULL)
        .put("wifi", wifi?.name ?: JSONObject.NULL).put("hotspot", hotspot?.name ?: JSONObject.NULL)
        .put("data", mobileData?.name ?: JSONObject.NULL)
        .put("name", name ?: JSONObject.NULL)
        .put("tSlot", triggerSlot).put("tMode", triggerMode?.name ?: JSONObject.NULL)
        .put("keepStopped", JSONArray(keepStopped.toList()))
        .put("tiles", JSONObject().apply { tiles.forEach { (c, t) -> put(c, t.name) } })
        .put("endTiles", JSONObject().apply { endTiles.forEach { (c, t) -> put(c, t.name) } })
        .put("vpnApp", vpnApp ?: JSONObject.NULL).put("vpnTile", vpnTile ?: JSONObject.NULL).put("vpn", vpn?.name ?: JSONObject.NULL)
        .put("endVpn", endVpn?.name ?: JSONObject.NULL)
        .put("vpnOffApp", vpnOffApp ?: JSONObject.NULL).put("endVpnOffApp", endVpnOffApp ?: JSONObject.NULL)
        .put("endCustom", endCustom)
        .put("eNet", endNet?.name ?: JSONObject.NULL).put("eData", endData?.name ?: JSONObject.NULL)
        .put("eWifi", endWifi?.name ?: JSONObject.NULL).put("eAp", endHotspot?.name ?: JSONObject.NULL)
        .put("apVia", hotspotVia?.name ?: JSONObject.NULL).put("eApVia", endHotspotVia?.name ?: JSONObject.NULL)
        .put("eBt", endBt?.name ?: JSONObject.NULL).put("eLoc", endLoc?.name ?: JSONObject.NULL)
        .put("appMethods", JSONObject().apply { appMethods.forEach { (p, m) -> put(p, m.name) } })
        .put("net", netMode?.name ?: JSONObject.NULL).put("netSlot", netSlot)
        .put("level", level).put("tApps", JSONArray(triggerApps)).put("cells", JSONArray(cells.toList()))
        .put("place", placeName ?: JSONObject.NULL)
        .put("air", airplane?.name ?: JSONObject.NULL).put("eAir", endAirplane?.name ?: JSONObject.NULL)
        .put("ps", powerSave?.name ?: JSONObject.NULL).put("ePs", endPowerSave?.name ?: JSONObject.NULL)
        .put("dSim", dataSim ?: JSONObject.NULL).put("eDSim", endDataSim ?: JSONObject.NULL)
        .put("wait", waitLocked).put("waitApps", waitAppsOnly).put("wake", wakeWifi)
        .put("launch", JSONArray(launchApps)).put("eStop", JSONArray(endStopApps)).put("eLaunch", JSONArray(endLaunchApps))
        .put("unl", unload).put("eUnl", endUnload).put("unlAll", unloadAll).put("eUnlAll", endUnloadAll)
        .put("lockWin", lockWindow)
        .put("steps", JSONArray(steps)).put("eSteps", JSONArray(endSteps))
        .put("unlock", unlockEnd?.toJson() ?: JSONObject.NULL)
        .put("eMethods", JSONObject().apply { endAppMethods.forEach { (p, m) -> put(p, m.name) } })
        .put("recon", reconnect).put("notice", notice.name)
        .put("ice", ice?.toJson() ?: JSONObject.NULL)
        .put("extras", JSONArray().apply { extras.forEach { put(it.toJson()) } })
        .put("sys", JSONObject(sys as Map<*, *>)).put("eSys", JSONObject(endSys as Map<*, *>))
        .put("note", note ?: JSONObject.NULL).put("eNote", endNote ?: JSONObject.NULL)
        .put("http", http ?: JSONObject.NULL).put("eHttp", endHttp ?: JSONObject.NULL)
        .put("shell", shell ?: JSONObject.NULL).put("eShell", endShell ?: JSONObject.NULL)
        .put("tMonth", trafficMonth).put("known", JSONArray(knownSsids))
        .put("tApVia", triggerHotspotVia?.name ?: JSONObject.NULL)
        .put("tApBand", triggerHotspotBand ?: JSONObject.NULL)
        .put("apBand", hotspotBand ?: JSONObject.NULL).put("eApBand", endHotspotBand ?: JSONObject.NULL)
        .put("tTile", triggerTile ?: JSONObject.NULL).put("tTileLabel", triggerTileLabel ?: JSONObject.NULL)
        .put("leaveS", appLeaveDelay).put("tPass", JSONArray(appPassThrough)).put("screenStay", appScreenOffStays)

    companion object {
        private fun JSONObject.toggles(key: String): Map<String, Toggle> =
            optJSONObject(key)?.let { m ->
                m.keys().asSequence().mapNotNull { c ->
                    runCatching { Toggle.valueOf(m.getString(c)) }.getOrNull()?.let { c to it }
                }.toMap()
            }.orEmpty()

        private fun JSONObject.strings(key: String): List<String> =
            optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

        fun fromJson(o: JSONObject): AppRule {
            val pkgs = o.optJSONArray("pkgs") ?: JSONArray()
            val ssid = o.str("ssid")
            return AppRule(
                id = o.getLong("id"),
                enabled = o.optBoolean("on", true),
                hour = o.getInt("h"),
                minute = o.getInt("m"),
                endHour = if (o.isNull("eh")) null else o.optInt("eh"),
                endMinute = if (o.isNull("em")) null else o.optInt("em"),
                days = o.optInt("days", 0),
                packages = (0 until pkgs.length()).map { pkgs.getString(it) },
                method = runCatching { FreezeMethod.valueOf(o.getString("method")) }
                    .getOrDefault(FreezeMethod.STOP_RESTRICT),
                ssid = ssid,
                releaseOnLeave = o.optBoolean("leave", true),
                bluetoothOff = o.optBoolean("bt", false),
                locationOff = o.optBoolean("loc", false),
                leaveOnWifiOff = if (o.has("wifiOff")) o.getBoolean("wifiOff") else !Prefs.ignoreWifiOff,
                trigger = o.str("trigger")?.let { runCatching { Trigger.valueOf(it) }.getOrNull() }
                    ?: if (ssid != null) Trigger.WIFI else Trigger.TIME,
                triggerHotspotVia = o.str("tApVia")?.let { runCatching { TetherMode.valueOf(it) }.getOrNull() },
                triggerHotspotBand = HotspotCondition.band(o.str("tApBand")),
                hotspotBand = HotspotCondition.band(o.str("apBand")),
                endHotspotBand = HotspotCondition.band(o.str("eApBand")),
                triggerTile = o.str("tTile")?.takeIf { Tiles.valid(it) },
                triggerTileLabel = o.str("tTileLabel"),
                btAddress = o.str("btAddr"),
                btName = o.str("btName"),
                endBtAddress = o.str("endBtAddr"),
                endBtName = o.str("endBtName"),
                wifi = o.str("wifi")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                hotspot = o.str("hotspot")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                mobileData = o.str("data")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                name = o.str("name"),
                triggerSlot = o.optInt("tSlot", 0),
                triggerMode = o.str("tMode")?.let { runCatching { NetMode.valueOf(it) }.getOrNull() },
                tiles = o.toggles("tiles"),
                endTiles = o.toggles("endTiles"),
                vpnTile = o.str("vpnTile"),
                vpnApp = o.str("vpnApp") ?: o.str("vpnTile")?.substringBefore('/'),
                vpn = o.str("vpn")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endVpn = o.str("endVpn")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                vpnOffApp = o.str("vpnOffApp"), endVpnOffApp = o.str("endVpnOffApp"),
                // Rules saved with the single on/off switch: off meant every app stays stopped.
                keepStopped = o.optJSONArray("keepStopped")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: if (!o.optBoolean("unstop", true)) (0 until pkgs.length()).map { pkgs.getString(it) }.toSet()
                    else emptySet(),
                endCustom = o.optBoolean("endCustom", false),
                endNet = o.str("eNet")?.let { runCatching { NetMode.valueOf(it) }.getOrNull() },
                endData = o.str("eData")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endWifi = o.str("eWifi")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endHotspot = o.str("eAp")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                hotspotVia = o.str("apVia")?.let { runCatching { TetherMode.valueOf(it) }.getOrNull() },
                endHotspotVia = o.str("eApVia")?.let { runCatching { TetherMode.valueOf(it) }.getOrNull() },
                endBt = o.str("eBt")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endLoc = o.str("eLoc")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                appMethods = o.optJSONObject("appMethods")?.let { m ->
                    m.keys().asSequence().mapNotNull { p ->
                        runCatching { FreezeMethod.valueOf(m.getString(p)) }.getOrNull()?.let { p to it }
                    }.toMap()
                }.orEmpty(),
                netMode = o.str("net")?.let { runCatching { NetMode.valueOf(it) }.getOrNull() },
                netSlot = o.optInt("netSlot", SLOT_ALL),
                level = o.optInt("level", 0),
                triggerApps = o.optJSONArray("tApps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                cells = o.optJSONArray("cells")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                placeName = o.str("place"),
                airplane = o.str("air")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endAirplane = o.str("eAir")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                powerSave = o.str("ps")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                endPowerSave = o.str("ePs")?.let { runCatching { Toggle.valueOf(it) }.getOrNull() },
                dataSim = if (o.isNull("dSim") || !o.has("dSim")) null else o.optInt("dSim"),
                endDataSim = if (o.isNull("eDSim") || !o.has("eDSim")) null else o.optInt("eDSim"),
            ).let { r ->
                // Rules from before this setting: time rules wait, the rest do not.
                r.copy(waitLocked = if (o.has("wait")) o.getBoolean("wait") else r.trigger == Trigger.TIME,
                    waitAppsOnly = o.optBoolean("waitApps", false), wakeWifi = o.optBoolean("wake", false),
                    launchApps = o.strings("launch"), endStopApps = o.strings("eStop"), endLaunchApps = o.strings("eLaunch"),
                    unload = o.optBoolean("unl", false), endUnload = o.optBoolean("eUnl", false),
                    unloadAll = o.optBoolean("unlAll", false), endUnloadAll = o.optBoolean("eUnlAll", false),
                    lockWindow = o.optBoolean("lockWin", false),
                    steps = o.strings("steps"), endSteps = o.strings("eSteps"),
                    unlockEnd = o.optJSONObject("unlock")?.let { runCatching { EndSpec.fromJson(it) }.getOrNull() },
                    appLeaveDelay = o.optInt("leaveS", 0), appPassThrough = o.strings("tPass"),
                    appScreenOffStays = o.optBoolean("screenStay", false),
                    reconnect = o.optBoolean("recon", false),
                    ice = o.optJSONObject("ice")?.let { runCatching { IceAction.fromJson(it) }.getOrNull() },
                    notice = runCatching { RunNotice.valueOf(o.getString("notice")) }.getOrDefault(RunNotice.SILENT),
                    extras = Extra.listFromJson(o.optJSONArray("extras")),
                    sys = o.optJSONObject("sys")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
                    endSys = o.optJSONObject("eSys")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
                    note = o.str("note"), endNote = o.str("eNote"), http = o.str("http"), endHttp = o.str("eHttp"),
                    shell = o.str("shell"), endShell = o.str("eShell"),
                    trafficMonth = o.optBoolean("tMonth", false), knownSsids = o.strings("known"),
                    endAppMethods = o.optJSONObject("eMethods")?.let { m ->
                        m.keys().asSequence().mapNotNull { p ->
                            runCatching { FreezeMethod.valueOf(m.getString(p)) }.getOrNull()?.let { p to it }
                        }.toMap()
                    }.orEmpty())
            }
        }
    }
}

/**
 * What the app changed on one package, so the end of the window can put back exactly what was
 * there before. Each field is null/false when nothing was changed — an app the user had already
 * restricted, or suspended with another tool, is left as it was.
 */
data class FrozenApp(
    val pkg: String,
    val ruleId: Long,
    /** When the app was frozen; an end boundary after this moment releases it. */
    val since: Long,
    val appop: String?,
    val bucket: String?,
    val suspended: Boolean,
    /** Pseudo-entries: the state to go back to ("on"/"off", or a NetMode name). */
    val prev: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("rule", ruleId).put("since", since)
        .put("appop", appop ?: JSONObject.NULL).put("bucket", bucket ?: JSONObject.NULL)
        .put("susp", suspended).put("prev", prev ?: JSONObject.NULL)

    val isPseudo: Boolean get() = pkg.startsWith("#")

    companion object {
        /** Pseudo-packages: a radio the rule switched off, released like any app. */
        const val BLUETOOTH = "#bluetooth"
        const val LOCATION = "#location"
        const val WIFI = "#wifi"
        const val HOTSPOT = "#hotspot"
        /** Full band/channel map before a rule changed the AP frequency. No credentials. */
        const val HOTSPOT_RADIO = "#hotspotradio"
        /** The hotspot traffic mode before a rule changed it; prev is a TetherMode name. */
        const val TETHER_MODE = "#tethermode"
        const val DATA = "#data"
        /** Followed by the tile component: "#tile:su.happ.proxyutility/.service.QSTileService". */
        const val TILE = "#tile:"
        /** Followed by the VPN app's tile component; its state is the VPN connection. */
        const val VPN = "#vpn:"
        /** The system VPN; prev is the package that was connected, or "off". */
        const val SYSVPN = "#sysvpn"
        /** Followed by a frozen app that held the VPN; reconnected after it is unfrozen. */
        const val VPN_BACK = "#vpnback:"
        /** Followed by the SIM slot: "#net0", "#net1". */
        const val NET = "#net"
        const val AIRPLANE = "#airplane"
        const val POWERSAVE = "#powersave"
        /** Followed by a [SysKey] name: "#sys:BRIGHT"; prev is [SysSettings.read]'s raw form. */
        const val SYS = "#sys:"
        /** The SIM used for mobile data; prev is its slot. */
        const val DATASIM = "#datasim"

        fun fromJson(o: JSONObject) = FrozenApp(
            pkg = o.getString("pkg"),
            ruleId = o.getLong("rule"),
            since = o.optLong("since"),
            appop = o.optString("appop").takeIf { it.isNotEmpty() && it != "null" },
            bucket = o.optString("bucket").takeIf { it.isNotEmpty() && it != "null" },
            suspended = o.optBoolean("susp"),
            prev = o.optString("prev").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}
