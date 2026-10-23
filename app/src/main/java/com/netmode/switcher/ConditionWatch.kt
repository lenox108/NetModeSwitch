package com.netmode.switcher

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The "while …" triggers that are states of the phone rather than connections: battery,
 * charging, screen off, an app on screen, a place, roaming, a weak signal, a call, headphones,
 * Do Not Disturb, no internet.
 *
 * Everything is event-driven where Android offers an event — runtime receivers, telephony and
 * audio callbacks — and lives in the app process, which the watcher service keeps alive while
 * such a rule exists. Two things have no event and are looked at instead: the app on screen
 * (every 2 s, only while the screen is on) and the cell towers (on every service-state change,
 * at most every 15 s, and every 5 minutes). The internet is the system's own check, nudged by a
 * probe once a minute while the screen is on.
 */
object ConditionWatch {

    private const val TAG = "netmode"

    val TRIGGERS = setOf(
        Trigger.BATTERY, Trigger.CHARGING, Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.APP_OPEN, Trigger.PLACE,
        Trigger.ROAMING, Trigger.WEAK_SIGNAL, Trigger.CALL, Trigger.HEADPHONES, Trigger.DND, Trigger.NO_INTERNET,
        Trigger.NOTIFY_APP, Trigger.USB, Trigger.CAR, Trigger.ALARM, Trigger.SUN, Trigger.TRAFFIC, Trigger.WIFI_OTHER,
        Trigger.VPN,
    )

    /** Kinds that change with the clock alone: an exact alarm wakes the check (see scheduleScreenAlarm). */
    private val TIMED = setOf(Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.ALARM, Trigger.SUN)

    private const val USB_STATE = "android.hardware.usb.action.USB_STATE"

    /**
     * Android Auto tells of the car through the androidx.car.app "CarConnection" provider and
     * this broadcast. Car mode alone is not enough: newer Android Auto often projects without
     * switching the phone into it, and then neither the connect nor the disconnect was seen.
     */
    private const val CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    private val CAR_CONNECTION_URI: android.net.Uri = android.net.Uri.parse("content://androidx.car.app.connection")
    private const val CAR_CONNECTION_STATE = "CarConnectionState"

    /** UsageEvents.Event.ACTIVITY_DESTROYED: a system API, though the events carry it. */
    private const val ACTIVITY_DESTROYED = 24

    private const val VPN_SETTLE_MS = 700L
    private const val TRAFFIC_EVERY_MS = 10 * 60_000L

    /**
     * A network the system still counts as working may have stopped passing traffic: the
     * system only looks again when something tells it to. This probe, twice failed, does.
     */
    private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
    private const val PROBE_EVERY_MS = 60_000L

    /** A weak signal has to last this long before it counts, and a good one to end it. */
    private const val WEAK_AFTER_MS = 30_000L
    private const val GOOD_AFTER_MS = 60_000L

    /**
     * A weak-signal window stays open at least this long. Its rule usually drops the network
     * mode, which changes what the signal means; without a floor it would flip back at once.
     */
    private const val WEAK_MIN_MS = 15 * 60_000L

    // --- state -----------------------------------------------------------------------------

    @Volatile private var batteryPct = 100
    @Volatile private var charging = false
    @Volatile private var screenOn = true
    @Volatile private var screenOffAt = 0L

    /** Locked since the screen went off, until unlocked (USER_PRESENT), not merely woken. */
    @Volatile private var locked = false
    @Volatile private var lockedAt = 0L
    @Volatile private var foreground: String? = null
    /** The app on screen is only on top of another for a moment (see [AppPresence.passes]). */
    @Volatile private var foregroundPasses = false
    /** Apps still on screen behind [foreground]: picture-in-picture, split screen (see [AppPresence.Behind]). */
    @Volatile private var behind = emptySet<String>()
    private val behindTracker = AppPresence.Behind()
    /** Up to when usage events have been read for [behindTracker] (wall clock); 0: not yet. */
    private var behindReadTo = 0L
    /** APP_OPEN rules whose app has left, while their delay runs: since when. */
    private val awaySince = mutableMapOf<Long, Long>()
    @Volatile private var cells: Set<String>? = null
    @Volatile private var roaming = false
    @Volatile private var inCall = false
    @Volatile private var headphones = false
    /** The Wi-Fi network connected, for "в сети «…»" conditions; [wifiKnown] once read. */
    @Volatile private var wifiSsid: String? = null
    @Volatile private var wifiKnown = false
    @Volatile private var usbConnected = false
    /** A VPN is connected, and whose app it is (null: could not be read); [vpnKnown] once read. */
    @Volatile private var vpnOn = false
    @Volatile private var vpnOwner: String? = null
    @Volatile private var vpnKnown = false
    private var vpnRead: Job? = null
    /** Mobile bytes of today and of this month, by NetworkStatsManager; null until read. */
    @Volatile private var trafficDay: Long? = null
    @Volatile private var trafficMonth: Long? = null
    private var trafficPoll: Job? = null
    /** Since when the internet is gone (elapsed realtime); 0 while there is internet. */
    @Volatile private var netLostSince = 0L
    private val signal = mutableMapOf<Int, Int>()
    private val weakSince = mutableMapOf<Int, Long>()
    private val goodSince = mutableMapOf<Int, Long>()

