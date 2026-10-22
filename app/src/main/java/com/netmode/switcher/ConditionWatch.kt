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
 * Do Not Disturb.
 *
 * Everything is event-driven where Android offers an event — runtime receivers, telephony and
 * audio callbacks — and lives in the app process, which the watcher service keeps alive while
 * such a rule exists. Two things have no event and are looked at instead: the app on screen
 * (every 2 s, only while the screen is on) and the cell towers (on every service-state change,
 * at most every 15 s, and every 5 minutes).
 */
object ConditionWatch {

    private const val TAG = "netmode"

    val TRIGGERS = setOf(
        Trigger.BATTERY, Trigger.CHARGING, Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.APP_OPEN, Trigger.PLACE,
        Trigger.ROAMING, Trigger.WEAK_SIGNAL, Trigger.CALL, Trigger.HEADPHONES, Trigger.DND,
    )

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
    /** APP_OPEN rules whose app has left, while their delay runs: since when. */
    private val awaySince = mutableMapOf<Long, Long>()
    @Volatile private var cells: Set<String>? = null
    @Volatile private var roaming = false
    @Volatile private var inCall = false
    @Volatile private var headphones = false
    private val signal = mutableMapOf<Int, Int>()
    private val weakSince = mutableMapOf<Int, Long>()
    private val goodSince = mutableMapOf<Int, Long>()

    private var registered = emptySet<Trigger>()
    private var appPoll: Job? = null
    private var placePoll: Job? = null
    private var lastPlaceRead = 0L
    /** Undo for each phone listener registered. */
    private val phoneCallbacks = mutableListOf<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()

    /** Waking Wi-Fi near a network needs the towers read just like a place rule does. */
    private fun used(): Set<Trigger> =
        Prefs.appRules.filter { it.enabled && it.hasActions && it.trigger in TRIGGERS }.map { it.trigger }.toSet() +
            (if (WifiWake.needed()) setOf(Trigger.PLACE) else emptySet())

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
            fg == null -> AppPresence.Seen.UNKNOWN
            foregroundPasses || rule.appPassThrough.any { AppKey.pkg(it) == fg } -> AppPresence.Seen.PASS
            else -> AppPresence.Seen.OTHER
        }
        val before = awaySince[rule.id]
        val delayMs = rule.appLeaveDelay * 1000L
        val look = AppPresence.look(seen, openSince != 0L, before, SystemClock.elapsedRealtime(), delayMs, rule.appScreenOffStays)
        if (look.awaySince == null) awaySince.remove(rule.id) else awaySince[rule.id] = look.awaySince
        // The delay only passes with time: look again once it is over.
        if (before == null && look.awaySince != null) recheckApps(delayMs)
        return look.holds
    }

    /**
     * Wakes the app check after [ms]. The screen may be off by then, with no polling to notice;
     * the wake lock keeps deep sleep from stretching a minute's delay into many.
     */
    private fun recheckApps(ms: Long) {
        App.scope.launch {
            val wake = App.ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmode:appleave")
            wake.acquire(ms + 60_000L)
            try {
                delay(ms + 500L)
                evaluateNow(Trigger.APP_OPEN)
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
    }

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
        if (Trigger.SCREEN_OFF in want || Trigger.LOCKED in want) scheduleScreenAlarm(ctx)
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
                    appPoll?.cancel()
                    scheduleScreenAlarm(context)
                    evaluate(Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.APP_OPEN)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // No lock screen at all, or one dismissed by itself (Smart Lock): unlocked now.
                    if (!keyguardLocked(context)) locked = false
                    if (Trigger.APP_OPEN in registered) startAppPoll()
                    evaluate(Trigger.SCREEN_OFF, Trigger.LOCKED)
                }
                Intent.ACTION_USER_PRESENT -> {
                    locked = false
                    evaluate(Trigger.LOCKED)
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> evaluate(Trigger.DND)
            }
        }
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
        if (Trigger.SCREEN_OFF in want || Trigger.LOCKED in want || Trigger.APP_OPEN in want) {
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Trigger.LOCKED in want) filter.addAction(Intent.ACTION_USER_PRESENT)
        if (Trigger.DND in want) filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (filter.countActions() > 0) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)?.let { readBattery(it) }
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

    private fun startAppPoll() {
        appPoll?.cancel()
        appPoll = App.scope.launch {
            while (screenOn) {
                val fg = runCatching { foregroundApp() }.getOrNull()
                if (fg != null && (fg.first != foreground || fg.second != foregroundPasses)) {
                    foreground = fg.first
                    foregroundPasses = fg.second
                    evaluate(Trigger.APP_OPEN)
                }
                delay(2_000)
            }
        }
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
        val next = (offWaits + lockWaits + windowWaits + nightWaits).filter { it > 0 }.minOrNull() ?: return
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
                ConditionWatch.evaluateNow(Trigger.SCREEN_OFF, Trigger.LOCKED)
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
