package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.netmode.switcher.databinding.ActivityRuleEditBinding
import kotlinx.coroutines.launch

/**
 * Creates or edits one rule, full screen: "when" → "what to do" → "at the end".
 *
 * Only the actions the rule uses are shown, plus "Add action" for the rest — the old dialog
 * listed every possible action at once, most of them "leave as is". The end block likewise
 * lists only what was chosen for it. A rule must never act on its own trigger (switching
 * Wi-Fi under a Wi-Fi rule would end its own window), so such actions are not offered.
 */
class RuleEditActivity : AppCompatActivity() {

    private enum class Act(val icon: Int, val title: Int) {
        NET(R.drawable.ic_t_net, R.string.act_network),
        DATA(R.drawable.ic_t_data, R.string.act_data_short),
        WIFI(R.drawable.ic_t_wifi, R.string.act_wifi),
        HOTSPOT(R.drawable.ic_hotspot, R.string.act_hotspot),
        VPN(R.drawable.ic_a_vpn, R.string.act_vpn),
        DATASIM(R.drawable.ic_a_sim, R.string.act_datasim),
        RECONNECT(R.drawable.ic_a_airplane, R.string.act_reconnect),
        AIRPLANE(R.drawable.ic_a_airplane, R.string.act_airplane),
        POWERSAVE(R.drawable.ic_t_battery, R.string.act_powersave),
        BT(R.drawable.ic_t_bt, R.string.bluetooth),
        LOC(R.drawable.ic_a_locoff, R.string.location_short),
        TILES(R.drawable.ic_a_tiles, R.string.act_tiles),
        SYS(R.drawable.ic_settings, R.string.act_sys),
        APPS(R.drawable.ic_apps, R.string.act_apps),
        LAUNCH(R.drawable.ic_t_app, R.string.act_launch),
        UNLOAD(R.drawable.ic_a_clean, R.string.act_unload),
        NOTE(R.drawable.ic_log, R.string.act_note),
        HTTP(R.drawable.ic_t_net, R.string.act_http),
        SHELL(R.drawable.ic_edit, R.string.act_shell);

        /** Works under both endings and is not mirrored into a custom end: the app actions. */
        val anyEnd: Boolean get() = this == APPS || this == LAUNCH || this == UNLOAD || isText

        /** A one-off with a text of its own: a notification, a request, a command. */
        val isText: Boolean get() = this == NOTE || this == HTTP || this == SHELL

        /** Only at the start: something done once, with nothing to put back or set at the end. */
        val startOnly: Boolean get() = this == RECONNECT
    }

    private class TriggerInfo(val trigger: Trigger, val icon: Int, val title: Int, val hint: Int, val short: Int)

    private val triggerInfos = listOf(
        TriggerInfo(Trigger.MANUAL, R.drawable.ic_t_manual, R.string.trigger_manual, R.string.trigger_manual_hint, R.string.pick_manual),
        TriggerInfo(Trigger.TIME, R.drawable.ic_t_time, R.string.trigger_time, R.string.trigger_time_hint, R.string.pick_time),
        TriggerInfo(Trigger.WIFI, R.drawable.ic_t_wifi, R.string.trigger_wifi, R.string.trigger_wifi_hint, R.string.pick_wifi),
        TriggerInfo(Trigger.BLUETOOTH, R.drawable.ic_t_bt, R.string.trigger_bt, R.string.trigger_bt_hint, R.string.pick_bt),
        TriggerInfo(Trigger.MOBILE_DATA, R.drawable.ic_t_data, R.string.trigger_data, R.string.data_trigger_hint, R.string.pick_data),
        TriggerInfo(Trigger.NET_MODE, R.drawable.ic_t_net, R.string.trigger_net, R.string.net_trigger_hint, R.string.pick_net),
        TriggerInfo(Trigger.BATTERY, R.drawable.ic_t_battery, R.string.trigger_battery, R.string.trigger_battery_hint, R.string.pick_battery),
        TriggerInfo(Trigger.CHARGING, R.drawable.ic_t_charging, R.string.trigger_charging, R.string.trigger_charging_hint, R.string.pick_charging),
        TriggerInfo(Trigger.SCREEN_OFF, R.drawable.ic_t_screen, R.string.trigger_screen, R.string.trigger_screen_hint, R.string.pick_screen),
        TriggerInfo(Trigger.LOCKED, R.drawable.ic_t_lock, R.string.trigger_locked, R.string.trigger_locked_hint, R.string.pick_locked),
        TriggerInfo(Trigger.APP_OPEN, R.drawable.ic_t_app, R.string.trigger_app, R.string.trigger_app_hint, R.string.pick_app),
        TriggerInfo(Trigger.PLACE, R.drawable.ic_t_place, R.string.trigger_place, R.string.trigger_place_hint, R.string.pick_place),
        TriggerInfo(Trigger.ROAMING, R.drawable.ic_t_roaming, R.string.trigger_roaming, R.string.trigger_roaming_hint, R.string.pick_roaming),
        TriggerInfo(Trigger.WEAK_SIGNAL, R.drawable.ic_t_signal, R.string.trigger_signal, R.string.trigger_signal_hint, R.string.pick_signal),
        TriggerInfo(Trigger.CALL, R.drawable.ic_t_call, R.string.trigger_call, R.string.trigger_call_hint, R.string.pick_call),
        TriggerInfo(Trigger.HEADPHONES, R.drawable.ic_t_headphones, R.string.trigger_headphones, R.string.trigger_headphones_hint, R.string.pick_headphones),
        TriggerInfo(Trigger.DND, R.drawable.ic_t_dnd, R.string.trigger_dnd, R.string.trigger_dnd_hint, R.string.pick_dnd),
        TriggerInfo(Trigger.NO_INTERNET, R.drawable.ic_t_nonet, R.string.trigger_nonet, R.string.trigger_nonet_hint, R.string.pick_nonet),
        TriggerInfo(Trigger.WIFI_OTHER, R.drawable.ic_t_wifi, R.string.trigger_wifi_other, R.string.trigger_wifi_other_hint, R.string.pick_wifi_other),
        TriggerInfo(Trigger.NOTIFY_APP, R.drawable.ic_t_notify, R.string.trigger_notify, R.string.trigger_notify_hint, R.string.pick_notify),
        TriggerInfo(Trigger.ALARM, R.drawable.ic_t_alarm, R.string.trigger_alarm, R.string.trigger_alarm_hint, R.string.pick_alarm),
        TriggerInfo(Trigger.SUN, R.drawable.ic_t_sun, R.string.trigger_sun, R.string.trigger_sun_hint, R.string.pick_sun),
        TriggerInfo(Trigger.TRAFFIC, R.drawable.ic_t_traffic, R.string.trigger_traffic, R.string.trigger_traffic_hint, R.string.pick_traffic),
        TriggerInfo(Trigger.CAR, R.drawable.ic_t_car, R.string.trigger_car, R.string.trigger_car_hint, R.string.pick_car),
        TriggerInfo(Trigger.USB, R.drawable.ic_t_usb, R.string.trigger_usb, R.string.trigger_usb_hint, R.string.pick_usb),
    )

    private lateinit var v: ActivityRuleEditBinding
    private var existing: AppRule? = null

    // --- "when" ---
    private var trigger = Trigger.TIME
    private var start = 23 to 0
    private var end = 7 to 0
    private var leftEnd = 19 to 0
    private var btDevice: BtDevice? = null
    private var endBt: BtDevice? = null
    private var triggerApps = listOf<String>()
    private var appPass = listOf<String>()
    private var appLeaveDelay = 0
    private var cells = setOf<String>()
    private var batteryLevel = 20
    private var screenMinutes = 10
    private var lockMinutes = 0
    private var signalLevel = 1
    private var manualMinutes = 0
    private var netLostSeconds = 60
    private var notifyLevel = 0
    private var alarmMinutes = 30
    private var sunLevel = 0
    private var trafficIdx = 2
    private var knownSsids = listOf<String>()
    private var notice = RunNotice.SILENT
    private val extras = mutableListOf<Extra>()
    /** The wait switch follows the trigger's default until the user sets it. */
    private var waitTouched = false

    // --- "what to do" ---
    private val shown = linkedSetOf<Act>()
    /**
     * "Что сделать" and the end in their order, pauses included: [Act] names and
     * [Steps.pause] tokens. Which actions there are is [shown] / [atEnd.shown]; these hold the order.
     */
    private val steps = mutableListOf<String>()
    private var netMode: NetMode? = null
    private var netSlot = SLOT_ALL
    private var data: Toggle? = null
    private var wifi: Toggle? = null
    private var hotspot: Pair<Toggle?, TetherMode?> = null to null
    private var vpn: Toggle? = null
    private var vpnApp: String? = null
    private var dataSim: Int? = null
    private var airplane: Toggle? = null
    private var powerSave: Toggle? = null
    private var btOff = false
    private var locOff = false
    private var tiles = mapOf<String, Toggle>()
    private var packages = listOf<String>()
    private var appMethods = mapOf<String, FreezeMethod>()
    private var keepStopped = setOf<String>()
    private var launchApps = listOf<String>()
    private var unloadAll = false
    private var sys = mapOf<String, String>()
    /** The one-offs' texts: [Act.NOTE], [Act.HTTP], [Act.SHELL]. */
    private val texts = mutableMapOf<Act, String>()

    // --- "at the end" ---
    /** One ending's actions: the rule's end, or the unlock of a lock rule with a time. */
    private class EndState {
        val shown = linkedSetOf<Act>()
        val steps = mutableListOf<String>()
        var net: NetMode? = null
        var data: Toggle? = null
        var wifi: Toggle? = null
        var hotspot: Pair<Toggle?, TetherMode?> = null to null
        var vpn: Toggle? = null
        var dataSim: Int? = null
        var airplane: Toggle? = null
        var powerSave: Toggle? = null
        var bt: Toggle? = null
        var loc: Toggle? = null
        var tiles = mapOf<String, Toggle>()
        var stopApps = listOf<String>()
        var appMethods = mapOf<String, FreezeMethod>()
        var launchApps = listOf<String>()
        var unloadAll = false
        var sys = mapOf<String, String>()
        val texts = mutableMapOf<Act, String>()

        fun clear(a: Act) {
            when (a) {
                Act.NET -> net = null; Act.DATA -> data = null; Act.WIFI -> wifi = null
                Act.HOTSPOT -> hotspot = null to null; Act.VPN -> vpn = null; Act.DATASIM -> dataSim = null
                Act.AIRPLANE -> airplane = null; Act.POWERSAVE -> powerSave = null; Act.BT -> bt = null
                Act.LOC -> loc = null; Act.TILES -> tiles = emptyMap()
                Act.APPS -> { stopApps = emptyList(); appMethods = emptyMap() }
                Act.LAUNCH -> launchApps = emptyList()
                Act.SYS -> sys = emptyMap()
                Act.NOTE, Act.HTTP, Act.SHELL -> texts -= a
                Act.UNLOAD, Act.RECONNECT -> Unit
            }
        }

        /** Fills from [e], showing what it sets; the apps are shown under either mode. */
        fun load(e: EndSpec) {
            net = e.net; data = e.data; wifi = e.wifi; hotspot = e.hotspot to e.hotspotVia
            vpn = e.vpn; dataSim = e.dataSim; airplane = e.airplane; powerSave = e.powerSave
            bt = e.bt; loc = e.loc; tiles = e.tiles
            stopApps = e.stopApps; appMethods = e.stopApps.associateWith { e.appMethods[it] ?: FreezeMethod.STOP_RESTRICT }
            launchApps = e.launchApps; unloadAll = e.unloadAll
            if (stopApps.isNotEmpty()) shown += Act.APPS
            if (launchApps.isNotEmpty()) shown += Act.LAUNCH
            if (e.unload) shown += Act.UNLOAD
            if (net != null) shown += Act.NET
            if (data != null) shown += Act.DATA
            if (wifi != null) shown += Act.WIFI
            if (hotspot.first != null) shown += Act.HOTSPOT
            if (vpn != null) shown += Act.VPN
            if (dataSim != null) shown += Act.DATASIM
            if (airplane != null) shown += Act.AIRPLANE
            if (powerSave != null) shown += Act.POWERSAVE
            if (bt != null) shown += Act.BT
            if (loc != null) shown += Act.LOC
            if (tiles.isNotEmpty()) shown += Act.TILES
            sys = e.sys
            if (sys.isNotEmpty()) shown += Act.SYS
            e.note?.let { texts[Act.NOTE] = it; shown += Act.NOTE }
            e.http?.let { texts[Act.HTTP] = it; shown += Act.HTTP }
            e.shell?.let { texts[Act.SHELL] = it; shown += Act.SHELL }
        }
    }