    private var registered = emptySet<Trigger>()
    private var appPoll: Job? = null
    private var netProbe: Job? = null
    private var placePoll: Job? = null
    private var lastPlaceRead = 0L
    /** Undo for each phone listener registered. */
    private val phoneCallbacks = mutableListOf<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Waking Wi-Fi near a network needs the towers read just like a place rule does; an extra
     * condition needs the same watcher as the trigger of its kind (the time has the alarm).
     */
    private fun used(): Set<Trigger> {
        val rules = Prefs.appRules.filter { it.enabled && it.hasActions }
        return rules.filter { it.trigger in TRIGGERS }.map { it.trigger }.toSet() +
            rules.flatMap { r -> r.extras.map { it.kind.trigger } }.filter { it != Trigger.TIME } +
            (if (WifiWake.needed()) setOf(Trigger.PLACE) else emptySet())
    }

    fun needed(): Boolean = used().isNotEmpty()

    // --- evaluation --------------------------------------------------------------------------

    /** Whether [rule]'s condition holds now; null when it cannot be told (keep as is). */
    private fun holds(rule: AppRule, openSince: Long): Boolean? = when (rule.trigger) {
        Trigger.BATTERY -> if (rule.level <= 0) powerSave() else !charging && batteryPct <= rule.level
        Trigger.CHARGING -> charging
        Trigger.SCREEN_OFF -> !screenOn && SystemClock.elapsedRealtime() - screenOffAt >= rule.level * 60_000L
        Trigger.LOCKED -> locked && SystemClock.elapsedRealtime() - lockedAt >= rule.level * 60_000L &&
            rule.inLockWindow(java.time.ZonedDateTime.now())
        Trigger.APP_OPEN -> appHolds(rule, openSince)
        Trigger.PLACE -> placeHolds(rule)
        Trigger.ROAMING -> roaming
        Trigger.CALL -> inCall
        Trigger.HEADPHONES -> headphones
        Trigger.DND -> dnd()
        Trigger.WEAK_SIGNAL -> weakHolds(rule, openSince)
        Trigger.NO_INTERNET -> InternetLoss.holds(netLostSince, SystemClock.elapsedRealtime(), rule.level * 1000L)
        Trigger.NOTIFY_APP -> if (!NotifWatch.connected) null else rule.triggerApps.any { k ->
            NotifWatch.active[AppKey.pkg(k)]?.let { ongoing -> rule.level != 1 || ongoing } == true
        }
        Trigger.USB -> usbConnected
        Trigger.CAR -> dropHolds(rule, openSince, carMode(), Trigger.CAR)
        Trigger.VPN -> if (!vpnKnown) null else AppPresence.vpnHolds(vpnOn, vpnOwner, rule.triggerApps)?.let {
            dropHolds(rule, openSince, it, Trigger.VPN)
        }
        Trigger.ALARM -> nextAlarm()?.let { at -> val now = System.currentTimeMillis(); now >= at - rule.level * 60_000L && now < at } ?: false
        Trigger.SUN -> Prefs.sunPlace?.let { (la, lo) -> Sun.dark(java.time.ZonedDateTime.now(), la, lo) != (rule.level == 1) }
        Trigger.TRAFFIC -> (if (rule.trafficMonth) trafficMonth else trafficDay)?.let { it >= rule.level * 1024L * 1024L }
        Trigger.WIFI_OTHER -> if (!wifiKnown) null else wifiSsid?.let { now ->
            rule.knownSsids.none { it.trim('"').equals(now.trim('"'), ignoreCase = true) }
        } ?: false
        else -> null
    }

    /**
     * Near the place when one of its towers was seen within [PLACE_GRACE_MS]. Walking or
     * driving out, the phone hops between the place's towers and strangers for a while; without
     * the grace every hop back would count as arriving again — and re-run the rule, e.g. switch
     * Wi-Fi back on right after "left home" switched it off.
     */
    private fun placeHolds(rule: AppRule): Boolean? {
        val now = cells ?: return null
        val t = SystemClock.elapsedRealtime()
        if (now.any { it in rule.cells }) {
            placeSeenAt[rule.id] = t
            return true
        }
        val seen = placeSeenAt[rule.id] ?: return false
        return t - seen < PLACE_GRACE_MS
    }

    private fun appHolds(rule: AppRule, openSince: Long): Boolean? {
        val fg = foreground
        val seen = when {
            fg != null && rule.triggerApps.any { AppKey.pkg(it) == fg } -> AppPresence.Seen.APP
            !screenOn -> AppPresence.Seen.SCREEN_OFF
            // Shrunk to a small window over the desktop or another app: still watched.
            rule.triggerApps.any { AppKey.pkg(it) in behind } -> AppPresence.Seen.APP
            fg == null -> AppPresence.Seen.UNKNOWN
            foregroundPasses || rule.appPassThrough.any { AppKey.pkg(it) == fg } -> AppPresence.Seen.PASS
            else -> AppPresence.Seen.OTHER
        }
        val before = awaySince[rule.id]
        val delayMs = rule.appLeaveDelay * 1000L
        val look = AppPresence.look(seen, openSince != 0L, before, SystemClock.elapsedRealtime(), delayMs, rule.appScreenOffStays)
        if (look.awaySince == null) awaySince.remove(rule.id) else awaySince[rule.id] = look.awaySince
        // The delay only passes with time: look again once it is over.
        if (before == null && look.awaySince != null) recheck(Trigger.APP_OPEN, delayMs)
        return look.holds
    }

    /**
     * Looks at [kind] again after [ms]: a delay that passes with time and no event. The screen
     * may be off by then; the wake lock keeps deep sleep from stretching minutes into many.
     */
    private fun recheck(kind: Trigger, ms: Long) {
        App.scope.launch {
            val wake = App.ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmode:recheck")
            wake.acquire(ms + 60_000L)
            try {
                delay(ms + 500L)
                evaluateNow(kind)
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
    }

    /**
     * [present] now, or gone for less than the rule's delay: wireless Android Auto drops for a
     * few seconds now and then, a VPN reconnects when the network changes, and each drop would
     * otherwise run the end — Wi-Fi off, and with it no way back for the projection.
     */
    private fun dropHolds(rule: AppRule, openSince: Long, present: Boolean, kind: Trigger): Boolean {
        if (present) {
            goneSince.remove(rule.id)
            return true
        }
        if (openSince == 0L || rule.appLeaveDelay <= 0) {
            goneSince.remove(rule.id)
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val since = goneSince[rule.id]
        if (since == null) {
            goneSince[rule.id] = now
            recheck(kind, rule.appLeaveDelay * 1000L)
            return true
        }
        if (now - since < rule.appLeaveDelay * 1000L) return true
        goneSince.remove(rule.id)
        return false
    }

    /** CAR and VPN rules whose car or VPN is gone, while their delay runs: since when. */
    private val goneSince = mutableMapOf<Long, Long>()

    private val placeSeenAt = mutableMapOf<Long, Long>()
    private const val PLACE_GRACE_MS = 10 * 60_000L

    private fun weakHolds(rule: AppRule, openSince: Long): Boolean? {
        val level = signal[rule.triggerSlot] ?: return null
        val now = SystemClock.elapsedRealtime()
        return if (openSince == 0L) {
            level <= rule.level && now - (weakSince[rule.triggerSlot] ?: now) >= WEAK_AFTER_MS
        } else {
            val good = level > rule.level && now - (goodSince[rule.triggerSlot] ?: now) >= GOOD_AFTER_MS
            val longEnough = System.currentTimeMillis() - openSince >= WEAK_MIN_MS
            !(good && longEnough)
        }
    }

    /** Re-checks the rules of [kinds] against the current state. */
    fun evaluate(vararg kinds: Trigger) {
        App.scope.launch { evaluateNow(*kinds) }
    }

    /** The same, to its end: for an alarm that has to finish its work before the phone sleeps. */
    suspend fun evaluateNow(vararg kinds: Trigger) {
        val set = kinds.toSet().ifEmpty { TRIGGERS }
        runCatching {
            AppFreezer.onConditions(set) { rule, openSince -> holds(rule, openSince) }
        }.onFailure { Log.w(TAG, "condition check failed", it) }
        runCatching { AppFreezer.onExtras(kinds.toSet().ifEmpty { null }) }
            .onFailure { Log.w(TAG, "extra condition check failed", it) }
    }

    // --- extra conditions ("И ещё если") ------------------------------------------------------

    /** Whether [e] holds now; null when it cannot be told yet. */
    fun extraHolds(e: Extra): Boolean? {
        val v: Boolean? = when (e.kind) {
            ExtraKind.TIME -> e.timeHolds(java.time.LocalTime.now())
            ExtraKind.WIFI -> if (!wifiKnown) null else e.ssid != null && wifiSsid?.trim('"').equals(e.ssid.trim('"'), ignoreCase = true)
            ExtraKind.CHARGING -> charging
            ExtraKind.BATTERY -> batteryPct <= e.level
            ExtraKind.HEADPHONES -> headphones
            ExtraKind.ROAMING -> roaming
            ExtraKind.DND -> dnd()
            ExtraKind.LOCKED -> locked
            ExtraKind.DARK -> Prefs.sunPlace?.let { (la, lo) -> Sun.dark(java.time.ZonedDateTime.now(), la, lo) }
        }
        return v?.let { it != e.negate }
    }

    /** All of [rule]'s extra conditions hold: true, one does not: false, one cannot be told: null. */
    fun extrasHold(rule: AppRule): Boolean? {
        var unknown = false
        for (e in rule.extras) when (extraHolds(e)) {
            false -> return false
            null -> unknown = true
            true -> Unit
        }
        return if (unknown) null else true
    }

    /** The first extra condition of [rule] known not to hold. */
    fun failingExtra(rule: AppRule): Extra? = rule.extras.firstOrNull { extraHolds(it) == false }

    /** Any VPN network, whichever app's: up, down, or another one in its place. */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = readVpn()
        override fun onLost(network: Network) = readVpn()
    }

    /**
     * Whether a VPN is up and whose: the owner from the shell, the system's own list of VPNs
     * there; without the shell, whether this app sees a VPN network at all.
     */
    private fun readVpn() {
        vpnRead?.cancel()
        vpnRead = App.scope.launch {
            // The network comes a moment before the system names the VPN that brought it.
            delay(VPN_SETTLE_MS)
            val owner = runCatching { VpnControl.owner() }.getOrNull()
            val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val on = owner != null || cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            if (vpnKnown && on == vpnOn && owner == vpnOwner) return@launch
            vpnOn = on
            vpnOwner = owner
            vpnKnown = true
            Log.i(TAG, "vpn: ${if (on) owner ?: "?" else "off"}")
            evaluate(Trigger.VPN)
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = readWifi(settle = true)
        override fun onLost(network: Network) = readWifi(settle = false)
    }

    /** The network name, read through the shell; right after association it may lag a moment. */
    private fun readWifi(settle: Boolean) {
        App.scope.launch {
            var now: String? = null
            repeat(if (settle) 3 else 1) { attempt ->
                if (settle) delay(if (attempt == 0) 1_000L else 1_500L)
                now = runCatching { WifiSsid.current() }.getOrNull()
                if (now != null) return@repeat
            }
            val changed = !wifiKnown || now != wifiSsid
            wifiSsid = now
            wifiKnown = true
            if (changed) evaluate(Trigger.WIFI, Trigger.WIFI_OTHER)
        }
    }

    // --- registration ------------------------------------------------------------------------

    fun sync() {
        val want = used()
        if (want == registered) return
        val ctx = App.ctx
        unregister(ctx)
        registered = want
        if (want.isEmpty()) return
        register(ctx, want)
        WifiWatchService.start(ctx, WifiWatchService.MODE_ALWAYS)
        refreshAll(ctx)
        // Started with the screen already off: no SCREEN_OFF broadcast will come to set it.
        if (want.any { it in TIMED }) scheduleScreenAlarm(ctx)
        evaluate()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val before = batteryPct to charging
                    readBattery(intent)
                    if (before != (batteryPct to charging)) evaluate(Trigger.BATTERY, Trigger.CHARGING)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> evaluate(Trigger.BATTERY)
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    screenOffAt = SystemClock.elapsedRealtime()
                    // Turning the screen off locks it (or will in a moment — the same here).
                    if (!locked) { locked = true; lockedAt = screenOffAt }
                    foreground = null
                    foregroundPasses = false
                    behind = emptySet()
                    appPoll?.cancel()
                    netProbe?.cancel()
                    scheduleScreenAlarm(context)
                    evaluate(Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.APP_OPEN)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // No lock screen at all, or one dismissed by itself (Smart Lock): unlocked now.
                    if (!keyguardLocked(context)) locked = false
                    if (Trigger.APP_OPEN in registered) startAppPoll()
                    if (Trigger.NO_INTERNET in registered) startNetProbe()
                    evaluate(Trigger.SCREEN_OFF, Trigger.LOCKED)
                }
                Intent.ACTION_USER_PRESENT -> {
                    locked = false
                    evaluate(Trigger.LOCKED)
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> evaluate(Trigger.DND)
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> readInternet()
                USB_STATE -> {
                    val now = intent.getBooleanExtra("connected", false)
                    if (now != usbConnected) { usbConnected = now; evaluate(Trigger.USB) }
                }
                android.app.UiModeManager.ACTION_ENTER_CAR_MODE, android.app.UiModeManager.ACTION_EXIT_CAR_MODE,
                CAR_CONNECTION_UPDATED -> {
                    evaluate(Trigger.CAR)
                    // Android Auto may announce the change before its provider says so: once more later.
                    recheck(Trigger.CAR, 5_000L)
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                    evaluate(Trigger.ALARM)
                    scheduleScreenAlarm(context)
                }
            }
        }
    }

    /** The default network: whichever it is, a change may be the internet going or coming. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = readInternet()
        override fun onLost(network: Network) = readInternet()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = readInternet()
    }

    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = readHeadphones()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = readHeadphones()
    }

    private fun onAudioMode(mode: Int) {
        val call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING
        if (call != inCall) {
            inCall = call
            evaluate(Trigger.CALL)
        }
    }

    /**
     * Created only from Android 12: the listener type does not exist before, and a field of
     * this object holding it would fail the whole object on Android 10.
     */
    private object ModeListener31 {
        val listener = AudioManager.OnModeChangedListener { onAudioMode(it) }
    }

    private fun register(ctx: Context, want: Set<Trigger>) {
        val filter = IntentFilter()
        if (Trigger.BATTERY in want || Trigger.CHARGING in want) {
            filter.addAction(Intent.ACTION_BATTERY_CHANGED)
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Trigger.SCREEN_OFF in want || Trigger.LOCKED in want || Trigger.APP_OPEN in want || Trigger.NO_INTERNET in want) {
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Trigger.NO_INTERNET in want) filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        if (Trigger.USB in want) filter.addAction(USB_STATE)
        if (Trigger.CAR in want) {
            filter.addAction(android.app.UiModeManager.ACTION_ENTER_CAR_MODE)
            filter.addAction(android.app.UiModeManager.ACTION_EXIT_CAR_MODE)
            filter.addAction(CAR_CONNECTION_UPDATED)
        }
        if (Trigger.ALARM in want) filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        if (Trigger.LOCKED in want) filter.addAction(Intent.ACTION_USER_PRESENT)
        if (Trigger.DND in want) filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (filter.countActions() > 0) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                ?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }?.let { readBattery(it) }
            // Sticky, but only one sticky comes back above: each is read for itself.
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
            if (Trigger.USB in want) {
                usbConnected = ctx.registerReceiver(null, IntentFilter(USB_STATE))?.getBooleanExtra("connected", false) == true
            }
        }
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (Trigger.HEADPHONES in want) audio.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
        if (Trigger.CALL in want) {
            if (Compat.SDK >= 31) audio.addOnModeChangedListener(executor, ModeListener31.listener)
            else listenCallsLegacy(ctx)
        }
        if (Trigger.ROAMING in want || Trigger.WEAK_SIGNAL in want || Trigger.PLACE in want) registerPhone(ctx, want)
        if (Trigger.APP_OPEN in want) {
            grantUsageAccess()
            if (ctx.getSystemService(PowerManager::class.java).isInteractive) startAppPoll()
        }
        if (Trigger.PLACE in want) startPlacePoll()
        if (Trigger.NOTIFY_APP in want) App.scope.launch { NotifWatch.enable() }
        if (Trigger.TRAFFIC in want) {
            grantUsageAccess()
            startTrafficPoll()
        }
        if (Trigger.WIFI in want || Trigger.WIFI_OTHER in want) {
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiCallback, Handler(Looper.getMainLooper()))
            }.onFailure { Log.w(TAG, "wifi callback failed", it) }
            readWifi(settle = false)
        }
        if (Trigger.VPN in want) {
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                    vpnCallback, Handler(Looper.getMainLooper()))
            }.onFailure { Log.w(TAG, "vpn callback failed", it) }
            readVpn()
        }
        if (Trigger.NO_INTERNET in want) {
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(netCallback, Handler(Looper.getMainLooper()))
            }.onFailure { Log.w(TAG, "network callback failed", it) }
            readInternet(notify = false)
            if (ctx.getSystemService(PowerManager::class.java).isInteractive) startNetProbe()
        }
    }

    private fun unregister(ctx: Context) {
        runCatching { ctx.unregisterReceiver(receiver) }
        val audio = ctx.getSystemService(AudioManager::class.java)
        runCatching { audio.unregisterAudioDeviceCallback(audioDevices) }
        if (Compat.SDK >= 31) runCatching { audio.removeOnModeChangedListener(ModeListener31.listener) }
        phoneCallbacks.forEach { runCatching { it() } }
        phoneCallbacks.clear()
        appPoll?.cancel()
        placePoll?.cancel()
        netProbe?.cancel()
        trafficPoll?.cancel()
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(vpnCallback) }
        vpnRead?.cancel()
        netLostSince = 0L
        wifiKnown = false
        vpnKnown = false
    }

    /** One callback per active SIM: service state for roaming and towers, strength for bars. */
    private fun registerPhone(ctx: Context, want: Set<Trigger>) {
        val base = ctx.getSystemService(TelephonyManager::class.java)
        for (slot in Sims.slots()) {
            val sub = Compat.subId(slot)
            if (sub < 0) continue
            val tm = base.createForSubscriptionId(sub)
            runCatching {
                phoneCallbacks += if (Compat.SDK >= 31) listenPhone(tm, ctx, slot) else listenPhoneLegacy(tm, ctx, slot)
            }.onFailure { Log.w(TAG, "telephony callback for slot $slot failed", it) }
        }
        if (Trigger.WEAK_SIGNAL in want) {
            // The 15-minute floor passes with time too.
            App.scope.launch { while (true) { delay(5 * 60_000L); if (Trigger.WEAK_SIGNAL in registered) evaluate(Trigger.WEAK_SIGNAL) else break } }
        }
    }

    private fun onServiceState(ctx: Context) {
        readRoaming(ctx)
        if (Trigger.PLACE in registered) readPlaceSoon()
    }

    private fun onSignal(slot: Int, level: Int) {
        val now = SystemClock.elapsedRealtime()
        signal[slot] = level
        val weak = Prefs.appRules.filter { it.trigger == Trigger.WEAK_SIGNAL && it.triggerSlot == slot }
        val threshold = weak.maxOfOrNull { it.level } ?: return
        if (level <= threshold) { weakSince.putIfAbsent(slot, now); goodSince.remove(slot) }
        else { goodSince.putIfAbsent(slot, now); weakSince.remove(slot) }
        evaluate(Trigger.WEAK_SIGNAL)
        // The durations above only pass with time; look again once they have.
        App.scope.launch { delay(GOOD_AFTER_MS + 1_000); evaluate(Trigger.WEAK_SIGNAL) }
    }

    /** Android 12+: service state for roaming and towers, strength for bars. Returns the undo. */
    private fun listenPhone(tm: TelephonyManager, ctx: Context, slot: Int): () -> Unit {
        val cb = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener, TelephonyCallback.SignalStrengthsListener {
            override fun onServiceStateChanged(state: ServiceState) = onServiceState(ctx)
            override fun onSignalStrengthsChanged(strength: SignalStrength) = onSignal(slot, strength.level)
        }
        tm.registerTelephonyCallback(executor, cb)
        return { tm.unregisterTelephonyCallback(cb) }
    }

    /** Android 10–11: the same through PhoneStateListener; the executor form needs no Looper. */
    @Suppress("DEPRECATION")
    private fun listenPhoneLegacy(tm: TelephonyManager, ctx: Context, slot: Int): () -> Unit {
        val listener = object : android.telephony.PhoneStateListener(executor) {
            override fun onServiceStateChanged(state: ServiceState) = onServiceState(ctx)
            override fun onSignalStrengthsChanged(strength: SignalStrength) = onSignal(slot, strength.level)
        }
        tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE or
            android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        return { tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE) }
    }

    /**
     * Android 10–11 has no audio-mode listener: a phone call is seen through the call state.
     * A messenger call is not a phone call there, so it does not count.
     */
    @Suppress("DEPRECATION")
    private fun listenCallsLegacy(ctx: Context) {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val listener = object : android.telephony.PhoneStateListener(executor) {
            override fun onCallStateChanged(state: Int, number: String?) {
                val call = state != TelephonyManager.CALL_STATE_IDLE
                if (call != inCall) {
                    inCall = call
                    evaluate(Trigger.CALL)
                }
            }
        }
        runCatching { tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE) }
            .onSuccess { phoneCallbacks += { tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE) } }
    }

    // --- readers ----------------------------------------------------------------------------

    private fun refreshAll(ctx: Context) {
        screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive
        if (!screenOn && screenOffAt == 0L) screenOffAt = SystemClock.elapsedRealtime()
        // Started while locked: counted from now, there is no knowing since when.
        val lockedNow = !screenOn || keyguardLocked(ctx)
        if (lockedNow && !locked) lockedAt = SystemClock.elapsedRealtime()
        locked = lockedNow
        readHeadphones(notify = false)
        readRoaming(ctx, notify = false)
        inCall = ctx.getSystemService(AudioManager::class.java).mode.let {
            it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
        }
        if (Trigger.PLACE in registered) readPlaceSoon(force = true)
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun powerSave(): Boolean = App.ctx.getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun dnd(): Boolean =
        App.ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    private fun readHeadphones(notify: Boolean = true) {
        val now = App.ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
        if (now != headphones) {
            headphones = now
            if (notify) evaluate(Trigger.HEADPHONES)
        }
    }

    private fun readRoaming(ctx: Context, notify: Boolean = true) {
        val base = ctx.getSystemService(TelephonyManager::class.java)
        val now = Sims.slots().any { slot ->
            val sub = Compat.subId(slot)
            sub >= 0 && runCatching { base.createForSubscriptionId(sub).isNetworkRoaming }.getOrDefault(false)
        }
        if (now != roaming) {
            roaming = now
            if (notify) evaluate(Trigger.ROAMING)
        }
    }

    /**
     * Whether the internet is there now, by the system's check of the default network. A loss
     * starts the clock; each rule's "longer than" is looked at again once it has run out.
     */
    private fun readInternet(notify: Boolean = true) {
        val ctx = App.ctx
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val airplane = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val lost = InternetLoss.lost(
            hasNetwork = caps != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            dataWanted = DataWatch.enabled(),
            airplane = airplane,
        )
        if (lost == (netLostSince != 0L)) return
        netLostSince = if (lost) SystemClock.elapsedRealtime() else 0L
        Log.i(TAG, if (lost) "internet lost" else "internet back")
        if (lost) {
            Prefs.appRules.filter { it.enabled && it.trigger == Trigger.NO_INTERNET }
                .map { it.level }.distinct().forEach { recheck(Trigger.NO_INTERNET, it * 1000L) }
        }
        if (notify) evaluate(Trigger.NO_INTERNET)
    }

    /**
     * While the screen is on: a request to the same kind of address the system checks with,
     * once a minute. Failing twice in a row on a network the system still counts as working,
     * it asks the system to check again — the system's answer is what counts, not the probe's.
     */
    private fun startNetProbe() {
        netProbe?.cancel()
        netProbe = App.scope.launch {
            var failures = 0
            while (screenOn) {
                delay(PROBE_EVERY_MS)
                // Already lost: the system knows, there is nothing to tell it.
                if (netLostSince != 0L) { failures = 0; continue }
                val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
                val net = cm.activeNetwork ?: continue
                if (probe(net)) { failures = 0; continue }
                if (++failures < 2) continue
                failures = 0
                Log.i(TAG, "internet probe failed twice: asking the system to check the network")
                runCatching { cm.reportNetworkConnectivity(net, false) }
            }
        }
    }

    private fun probe(net: Network): Boolean = runCatching {
        val c = net.openConnection(java.net.URL(PROBE_URL)) as java.net.HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = false
        c.useCaches = false
        try { c.responseCode == 204 } finally { c.disconnect() }
    }.getOrDefault(false)

    /** In car mode, or Android Auto (projection or Automotive) reports a car connected. */
    private fun carMode(): Boolean = runCatching {
        App.ctx.getSystemService(android.app.UiModeManager::class.java).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_CAR
    }.getOrDefault(false) || carConnected()

    /** CarConnection's state: 0 — no car, 1 — Android Automotive, 2 — Android Auto projection. */
    private fun carConnected(): Boolean = runCatching {
        App.ctx.contentResolver.query(CAR_CONNECTION_URI, arrayOf(CAR_CONNECTION_STATE), null, null, null)?.use { c ->
            val col = c.getColumnIndex(CAR_CONNECTION_STATE)
            col >= 0 && c.moveToNext() && c.getInt(col) != 0
        } ?: false
    }.getOrDefault(false)

    /** When the next alarm clock rings (wall time), or null with none set. */
    private fun nextAlarm(): Long? =
        runCatching { App.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()

    /**
     * Mobile traffic of today and of the month, all SIMs together — the per-SIM figures are
     * the system's own. Read every ten minutes; a day or a month starting over shows at the next.
     */
    private fun startTrafficPoll() {
        trafficPoll?.cancel()
        trafficPoll = App.scope.launch {
            while (true) {
                val zone = java.time.ZoneId.systemDefault()
                val today = java.time.LocalDate.now(zone)
                val day = mobileBytes(today.atStartOfDay(zone).toInstant().toEpochMilli())
                val month = mobileBytes(today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val changed = day != trafficDay || month != trafficMonth
                trafficDay = day
                trafficMonth = month
                if (changed) evaluate(Trigger.TRAFFIC)
                delay(TRAFFIC_EVERY_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun mobileBytes(from: Long): Long? = runCatching {
        val b = App.ctx.getSystemService(android.app.usage.NetworkStatsManager::class.java)
            .querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, from, System.currentTimeMillis())
        b.rxBytes + b.txBytes
    }.onFailure { Log.w(TAG, "traffic read failed", it) }.getOrNull()

    /** Tower changes come in bursts; one shell read per 15 s is plenty. */
    private fun readPlaceSoon(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPlaceRead < 15_000L) return
        lastPlaceRead = now
        App.scope.launch {
            val read = runCatching { currentCells() }.onFailure { Log.w(TAG, "tower read failed", it) }.getOrNull()
            Log.i(TAG, "towers: $read")
            // Nothing seen (airplane mode, no service): keep the last answer rather than
            // "left the place", which would flip every place rule on a tunnel.
            if (read.isNullOrEmpty()) return@launch
            // Evaluated on every read, changed or not: leaving is the grace running out,
            // which no tower change announces.
            cells = read
            evaluate(Trigger.PLACE)
            runCatching { WifiWake.onTowers(read) }.onFailure { Log.w(TAG, "wifi wake failed", it) }
        }
    }

    /** A fresh tower read now, e.g. from the Wi-Fi wake alarm. */
    fun refreshTowers() = readPlaceSoon(force = true)

    private fun startPlacePoll() {
        placePoll?.cancel()
        placePoll = App.scope.launch {
            while (true) {
                // Waking Wi-Fi alone needs no timer: arriving changes the tower, and the system
                // reports that; the one case with no change (a short trip under the same
                // tower) has its own alarm in WifiWake, set only near home.
                val placeRules = Prefs.appRules.any { it.enabled && it.hasActions && it.trigger == Trigger.PLACE }
                if (placeRules) readPlaceSoon(force = true)
                delay(5 * 60_000L)
            }
        }
    }

    private val CELL = Regex("CellIdentity(Lte|Nr|Wcdma|Gsm|Tdscdma):\\{([^}]*)")
    private const val INVALID = "2147483647"

    /**
     * Towers the phone is on now, as "L:tac:ci" and the like. Read through the privileged shell:
     * the public API hands them out only with location permission, and asking for location
     * would light the location indicator on every look.
     */
    suspend fun currentCells(): Set<String> {
        val out = Privileged.exec("dumpsys telephony.registry 2>/dev/null | grep -oE 'mCellIdentity=CellIdentity[A-Za-z]+:\\{[^}]*'")
        return CELL.findAll(out).mapNotNull { m ->
            val kind = m.groupValues[1]
            val body = m.groupValues[2]
            fun f(name: String) = Regex("\\b$name=(\\d+)").find(body)?.groupValues?.get(1)?.takeIf { it != INVALID && it != "9223372036854775807" }
            when (kind) {
                "Lte" -> f("mTac")?.let { t -> f("mCi")?.let { "L:$t:$it" } }
                "Nr" -> f("mTac")?.let { t -> f("mNci")?.let { "N:$t:$it" } }
                else -> f("mLac")?.let { l -> f("mCid")?.let { "${kind.first()}:$l:$it" } }
            }
        }.toSet()
    }

    /** From [AppWatchService]: an app came to the front, at once. */
    fun onForeground(pkg: String, cls: String?) {
        if (Trigger.APP_OPEN !in registered || !screenOn) return
        val passes = AppPresence.passes(pkg, cls)
        if (pkg == foreground && passes == foregroundPasses) return
        foreground = pkg
        foregroundPasses = passes
        // The app left for a small window is still on screen: know it before the rule looks.
        refreshBehind()
        Log.i(TAG, "on screen (instant): $pkg")
        evaluate(Trigger.APP_OPEN)
    }

    private fun startAppPoll() {
        appPoll?.cancel()
        appPoll = App.scope.launch {
            while (screenOn) {
                var changed = false
                // The service reports each change of the front app itself; looking as well would only cost.
                if (!AppWatchService.connected) {
                    val fg = runCatching { foregroundApp() }.getOrNull()
                    if (fg != null && (fg.first != foreground || fg.second != foregroundPasses)) {
                        foreground = fg.first
                        foregroundPasses = fg.second
                        changed = true
                    }
                }
                // A small window closing raises no window event: this look is needed either way.
                if (refreshBehind()) changed = true
                if (changed) evaluate(Trigger.APP_OPEN)
                delay(2_000)
            }
        }
    }

    /** Reads [behind] afresh; whether it changed. */
    private fun refreshBehind(): Boolean {
        val now = runCatching { synchronized(behindTracker) { readBehind() } }.getOrNull() ?: return false
        if (now == behind) return false
        behind = now
        Log.i(TAG, "on screen behind: ${now.joinToString().ifEmpty { "-" }}")
        return true
    }

    /**
     * Apps on screen apart from the one in front, from the usage events since the last read —
     * the first read goes back an hour, for a small window opened before the rule was.
     */
    private fun readBehind(): Set<String> {
        val usm = App.ctx.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = if (behindReadTo == 0L) end - 3_600_000L else behindReadTo
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED ->
                    behindTracker.onEvent(e.packageName, e.className, true)
                UsageEvents.Event.ACTIVITY_STOPPED, ACTIVITY_DESTROYED ->
                    behindTracker.onEvent(e.packageName, e.className, false)
            }
        }
        behindReadTo = end
        return behindTracker.packages(foreground)
    }

    /**
     * The last app to come to the front, from usage events of the last minute, and whether it
     * only sits on top of another; null when nothing came.
     */
    private fun foregroundApp(): Pair<String, Boolean>? {
        val usm = App.ctx.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60_000L, end)
        val e = UsageEvents.Event()
        var last: Pair<String, Boolean>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName to AppPresence.passes(e.packageName, e.className)
        }
        return last
    }

    /** Usage access is an app-op the privileged shell can grant; no settings screen needed. */
    fun grantUsageAccess() {
        App.scope.launch {
            runCatching { Privileged.exec("appops set ${App.ctx.packageName} GET_USAGE_STATS allow") }
        }
    }

    private fun keyguardLocked(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    // --- screen-off timer ------------------------------------------------------------------

    /**
     * "Screen off for N minutes" has no event of its own; an exact alarm wakes the check at
     * the earliest threshold still ahead. Doze would stretch a plain delay.
     */
    fun scheduleScreenAlarm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 300, Intent(ctx, ConditionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val now = SystemClock.elapsedRealtime()
        val rules = Prefs.appRules.filter { it.enabled }
        val offWaits = if (screenOn) emptyList() else
            rules.filter { it.trigger == Trigger.SCREEN_OFF }.map { it.level * 60_000L - (now - screenOffAt) }
        // Locked rules count from the lock, and go on counting with the screen woken but locked.
        val lockWaits = if (!locked) emptyList() else
            rules.filter { it.trigger == Trigger.LOCKED }.map { it.level * 60_000L - (now - lockedAt) }
        // A window opening or closing while locked is a change of its own.
        val wall = java.time.ZonedDateTime.now()
        val windowWaits = if (!locked) emptyList() else
            rules.filter { it.trigger == Trigger.LOCKED && it.lockWindow && it.endHour != null }.flatMap { r ->
                listOfNotNull(r.nextStart(wall), r.lastStart(wall)?.let { r.lockWindowEnd(it) }?.takeIf { it.isAfter(wall) })
                    .map { it.toInstant().toEpochMilli() - System.currentTimeMillis() }
            }
        // An unlocked night ends with its time, the phone locked or not.
        val paused = Prefs.lockPaused
        val nightWaits = rules.filter { it.id in paused && it.splitEnd }.mapNotNull { r ->
            r.lastStart(wall)?.let { r.lockWindowEnd(it) }?.takeIf { it.isAfter(wall) }
                ?.let { it.toInstant().toEpochMilli() - System.currentTimeMillis() }
        }
        // "Перед будильником" opens and closes with the alarm clock.
        val alarmWaits = if (Trigger.ALARM !in registered) emptyList() else nextAlarm()?.let { at ->
            rules.filter { it.trigger == Trigger.ALARM }.map { at - it.level * 60_000L - System.currentTimeMillis() } +
                (at - System.currentTimeMillis())
        }.orEmpty()
        // Sunset and sunrise, for the trigger and for "темно" among the extra conditions.
        val sunWaits = if (Trigger.SUN !in registered) emptyList() else Prefs.sunPlace?.let { (la, lo) ->
            Sun.nextEdge(wall, la, lo)?.let { listOf(it.toInstant().toEpochMilli() - System.currentTimeMillis()) }
        }.orEmpty()
        val next = (offWaits + lockWaits + windowWaits + nightWaits + alarmWaits + sunWaits).filter { it > 0 }.minOrNull() ?: return
        val at = System.currentTimeMillis() + next + 1_000
        if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

/** Wakes the screen-off check; see [ConditionWatch.scheduleScreenAlarm]. */
class ConditionReceiver : BroadcastReceiver() {
    /**
     * Fires in deep sleep ("locked for 5 minutes"): the phone is kept awake until the rule's
     * work is done. Without it the work stalls the moment this returns, and freezing two dozen
     * apps would crawl on into the time limit.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val wake = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "netmode:condition")
        wake.acquire(3 * 60_000L)
        val work = App.scope.launch {
            try {
                ConditionWatch.evaluateNow(Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.ALARM, Trigger.SUN)
                ConditionWatch.scheduleScreenAlarm(context)
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
        // The broadcast itself must not outlast its window; the wake lock carries the rest.
        App.scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { work.join() }
            pending.finish()
        }
    }
}