    private val atEnd = EndState()
    /** The unlock block of a lock rule with a time ([AppRule.unlockEnd]). */
    private val unlock = EndState()
    private var unlockMode = EndMode.RESTORE

    /** The choice view of each action row, to open it right after the action is added. */
    private val rowChoices = mutableMapOf<Act, ChoiceView>()

    private val simSlots by lazy { Sims.slots() }

    /** Which list the app picker is filling. */
    private enum class Pick { STOP, TRIGGER, PASS, LAUNCH, END_STOP, END_LAUNCH, UNLOCK_STOP, UNLOCK_LAUNCH, UNLOAD_KEEP }
    private var picking = Pick.STOP
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val pkgs = data?.getStringArrayExtra(AppPickerActivity.EXTRA_PACKAGES)
        if (result.resultCode == RESULT_OK && pkgs != null) {
            val names = data.getStringArrayExtra(AppPickerActivity.EXTRA_METHODS).orEmpty()
            val methods = pkgs.zip(names).mapNotNull { (p, m) ->
                runCatching { FreezeMethod.valueOf(m) }.getOrNull()?.let { p to it }
            }.toMap()
            when (picking) {
                Pick.TRIGGER -> triggerApps = pkgs.toList()
                Pick.PASS -> appPass = pkgs.toList()
                Pick.LAUNCH -> { launchApps = pkgs.toList(); if (launchApps.isEmpty()) shown -= Act.LAUNCH }
                Pick.END_STOP -> { atEnd.stopApps = pkgs.toList(); atEnd.appMethods = methods; if (atEnd.stopApps.isEmpty()) atEnd.shown -= Act.APPS }
                Pick.END_LAUNCH -> { atEnd.launchApps = pkgs.toList(); if (atEnd.launchApps.isEmpty()) atEnd.shown -= Act.LAUNCH }
                Pick.UNLOCK_STOP -> { unlock.stopApps = pkgs.toList(); unlock.appMethods = methods; if (unlock.stopApps.isEmpty()) unlock.shown -= Act.APPS }
                Pick.UNLOCK_LAUNCH -> { unlock.launchApps = pkgs.toList(); if (unlock.launchApps.isEmpty()) unlock.shown -= Act.LAUNCH }
                // One list for every rule and the widget, so it is kept at once, not with the rule.
                Pick.UNLOAD_KEEP -> Prefs.unloadExclude = pkgs.toList()
                Pick.STOP -> {
                    packages = pkgs.toList()
                    appMethods = methods
                    if (packages.isEmpty()) shown -= Act.APPS
                }
            }
            paint()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        v = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(v.root)

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        existing = Prefs.appRules.find { it.id == id }
        val source = existing ?: intent.getStringExtra(EXTRA_TEMPLATE)?.let {
            runCatching { AppRule.fromJson(org.json.JSONObject(it)) }.getOrNull()
        }
        load(source)

        v.toolbar.title = getString(if (existing == null) R.string.rule_new else R.string.rule_edit)
        v.toolbar.setNavigationOnClickListener { finish() }
        if (existing != null) {
            v.toolbar.menu.add(0, MENU_APPLY, 0, R.string.apply_now)
                .setIcon(R.drawable.ic_t_manual).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            v.toolbar.menu.add(0, MENU_RELEASE, 1, R.string.release_now)
            v.toolbar.menu.add(0, MENU_DELETE, 2, R.string.delete)
            v.toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_APPLY -> applyNow()
                    MENU_RELEASE -> releaseNow()
                    MENU_DELETE -> confirmDelete()
                }
                true
            }
        }
        wire()
        paint()
    }

    private fun load(r: AppRule?) {
        v.ruleName.setText(r?.name.orEmpty())
        trigger = when (r?.trigger) {
            null -> Trigger.TIME
            Trigger.WIFI_LEFT -> Trigger.WIFI
            else -> r.trigger
        }
        (if (r?.trigger == Trigger.WIFI_LEFT) v.wifiAfter else v.wifiWhile).isChecked = true
        start = (r?.hour ?: 23) to (r?.minute ?: 0)
        if (r?.hasEnd == true || (r?.lockWindow == true && r.endHour != null && r.endMinute != null)) end = r.endHour!! to r.endMinute!!
        if (r?.hasLeftEndTime == true) leftEnd = r.endHour!! to r.endMinute!!
        v.hasEnd.isChecked = r?.hasEnd ?: (r == null)
        v.lockWindow.isChecked = r?.lockWindow ?: false
        listOf(v.d1, v.d2, v.d3, v.d4, v.d5, v.d6, v.d7).forEachIndexed { i, c -> c.isChecked = (r?.days ?: 0) and (1 shl i) != 0 }
        v.ssid.setText(r?.ssid.orEmpty())
        v.leftEndTimeOn.isChecked = r?.hasLeftEndTime ?: true
        v.leftEndBtOn.isChecked = r?.endBtAddress != null
        v.wifiOffLeaves.isChecked = r?.leaveOnWifiOff ?: false
        btDevice = r?.btAddress?.let { BtDevice(it, r.btName ?: it) }
        endBt = r?.endBtAddress?.let { BtDevice(it, r.endBtName ?: it) }
        v.releaseOnLeave.isChecked = r?.releaseOnLeave ?: true
        // Off for a new rule: waiting while the phone is in use reads as "the rule did nothing".
        v.waitRow.toggle.isChecked = r?.waitLocked ?: false
        v.wakeRow.toggle.isChecked = r?.wakeWifi ?: false
        v.waitApps.isChecked = r?.waitAppsOnly ?: false
        waitTouched = existing != null
        triggerApps = r?.triggerApps.orEmpty()
        appPass = r?.appPassThrough.orEmpty()
        notice = r?.notice ?: RunNotice.SILENT
        extras.clear()
        extras += r?.extras.orEmpty()
        // A new rule waits a little: leaving for a moment is the common case, not the exception.
        appLeaveDelay = r?.appLeaveDelay ?: 5
        v.appScreenStays.isChecked = r?.appScreenOffStays ?: false
        cells = r?.cells.orEmpty()
        v.placeName.setText(r?.placeName.orEmpty())
        when (r?.trigger) {
            Trigger.BATTERY -> batteryLevel = r.level
            Trigger.SCREEN_OFF -> screenMinutes = r.level
            Trigger.LOCKED -> lockMinutes = r.level
            Trigger.WEAK_SIGNAL -> signalLevel = r.level
            Trigger.MANUAL -> manualMinutes = r.level
            Trigger.NO_INTERNET -> netLostSeconds = r.level
            Trigger.NOTIFY_APP -> notifyLevel = r.level
            Trigger.ALARM -> alarmMinutes = r.level
            Trigger.SUN -> sunLevel = r.level
            Trigger.TRAFFIC -> trafficIdx = TRAFFIC_OPTS.indexOf(r.level to r.trafficMonth).coerceAtLeast(0)
            else -> Unit
        }
        knownSsids = r?.knownSsids.orEmpty()
        v.tSlot.setItems(simSlots.map { "SIM ${it + 1}" }, simSlots.indexOf(r?.triggerSlot ?: 0))
        v.tMode.setItems(NetMode.entries.map { it.label }, NetMode.entries.indexOf(r?.triggerMode ?: NetMode.FULL))

        if (r == null) return
        netMode = r.netMode; netSlot = r.netSlot; data = r.mobileData; wifi = r.wifi
        hotspot = r.hotspot to r.hotspotVia; vpn = r.vpn; vpnApp = r.vpnApp; dataSim = r.dataSim
        airplane = r.airplane; powerSave = r.powerSave; btOff = r.bluetoothOff; locOff = r.locationOff
        tiles = r.tiles; packages = r.packages; keepStopped = r.keepStopped
        launchApps = r.launchApps; atEnd.stopApps = r.endStopApps; atEnd.launchApps = r.endLaunchApps
        atEnd.appMethods = r.endStopApps.associateWith { r.endMethodOf(it) }
        if (atEnd.stopApps.isNotEmpty()) atEnd.shown += Act.APPS
        if (atEnd.launchApps.isNotEmpty()) atEnd.shown += Act.LAUNCH
        if (launchApps.isNotEmpty()) shown += Act.LAUNCH
        appMethods = r.packages.associateWith { r.methodOf(it) }
        if (netMode != null) shown += Act.NET
        if (data != null) shown += Act.DATA
        if (wifi != null) shown += Act.WIFI
        if (hotspot.first != null) shown += Act.HOTSPOT
        if (vpn != null) shown += Act.VPN
        if (dataSim != null) shown += Act.DATASIM
        if (airplane != null) shown += Act.AIRPLANE
        if (powerSave != null) shown += Act.POWERSAVE
        if (btOff) shown += Act.BT
        if (locOff) shown += Act.LOC
        if (tiles.isNotEmpty()) shown += Act.TILES
        if (packages.isNotEmpty()) shown += Act.APPS
        sys = r.sys
        if (sys.isNotEmpty()) shown += Act.SYS
        r.note?.let { texts[Act.NOTE] = it; shown += Act.NOTE }
        r.http?.let { texts[Act.HTTP] = it; shown += Act.HTTP }
        r.shell?.let { texts[Act.SHELL] = it; shown += Act.SHELL }
        r.endNote?.let { atEnd.texts[Act.NOTE] = it; atEnd.shown += Act.NOTE }
        r.endHttp?.let { atEnd.texts[Act.HTTP] = it; atEnd.shown += Act.HTTP }
        r.endShell?.let { atEnd.texts[Act.SHELL] = it; atEnd.shown += Act.SHELL }

        (if (r.endCustom) v.endCustom else v.endRestore).isChecked = true
        atEnd.net = r.endNet; atEnd.data = r.endData; atEnd.wifi = r.endWifi; atEnd.hotspot = r.endHotspot to r.endHotspotVia
        atEnd.vpn = r.endVpn; atEnd.dataSim = r.endDataSim; atEnd.airplane = r.endAirplane; atEnd.powerSave = r.endPowerSave
        atEnd.bt = r.endBt; atEnd.loc = r.endLoc; atEnd.tiles = r.endTiles; atEnd.sys = r.endSys
        atEnd.shown += shown.filter { !it.anyEnd }
        if (r.unload) shown += Act.UNLOAD
        if (r.reconnect) shown += Act.RECONNECT
        if (r.endUnload) atEnd.shown += Act.UNLOAD
        unloadAll = r.unloadAll; atEnd.unloadAll = r.endUnloadAll
        if (atEnd.net != null) atEnd.shown += Act.NET
        if (atEnd.data != null) atEnd.shown += Act.DATA
        if (atEnd.wifi != null) atEnd.shown += Act.WIFI
        if (atEnd.hotspot.first != null) atEnd.shown += Act.HOTSPOT
        if (atEnd.vpn != null) atEnd.shown += Act.VPN
        if (atEnd.dataSim != null) atEnd.shown += Act.DATASIM
        if (atEnd.airplane != null) atEnd.shown += Act.AIRPLANE
        if (atEnd.powerSave != null) atEnd.shown += Act.POWERSAVE
        if (atEnd.bt != null) atEnd.shown += Act.BT
        if (atEnd.loc != null) atEnd.shown += Act.LOC
        if (atEnd.tiles.isNotEmpty()) atEnd.shown += Act.TILES
        if (atEnd.sys.isNotEmpty()) atEnd.shown += Act.SYS
        // The unlock block: its own, or — for a rule from before it — the same as the end,
        // which saves back as none: unlocking then ends the night, as it always did.
        val u = r.unlockEnd ?: r.endSpec
        unlockMode = u.mode
        unlock.load(u)
        unlock.shown += shown.filter { !it.anyEnd }
        v.unlockKeepApps.isChecked = u.keepApps
        when (u.mode) {
            EndMode.RESTORE -> v.unlockRestore
            EndMode.CUSTOM -> v.unlockCustom
            EndMode.NOTHING -> v.unlockNothing
        }.isChecked = true
        // A rule from before steps: its actions in the order they have always run in.
        steps += r.steps.ifEmpty { shown.map { it.name }.sortedBy { Steps.START_ORDER.indexOf(it) } }
        atEnd.steps += r.endSteps.ifEmpty { atEnd.shown.map { it.name }.sortedBy { Steps.END_ORDER.indexOf(it) } }
        unlock.steps += u.steps.ifEmpty { unlock.shown.map { it.name }.sortedBy { Steps.END_ORDER.indexOf(it) } }
    }

    private fun wire() {
        v.triggerPick.setOnClickListener { chooseTrigger() }
        v.wakeRow.title.setText(R.string.wake_wifi)
        v.wakeRow.root.setOnClickListener { v.wakeRow.toggle.toggle() }
        v.wakeRow.toggle.setOnCheckedChangeListener { _, _ -> paint() }
        v.wakeForget.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.wake_forget_confirm)
                .setPositiveButton(R.string.wake_forget) { _, _ ->
                    cells = emptySet()
                    existing?.let { WifiWake.forget(it.id) }
                    paint()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        v.waitRow.title.setText(R.string.wait_locked)
        v.waitRow.summary.setText(R.string.wait_locked_summary)
        v.waitRow.root.setOnClickListener { waitTouched = true; v.waitRow.toggle.toggle() }
        v.waitRow.toggle.setOnClickListener { waitTouched = true }
        v.waitRow.toggle.setOnCheckedChangeListener { _, _ -> paint() }
        v.start.setOnClickListener { askTime(start) { start = it } }
        v.end.setOnClickListener { askTime(end) { end = it } }
        v.leftEndTime.setOnClickListener { askTime(leftEnd) { leftEnd = it } }
        listOf(v.hasEnd, v.lockWindow, v.leftEndTimeOn, v.leftEndBtOn, v.releaseOnLeave).forEach {
            it.setOnCheckedChangeListener { _, _ -> paint() }
        }
        listOf(v.d1, v.d2, v.d3, v.d4, v.d5, v.d6, v.d7).forEach { it.setOnCheckedChangeListener { _, _ -> paint() } }
        v.wifiMode.setOnCheckedChangeListener { _, _ -> paint() }
        v.endMode.setOnCheckedChangeListener { _, _ -> paint() }
        v.btDevice.setOnClickListener { pickBtDevice { btDevice = it; paint() } }
        v.leftEndBt.setOnClickListener { pickBtDevice { endBt = it; paint() } }
        v.tSlot.onChange = { paint() }
        v.level.onChange = { i ->
            when (trigger) {
                Trigger.BATTERY -> batteryLevel = BATTERY_LEVELS[i]
                Trigger.SCREEN_OFF -> screenMinutes = SCREEN_MINUTES[i]
                Trigger.LOCKED -> lockMinutes = LOCK_MINUTES[i]
                Trigger.WEAK_SIGNAL -> signalLevel = i
                Trigger.MANUAL -> manualMinutes = MANUAL_MINUTES[i]
                Trigger.NO_INTERNET -> netLostSeconds = NET_LOST_SECONDS[i]
                Trigger.NOTIFY_APP -> notifyLevel = i
                Trigger.ALARM -> alarmMinutes = ALARM_MINUTES[i]
                Trigger.SUN -> sunLevel = i
                Trigger.TRAFFIC -> trafficIdx = i
                else -> Unit
            }
            paint()
        }
        v.useCurrent.setOnClickListener {
            lifecycleScope.launch {
                val ssid = WifiSsid.current()
                if (ssid != null) { v.ssid.setText(ssid); v.ssidLayout.error = null }
                else Toast.makeText(this@RuleEditActivity, R.string.ssid_not_found, Toast.LENGTH_SHORT).show()
            }
        }
        v.triggerApps.setOnClickListener { pickPlain(Pick.TRIGGER, triggerApps) }
        v.appPass.setOnClickListener { pickPlain(Pick.PASS, appPass) }
        v.addExtra.setOnClickListener { addExtra() }
        v.sunPlace.setOnClickListener { askSunPlace { paint() } }
        v.knownSsids.setOnClickListener { askKnownSsids() }
        v.notice.onChange = { i ->
            notice = RunNotice.entries[i]
            // Without notifications a question cannot be seen; the rule would run unasked.
            if (notice != RunNotice.SILENT && !Compat.granted(this, android.Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8)
            }
            paint()
        }
        v.sceneShortcut.setOnClickListener {
            val saved = existing ?: return@setOnClickListener
            // Labelled as edited; the shortcut itself only carries the scene's id.
            val rule = build(saved.id, saved.enabled)
            if (!validated(rule)) return@setOnClickListener
            if (!Scenes.pin(this, rule)) {
                Snackbar.make(v.root, R.string.scene_shortcut_unsupported, Snackbar.LENGTH_LONG).setAnchorView(v.save).show()
            }
        }
        v.placeAdd.setOnClickListener {
            v.placeAdd.isEnabled = false
            lifecycleScope.launch {
                val now = runCatching { ConditionWatch.currentCells() }.getOrDefault(emptySet())
                v.placeAdd.isEnabled = true
                if (now.isEmpty()) {
                    Toast.makeText(this@RuleEditActivity, R.string.place_none, Toast.LENGTH_LONG).show()
                } else {
                    val added = (now - cells).size
                    cells = cells + now
                    Toast.makeText(this@RuleEditActivity, getString(R.string.place_added, added), Toast.LENGTH_SHORT).show()
                }
                paint()
            }
        }
        v.placeClear.setOnClickListener { cells = emptySet(); paint() }
        v.addAction.setOnClickListener { addAction(null) }
        v.addEndAction.setOnClickListener { addAction(atEnd) }
        v.addUnlockAction.setOnClickListener { addAction(unlock) }
        v.addUnlockPause.setOnClickListener { askPause(60) { unlock.steps += Steps.pause(it); paint() } }
        v.unlockMode.setOnCheckedChangeListener { _, id ->
            unlockMode = when (id) {
                R.id.unlockCustom -> EndMode.CUSTOM
                R.id.unlockNothing -> EndMode.NOTHING
                else -> EndMode.RESTORE
            }
            paint()
        }
        v.addPause.setOnClickListener { askPause(60) { steps += Steps.pause(it); paint() } }
        v.addEndPause.setOnClickListener { askPause(60) { atEnd.steps += Steps.pause(it); paint() } }
        v.unstop.setOnClickListener {
            val labels = AppPickerActivity.labels(packages).toTypedArray()
            val checked = BooleanArray(packages.size) { packages[it] !in keepStopped }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unstop_choose)
                .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.done_btn) { _, _ ->
                    keepStopped = packages.filterIndexed { i, _ -> !checked[i] }.toSet()
                    paint()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        v.save.setOnClickListener { save() }
    }

    // --- rendering ------------------------------------------------------------------------

    private fun hm(t: Pair<Int, Int>) = "%02d:%02d".format(t.first, t.second)

    /** "сразу", "через 15 с", "через 5 мин" */
    private fun leaveLabel(seconds: Int): String = when {
        seconds <= 0 -> getString(R.string.app_leave_now)
        seconds % 60 == 0 -> getString(R.string.after_minutes_x, seconds / 60)
        else -> getString(R.string.app_leave_seconds, seconds)
    }

    private val wifiLeft get() = trigger == Trigger.WIFI && v.wifiAfter.isChecked
    private val effectiveTrigger get() = if (wifiLeft) Trigger.WIFI_LEFT else trigger

    private val restores: Boolean
        get() = when (effectiveTrigger) {
            Trigger.TIME -> v.hasEnd.isChecked
            Trigger.WIFI_LEFT -> true
            else -> v.releaseOnLeave.isChecked
        }

    /** Actions that would undo their own trigger are not offered. */
    /**
     * What may be set at the end: more than at the start, since by then the window is closed
     * and nothing can undo it — a Wi-Fi rule may switch Wi-Fi off once you have left.
     */
    private fun allowedEnd(a: Act): Boolean {
        val t = effectiveTrigger
        if (a.startOnly) return false
        return allowed(a) || (t == Trigger.WIFI && (a == Act.WIFI || a == Act.HOTSPOT)) ||
            (a == Act.BT && (t == Trigger.BLUETOOTH || t == Trigger.HEADPHONES))
    }

    private fun allowed(a: Act): Boolean {
        val t = effectiveTrigger
        return when (a) {
            Act.WIFI, Act.HOTSPOT -> t != Trigger.WIFI && t != Trigger.WIFI_OTHER
            Act.DATA -> t != Trigger.MOBILE_DATA
            Act.BT -> t != Trigger.BLUETOOTH && t != Trigger.HEADPHONES && !(t == Trigger.WIFI_LEFT && v.leftEndBtOn.isChecked)
            Act.AIRPLANE -> t !in setOf(Trigger.WIFI, Trigger.WIFI_LEFT, Trigger.BLUETOOTH, Trigger.MOBILE_DATA,
                Trigger.PLACE, Trigger.ROAMING, Trigger.WEAK_SIGNAL, Trigger.CALL, Trigger.NET_MODE, Trigger.NO_INTERNET, Trigger.WIFI_OTHER)
            // Airplane mode for a moment drops Wi-Fi, Bluetooth and the SIMs, which these watch;
            // a lost internet is what it is for.
            Act.RECONNECT -> t !in setOf(Trigger.WIFI, Trigger.WIFI_LEFT, Trigger.BLUETOOTH, Trigger.MOBILE_DATA,
                Trigger.PLACE, Trigger.ROAMING, Trigger.WEAK_SIGNAL, Trigger.CALL, Trigger.NET_MODE, Trigger.WIFI_OTHER)
            Act.POWERSAVE -> !(t == Trigger.BATTERY && batteryLevel == 0)
            Act.DATASIM -> simSlots.size > 1
            else -> true
        }
    }

    private fun paint() {
        val t = trigger
        val info = triggerInfos.first { it.trigger == t }
        v.triggerPick.text = getString(info.title)
        v.triggerPick.setIconResource(info.icon)
        v.triggerHint.setText(info.short)

        fun show(view: View, on: Boolean) { view.visibility = if (on) View.VISIBLE else View.GONE }
        show(v.lockWindow, t == Trigger.LOCKED)
        show(v.timeBlock, t == Trigger.TIME || (t == Trigger.LOCKED && v.lockWindow.isChecked))
        // In a lock window both times are the window; there is no "no end".
        show(v.hasEnd, t == Trigger.TIME)
        show(v.hasEndHint, t == Trigger.TIME)
        show(v.wifiBlock, t == Trigger.WIFI)
        show(v.leftEndBlock, wifiLeft)
        show(v.wakeRow.root, t == Trigger.WIFI && !wifiLeft)
        show(v.wakeForget, t == Trigger.WIFI && !wifiLeft && v.wakeRow.toggle.isChecked && cells.isNotEmpty())
        v.wakeRow.summary.text = getString(R.string.wake_wifi_summary, cells.size)
        show(v.btDevice, t == Trigger.BLUETOOTH)
        show(v.slotRow, t == Trigger.NET_MODE || t == Trigger.WEAK_SIGNAL)
        show(v.modeRow, t == Trigger.NET_MODE)
        show(v.levelRow, t == Trigger.BATTERY || t == Trigger.SCREEN_OFF || t == Trigger.LOCKED || t == Trigger.WEAK_SIGNAL ||
            (t == Trigger.MANUAL && v.releaseOnLeave.isChecked) || t == Trigger.NO_INTERNET ||
            t == Trigger.NOTIFY_APP || t == Trigger.ALARM || t == Trigger.SUN || t == Trigger.TRAFFIC)
        show(v.sunPlace, t == Trigger.SUN || extras.any { it.kind == ExtraKind.DARK })
        v.sunPlace.text = Prefs.sunPlace?.let { (la, lo) -> getString(R.string.sun_place, "%.4f, %.4f".format(java.util.Locale.US, la, lo)) }
            ?: getString(R.string.sun_place_none)
        show(v.knownSsids, t == Trigger.WIFI_OTHER)
        v.knownSsids.text = if (knownSsids.isEmpty()) getString(R.string.known_ssids_none)
        else getString(R.string.known_ssids, knownSsids.joinToString(", "))
        // Locked, nobody is using the phone: there is nothing to wait for. A scene is pressed by
        // someone using it: waiting would only hide that it worked.
        show(v.waitRow.root, t != Trigger.LOCKED && t != Trigger.MANUAL)
        show(v.sceneShortcut, t == Trigger.MANUAL)
        // Another rule pulling the same thing another way: whichever comes last wins.
        val clashes = runCatching { build(existing?.id ?: -1L, true).conflicts(Prefs.appRules) }.getOrDefault(emptyList())
        show(v.conflictHint, clashes.isNotEmpty())
        v.conflictHint.text = getString(R.string.conflict_hint,
            clashes.joinToString("; ") { (r, what) -> "«${r.name ?: r.timeLabel}» — ${what.joinToString(", ")}" })
        // A scene is pressed when wanted; "after leaving" has no state to wait on.
        show(v.extrasBlock, t != Trigger.MANUAL && !wifiLeft)
        show(v.extrasHint, extras.isNotEmpty())
        renderExtras()
        // A scene is pressed by hand: there is nothing to ask or tell.
        show(v.noticeRow, t != Trigger.MANUAL)
        show(v.noticeHint, t != Trigger.MANUAL && notice != RunNotice.SILENT)
        v.notice.setItems(listOf(R.string.notice_silent, R.string.notice_notify, R.string.notice_ask).map { getString(it) }, notice.ordinal)
        v.noticeHint.setText(if (notice == RunNotice.ASK) R.string.notice_ask_hint else R.string.notice_notify_hint)
        v.sceneShortcut.isEnabled = existing != null
        v.sceneShortcut.text = getString(if (existing != null) R.string.scene_shortcut else R.string.scene_shortcut_save_first)
        v.releaseOnLeave.setText(if (t == Trigger.MANUAL) R.string.manual_toggle else R.string.release_on_leave)
        show(v.triggerApps, t == Trigger.APP_OPEN || t == Trigger.NOTIFY_APP)
        show(v.appLeaveBlock, t == Trigger.APP_OPEN)
        show(v.placeBlock, t == Trigger.PLACE)
        val condition = t.isCondition && !wifiLeft
        show(v.releaseOnLeave, condition)
        show(v.releaseHint, false)
        // Only meaningful when the rule stops apps and does something else besides.
        show(v.waitApps, v.waitRow.toggle.isChecked && Act.APPS in shown && shown.size > 1)

        when (t) {
            Trigger.BATTERY -> {
                v.levelLabel.setText(R.string.level_battery)
                v.level.setItems(BATTERY_LEVELS.map { if (it == 0) getString(R.string.level_battery_saver) else "≤ $it%" },
                    BATTERY_LEVELS.indexOf(batteryLevel).coerceAtLeast(0))
            }
            Trigger.SCREEN_OFF -> {
                v.levelLabel.setText(R.string.level_screen)
                v.level.setItems(SCREEN_MINUTES.map { getString(R.string.minutes_x, it) },
                    SCREEN_MINUTES.indexOf(screenMinutes).coerceAtLeast(0))
            }
            Trigger.LOCKED -> {
                v.levelLabel.setText(R.string.level_locked)
                v.level.setItems(LOCK_MINUTES.map { if (it == 0) getString(R.string.level_locked_now) else getString(R.string.after_minutes_x, it) },
                    LOCK_MINUTES.indexOf(lockMinutes).coerceAtLeast(0))
            }
            Trigger.WEAK_SIGNAL -> {
                v.levelLabel.setText(R.string.level_signal)
                v.level.setItems(resources.getStringArray(R.array.signal_levels).toList(), signalLevel)
            }
            Trigger.NOTIFY_APP -> {
                v.levelLabel.setText(R.string.level_notify)
                v.level.setItems(listOf(getString(R.string.notify_any), getString(R.string.notify_ongoing)), notifyLevel)
            }
            Trigger.ALARM -> {
                v.levelLabel.setText(R.string.level_alarm)
                v.level.setItems(ALARM_MINUTES.map { Steps.words(it * 60) }, ALARM_MINUTES.indexOf(alarmMinutes).coerceAtLeast(0))
            }
            Trigger.SUN -> {
                v.levelLabel.setText(R.string.level_sun)
                v.level.setItems(listOf(getString(R.string.sun_dark), getString(R.string.sun_light)), sunLevel)
            }
            Trigger.TRAFFIC -> {
                v.levelLabel.setText(R.string.level_traffic)
                v.level.setItems(TRAFFIC_OPTS.map { (mb, month) ->
                    trafficLabel(mb) + " " + getString(if (month) R.string.traffic_month else R.string.traffic_day)
                }, trafficIdx)
            }
            Trigger.NO_INTERNET -> {
                v.levelLabel.setText(R.string.level_screen)
                v.level.setItems(NET_LOST_SECONDS.map { Steps.words(it) }, NET_LOST_SECONDS.indexOf(netLostSeconds).coerceAtLeast(0))
            }
            Trigger.MANUAL -> {
                v.levelLabel.setText(R.string.level_manual)
                v.level.setItems(MANUAL_MINUTES.map { if (it == 0) getString(R.string.manual_forever) else getString(R.string.after_x, Steps.words(it * 60)) },
                    MANUAL_MINUTES.indexOf(manualMinutes).coerceAtLeast(0))
            }
            else -> Unit
        }

        v.start.text = getString(R.string.start_at, hm(start))
        val withEnd = v.hasEnd.isChecked || t == Trigger.LOCKED
        v.end.text = if (withEnd) getString(R.string.end_at, hm(end)) else getString(R.string.no_end)
        v.end.isEnabled = withEnd
        val heavy = packages.any { (appMethods[it] ?: FreezeMethod.STOP_RESTRICT) != FreezeMethod.STOP }
        v.hasEndHint.text = getString(
            when {
                v.hasEnd.isChecked -> R.string.has_end_hint
                heavy -> R.string.no_end_heavy_hint
                else -> R.string.no_end_hint
            }
        )
        val days = listOf(v.d1, v.d2, v.d3, v.d4, v.d5, v.d6, v.d7).count { it.isChecked }
        v.daysHint.setText(R.string.days_none)
        show(v.daysHint, days == 0)
        v.leftEndTime.text = getString(R.string.end_time_x, hm(leftEnd))
        v.leftEndTime.isEnabled = v.leftEndTimeOn.isChecked
        v.leftEndBt.text = endBt?.let { getString(R.string.device_x, it.name) } ?: getString(R.string.choose_device)
        v.leftEndBt.isEnabled = v.leftEndBtOn.isChecked
        v.btDevice.text = btDevice?.let { getString(R.string.device_x, it.name) } ?: getString(R.string.choose_device)
        v.triggerApps.text = if (triggerApps.isEmpty()) getString(R.string.trigger_apps_choose)
        else AppPickerActivity.labels(triggerApps).joinToString(", ")
        v.appPass.text = if (appPass.isEmpty()) getString(R.string.app_pass_choose)
        else getString(R.string.app_pass_x, AppPickerActivity.labels(appPass).joinToString(", "))
        // A delay from elsewhere (a backup) that the list does not offer is kept as it is.
        val delays = (LEAVE_DELAYS + appLeaveDelay).distinct().sorted()
        v.appLeaveDelay.setItems(delays.map { leaveLabel(it) }, delays.indexOf(appLeaveDelay))
        v.appLeaveDelay.onChange = { i -> appLeaveDelay = delays[i]; paint() }
        v.placeAdd.text = getString(R.string.place_add, cells.size)
        v.placeClear.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE

        // Actions no longer allowed for this trigger are dropped rather than kept hidden: a
        // hidden value would still be saved and applied.
        shown.removeAll { !allowed(it) }
        atEnd.shown.removeAll { !allowedEnd(it) }
        renderActions()

        show(v.endSection, restores)
        val split = t == Trigger.LOCKED && v.lockWindow.isChecked && restores
        // Named after what ends the window: for a Wi-Fi rule this block is simply "after
        // leaving", so one rule holds both "while connected" and "after leaving".
        v.endTitle.text = when (effectiveTrigger) {
            Trigger.WIFI -> getString(R.string.end_title_wifi)
            Trigger.BLUETOOTH -> getString(R.string.end_title_bt)
            Trigger.MOBILE_DATA -> getString(R.string.end_title_data)
            Trigger.MANUAL -> getString(R.string.end_title_manual)
            Trigger.TIME -> getString(R.string.end_title_time, hm(end))
            Trigger.WIFI_LEFT -> getString(R.string.at_end)
            Trigger.LOCKED -> if (split) getString(R.string.end_title_window, hm(end)) else getString(R.string.end_title_unlock)
            else -> getString(R.string.end_title_condition)
        }
        val custom = v.endCustom.isChecked
        renderEnd(atEnd, v.endActions, custom, nothing = false, addPause = v.addEndPause, orderHint = v.endOrderHint)

        // A lock rule with a time: unlocking inside it has its own block.
        show(v.unlockSection, split)
        if (split) {
            unlock.shown.removeAll { !allowedEnd(it) }
            val uCustom = unlockMode == EndMode.CUSTOM
            val nothing = unlockMode == EndMode.NOTHING
            renderEnd(unlock, v.unlockActions, uCustom, nothing, v.addUnlockPause, v.unlockOrderHint)
            show(v.addUnlockAction, !nothing && Act.entries.any { allowedEnd(it) && it !in unlock.shown && (uCustom || it.anyEnd) })
            show(v.unlockKeepApps, !nothing && packages.isNotEmpty())
            v.unlockTitle.text = getString(R.string.unlock_title, hm(end))
            v.unlockHint.text = getString(when (unlockMode) {
                EndMode.RESTORE -> R.string.unlock_restore_hint
                EndMode.CUSTOM -> R.string.unlock_custom_hint
                EndMode.NOTHING -> R.string.unlock_nothing_hint
            }, hm(end))
        }
        show(v.addEndAction, Act.entries.any { allowedEnd(it) && it !in atEnd.shown && (custom || it.anyEnd) })
        v.endHint.setText(if (custom) R.string.end_custom_hint else R.string.end_restore_hint)
        show(v.unstop, packages.isNotEmpty())
        show(v.unstopHint, packages.isNotEmpty())
        v.unstop.text = getString(R.string.unstop_at_end, packages.count { it !in keepStopped }, packages.size)
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    /** "icon  Label ………… value ▾  ×" */
    private fun row(parent: LinearLayout, a: Act, label: String, value: View?, onRemove: (() -> Unit)?): LinearLayout =
        row(parent, a.icon, label, value, onRemove)

    private fun row(parent: LinearLayout, icon: Int, label: String, value: View?, onRemove: (() -> Unit)?): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        line.addView(ImageView(this).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
        })
        line.addView(TextView(this, null, 0, R.style.NMS_RowLabel).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        value?.let { line.addView(it) }
        if (onRemove != null) {
            line.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                contentDescription = getString(R.string.remove)
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setOnClickListener { onRemove() }
            })
        }
        parent.addView(line)
        return line
    }

    /** "Не выгружать: Telegram, Яндекс Музыка" — the shared exception list, editable from any rule. */
    private fun unloadKeepButton(): View {
        val keep = Prefs.unloadExclude
        return MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = if (keep.isEmpty()) getString(R.string.unload_keep_none)
                else getString(R.string.unload_keep_list, AppPickerActivity.labels(keep).joinToString(", "))
            setOnClickListener { pickPlain(Pick.UNLOAD_KEEP, Prefs.unloadExclude) }
        }
    }

    /** "фон" — what the system counts as background; "всё" — music and navigation too. */
    private fun unloadChoice(all: Boolean, onPick: (Boolean) -> Unit) =
        choice(listOf(getString(R.string.unload_bg), getString(R.string.unload_all)), if (all) 1 else 0) { onPick(it == 1) }

    private fun choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = ChoiceView(this).apply {
        setItems(labels, selected)
        onChange = { onPick(it); paint() }
    }

    private fun sub(parent: LinearLayout, view: View) {
        view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(32) }
        parent.addView(view)
    }

    private fun hint(text: String) = TextView(this, null, 0, R.style.NMS_Hint).apply { this.text = text }

    private val onOff get() = listOf(getString(R.string.turn_on), getString(R.string.turn_off))
    private fun toggleOf(i: Int) = if (i == 0) Toggle.ON else Toggle.OFF
    private fun indexOf(t: Toggle?) = if (t == Toggle.OFF) 1 else 0

    /** Hotspot choices; "on, route unchanged" is kept only for rules that already use it. */
    private fun hotspotOptions(current: Pair<Toggle?, TetherMode?>): List<Pair<Pair<Toggle?, TetherMode?>, String>> = buildList {
        add((Toggle.ON to TetherMode.DIRECT) to getString(R.string.ap_on_direct))
        add((Toggle.ON to TetherMode.VPN) to getString(R.string.ap_on_vpn))
        add((Toggle.ON to TetherMode.PROXY) to getString(R.string.ap_on_proxy))
        if (current == (Toggle.ON to null)) add((Toggle.ON to null) to getString(R.string.ap_on_keep))
        add((Toggle.OFF to null) to getString(R.string.turn_off))
    }

    private fun netTargetLabel(): String = when {
        trigger == Trigger.NET_MODE -> "SIM ${otherSlot() + 1}"
        netSlot == SLOT_ALL -> getString(R.string.all_sims_short)
        else -> "SIM ${netSlot + 1}"
    }

    /** The SIM a "network mode" rule acts on: always the one it does not watch. */
    private fun otherSlot(): Int {
        val watched = simSlots.getOrElse(v.tSlot.selectedItemPosition) { 0 }
        return simSlots.firstOrNull { it != watched } ?: (1 - watched)
    }

    private fun renderActions() {
        val container = v.actions
        container.removeAllViews()
        rowChoices.clear()
        sync(steps, shown)
        val order = steps.toMutableList()
        val commit = { steps.clear(); steps += order }
        for ((i, step) in order.withIndex()) {
            val box = block(container, order, commit)
            val a = Act.entries.firstOrNull { it.name == step }
            if (a == null) {
                pauseRow(box, order, i, commit)
                continue
            }
            val remove = { removeAction(a) }
            when (a) {
                Act.NET -> {
                    // Only what works on this phone, plus whatever the rule already has.
                    val modes = (Profile.available() + listOfNotNull(netMode)).distinct().sortedBy { it.ordinal }
                    val c = choice(modes.map { it.label }, modes.indexOf(netMode ?: NetMode.FULL).coerceAtLeast(0)) { netMode = modes[it] }
                    if (netMode == null) netMode = NetMode.FULL
                    rowChoices[a] = c
                    row(box, a, getString(R.string.act_network_sim, netTargetLabel()), c, remove)
                    if (simSlots.size > 1 && trigger != Trigger.NET_MODE) {
                        val targets = listOf(SLOT_ALL) + simSlots
                        val simChoice = choice(listOf(getString(R.string.all_sims)) + simSlots.map { Sims.slotLabel(it) },
                            targets.indexOf(netSlot).coerceAtLeast(0)) { netSlot = targets[it] }
                        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                        line.addView(TextView(this, null, 0, R.style.NMS_Hint).apply {
                            setText(R.string.net_sim_pick)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        line.addView(simChoice)
                        sub(box, line)
                    }
                }
                Act.DATA, Act.WIFI, Act.AIRPLANE, Act.POWERSAVE -> {
                    val cur = when (a) { Act.DATA -> data; Act.WIFI -> wifi; Act.AIRPLANE -> airplane; else -> powerSave }
                    val c = choice(onOff, indexOf(cur)) { i ->
                        when (a) { Act.DATA -> data = toggleOf(i); Act.WIFI -> wifi = toggleOf(i); Act.AIRPLANE -> airplane = toggleOf(i); else -> powerSave = toggleOf(i) }
                    }
                    if (cur == null) when (a) { Act.DATA -> data = Toggle.ON; Act.WIFI -> wifi = Toggle.ON; Act.AIRPLANE -> airplane = Toggle.ON; else -> powerSave = Toggle.ON }
                    rowChoices[a] = c
                    row(box, a, getString(a.title), c, remove)
                    // With Wi-Fi off the phone cannot see the network again: coming back
                    // will not end the window, only the time or the Bluetooth device will.
                    if (a == Act.WIFI && wifi == Toggle.OFF && wifiLeft) {
                        sub(box, hint(getString(if (v.leftEndTimeOn.isChecked || v.leftEndBtOn.isChecked)
                            R.string.wifi_off_left_ok else R.string.wifi_off_left_warn)).apply {
                            if (!v.leftEndTimeOn.isChecked && !v.leftEndBtOn.isChecked) setTextColor(themeColor(android.R.attr.colorError))
                        })
                    }
                }
                Act.HOTSPOT -> {
                    val opts = hotspotOptions(hotspot)
                    if (hotspot.first == null) hotspot = opts.first().first
                    val c = choice(opts.map { it.second }, opts.indexOfFirst { it.first == hotspot }.coerceAtLeast(0)) { hotspot = opts[it].first }
                    rowChoices[a] = c
                    row(box, a, getString(a.title), c, remove)
                }
                Act.VPN -> {
                    if (vpn == null) vpn = Toggle.ON
                    val c = choice(onOff, indexOf(vpn)) { vpn = toggleOf(it) }
                    rowChoices[a] = c
                    row(box, a, getString(a.title), c, remove)
                    if (vpn == Toggle.ON) sub(box, vpnAppButton())
                }
                Act.DATASIM -> {
                    if (dataSim == null) dataSim = simSlots.first()
                    val c = choice(simSlots.map { Sims.slotLabel(it) }, simSlots.indexOf(dataSim).coerceAtLeast(0)) { dataSim = simSlots[it] }
                    rowChoices[a] = c
                    row(box, a, getString(a.title), c, remove)
                }
                Act.BT -> { btOff = true; row(box, a, getString(R.string.bt_off), null, remove) }
                Act.LOC -> { locOff = true; row(box, a, getString(R.string.loc_off), null, remove) }
                Act.RECONNECT -> {
                    row(box, a, getString(a.title), null, remove)
                    sub(box, hint(getString(R.string.act_reconnect_hint)))
                }
                Act.SYS -> {
                    row(box, a, getString(a.title), changeButton(sys.isEmpty()) {
                        pickSys(sys) { sys = it; if (sys.isEmpty()) shown -= Act.SYS; paint() }
                    }, remove)
                    if (sys.isNotEmpty()) sub(box, hint(SysKey.summary(sys)))
                    if (SysKey.REFRESH.name in sys) sub(box, hint(getString(R.string.sys_refresh_hint)))
                }
                Act.NOTE, Act.HTTP, Act.SHELL -> {
                    row(box, a, getString(a.title), changeButton(texts[a] == null) {
                        askText(a, texts[a]) { t -> if (t == null) { texts -= a; shown -= a } else texts[a] = t; paint() }
                    }, remove)
                    texts[a]?.let { sub(box, hint(it)) }
                }
                Act.TILES -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(if (tiles.isEmpty()) R.string.choose else R.string.change)
                        setOnClickListener { pickTiles(tiles) { tiles = it; if (tiles.isEmpty()) shown -= Act.TILES; paint() } }
                    }
                    row(box, a, getString(a.title), b, remove)
                    tileSummary(tiles)?.let { sub(box, it) }
                }
                Act.UNLOAD -> {
                    row(box, a, getString(R.string.act_unload_row), unloadChoice(unloadAll) { unloadAll = it }, remove)
                    sub(box, hint(getString(if (unloadAll) R.string.unload_all_hint else R.string.unload_hint)))
                    sub(box, unloadKeepButton())
                }
                Act.LAUNCH -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(R.string.apps_choose_n, launchApps.size)
                        setOnClickListener { pickPlain(Pick.LAUNCH, launchApps) }
                    }
                    row(box, a, getString(a.title), b, remove)
                    if (launchApps.isNotEmpty()) sub(box, hint(AppPickerActivity.labels(launchApps).joinToString(", ")))
                }
                Act.APPS -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(R.string.apps_choose_n, packages.size)
                        setOnClickListener { pickApps() }
                    }
                    row(box, a, getString(a.title), b, remove)
                    if (packages.isNotEmpty()) {
                        // One line per method: "Остановить + запрет фона: Т-Банк, Spotify".
                        val names = packages.zip(AppPickerActivity.labels(packages)).toMap()
                        sub(box, hint(packages.groupBy { appMethods[it] ?: FreezeMethod.STOP_RESTRICT }.entries
                            .joinToString("\n") { (m, ps) -> m.label + ": " + ps.joinToString(", ") { names.getValue(it) } }))
                    }
                    if (!restores && packages.any { (appMethods[it] ?: FreezeMethod.STOP_RESTRICT) != FreezeMethod.STOP }) {
                        sub(box, hint(getString(R.string.no_end_heavy_hint)).apply { setTextColor(themeColor(android.R.attr.colorError)) })
                    }
                }
            }
        }
        v.noActions.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        v.addPause.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        v.orderHint.visibility = if (order.size > 1) View.VISIBLE else View.GONE
        v.addAction.visibility = if (Act.entries.any { allowed(it) && it !in shown }) View.VISIBLE else View.GONE
    }

    private fun vpnAppButton() = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        text = vpnApp?.let { getString(R.string.vpn_app_x, AppPickerActivity.labels(listOf(it)).first()) }
            ?: getString(R.string.vpn_app_choose)
        setOnClickListener { pickVpnApp { vpnApp = it; paint() } }
    }

    /**
     * One ending's rows: the rule's end, or the unlock block. [custom] — set by hand, [nothing]
     * — the unlock block's "ничего не менять", with no rows at all.
     */
    private fun renderEnd(st: EndState, container: LinearLayout, custom: Boolean, nothing: Boolean, addPause: View, orderHint: View) {
        container.removeAllViews()
        val keep = getString(R.string.keep_as_is)
        sync(st.steps, st.shown)
        // Apps work under both endings; the states to set only when set by hand. The hidden
        // ones keep their place at the back, for when "set by hand" is chosen again.
        val visible = { s: String -> Act.entries.firstOrNull { it.name == s }.let { !nothing && (it == null || custom || it.anyEnd) } }
        val order = st.steps.filter(visible).toMutableList()
        val hidden = st.steps.filterNot(visible)
        val commit = { st.steps.clear(); st.steps += order + hidden }
        addPause.visibility = if (order.any { Steps.pauseOf(it) == null }) View.VISIBLE else View.GONE
        orderHint.visibility = if (order.size > 1) View.VISIBLE else View.GONE
        for ((i, step) in order.withIndex()) {
            val box = block(container, order, commit)
            val a = Act.entries.firstOrNull { it.name == step }
            if (a == null) {
                pauseRow(box, order, i, commit)
                continue
            }
            val remove = { st.shown -= a; st.clear(a); paint() }
            when (a) {
                Act.NET -> {
                    val avail = (Profile.available() + listOfNotNull(st.net)).distinct().sortedBy { it.ordinal }
                    val modes = listOf<NetMode?>(null) + avail
                    row(box, a, getString(R.string.act_network_sim, netTargetLabel()),
                        choice(listOf(keep) + avail.map { it.label }, modes.indexOf(st.net)) { st.net = modes[it] }, remove)
                }
                Act.DATA, Act.WIFI, Act.AIRPLANE, Act.POWERSAVE, Act.BT, Act.LOC -> {
                    val cur = when (a) {
                        Act.DATA -> st.data; Act.WIFI -> st.wifi; Act.AIRPLANE -> st.airplane
                        Act.POWERSAVE -> st.powerSave; Act.BT -> st.bt; else -> st.loc
                    }
                    val values = listOf(null, Toggle.ON, Toggle.OFF)
                    val c = choice(listOf(keep) + onOff, values.indexOf(cur)) { i ->
                        val t = values[i]
                        when (a) {
                            Act.DATA -> st.data = t; Act.WIFI -> st.wifi = t; Act.AIRPLANE -> st.airplane = t
                            Act.POWERSAVE -> st.powerSave = t; Act.BT -> st.bt = t; else -> st.loc = t
                        }
                    }
                    row(box, a, getString(if (a == Act.LOC) R.string.location_short else a.title), c, remove)
                }
                Act.HOTSPOT -> {
                    val opts = listOf((null to null) to keep) + hotspotOptions(st.hotspot)
                    row(box, a, getString(a.title),
                        choice(opts.map { it.second }, opts.indexOfFirst { it.first == st.hotspot }.coerceAtLeast(0)) { st.hotspot = opts[it].first }, remove)
                }
                Act.VPN -> {
                    val values = listOf(null, Toggle.ON, Toggle.OFF)
                    row(box, a, getString(a.title), choice(listOf(keep) + onOff, values.indexOf(st.vpn)) { st.vpn = values[it] }, remove)
                    if (st.vpn == Toggle.ON && vpn != Toggle.ON) sub(box, vpnAppButton())
                }
                Act.DATASIM -> {
                    val values = listOf<Int?>(null) + simSlots
                    row(box, a, getString(a.title),
                        choice(listOf(keep) + simSlots.map { Sims.slotLabel(it) }, values.indexOf(st.dataSim).coerceAtLeast(0)) { st.dataSim = values[it] }, remove)
                }
                Act.TILES -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(if (st.tiles.isEmpty()) R.string.choose else R.string.change)
                        setOnClickListener { pickTiles(st.tiles) { st.tiles = it; paint() } }
                    }
                    row(box, a, getString(a.title), b, remove)
                    tileSummary(st.tiles)?.let { sub(box, it) }
                }
                Act.APPS -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(R.string.apps_choose_n, st.stopApps.size)
                        setOnClickListener { pickWithMethods(if (st === unlock) Pick.UNLOCK_STOP else Pick.END_STOP, st.stopApps, st.appMethods) }
                    }
                    row(box, a, getString(a.title), b, remove)
                    if (st.stopApps.isNotEmpty()) {
                        val names = st.stopApps.zip(AppPickerActivity.labels(st.stopApps)).toMap()
                        sub(box, hint(st.stopApps.groupBy { st.appMethods[it] ?: FreezeMethod.STOP_RESTRICT }.entries
                            .joinToString("\n") { (m, ps) -> m.label + ": " + ps.joinToString(", ") { names.getValue(it) } }))
                        if (st.stopApps.any { (st.appMethods[it] ?: FreezeMethod.STOP_RESTRICT) != FreezeMethod.STOP }) {
                            sub(box, hint(getString(R.string.end_apps_until_next)))
                        }
                    }
                }
                Act.UNLOAD -> {
                    row(box, a, getString(R.string.act_unload_row), unloadChoice(st.unloadAll) { st.unloadAll = it }, remove)
                    sub(box, hint(getString(if (st.unloadAll) R.string.unload_all_hint else R.string.unload_hint)))
                    sub(box, unloadKeepButton())
                }
                Act.LAUNCH -> {
                    val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        text = getString(R.string.apps_choose_n, st.launchApps.size)
                        setOnClickListener { pickPlain(if (st === unlock) Pick.UNLOCK_LAUNCH else Pick.END_LAUNCH, st.launchApps) }
                    }
                    row(box, a, getString(a.title), b, remove)
                    if (st.launchApps.isNotEmpty()) sub(box, hint(AppPickerActivity.labels(st.launchApps).joinToString(", ")))
                }
                Act.SYS -> {
                    row(box, a, getString(a.title), changeButton(st.sys.isEmpty()) { pickSys(st.sys) { st.sys = it; paint() } }, remove)
                    if (st.sys.isNotEmpty()) sub(box, hint(SysKey.summary(st.sys)))
                }
                Act.NOTE, Act.HTTP, Act.SHELL -> {
                    row(box, a, getString(a.title), changeButton(st.texts[a] == null) {
                        askText(a, st.texts[a]) { t -> if (t == null) { st.texts -= a; st.shown -= a } else st.texts[a] = t; paint() }
                    }, remove)
                    st.texts[a]?.let { sub(box, hint(it)) }
                }
                // Never offered at the end (see allowedEnd).
                Act.RECONNECT -> Unit
            }
        }
    }



    /** Keeps [list] to the actions in [acts], in its own order; ones not in it yet go last. */
    private fun sync(list: MutableList<String>, acts: Collection<Act>) {
        list.removeAll { s -> Steps.pauseOf(s) == null && acts.none { it.name == s } }
        acts.filter { it.name !in list }.forEach { list += it.name }
    }

    /**
     * One step's rows in [container], with a handle to drag it by when there is an order to
     * change. Returns the box the rows go in.
     */
    private fun block(container: LinearLayout, order: MutableList<String>, commit: () -> Unit): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (order.size > 1) outer.addView(handle(container, outer, order, commit))
        outer.addView(content)
        container.addView(outer)
        return content
    }

    /**
     * Drag to reorder. The other steps only slide aside while dragging; the order changes when
     * the finger lifts — taking a view out of its parent mid-gesture would cancel the gesture.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun handle(container: LinearLayout, block: View, order: MutableList<String>, commit: () -> Unit) = ImageView(this).apply {
        setImageResource(R.drawable.ic_drag)
        alpha = 0.55f
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = getString(R.string.drag_step)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(48)).apply { marginEnd = dp(4) }
        // Near the screen's edge: without this the drag is taken for the system's "back".
        if (Compat.SDK >= 29) addOnLayoutChangeListener { h, l, t, r, b, _, _, _, _ ->
            h.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, r - l, b - t))
        }
        var downY = 0f
        var target = -1
        setOnTouchListener { view, e ->
            val from = container.indexOfChild(block)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    downY = e.rawY
                    target = from
                    block.elevation = dp(6).toFloat()
                    block.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    block.translationY = e.rawY - downY
                    val mid = block.top + block.translationY + block.height / 2f
                    var to = from
                    for (k in 0 until container.childCount) {
                        val c = container.getChildAt(k)
                        val cMid = c.top + c.height / 2f
                        if (k > from && mid > cMid) to = maxOf(to, k)
                        if (k < from && mid < cMid) to = minOf(to, k)
                    }
                    if (to != target) {
                        target = to
                        for (k in 0 until container.childCount) {
                            if (k == from) continue
                            val shift = when (k) {
                                in (from + 1)..to -> -block.height
                                in to until from -> block.height
                                else -> 0
                            }
                            container.getChildAt(k).animate().translationY(shift.toFloat()).setDuration(120).start()
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Redrawn on the way out, the old handle is sent a CANCEL of its own: ignored.
                    if (target < 0 || from < 0) return@setOnTouchListener true
                    for (k in 0 until container.childCount) container.getChildAt(k).apply { animate().cancel(); translationY = 0f }
                    block.elevation = 0f
                    block.background = null
                    val to = target
                    target = -1
                    if (e.actionMasked == android.view.MotionEvent.ACTION_UP && to != from) {
                        order.add(to, order.removeAt(from))
                        commit()
                        paint()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** "⌛ Пауза ………… 00:01:00 ×" */
    private fun pauseRow(box: LinearLayout, order: MutableList<String>, i: Int, commit: () -> Unit) {
        val seconds = Steps.pauseOf(order[i]) ?: 0
        val b = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            text = Steps.clock(seconds)
            setOnClickListener { askPause(seconds) { order[i] = Steps.pause(it); commit(); paint() } }
        }
        row(box, R.drawable.ic_hourglass, getString(R.string.pause_step), b) { order.removeAt(i); commit(); paint() }
    }

    /** Hours, minutes and seconds, up to 23:59:59. */
    private fun askPause(initial: Int, done: (Int) -> Unit) {
        fun picker(max: Int, value: Int) = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = max
            this.value = value
            setFormatter { "%02d".format(it) }
        }
        val h = picker(23, initial / 3600)
        val m = picker(59, initial / 60 % 60)
        val s = picker(59, initial % 60)
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        for ((p, unit) in listOf(h to R.string.pause_h, m to R.string.pause_m, s to R.string.pause_s)) {
            box.addView(p)
            box.addView(TextView(this, null, 0, R.style.NMS_RowLabel).apply {
                setText(unit)
                setPadding(dp(6), 0, dp(14), 0)
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pause_title)
            .setView(box)
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                val total = (h.value * 3600 + m.value * 60 + s.value).coerceAtMost(Steps.MAX_PAUSE_S)
                if (total == 0) android.widget.Toast.makeText(this, R.string.pause_zero, android.widget.Toast.LENGTH_SHORT).show()
                else done(total)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeAction(a: Act) {
        shown -= a
        when (a) {
            Act.NET -> netMode = null; Act.DATA -> data = null; Act.WIFI -> wifi = null
            Act.HOTSPOT -> hotspot = null to null; Act.VPN -> vpn = null; Act.DATASIM -> dataSim = null
            Act.AIRPLANE -> airplane = null; Act.POWERSAVE -> powerSave = null; Act.BT -> btOff = false
            Act.LOC -> locOff = false; Act.TILES -> tiles = emptyMap(); Act.APPS -> packages = emptyList()
            Act.LAUNCH -> launchApps = emptyList()
            Act.SYS -> sys = emptyMap()
            Act.NOTE, Act.HTTP, Act.SHELL -> texts -= a
            Act.UNLOAD, Act.RECONNECT -> Unit
        }
        paint()
    }

    /** Adds an action to "Что сделать" ([target] null) or to an ending. */
    private fun addAction(target: EndState?) {
        val custom = if (target === unlock) unlockMode == EndMode.CUSTOM else v.endCustom.isChecked
        val options = Act.entries.filter {
            if (target != null) allowedEnd(it) && it !in target.shown && (custom || it.anyEnd)
            else allowed(it) && it !in shown
        }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (target != null) R.string.add_end_action else R.string.add_action)
            .setItems(options.map { getString(it.title) }.toTypedArray()) { _, i ->
                val a = options[i]
                if (target != null) {
                    target.shown += a
                    val forUnlock = target === unlock
                    when (a) {
                        Act.APPS -> pickWithMethods(if (forUnlock) Pick.UNLOCK_STOP else Pick.END_STOP, target.stopApps, target.appMethods)
                        Act.LAUNCH -> pickPlain(if (forUnlock) Pick.UNLOCK_LAUNCH else Pick.END_LAUNCH, target.launchApps)
                        Act.SYS -> pickSys(target.sys) { target.sys = it; paint() }
                        Act.NOTE, Act.HTTP, Act.SHELL -> askText(a, null) { t ->
                            if (t == null) target.shown -= a else target.texts[a] = t; paint()
                        }
                        else -> Unit
                    }
                    paint()
                    return@setItems
                }
                shown += a
                // A new action at the start is also offered in a custom end, set to "leave".
                if (!a.anyEnd && !a.startOnly) {
                    atEnd.shown += a
                    unlock.shown += a
                }
                when (a) {
                    Act.TILES -> pickTiles(tiles) { tiles = it; if (tiles.isEmpty()) shown -= Act.TILES; paint() }
                    Act.APPS -> pickApps()
                    Act.LAUNCH -> pickPlain(Pick.LAUNCH, launchApps)
                    Act.SYS -> pickSys(sys) { sys = it; if (sys.isEmpty()) shown -= Act.SYS; paint() }
                    Act.NOTE, Act.HTTP, Act.SHELL -> askText(a, null) { t -> if (t == null) shown -= a else texts[a] = t; paint() }
                    else -> Unit
                }
                paint()
                // Straight into the value, so adding an action is one step.
                v.actions.post { rowChoices[a]?.performClick() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun changeButton(empty: Boolean, onClick: () -> Unit) =
        MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            text = getString(if (empty) R.string.choose else R.string.change)
            setOnClickListener { onClick() }
        }

    /** Each setting, then its value; "не менять" takes it out. */
    private fun pickSys(current: Map<String, String>, done: (Map<String, String>) -> Unit) {
        val keys = SysKey.entries
        val labels = keys.map { k -> k.title + (current[k.name]?.let { " — " + k.optionLabel(it) } ?: "") }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.act_sys)
            .setItems(labels.toTypedArray()) { _, i ->
                val k = keys[i]
                MaterialAlertDialogBuilder(this)
                    .setTitle(k.title)
                    .setItems((listOf(getString(R.string.sys_leave)) + k.options.map { k.optionLabel(it) }).toTypedArray()) { _, j ->
                        done(if (j == 0) current - k.name else current + (k.name to k.options[j - 1]))
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The text of a one-off; blank or cancelled hands back null for a new one. */
    private fun askText(a: Act, current: String?, done: (String?) -> Unit) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(current.orEmpty())
            if (a != Act.NOTE) typeface = android.graphics.Typeface.MONOSPACE
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(when (a) { Act.NOTE -> R.string.text_note; Act.HTTP -> R.string.text_http; else -> R.string.text_shell })
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> done(input.text?.toString()?.trim()?.ifEmpty { null }) }
            .setNegativeButton(R.string.cancel) { _, _ -> if (current == null) done(null) }
            .show()
    }

    private fun tileSummary(map: Map<String, Toggle>): View? {
        if (map.isEmpty()) return null
        return hint(map.entries.joinToString("\n") { (c, t) ->
            Tiles.label(c) + " → " + getString(if (t == Toggle.ON) R.string.turn_on else R.string.turn_off).lowercase()
        })
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return a.getColor(0, 0xFFB3261E.toInt()).also { a.recycle() }
    }

    // --- "И ещё если" ----------------------------------------------------------------------

    private fun extraIcon(k: ExtraKind) = when (k) {
        ExtraKind.TIME -> R.drawable.ic_t_time
        ExtraKind.WIFI -> R.drawable.ic_t_wifi
        ExtraKind.CHARGING -> R.drawable.ic_t_charging
        ExtraKind.BATTERY -> R.drawable.ic_t_battery
        ExtraKind.HEADPHONES -> R.drawable.ic_t_headphones
        ExtraKind.ROAMING -> R.drawable.ic_t_roaming
        ExtraKind.DND -> R.drawable.ic_t_dnd
        ExtraKind.LOCKED -> R.drawable.ic_t_lock
        ExtraKind.DARK -> R.drawable.ic_t_sun
    }

    private fun renderExtras() {
        v.extrasList.removeAllViews()
        extras.toList().forEachIndexed { i, e ->
            val line = row(v.extrasList, extraIcon(e.kind), e.label.replaceFirstChar { it.uppercase() }, null) {
                extras.removeAt(i); paint()
            }
            // The ones with a value of their own open it again on a tap.
            if (e.kind == ExtraKind.TIME || e.kind == ExtraKind.WIFI || e.kind == ExtraKind.BATTERY) {
                line.setOnClickListener { editExtra(e) { extras[i] = it; paint() } }
            }
        }
    }

    private fun addExtra() {
        val simple = listOf(
            Extra(ExtraKind.CHARGING), Extra(ExtraKind.CHARGING, negate = true),
            Extra(ExtraKind.HEADPHONES), Extra(ExtraKind.HEADPHONES, negate = true),
            Extra(ExtraKind.ROAMING), Extra(ExtraKind.ROAMING, negate = true),
            Extra(ExtraKind.DND), Extra(ExtraKind.DND, negate = true),
            Extra(ExtraKind.LOCKED), Extra(ExtraKind.LOCKED, negate = true),
            Extra(ExtraKind.DARK), Extra(ExtraKind.DARK, negate = true),
        )
        val options = listOf(
            getString(R.string.extra_time) to Extra(ExtraKind.TIME, from = 23 * 60, to = 7 * 60),
            getString(R.string.extra_wifi_in) to Extra(ExtraKind.WIFI),
            getString(R.string.extra_wifi_out) to Extra(ExtraKind.WIFI, negate = true),
            getString(R.string.extra_battery) to Extra(ExtraKind.BATTERY, level = 20),
        ) + simple.map { it.label.replaceFirstChar { c -> c.uppercase() } to it }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.extras_add)
            .setItems(options.map { it.first }.toTypedArray()) { _, i ->
                editExtra(options[i].second) {
                    extras += it
                    if (it.kind == ExtraKind.DARK && Prefs.sunPlace == null) askSunPlace { paint() }
                    paint()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks for what [e] needs of its own — times, a network, a level — and hands it back. */
    private fun editExtra(e: Extra, done: (Extra) -> Unit) {
        when (e.kind) {
            ExtraKind.TIME -> {
                TimePickerDialog(this, { _, fh, fm ->
                    TimePickerDialog(this, { _, th, tm -> done(e.copy(from = fh * 60 + fm, to = th * 60 + tm)) },
                        e.to / 60, e.to % 60, true).apply { setTitle(R.string.extra_time_to) }.show()
                }, e.from / 60, e.from % 60, true).apply { setTitle(R.string.extra_time_from) }.show()
            }
            ExtraKind.WIFI -> {
                val input = com.google.android.material.textfield.TextInputEditText(this).apply {
                    setSingleLine()
                    setText(e.ssid.orEmpty())
                }
                val box = android.widget.FrameLayout(this).apply {
                    setPadding(dp(24), dp(8), dp(24), 0)
                    addView(input)
                }
                // The network the phone is on is the likely one.
                if (e.ssid == null) lifecycleScope.launch {
                    WifiSsid.current()?.let { if (input.text.isNullOrEmpty()) input.setText(it) }
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.extra_wifi_name)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        input.text?.toString()?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }?.let { done(e.copy(ssid = it)) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            ExtraKind.BATTERY -> {
                val levels = listOf(10, 15, 20, 30, 50)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.extra_battery)
                    .setItems(levels.map { "≤ $it%" }.toTypedArray()) { _, i -> done(e.copy(level = levels[i])) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            else -> done(e)
        }
    }

    /** "55.75, 37.62": two numbers, latitude first; kept for every rule. */
    private fun askSunPlace(done: () -> Unit) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setSingleLine()
            Prefs.sunPlace?.let { (la, lo) -> setText("%.4f, %.4f".format(java.util.Locale.US, la, lo)) }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(hint(getString(R.string.sun_place_hint)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trigger_sun)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = Regex("-?\\d+(?:\\.\\d+)?").findAll(input.text.toString()).map { it.value.toDouble() }.toList()
                if (n.size == 2 && n[0] in -90.0..90.0 && n[1] in -180.0..180.0) {
                    Prefs.sunPlace = n[0] to n[1]
                    done()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askKnownSsids() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(knownSsids.joinToString("\n"))
            minLines = 3
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        // The network the phone is on now is most likely one of the user's own.
        if (knownSsids.isEmpty()) lifecycleScope.launch {
            WifiSsid.current()?.let { if (input.text.isNullOrEmpty()) input.setText(it) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.known_ssids_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                knownSsids = input.text.toString().lines().map { it.trim().trim('"') }.filter { it.isNotEmpty() }.distinct()
                paint()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askTime(initial: Pair<Int, Int>, done: (Pair<Int, Int>) -> Unit) {
        TimePickerDialog(this, { _, h, m -> done(h to m); paint() }, initial.first, initial.second, true).show()
    }

    private fun chooseTrigger() {
        val items = triggerInfos.map { info ->
            val text = android.text.SpannableStringBuilder(getString(info.title))
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, text.length, 0)
            val from = text.length
            text.append("\n").append(getString(info.short))
            text.setSpan(android.text.style.RelativeSizeSpan(0.8f), from, text.length, 0)
            text
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sec_when)
            .setSingleChoiceItems(items, triggerInfos.indexOfFirst { it.trigger == trigger }) { d, i ->
                trigger = triggerInfos[i].trigger
                if ((trigger == Trigger.APP_OPEN || trigger == Trigger.NOTIFY_APP) && triggerApps.isEmpty()) v.triggerApps.post { v.triggerApps.performClick() }
                if (trigger == Trigger.SUN && Prefs.sunPlace == null) v.sunPlace.post { v.sunPlace.performClick() }
                if (trigger == Trigger.WIFI_OTHER && knownSsids.isEmpty()) v.knownSsids.post { v.knownSsids.performClick() }
                // Putting back what fixed the internet once it is back would break it again.
                if (trigger == Trigger.NO_INTERNET && existing == null) v.releaseOnLeave.isChecked = false
                d.dismiss()
                paint()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The picker without stop methods: apps to open, to stop at the end, to watch for. */
    private fun pickPlain(target: Pick, current: List<String>) {
        picking = target
        picker.launch(Intent(this, AppPickerActivity::class.java)
            .putExtra(AppPickerActivity.EXTRA_PLAIN, true)
            .putExtra(AppPickerActivity.EXTRA_PACKAGES, current.toTypedArray()))
    }

    /** The picker with a stop method per app, for the start or the end of the window. */
    private fun pickWithMethods(target: Pick, current: List<String>, methods: Map<String, FreezeMethod>) {
        picking = target
        picker.launch(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_PACKAGES, current.toTypedArray())
                .putExtra(AppPickerActivity.EXTRA_METHODS,
                    current.map { (methods[it] ?: FreezeMethod.STOP_RESTRICT).name }.toTypedArray())
        )
    }

    private fun pickApps() {
        picking = Pick.STOP
        picker.launch(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_PACKAGES, packages.toTypedArray())
                .putExtra(AppPickerActivity.EXTRA_METHODS,
                    packages.map { (appMethods[it] ?: FreezeMethod.STOP_RESTRICT).name }.toTypedArray())
        )
    }

    /** Bluetooth devices need BLUETOOTH_CONNECT; once refused for good, only settings can grant it. */
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) return@registerForActivityResult
        if (!shouldShowRequestPermissionRationale(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            Snackbar.make(v.root, R.string.bt_permission_settings, Snackbar.LENGTH_LONG)
                .setAction(R.string.open_settings) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.show()
        }
    }

    private fun pickBtDevice(done: (BtDevice) -> Unit) {
        if (!Compat.granted(this, android.Manifest.permission.BLUETOOTH_CONNECT)) {
            btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        lifecycleScope.launch {
            val devices = BtDevices.saved(this@RuleEditActivity)
            if (devices.isEmpty()) {
                Toast.makeText(this@RuleEditActivity, R.string.no_bonded, Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@RuleEditActivity)
                .setTitle(R.string.choose_device)
                .setItems(devices.map { it.name }.toTypedArray()) { _, i -> done(devices[i]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // --- building and saving ---------------------------------------------------------------

    private fun build(id: Long, enabled: Boolean): AppRule {
        val t = effectiveTrigger
        var days = 0
        listOf(v.d1, v.d2, v.d3, v.d4, v.d5, v.d6, v.d7).forEachIndexed { i, c -> if (c.isChecked) days = days or (1 shl i) }
        val endTime = when {
            t == Trigger.TIME && v.hasEnd.isChecked -> end
            t == Trigger.LOCKED && v.lockWindow.isChecked -> end
            t == Trigger.WIFI_LEFT && v.leftEndTimeOn.isChecked -> leftEnd
            else -> null
        }
        val custom = v.endCustom.isChecked
        fun <T> e(a: Act, value: T?): T? = value.takeIf { custom && a in atEnd.shown && allowedEnd(a) }
        val built = AppRule(
            id = id,
            enabled = enabled,
            hour = start.first,
            minute = start.second,
            endHour = endTime?.first,
            endMinute = endTime?.second,
            // Days belong to a time: a time rule's, or a lock window's.
            days = if (t == Trigger.TIME || (t == Trigger.LOCKED && v.lockWindow.isChecked)) days else 0,
            packages = packages.takeIf { Act.APPS in shown }.orEmpty(),
            method = FreezeMethod.STOP_RESTRICT,
            appMethods = appMethods.filterKeys { it in packages },
            ssid = v.ssid.text.toString().trim().trim('"').ifEmpty { null }.takeIf { t == Trigger.WIFI || t == Trigger.WIFI_LEFT },
            releaseOnLeave = v.releaseOnLeave.isChecked,
            bluetoothOff = btOff && Act.BT in shown,
            locationOff = locOff && Act.LOC in shown,
            leaveOnWifiOff = v.wifiOffLeaves.isChecked,
            trigger = t,
            btAddress = btDevice?.address?.takeIf { t == Trigger.BLUETOOTH },
            btName = btDevice?.name?.takeIf { t == Trigger.BLUETOOTH },
            endBtAddress = endBt?.address?.takeIf { t == Trigger.WIFI_LEFT && v.leftEndBtOn.isChecked },
            endBtName = endBt?.name?.takeIf { t == Trigger.WIFI_LEFT && v.leftEndBtOn.isChecked },
            wifi = wifi.takeIf { Act.WIFI in shown },
            hotspot = hotspot.first.takeIf { Act.HOTSPOT in shown },
            hotspotVia = hotspot.second.takeIf { Act.HOTSPOT in shown && hotspot.first == Toggle.ON },
            mobileData = data.takeIf { Act.DATA in shown },
            netMode = netMode.takeIf { Act.NET in shown },
            // A "network mode" rule may only change the other SIM: touching the SIM it
            // watches would end its own window at once.
            netSlot = if (t == Trigger.NET_MODE) otherSlot() else netSlot,
            name = v.ruleName.text.toString().trim().ifEmpty { null },
            triggerSlot = simSlots.getOrElse(v.tSlot.selectedItemPosition) { 0 },
            triggerMode = NetMode.entries[v.tMode.selectedItemPosition].takeIf { t == Trigger.NET_MODE },
            keepStopped = keepStopped.filter { it in packages }.toSet(),
            tiles = tiles.takeIf { Act.TILES in shown }.orEmpty(),
            endTiles = e(Act.TILES, atEnd.tiles).orEmpty(),
            vpnApp = vpnApp,
            vpn = vpn.takeIf { Act.VPN in shown },
            endVpn = e(Act.VPN, atEnd.vpn),
            endCustom = custom,
            endNet = e(Act.NET, atEnd.net),
            endData = e(Act.DATA, atEnd.data),
            endWifi = e(Act.WIFI, atEnd.wifi),
            endHotspot = e(Act.HOTSPOT, atEnd.hotspot.first),
            endHotspotVia = e(Act.HOTSPOT, atEnd.hotspot.second),
            endBt = e(Act.BT, atEnd.bt),
            endLoc = e(Act.LOC, atEnd.loc),
            level = when (t) {
                Trigger.BATTERY -> batteryLevel
                Trigger.SCREEN_OFF -> screenMinutes
                Trigger.LOCKED -> lockMinutes
                Trigger.WEAK_SIGNAL -> signalLevel
                Trigger.MANUAL -> manualMinutes.takeIf { v.releaseOnLeave.isChecked } ?: 0
                Trigger.NO_INTERNET -> netLostSeconds
                Trigger.NOTIFY_APP -> notifyLevel
                Trigger.ALARM -> alarmMinutes
                Trigger.SUN -> sunLevel
                Trigger.TRAFFIC -> TRAFFIC_OPTS[trafficIdx].first
                else -> 0
            },
            trafficMonth = t == Trigger.TRAFFIC && TRAFFIC_OPTS[trafficIdx].second,
            knownSsids = knownSsids.takeIf { t == Trigger.WIFI_OTHER }.orEmpty(),
            triggerApps = triggerApps.takeIf { t == Trigger.APP_OPEN || t == Trigger.NOTIFY_APP }.orEmpty(),
            appLeaveDelay = appLeaveDelay.takeIf { t == Trigger.APP_OPEN } ?: 0,
            appPassThrough = appPass.takeIf { t == Trigger.APP_OPEN }.orEmpty(),
            appScreenOffStays = t == Trigger.APP_OPEN && v.appScreenStays.isChecked,
            // A Wi-Fi rule keeps the towers it learned (see WifiWake).
            cells = cells.takeIf { t == Trigger.PLACE || t == Trigger.WIFI }.orEmpty(),
            wakeWifi = t == Trigger.WIFI && v.wakeRow.toggle.isChecked,
            launchApps = launchApps.takeIf { Act.LAUNCH in shown }.orEmpty(),
            endStopApps = atEnd.stopApps.takeIf { restores && Act.APPS in atEnd.shown }.orEmpty(),
            endAppMethods = atEnd.appMethods.filterKeys { it in atEnd.stopApps },
            endLaunchApps = atEnd.launchApps.takeIf { restores && Act.LAUNCH in atEnd.shown }.orEmpty(),
            lockWindow = t == Trigger.LOCKED && v.lockWindow.isChecked,
            unload = Act.UNLOAD in shown,
            reconnect = Act.RECONNECT in shown,
            sys = sys.takeIf { Act.SYS in shown }.orEmpty(),
            endSys = e(Act.SYS, atEnd.sys).orEmpty(),
            note = texts[Act.NOTE].takeIf { Act.NOTE in shown },
            http = texts[Act.HTTP].takeIf { Act.HTTP in shown },
            shell = texts[Act.SHELL].takeIf { Act.SHELL in shown },
            endNote = atEnd.texts[Act.NOTE].takeIf { restores && Act.NOTE in atEnd.shown },
            endHttp = atEnd.texts[Act.HTTP].takeIf { restores && Act.HTTP in atEnd.shown },
            endShell = atEnd.texts[Act.SHELL].takeIf { restores && Act.SHELL in atEnd.shown },
            notice = notice.takeIf { t != Trigger.MANUAL } ?: RunNotice.SILENT,
            extras = extras.toList().takeIf { t != Trigger.MANUAL && t != Trigger.WIFI_LEFT }.orEmpty(),
            endUnload = restores && Act.UNLOAD in atEnd.shown,
            unloadAll = unloadAll && Act.UNLOAD in shown,
            endUnloadAll = atEnd.unloadAll && restores && Act.UNLOAD in atEnd.shown,
            placeName = v.placeName.text.toString().trim().ifEmpty { null }.takeIf { t == Trigger.PLACE },
            airplane = airplane.takeIf { Act.AIRPLANE in shown },
            endAirplane = e(Act.AIRPLANE, atEnd.airplane),
            powerSave = powerSave.takeIf { Act.POWERSAVE in shown },
            endPowerSave = e(Act.POWERSAVE, atEnd.powerSave),
            dataSim = dataSim.takeIf { Act.DATASIM in shown },
            endDataSim = e(Act.DATASIM, atEnd.dataSim),
            waitLocked = v.waitRow.toggle.isChecked && t != Trigger.LOCKED && t != Trigger.MANUAL,
            waitAppsOnly = v.waitRow.toggle.isChecked && t != Trigger.LOCKED && t != Trigger.MANUAL && v.waitApps.isChecked && v.waitApps.visibility == View.VISIBLE,
            steps = Steps.toSave(steps.filter { s -> Steps.pauseOf(s) != null || shown.any { it.name == s } }, Steps.START_ORDER),
            endSteps = if (!restores) emptyList() else Steps.toSave(atEnd.steps.filter { s ->
                Steps.pauseOf(s) != null || atEnd.shown.any { it.name == s && allowedEnd(it) && (custom || it.anyEnd) }
            }, Steps.END_ORDER),
        )
        // The same as the end, it is no block of its own: unlocking ends the night.
        val split = t == Trigger.LOCKED && v.lockWindow.isChecked && restores
        return built.copy(unlockEnd = if (split) unlockSpec().takeIf { it != built.endSpec } else null)
    }

    /** The unlock block as set: only what is shown counts, as in the end. */
    private fun unlockSpec(): EndSpec {
        if (unlockMode == EndMode.NOTHING) return EndSpec(mode = EndMode.NOTHING)
        val u = unlock
        val custom = unlockMode == EndMode.CUSTOM
        fun <T> e(a: Act, value: T?): T? = value.takeIf { custom && a in u.shown && allowedEnd(a) }
        val stop = u.stopApps.takeIf { Act.APPS in u.shown }.orEmpty()
        return EndSpec(
            mode = unlockMode,
            keepApps = v.unlockKeepApps.isChecked && packages.isNotEmpty(),
            net = e(Act.NET, u.net), data = e(Act.DATA, u.data), wifi = e(Act.WIFI, u.wifi),
            hotspot = e(Act.HOTSPOT, u.hotspot.first), hotspotVia = e(Act.HOTSPOT, u.hotspot.second),
            bt = e(Act.BT, u.bt), loc = e(Act.LOC, u.loc), vpn = e(Act.VPN, u.vpn),
            airplane = e(Act.AIRPLANE, u.airplane), powerSave = e(Act.POWERSAVE, u.powerSave),
            dataSim = e(Act.DATASIM, u.dataSim), tiles = e(Act.TILES, u.tiles).orEmpty(),
            stopApps = stop, appMethods = u.appMethods.filterKeys { it in stop },
            launchApps = u.launchApps.takeIf { Act.LAUNCH in u.shown }.orEmpty(),
            unload = Act.UNLOAD in u.shown, unloadAll = u.unloadAll && Act.UNLOAD in u.shown,
            sys = e(Act.SYS, u.sys).orEmpty(),
            note = u.texts[Act.NOTE].takeIf { Act.NOTE in u.shown }, http = u.texts[Act.HTTP].takeIf { Act.HTTP in u.shown },
            shell = u.texts[Act.SHELL].takeIf { Act.SHELL in u.shown },
            steps = Steps.toSave(u.steps.filter { s ->
                Steps.pauseOf(s) != null || u.shown.any { it.name == s && allowedEnd(it) && (custom || it.anyEnd) }
            }, Steps.END_ORDER),
        )
    }

    /** Points at what is missing instead of a toast that says it somewhere else. */
    private fun problem(rule: AppRule): Pair<Int, View>? = when {
        (rule.trigger == Trigger.WIFI || rule.trigger == Trigger.WIFI_LEFT) && rule.ssid == null -> {
            v.ssidLayout.error = getString(R.string.ssid_required)
            R.string.ssid_required to v.ssidLayout
        }
        rule.trigger == Trigger.BLUETOOTH && rule.btAddress == null -> R.string.device_required to v.btDevice
        rule.trigger == Trigger.WIFI_LEFT && v.leftEndBtOn.isChecked && rule.endBtAddress == null -> R.string.device_required to v.leftEndBt
        (rule.trigger == Trigger.APP_OPEN || rule.trigger == Trigger.NOTIFY_APP) && rule.triggerApps.isEmpty() ->
            R.string.trigger_apps_required to v.triggerApps
        (rule.trigger == Trigger.SUN || rule.extras.any { it.kind == ExtraKind.DARK }) && Prefs.sunPlace == null ->
            R.string.sun_place_required to v.sunPlace
        rule.trigger == Trigger.WIFI_OTHER && rule.knownSsids.isEmpty() -> R.string.known_ssids_none to v.knownSsids
        rule.trigger == Trigger.MANUAL && rule.name == null -> R.string.scene_name_required to v.ruleName
        rule.trigger == Trigger.PLACE && rule.cells.isEmpty() -> R.string.place_required to v.placeAdd
        !rule.hasActions -> R.string.apps_required to v.addAction
        (rule.vpn == Toggle.ON || rule.endVpn == Toggle.ON) && rule.vpnApp == null -> R.string.vpn_app_required to v.actions
        else -> null
    }

    private fun validated(rule: AppRule): Boolean {
        v.ssidLayout.error = null
        val (msg, view) = problem(rule) ?: return true
        v.scroll.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            v.scroll.offsetDescendantRectToMyCoords(view, rect)
            v.scroll.smoothScrollTo(0, (rect.top - dp(24)).coerceAtLeast(0))
        }
        Snackbar.make(v.root, msg, Snackbar.LENGTH_LONG).setAnchorView(v.save).show()
        return false
    }

    private fun save() {
        val rule = build(existing?.id ?: Prefs.newId(), existing?.enabled ?: true)
        if (!validated(rule)) return
        val alarms = AppFreezer.alarmApps(this)
        val alarmPkgs = rule.packages.filter { AppKey.pkg(it) in alarms }
        if (alarmPkgs.isEmpty()) commit(rule)
        else MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.alarm_confirm, AppPickerActivity.labels(alarmPkgs).joinToString(", ")))
            .setPositiveButton(R.string.save_anyway) { _, _ -> commit(rule) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun commit(rule: AppRule) {
        val needsBattery = RuleStore.save(rule)
        if (!needsBattery) { finish(); return }
        // Without the exemption Android refuses the background watcher such a rule needs.
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.battery_needed)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
                finish()
            }
            .setNegativeButton(R.string.later) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun applyNow() {
        val rule = build(existing!!.id, existing!!.enabled)
        if (!validated(rule)) return
        if (!rule.enabled) {
            Snackbar.make(v.root, R.string.apply_disabled, Snackbar.LENGTH_LONG).setAnchorView(v.save).show()
            return
        }
        lifecycleScope.launch {
            AppFreezer.freezeNow(rule)
            Snackbar.make(v.root, R.string.applied, Snackbar.LENGTH_SHORT).setAnchorView(v.save).show()
        }
    }

    private fun releaseNow() {
        lifecycleScope.launch {
            val n = runCatching { AppFreezer.releaseNow(existing!!.id) }.getOrDefault(0)
            Snackbar.make(v.root, getString(R.string.released, n), Snackbar.LENGTH_SHORT).setAnchorView(v.save).show()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, existing?.name ?: existing?.timeLabel.orEmpty()))
            .setPositiveButton(R.string.delete) { _, _ ->
                RuleStore.delete(existing!!.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "rule_id"
        /** A rule as JSON to start from: the templates on the empty rules screen. */
        const val EXTRA_TEMPLATE = "template"
        private const val MENU_APPLY = 1
        private const val MENU_RELEASE = 2
        private const val MENU_DELETE = 3
        private val BATTERY_LEVELS = listOf(0, 10, 15, 20, 30, 50)
        private val SCREEN_MINUTES = listOf(1, 5, 10, 15, 30, 60)
        private val LOCK_MINUTES = listOf(0, 1, 5, 10, 15, 30)
        /** Seconds an app may be away before it counts as closed. */
        private val LEAVE_DELAYS = listOf(0, 5, 15, 30, 60, 300)
        /** Minutes until a scene ends by itself; 0: until it is switched off. */
        private val MANUAL_MINUTES = listOf(0, 15, 30, 60, 120, 240, 480)
        /** How long the internet has to be gone, in seconds. */
        private val NET_LOST_SECONDS = listOf(30, 60, 120, 300, 600)
        private val ALARM_MINUTES = listOf(5, 15, 30, 60, 120)
        /** Traffic thresholds: megabytes, and whether of the month. */
        private val TRAFFIC_OPTS = listOf(100 to false, 500 to false, 1024 to false, 2048 to false,
            5120 to true, 10240 to true, 20480 to true, 51200 to true)
    }
}
