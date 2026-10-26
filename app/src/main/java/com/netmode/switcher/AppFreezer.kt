package com.netmode.switcher

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Stops apps for the length of an [AppRule] window and puts back what it changed afterwards.
 *
 * All of it is plain shell, one invocation per event for every package at once. What works is
 * measured on a OnePlus 15 / OxygenOS 16:
 *  - `am force-stop` — root and shell; alarms and jobs are dropped, the app stays stopped
 *    (watched for two minutes, nothing restarted it).
 *  - `cmd appops set … RUN_ANY_IN_BACKGROUND` — root only: OxygenOS strips
 *    MANAGE_APP_OPS_MODES from shell, so through Shizuku this step fails and is skipped.
 *  - `am set-standby-bucket … restricted` — root and shell, but silently ignored for exempt
 *    apps (one with a home-screen widget stayed at 10).
 *  - `pm suspend` — root and shell.
 */
object AppFreezer {

    private const val TAG = "netmode"
    private val lock = Mutex()

    /** A late alarm still counts for a start-only rule within this slack. */
    private val GRACE: Duration = Duration.ofMinutes(10)

    /**
     * After a reboot a missed start-only rule is still applied within this slack — the phone
     * switched off at 20:00 still ends up in the 21:00 network mode.
     */
    private val BOOT_GRACE: Duration = Duration.ofHours(24)

    /** cmd first (Android 13+), svc / settings as the fallback for older or trimmed builds. */
    const val BT_OFF = "cmd bluetooth_manager disable >/dev/null 2>&1 || svc bluetooth disable >/dev/null 2>&1"
    const val BT_ON = "cmd bluetooth_manager enable >/dev/null 2>&1 || svc bluetooth enable >/dev/null 2>&1"
    private const val LOC_OFF = "cmd location set-location-enabled false >/dev/null 2>&1 || settings put secure location_mode 0"
    private const val LOC_ON = "cmd location set-location-enabled true >/dev/null 2>&1 || settings put secure location_mode 3"
    const val WIFI_OFF = "cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || svc wifi disable >/dev/null 2>&1"
    const val DATA_OFF = "svc data disable >/dev/null 2>&1 || cmd phone data disable >/dev/null 2>&1"
    const val DATA_ON = "svc data enable >/dev/null 2>&1 || cmd phone data enable >/dev/null 2>&1"
    const val WIFI_ON = "cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || svc wifi enable >/dev/null 2>&1"
    /** "Переподключить моб. сеть": how long airplane mode stays on, and how often at most. */
    private const val RECONNECT_PAUSE_MS = 3_000L
    private const val RECONNECT_EVERY_MS = 5 * 60_000L
    // Android 10 has no airplane-mode command: the setting plus the system's own broadcast (root).
    const val AIR_ON = "cmd connectivity airplane-mode enable >/dev/null 2>&1 || " +
        "{ settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1; }"
    const val AIR_OFF = "cmd connectivity airplane-mode disable >/dev/null 2>&1 || " +
        "{ settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1; }"
    const val PS_ON = "cmd power set-mode 1 >/dev/null 2>&1 || settings put global low_power 1"
    const val PS_OFF = "cmd power set-mode 0 >/dev/null 2>&1 || settings put global low_power 0"

    /** One call reads every radio the side actions may touch. */
    private val STATE_SCRIPT =
        "echo \"@WIFI \$(settings get global wifi_on)\"\n" +
            "echo \"@AP \$(${Compat.TETHER_DUMP} 2>/dev/null | grep -cE '(wlan|softap|ap_br)[^ ]* - TetheredState')\"\n" +
            "echo \"@BT \$(settings get global bluetooth_on)\"\n" +
            "echo \"@DATA \$(settings get global mobile_data)\"\n" +
            "echo \"@LOC \$(cmd location is-location-enabled 2>/dev/null) \$(settings get secure location_mode)\"\n" +
            "echo \"@AIR \$(settings get global airplane_mode_on)\"\n" +
            "echo \"@PS \$(settings get global low_power)\""

    /** Wi-Fi events this soon after the app switched Wi-Fi itself are its own doing. */
    private const val SELF_WIFI_WINDOW_MS = 60_000L

    /**
     * Loop guard for rules that trigger each other ("data on → 3G" + "SIM in 3G → data off").
     * An event that arrives within [CHAIN_WINDOW_MS] of a rule acting counts as part of a chain;
     * a chain longer than [CHAIN_MAX] is cut and reported instead of flipping forever.
     */
    private const val CHAIN_WINDOW_MS = 15_000L
    private const val CHAIN_MAX = 4
    private var chainDepth = 0
    private var lastActAt = 0L

    private fun chainOk(what: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        chainDepth = if (now - lastActAt < CHAIN_WINDOW_MS) chainDepth + 1 else 0
        if (chainDepth < CHAIN_MAX) return true
        Prefs.log("правила перекидывают друг друга ($what) — остановлено, проверь условия правил")
        return false
    }

    /**
     * The guard counts events that actually start something, once per event: an event no rule
     * acts on (a Wi-Fi reconnect after a restart) is not part of any chain, and several rules
     * opening on the same event are one step, not several.
     * 0 — nothing to count, 1 — an event began and is not counted yet, 2 — it was cut.
     */
    private var eventState = 0

    private fun beginEvent() {
        eventState = 1
    }

    private fun allowedByChain(what: String): Boolean {
        if (eventState == 1) eventState = if (chainOk(what)) 0 else 2
        return eventState != 2
    }

    private fun acted() {
        lastActAt = android.os.SystemClock.elapsedRealtime()
    }

    /** Runs [block] with no rule event in between, e.g. both writes of the dual-SIM scenario. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    private const val PENDING_ALL = "all"
    private const val PENDING_APPS = "apps"

    private val BUCKET_NAMES = mapOf(
        10 to "active", 20 to "working_set", 30 to "frequent", 40 to "rare", 45 to "restricted",
    )


    private val CORE = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.settings",
        "com.android.shell", "com.android.providers.telephony", "com.android.networkstack",
        "com.google.android.gms", "com.google.android.gsf",
        "moe.shizuku.privileged.api", "me.weishu.kernelsu", "com.topjohnwu.magisk",
    )

    /**
     * Packages that must never be stopped: the app itself and the things it runs through, the
     * system UI, and whatever currently fills a role the phone cannot be without.
     */
    fun protectedPackages(ctx: Context = App.ctx): Set<String> {
        val pm = ctx.packageManager
        val out = CORE.toMutableSet()
        out += ctx.packageName
        runCatching {
            pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName?.let { out += it }
        }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { out += it }
        }
        runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)?.let { out += it } }
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out += it } }
        return out
    }

    /**
     * Apps that own alarms: stopping one cancels its alarms and the user oversleeps. They are
     * not blocked, only flagged, so the choice stays the user's.
     */
    fun alarmApps(ctx: Context = App.ctx): Set<String> {
        val pm = ctx.packageManager
        val out = mutableSetOf<String>()
        for (action in listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM)) {
            runCatching {
                pm.queryIntentActivities(Intent(action), 0).forEach { out += it.activityInfo.packageName }
            }
        }
        runCatching {
            ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
                ?.showIntent?.creatorPackage?.let { out += it }
        }
        return out
    }

    /**
     * Brings reality in line with the rules: releases what should no longer be held and
     * applies any start that is due and not yet handled. Returns true when it did anything.
     */
    suspend fun reconcile(boot: Boolean = false, catchUp: Boolean = true): Boolean = lock.withLock {
        val now = ZonedDateTime.now()
        val rules = Prefs.appRules
        var acted = false
        eventState = 0
        // Without root or Shizuku nothing can be applied or put back: leave every record as it
        // is, so the next reconcile (alarm, boot, opening the app) does the work.
        if (!Privileged.available()) {
            Prefs.log("правила: нет root/Shizuku — применю, когда доступ появится")
            AccessWait.arm(afterBoot = boot)
            return@withLock false
        }

        // Closes that failed earlier for lack of access.
        for (id in Prefs.pendingClose) {
            closeLocked(rules.find { it.id == id }, id, "повтор возврата")
            acted = true
        }
        if (closeOverNights()) acted = true

        for ((id, since) in Prefs.openWindows) {
            val rule = rules.find { it.id == id }
            val timedOut = rule?.manualEndAfter(zoned(since))?.isAfter(now) == false
            val ended = rule == null || !rule.enabled ||
                (rule.lastEnd(now)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE) > since ||
                rule.leftEndAfter(zoned(since))?.isAfter(now) == false || timedOut
            if (ended) {
                if (rule != null && !rule.enabled && !rule.isTime) Prefs.appHandled = Prefs.appHandled - id
                closeLocked(rule, id, when {
                    rule == null -> "правило удалено"
                    !rule.enabled -> "правило выключено"
                    timedOut -> "время сцены вышло"
                    else -> "окончание ${rule.timeLabel}"
                })
                acted = true
            }
        }

        // Whatever is left over: an app removed from a rule, entries of a deleted rule, and
        // entries from before windows were tracked.
        val open = Prefs.openWindows
        val stray = Prefs.frozen.filter { e ->
            if (e.ruleId < 0) {
                val r = rules.find { it.id == -e.ruleId }
                return@filter r == null || !r.enabled ||
                    AppKey.pkg(e.pkg.removePrefix(FrozenApp.VPN_BACK)) !in r.endStopApps.map { AppKey.pkg(it) }
            }
            val rule = rules.find { it.id == e.ruleId }
            // A restoring rule keeps its window open for as long as it holds anything, so an
            // entry of a closed window is one whose restore failed: retried here.
            rule == null || !rule.enabled || !rule.covers(e.pkg) || (e.ruleId !in open && (rule.restores ||
                (rule.lastEnd(now)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE) > e.since ||
                    rule.leftEndAfter(zoned(e.since))?.isAfter(now) == false))
        }
        if (stray.isNotEmpty()) {
            runCatching { restore(stray) }
                .onSuccess { failed ->
                    Prefs.log("возвращено как было (${stray.size})" + if (failed > 0) ", ошибок $failed" else "")
                }
                .onFailure { Prefs.log("не удалось вернуть — ${it.message}") }
            acted = true
        }

        val handled = Prefs.appHandled.toMutableMap()
        // Oldest first, so when several are due at once the most recent one wins.
        val due = rules.filter { it.enabled && it.isTime && it.hasActions }
            .mapNotNull { r -> r.lastStart(now)?.let { r to it } }
            .sortedBy { it.second }
        for ((rule, start) in due) {
            val startMs = start.toInstant().toEpochMilli()
            if ((handled[rule.id] ?: 0L) >= startMs) continue
            val late = Duration.between(start, now)
            val inside = rule.endAfter(start)?.isAfter(now)
                ?: (late <= if (boot && catchUp) BOOT_GRACE else GRACE)
            // With catch-up off, a window missed while the phone was off is skipped too.
            if (!inside || (boot && !catchUp && late > GRACE)) continue
            handled[rule.id] = startMs
            Prefs.appHandled = handled
            freezeLocked(rule, if (boot) "догон после загрузки" else "расписание ${rule.timeLabel}")
            acted = true
        }
        acted
    }

    /**
     * After a reboot the connections a window depended on may be gone with no event to say so:
     * windows of Bluetooth devices that are not connected are closed (a device that does come
     * back reopens them through its connect broadcast).
     */
    suspend fun afterBoot() = lock.withLock {
        val connected = BtDevices.connected(App.ctx)
        val rules = Prefs.appRules.filter { it.trigger == Trigger.BLUETOOTH }
        val gone = rules.filter { r -> r.btAddress?.uppercase() !in connected }
        Prefs.appHandled = Prefs.appHandled - gone.map { it.id }.toSet()
        releaseLocked(gone.filter { it.releaseOnLeave }, "после перезагрузки устройство не подключено")
    }

    /** "Apply now" from the rule dialog; released at the next end boundary like any other. */
    suspend fun freezeNow(rule: AppRule) = lock.withLock {
        // Only a time rule has a start to mark. For the others the mark means "the event was
        // seen", and setting it here would make the real arrival look already handled.
        if (rule.isTime) rule.lastStart(ZonedDateTime.now())?.let {
            Prefs.appHandled = Prefs.appHandled + (rule.id to it.toInstant().toEpochMilli())
        }
        eventState = 0
        freezeLocked(rule, "вручную", manual = true)
    }

    /** The phone is being used right now: screen on and unlocked. */
    fun inUse(): Boolean {
        val ctx = App.ctx
        return ctx.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    }

    /**
     * Applies what waited for the phone to be locked. Called a minute after locking; does
     * nothing if the phone is in use again by then. A window that closed in the meantime has
     * already dropped its wait (see closeLocked), so only what is still due is applied.
     */
    suspend fun runPending() = lock.withLock {
        val pending = Prefs.pendingStarts
        if (pending.isEmpty() || inUse()) return@withLock
        eventState = 0
        Prefs.pendingStarts = emptyMap()
        val rules = Prefs.appRules.associateBy { it.id }
        for ((id, what) in pending) {
            val rule = rules[id] ?: continue
            if (!rule.enabled || (rule.restores && id !in Prefs.openWindows)) continue
            val why = rule.logPrefix + "экран погашен"
            acted()
            if (rule.steps.isNotEmpty()) {
                // An ordered rule waits as a whole: its steps make no sense out of order.
                runSteps(rule, end = false, from = 0, why = why)
                continue
            }
            if (what == PENDING_ALL) {
                sideActions(rule, why)
                if (rule.reconnect) reconnect(why)
                Freezer.ruleStart(rule, why)
                if (rule.unload) unloadBackground(why, keep = rule.launchApps, all = rule.unloadAll)
                launchApps(rule.launchApps, why)
                OneShots.run(rule.note, rule.http, rule.shell, why)
            } else if (rule.unload) {
                unloadBackground(why, keep = rule.launchApps, all = rule.unloadAll)
            }
            freezeApps(rule, why)
        }
        DeferWatch.sync()
    }

    /**
     * The extra conditions of rules have changed ([kinds] of them; null: any): a rule whose
     * trigger holds starts once they all hold, and one in force ends when one of them goes.
     * Returns whether anything was done.
     */
    suspend fun onExtras(kinds: Set<Trigger>?): Boolean = lock.withLock {
        val rules = Prefs.appRules.filter { r ->
            r.enabled && r.hasActions && r.extras.isNotEmpty() && (kinds == null || r.extras.any { it.kind.trigger in kinds })
        }
        var acted = false
        for (rule in rules) {
            val extras = ConditionWatch.extrasHold(rule)
            val open = if (rule.restores) rule.id in Prefs.openWindows else rule.id in Prefs.extraRan
            val step = ExtraGate.step(open, if (extras == true && !open) primaryHolds(rule) else null, extras)
            when (step) {
                ExtraGate.Step.START -> {
                    eventState = 0
                    freezeLocked(rule, "${rule.timeLabel} — условия выполнены")
                    acted = true
                }
                ExtraGate.Step.END -> {
                    val gone = ConditionWatch.failingExtra(rule)?.label.orEmpty()
                    if (rule.restores) {
                        closeLocked(rule, rule.id, "${rule.timeLabel} — больше нет «$gone»")
                        acted = true
                    }
                    Prefs.extraRan = Prefs.extraRan - rule.id
                }
                ExtraGate.Step.NONE -> Unit
            }
        }
        acted
    }

    /**
     * Whether [rule]'s own trigger holds now, by its kind; null where that cannot be told — an
     * event or a press, rather than a state — and such a rule only starts with its trigger.
     */
    private suspend fun primaryHolds(rule: AppRule): Boolean? = when (rule.trigger) {
        in ConditionWatch.TRIGGERS -> (Prefs.appHandled[rule.id] ?: 0L) != 0L
        Trigger.TIME -> {
            val now = ZonedDateTime.now()
            rule.lastStart(now)?.let { rule.endAfter(it)?.isAfter(now) } ?: false
        }
        Trigger.WIFI -> rule.matchesSsid(Prefs.lastSsid)
        Trigger.BLUETOOTH -> rule.btAddress?.uppercase()?.let { it in BtDevices.connected(App.ctx) }
        Trigger.MOBILE_DATA -> DataWatch.enabled()
        Trigger.NET_MODE -> ModeController.modeOf(rule.triggerSlot)?.let { it == rule.triggerMode }
        else -> null
    }

    private fun dropAsked(id: Long) {
        Prefs.askedStarts = Prefs.askedStarts - id
        Prefs.openWindows = Prefs.openWindows - id
    }

    /** "Выполнить" on the question: the start that waited for it, if it still waits. */
    suspend fun runAsked(ruleId: Long) = lock.withLock {
        val rule = Prefs.appRules.find { it.id == ruleId }
        if (ruleId !in Prefs.askedStarts || rule == null || !rule.enabled) {
            Prefs.askedStarts = Prefs.askedStarts - ruleId
            return@withLock
        }
        eventState = 0
        freezeLocked(rule, "подтверждено", manual = true)
    }

    /** "Пропустить": the window closes as if it never opened; the condition must begin anew. */
    suspend fun skipAsked(ruleId: Long) = lock.withLock {
        if (ruleId !in Prefs.askedStarts) return@withLock
        dropAsked(ruleId)
        val rule = Prefs.appRules.find { it.id == ruleId }
        Prefs.log("(${rule?.logPrefix.orEmpty()}${rule?.timeLabel.orEmpty()}): пропущено")
        AlarmScheduler.rearm()
    }

    /** Ends a rule's window right now, the way the rule says to end it. */
    suspend fun releaseNow(ruleId: Long): Int = lock.withLock {
        val n = Prefs.frozen.count { it.ruleId == ruleId }
        closeLocked(Prefs.appRules.find { it.id == ruleId }, ruleId, "вручную")
        n
    }

    /** Marks the current window as handled so saving a rule never fires it on the spot. */
    fun markSaved(rule: AppRule) {
        if (!rule.isTime) return
        val start = rule.lastStart(ZonedDateTime.now()) ?: return
        Prefs.appHandled = Prefs.appHandled + (rule.id to start.toInstant().toEpochMilli())
    }

    /**
     * Connected to [ssid]: applies every matching "while connected" rule not yet applied for
     * this connection, and ends any "after leaving" window for it — the phone is back.
     * [newConnection] is false for the repeated callbacks of the same connection.
     */
    suspend fun onWifiConnected(ssid: String, newConnection: Boolean) = lock.withLock {
        beginEvent()
        val matching = Prefs.appRules.filter { it.matchesSsid(ssid) }
        releaseLocked(matching.filter { it.trigger == Trigger.WIFI_LEFT }, "вернулся в «$ssid»")

        val handled = Prefs.appHandled.toMutableMap()
        for (rule in matching.filter { it.enabled && it.isWifi && it.hasActions }) {
            if (!newConnection && (handled[rule.id] ?: 0L) != 0L) continue
            handled[rule.id] = System.currentTimeMillis()
            Prefs.appHandled = handled
            freezeLocked(rule, "Wi-Fi «$ssid» подключён")
        }
    }

    /**
     * Left [ssid]. With [adapterOff] only rules that count switching Wi-Fi off as leaving act;
     * the rest stay as they are and are not re-applied on the reconnect that follows.
     * Returns true when some rule was kept, so the caller keeps remembering the network.
     */
    suspend fun onWifiLost(ssid: String, adapterOff: Boolean): Boolean = lock.withLock {
        beginEvent()
        val matching = Prefs.appRules.filter { it.enabled && it.hasActions && it.matchesSsid(ssid) }
        val (acting, kept) = matching.partition { !adapterOff || it.leaveOnWifiOff }
        if (kept.isNotEmpty()) Prefs.log("Wi-Fi выключен целиком — правила «$ssid» оставлены как есть")

        val connected = acting.filter { it.isWifi }
        Prefs.appHandled = Prefs.appHandled - connected.map { it.id }.toSet()
        releaseLocked(
            connected.filter { it.releaseOnLeave },
            if (adapterOff) "Wi-Fi выключен" else "Wi-Fi «$ssid» отключён",
        )
        for (rule in acting.filter { it.trigger == Trigger.WIFI_LEFT }) {
            freezeLocked(rule, "ушёл из «$ssid»")
        }
        kept.isNotEmpty()
    }

    /** A Bluetooth device connected: applies the rules bound to it. */
    suspend fun onBtConnected(address: String, name: String?) = lock.withLock {
        beginEvent()
        val handled = Prefs.appHandled.toMutableMap()
        for (rule in Prefs.appRules.filter {
            it.enabled && it.hasActions && it.trigger == Trigger.BLUETOOTH && it.btAddress.equals(address, true)
        }) {
            if ((handled[rule.id] ?: 0L) != 0L) continue
            handled[rule.id] = System.currentTimeMillis()
            Prefs.appHandled = handled
            freezeLocked(rule, "подключено «${name ?: address}»")
        }
    }

    /**
     * A Bluetooth device disconnected: ends the rules bound to it, and any "after leaving
     * Wi-Fi" window set to close on it — arriving home by car.
     */
    suspend fun onBtDisconnected(address: String, name: String?) = lock.withLock {
        val rules = Prefs.appRules
        val bound = rules.filter { it.trigger == Trigger.BLUETOOTH && it.btAddress.equals(address, true) }
        Prefs.appHandled = Prefs.appHandled - bound.map { it.id }.toSet()
        // Bluetooth switched off by a rule drops every device: that is not arriving by car.
        val selfOff = System.currentTimeMillis() - Prefs.selfBtAt < SELF_WIFI_WINDOW_MS
        val closing = bound.filter { it.releaseOnLeave } +
            if (selfOff) emptyList()
            else rules.filter { it.trigger == Trigger.WIFI_LEFT && it.endBtAddress.equals(address, true) }
        releaseLocked(closing, "отключено «${name ?: address}»")
    }

    private fun sourceOf(@Suppress("UNUSED_PARAMETER") rule: AppRule?): ModeController.Source =
        ModeController.Source.SCHEDULE

    /**
     * A SIM's network mode changed: opens the "Сеть" rules waiting for this mode on this SIM
     * and closes those that were waiting for the mode it just left.
     */
    suspend fun onNetModeChanged(slot: Int, mode: NetMode) = lock.withLock {
        val rules = Prefs.appRules.filter { it.trigger == Trigger.NET_MODE && it.triggerSlot == slot }
        if (rules.isEmpty()) return@withLock
        beginEvent()
        val (matching, others) = rules.partition { it.triggerMode == mode }
        val open = Prefs.appHandled
        val leaving = others.filter { (open[it.id] ?: 0L) != 0L }
        Prefs.appHandled = Prefs.appHandled - leaving.map { it.id }.toSet()
        releaseLocked(leaving.filter { it.releaseOnLeave }, "SIM ${slot + 1} теперь ${mode.short}")
        val handled = Prefs.appHandled.toMutableMap()
        for (rule in matching.filter { it.enabled && it.hasActions }) {
            if ((handled[rule.id] ?: 0L) != 0L) continue
            handled[rule.id] = System.currentTimeMillis()
            Prefs.appHandled = handled
            freezeLocked(rule, "SIM ${slot + 1} → ${mode.short}")
        }
    }

    /** Mobile data switched on or off: opens or closes the rules bound to it. */
    suspend fun onDataChanged(on: Boolean) = lock.withLock {
        beginEvent()
        val rules = Prefs.appRules.filter { it.trigger == Trigger.MOBILE_DATA }
        if (on) {
            val handled = Prefs.appHandled.toMutableMap()
            for (rule in rules.filter { it.enabled && it.hasActions }) {
                if ((handled[rule.id] ?: 0L) != 0L) continue
                handled[rule.id] = System.currentTimeMillis()
                Prefs.appHandled = handled
                freezeLocked(rule, "мобильный интернет включён")
            }
        } else {
            Prefs.appHandled = Prefs.appHandled - rules.map { it.id }.toSet()
            releaseLocked(rules.filter { it.releaseOnLeave }, "мобильный интернет выключен")
        }
    }

    /**
     * The state triggers of [ConditionWatch]: opens the rules of [triggers] whose condition
     * now holds and closes those whose condition stopped holding. [holds] gets the rule and
     * the moment its window opened (0 when closed); null means "cannot tell, leave it".
     */
    suspend fun onConditions(triggers: Set<Trigger>, holds: (AppRule, Long) -> Boolean?) = lock.withLock {
        // Unlocked nights whose time is now over: the end of the time.
        if (Trigger.LOCKED in triggers) closeOverNights()
        val rules = Prefs.appRules.filter { it.trigger in triggers }
        if (rules.isEmpty()) return@withLock
        val handled = Prefs.appHandled
        val leaving = rules.filter { r ->
            val since = handled[r.id] ?: 0L
            since != 0L && (!r.enabled || holds(r, since) == false)
        }
        val entering = rules.filter { r ->
            r.enabled && r.hasActions && (handled[r.id] ?: 0L) == 0L && holds(r, 0L) == true
        }
        if (leaving.isEmpty() && entering.isEmpty()) return@withLock
        beginEvent()
        Prefs.appHandled = Prefs.appHandled - leaving.map { it.id }.toSet()
        // A rule with no end has no window to close: its unanswered question goes here.
        for (rule in leaving.filter { !it.releaseOnLeave && it.id in Prefs.askedStarts }) {
            RunNotices.cancel(rule.id)
            dropAsked(rule.id)
        }
        // From one app straight into another: the new app's rule starts first, so whatever both
        // set — the VPN on — is handed over at the old one's end instead of put back and set
        // again a moment later.
        val (handover, rest) = entering.partition { it.trigger == Trigger.APP_OPEN }
        for (rule in handover) {
            Prefs.appHandled = Prefs.appHandled + (rule.id to System.currentTimeMillis())
            freezeLocked(rule, rule.timeLabel)
        }
        val now = ZonedDateTime.now()
        for (rule in leaving.filter { it.releaseOnLeave }) {
            // Unlocked inside its time: its unlock block, and the night goes on.
            val spec = rule.unlockEnd?.takeIf { rule.enabled && rule.splitEnd && rule.inLockWindow(now) }
            if (spec != null && rule.id in Prefs.openWindows) unlockLocked(rule, spec, "${rule.timeLabel} — разблокирован")
            else releaseLocked(listOf(rule), "${rule.timeLabel} — больше нет")
        }
        for (rule in rest) {
            Prefs.appHandled = Prefs.appHandled + (rule.id to System.currentTimeMillis())
            freezeLocked(rule, rule.timeLabel)
        }
    }

    /**
     * A SIM came on ([slot]) while rules are in force: it gets the network mode of the newest
     * one that sets it, recorded like the start's change so the end puts it back. A night unlocked
     * back to normal leaves it be. Returns the mode it should be in, null when no rule sets one.
     * [recheck]: a second look a little later, writing again only if the modem reset it.
     */
    suspend fun onSimAdded(slot: Int, recheck: Boolean = false): NetMode? = lock.withLock {
        val rules = Prefs.appRules.associateBy { it.id }
        val paused = Prefs.lockPaused
        val (rule, mode) = Prefs.openWindows.entries.sortedByDescending { it.value }
            .mapNotNull { rules[it.key] }
            .filter { it.enabled && it.restores && it.id !in Prefs.askedStarts && (it.netSlot == SLOT_ALL || it.netSlot == slot) }
            .firstNotNullOfOrNull { r ->
                val spec = r.unlockEnd?.takeIf { r.id in paused }
                // Unlocked inside the time: the network is what the unlock block left it.
                val m = when (spec?.mode) {
                    null, EndMode.NOTHING -> r.netMode
                    EndMode.CUSTOM -> spec.net ?: r.netMode
                    EndMode.RESTORE -> null
                }
                m?.let { r to it }
            } ?: return@withLock null
        if (!Privileged.available()) return@withLock mode
        if (recheck && runCatching { Telephony.readMask(slot) }.getOrDefault(-1L) == Profile.maskFor(mode, slot)) return@withLock mode
        acted()
        val why = rule.logPrefix + if (recheck) "SIM ${slot + 1} сбросила режим" else "включена SIM ${slot + 1}"
        sideActions(rule.copy(netMode = mode), why, only = setOf(FrozenApp.NET + slot))
        mode
    }

    fun hasDataRules(): Boolean = Prefs.appRules.any { it.enabled && it.hasActions && it.trigger == Trigger.MOBILE_DATA }

    /** Whether some rule needs to hear about disconnects from [ssid]. */
    fun needsLoss(ssid: String): Boolean = Prefs.appRules.any {
        it.enabled && it.hasActions && it.matchesSsid(ssid) &&
            (it.trigger == Trigger.WIFI_LEFT || (it.isWifi && it.releaseOnLeave))
    }

    fun hasWifiRules(): Boolean = Prefs.appRules.any {
        it.enabled && it.hasActions && (it.isWifi || it.trigger == Trigger.WIFI_LEFT)
    }

    /** True for a moment after the app itself switched Wi-Fi or the hotspot. */
    fun changedWifiRecently(): Boolean =
        System.currentTimeMillis() - Prefs.selfWifiAt < SELF_WIFI_WINDOW_MS

    private suspend fun releaseLocked(rules: List<AppRule>, why: String) {
        val open = Prefs.openWindows
        val frozenIds = Prefs.frozen.map { it.ruleId }.toSet()
        for (rule in rules) {
            if (rule.id in open || rule.id in frozenIds) closeLocked(rule, rule.id, why)
        }
    }

    /**
     * Closes a rule's window. Stopped apps are always put back. Radios and network mode are
     * put back too, unless the rule has its own end state — then that is applied instead and
     * the recorded "before" states are dropped.
     */
    private suspend fun closeLocked(rule: AppRule?, id: Long, reason: String) {
        val why = rule?.logPrefix.orEmpty() + reason
        RunNotices.cancel(id)
        // Never confirmed: nothing was done, so nothing is put back and no end is run.
        if (id in Prefs.askedStarts) {
            dropAsked(id)
            Prefs.log("($why): не подтверждено — ничего не менялось")
            AlarmScheduler.rearm()
            return
        }
        if (!Privileged.available()) {
            // Nothing can be put back now; the window stays on record and reconcile retries.
            Prefs.pendingClose = Prefs.pendingClose + id
            Prefs.log("($why): нет root/Shizuku — верну, когда доступ появится")
            return
        }
        Prefs.pendingClose = Prefs.pendingClose - id
        Prefs.lockPaused = Prefs.lockPaused - id
        // The start's steps still waiting out a pause will not run now: the condition is over.
        if (StepQueue.cancel(id, end = false)) Prefs.log("($why): остальные шаги не выполнены — условие закончилось")
        StepQueue.cancel(id, end = true)
        // The end is never held back, and a start still waiting for the lock is now moot.
        if (id in Prefs.pendingStarts) Prefs.pendingStarts = Prefs.pendingStarts - id
        acted()
        val entries = Prefs.frozen.filter { it.ruleId == id }
        Prefs.openWindows = Prefs.openWindows - id
        // What the window thawed in the freezer freezes again.
        runCatching { Freezer.ruleEnd(id, why) }

        // Another rule still open over the same thing — 08–20 → 3G around 12–13 → 2G — gets
        // it back instead of the state from before both: its own value is applied again and
        // the original state waits for its end.
        val heir = heirs()
        val handed = entries.mapNotNull { e -> heir(e.pkg)?.let { e to it } }
        if (handed.isNotEmpty()) {
            val moved = handed.associate { (e, r) -> e.pkg to r.id }
            Prefs.frozen = Prefs.frozen.map { e -> if (e.ruleId == id && e.pkg in moved) e.copy(ruleId = moved.getValue(e.pkg)) else e }
            for ((heirRule, keys) in handed.groupBy({ it.second }, { it.first.pkg })) {
                val pseudo = keys.filter { it.startsWith("#") }.toSet()
                if (pseudo.isNotEmpty()) sideActions(heirRule, "$why → снова действует ${heirRule.name?.let { "«$it»" } ?: heirRule.timeLabel}", only = pseudo)
            }
        }
        val remaining = entries.filterNot { e -> handed.any { it.first.pkg == e.pkg } }
        val custom = rule != null && rule.enabled && rule.endCustom
        val toRestore = if (custom) remaining.filterNot { it.isPseudo } else remaining
        if (custom) Prefs.frozen = Prefs.frozen.filterNot { it.ruleId == id && it.isPseudo }

        if (toRestore.isNotEmpty()) {
            runCatching { restore(toRestore) }
                .onSuccess { failed ->
                    Prefs.log("($why): возвращено как было (${toRestore.size})" + if (failed > 0) ", ошибок $failed" else "")
                }
                .onFailure { Prefs.log("($why): не удалось вернуть — ${it.message}") }
        }
        if (rule != null && rule.enabled && rule.endSteps.isNotEmpty()) {
            unstop(rule.unstopApps.filter { heir(it) == null }, why)
            runSteps(rule, end = true, from = 0, why = why)
            AlarmScheduler.rearm()
            return
        }
        if (custom) applyEnd(rule!!, why, skip = { key -> heir(key) != null })
        if (rule != null) unstop(rule.unstopApps.filter { heir(it) == null }, why)
        // Whichever way the window ends: "left the car — the navigator closes". Held (a
        // background ban, frozen) until the rule's next start.
        if (rule != null && rule.enabled) {
            freezeApps(rule, why, keys = rule.endStopApps.filter { heir(it) == null }, methodOf = rule::endMethodOf,
                owner = endOwner(rule.id), message = App.ctx.getString(R.string.suspend_message, ""))
            if (rule.endUnload) unloadBackground(why, keep = rule.endLaunchApps, all = rule.endUnloadAll)
            launchApps(rule.endLaunchApps, why)
            OneShots.run(rule.endNote, rule.endHttp, rule.endShell, why)
        }
        AlarmScheduler.rearm()
    }

    /**
     * Unloads the background, as "Очистить всё" in the recents does, for every app with a
     * launcher icon — preinstalled ones too, but not the system's own services: Google Play
     * services and the like come straight back and cost more than they free.
     *
     * Normally (am kill) only what the system itself counts as background goes: cached
     * processes and background services. What is on screen or perceptible stays — music,
     * navigation, a VPN, anything with a foreground service.
     *
     * With [all] those go too (force-stop), except the app on screen, and each stopped app
     * is let out of the "stopped" state at once, so its push keeps arriving. Never
     * force-stopped: the alarm clock, the dialer and the SMS app (alarms and calls would go),
     * and whatever the system or the user bound to a job — the VPN, a local proxy such as the
     * one the hotspot runs through, accessibility services, notification listeners, autofill,
     * device admins, a live wallpaper. Stopping those switches off what they were set up for.
     *
     * Never touched either way: the user's "Не выгружать" list, apps locked in the recents
     * (the padlock), the launcher, the
     * keyboard, this app, the rules' VPN apps, apps a rule holds, and [keep]. Returns how many
     * apps had their processes ended.
     */
    suspend fun unloadBackground(why: String, keep: List<String> = emptyList(), all: Boolean = false): Int {
        val ctx = App.ctx
        val pm = ctx.packageManager
        fun pkgOf(intent: android.content.Intent) = runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
        val home = pkgOf(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME))
        val keyboard = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        val launchable = runCatching {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val protected = if (!all) emptyList() else listOfNotNull(
            runCatching { ctx.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.showIntent?.creatorPackage }.getOrNull(),
            pkgOf(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)),
            runCatching { ctx.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull(),
            runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull(),
            runCatching { ctx.getSystemService(android.app.WallpaperManager::class.java).wallpaperInfo?.packageName }.getOrNull(),
        ) + if (!all) emptyList() else boundBySystem()
        // Whole packages: the system's roles, which have no clones. Everything the user chose
        // — exceptions, a rule's apps — is per copy: "pkg#user", the clone apart from the original.
        val skipAll = (protected + Prefs.appRules.mapNotNull { it.vpnApp }.map { AppKey.pkg(it) } +
            listOfNotNull(ctx.packageName, home, keyboard)).toSet()
        fun shellKey(key: String) = "${AppKey.pkg(key)}#${AppKey.user(key)}"
        val skipKeys = (keep + Freezer.protectedKeys() + Prefs.unloadExclude + Prefs.frozen.filterNot { it.isPseudo }.map { it.pkg })
            .filter { AppKey.valid(it) }.map(::shellKey).toSet()
        // One shell pass: those apps' running processes, before and after. In the "all" mode
        // the app on screen is read there too, at the last moment.
        val script = """
apps=" ${launchable.joinToString(" ")} "
skipall=" ${skipAll.joinToString(" ")} "
skipkeys=" ${skipKeys.joinToString(" ")} "
# Padlocks per app and user ("pkg#user"); one counts only where the app is installed there.
entries=${'$'}(cat $RECENTS_LOCK 2>/dev/null | grep -oE '\{[^}]*\}' | grep -E '"scenes":(1|2)[,}]' | grep -oE '"packageNameUserId":"[^"]+' | cut -d'"' -f4)
locked=""
for u in ${'$'}(echo "${'$'}entries" | grep '#' | cut -d'#' -f2 | sort -u); do
    inst=" ${'$'}(pm list packages --user ${'$'}u 2>/dev/null | cut -d: -f2 | tr '\n' ' ') "
    for e in ${'$'}(echo "${'$'}entries" | grep "#${'$'}u${'$'}"); do
        case "${'$'}inst" in *" ${'$'}{e%#*} "*) locked="${'$'}locked ${'$'}e" ;; esac
    done
done
echo "@K ${'$'}(echo ${'$'}locked | wc -w)"
skipkeys="${'$'}skipkeys ${'$'}locked "
# Every running app process as "pkg#user": the user from its uid name, u0_a123 / u999_a123.
running() { ps -A -o USER=,NAME= | while read uname pname; do
    case "${'$'}uname" in u[0-9]*_*) ;; *) continue ;; esac
    u=${'$'}{uname#u}; u=${'$'}{u%%_*}; p=${'$'}{pname%%:*}
    case "${'$'}apps" in *" ${'$'}p "*) ;; *) continue ;; esac
    case "${'$'}skipall" in *" ${'$'}p "*) continue ;; esac
    case "${'$'}skipkeys" in *" ${'$'}p#${'$'}u "*) continue ;; esac
    echo "${'$'}p#${'$'}u"
done | sort -u; }
before=${'$'}(running)
${if (all) """
# Network jobs: the connected VPN app, and apps serving a proxy on 127.0.0.1 (per user, by uid).
vpn=${'$'}(dumpsys connectivity 2>/dev/null | grep -oE 'VPN CONNECTED extra: VPN:[A-Za-z0-9_.]+' | cut -d: -f3)
net=""
for uid in ${'$'}(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '${'$'}4=="0A" && (${'$'}2 ~ /^0100007F:/ || ${'$'}2 ~ /0000000000000000FFFF00000100007F:/) {print ${'$'}8}' | sort -u); do
    [ "${'$'}uid" -ge 10000 ] 2>/dev/null || continue
    p=${'$'}(pm list packages -U 2>/dev/null | grep " uid:${'$'}((uid % 100000))${'$'}" | head -1 | cut -d: -f2 | cut -d' ' -f1)
    [ -n "${'$'}p" ] && net="${'$'}net ${'$'}p#${'$'}((uid / 100000))"
done
# The app on screen, with its user: "... u0 com.app/.Main ...".
top=${'$'}(dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -oE 'u[0-9]+ [A-Za-z0-9_.]+/' | head -1 | sed 's/^u\([0-9]*\) \(.*\)\/${'$'}/\2#\1/')
for k in ${'$'}before; do
    p=${'$'}{k%#*}; u=${'$'}{k#*#}
    [ "${'$'}k" = "${'$'}top" ] && continue
    case " ${'$'}net " in *" ${'$'}k "*) continue ;; esac
    case " ${'$'}vpn " in *" ${'$'}p "*) continue ;; esac
    ${if (Compat.SDK >= 34) """am force-stop --user ${'$'}u "${'$'}p" >/dev/null 2>&1
    pm unstop --user ${'$'}u "${'$'}p" >/dev/null 2>&1""" else
    // No "unstop" before Android 14: a force-stop would cut push until the app is opened. The
    // processes are ended instead (root), which leaves the app free to start again on its own.
    """for pid in ${'$'}(ps -A -o PID=,USER=,NAME= | awk -v n="${'$'}p" -v u="u${'$'}{u}_" 'index(${'$'}2,u)==1 && (${'$'}3==n || index(${'$'}3,n":")==1) {print ${'$'}1}'); do kill -9 ${'$'}pid 2>/dev/null; done
    am kill --user ${'$'}u "${'$'}p" >/dev/null 2>&1"""}
done
""" else """
for k in ${'$'}before; do cmd activity kill --user ${'$'}{k#*#} "${'$'}{k%#*}" >/dev/null 2>&1; done
"""}
sleep 1
after=${'$'}(running)
echo "@B ${'$'}(echo "${'$'}before" | grep -c .) @A ${'$'}(echo "${'$'}after" | grep -c .)"
""".trimIndent()
        val what = if (all) "всё выгружено" else "фон выгружен"
        val out = runCatching { Privileged.exec(script) }.getOrElse {
            Prefs.log("($why): выгрузить не удалось — ${it.message}")
            return 0
        }
        val b = Regex("""@B (\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val a = Regex("""@A (\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: b
        val n = (b - a).coerceAtLeast(0)
        val locked = Regex("""@K (\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        Prefs.log("($why): $what — закрыто приложений: $n" + (if (a > 0) ", работают дальше: $a" else "") +
            if (locked > 0) ", закреплённые замком не тронуты: $locked" else "")
        return n
    }

    /** Apps holding a job the user set up: accessibility, notification access, autofill, contactless payment, admin. */
    private fun boundBySystem(): List<String> {
        val ctx = App.ctx
        fun secure(key: String) = runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, key) }.getOrNull()
        val components = listOf("enabled_accessibility_services", "enabled_notification_listeners", "autofill_service",
            "nfc_payment_default_component")
            .flatMap { secure(it)?.split(':').orEmpty() }
            .map { it.substringBefore('/') }
        val admins = runCatching {
            ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java).activeAdmins?.map { it.packageName }
        }.getOrNull().orEmpty()
        return (components + admins).filter { it.isNotBlank() }
    }

    /**
     * OxygenOS / ColorOS: the launcher's padlock states for the recents, one JSON object per
     * app and user — {"packageNameUserId":"pkg#0","scenes":N}. Measured on OxygenOS 16:
     * scenes 1 is locked by default (the system's own list: Gmail, WhatsApp…), 2 locked by
     * the user, 4 unlocked by the user; 16 and 32 are the system's own entries. The copy in
     * /data/oplus/os/battery/recent_lock.xml is not it: it does not follow an unlock.
     * A glob, as ColorOS names its launcher differently. Read through init's root: the root
     * shell started from this app sees storage as this app does, and app data isolation hides
     * the launcher's folder there.
     */
    private const val RECENTS_LOCK = "/proc/1/root/data/user_de/0/*/files/oplus/recenttask/app_lock_data_file_name"

    /** Owner of the apps a rule holds from its end until its next start. */
    fun endOwner(ruleId: Long): Long = -ruleId

    /**
     * Opens apps by their launcher activity. Through the privileged shell, which may start
     * activities from the background — the app itself may not since Android 10.
     */
    private suspend fun launchApps(keys: List<String>, why: String) {
        val pm = App.ctx.packageManager
        val cmds = keys.filter { AppKey.valid(it) }.mapNotNull { k ->
            val c = runCatching { pm.getLaunchIntentForPackage(AppKey.pkg(k))?.component }.getOrNull() ?: return@mapNotNull null
            "am start --user ${AppKey.user(k)} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${c.flattenToShortString()} >/dev/null 2>&1"
        }
        if (cmds.isEmpty()) return
        runCatching { Privileged.exec(cmds.joinToString("\n")) }
            .onSuccess { Prefs.log("($why): открыто — " + AppPickerActivity.labels(keys).joinToString(", ")) }
            .onFailure { Prefs.log("($why): открыть не удалось — ${it.message}") }
    }

    /**
     * pm unstop: the app leaves the "stopped" state a force-stop put it in, so push messages
     * and system events are delivered again. It is not launched and nothing appears on screen.
     * Measured on OxygenOS 16: works as shell and root, for clones with --user.
     */
    private suspend fun unstop(keys: List<String>, why: String) {
        val held = Prefs.frozen.filter { it.suspended }.map { it.pkg }.toSet()
        val targets = keys.filter { AppKey.valid(it) && it !in held }
        if (targets.isEmpty()) return
        runCatching {
            Privileged.exec(targets.joinToString("\n") {
                "pm unstop --user ${AppKey.user(it)} ${AppKey.pkg(it)} >/dev/null 2>&1"
            })
        }.onSuccess { Prefs.log("($why): push и фоновая работа снова разрешены (${targets.size})") }
    }

    /** The rule's own end state, applied as absolute values. */
    /**
     * [record]: what this changes is remembered like the start's changes, so the end of the time
     * puts it back too — for the unlock block, whose window stays open.
     */
    private suspend fun applyEnd(rule: AppRule, why: String, skip: (String) -> Boolean = { false }, record: Boolean = false) {
        // The unlock block of a lock rule runs through here too: its log says which it was.
        val label = if (rule.id in Prefs.lockPaused) "при разблокировке" else "в конце"
        val done = mutableListOf<String>()
        val noted = mutableListOf<FrozenApp>()
        val since = System.currentTimeMillis()
        val taken = mutableSetOf<String>()
        // Only the first change of a thing is its "before"; one already on record keeps its own,
        // and is taken over from another rule as the start does — at the end it is handed back.
        fun note(key: String, prev: String) {
            if (!record || noted.any { it.pkg == key }) return
            val held = Prefs.frozen.firstOrNull { it.pkg == key }
            if (held == null) noted += FrozenApp(key, rule.id, since, null, null, false, prev)
            else if (held.ruleId != rule.id) taken += key
        }
        try {
            rule.endNet?.let { mode ->
                for (slot in Sims.resolve(rule.netSlot).filterNot { skip(FrozenApp.NET + it) }) {
                    val mask = if (record) runCatching { Telephony.readMask(slot) }.getOrDefault(-1L) else -1L
                    ModeController.apply(slot, mode, sourceOf(rule))
                        .onSuccess {
                            done += "SIM ${slot + 1} → ${mode.short}"
                            if (mask > 0 && mask != Profile.maskFor(mode, slot)) note(FrozenApp.NET + slot, NetMode.exact(mask)?.name ?: "0x%X".format(mask))
                        }
                        .onFailure { done += "SIM ${slot + 1}: ошибка ${it.message}" }
                }
            }
            val state = Privileged.exec(STATE_SCRIPT).lines()
            fun flag(tag: String) = state.firstOrNull { it.startsWith("@$tag ") }?.substringAfter(' ')?.trim()
            val locLine = flag("LOC").orEmpty()
            val now = mapOf(
                "data" to (flag("DATA") == "1"),
                "air" to (flag("AIR") == "1"),
                "ps" to (flag("PS") == "1"),
                "wifi" to flag("WIFI").let { it != null && it != "0" },
                "ap" to (flag("AP") == "1"),
                "bt" to (flag("BT") == "1"),
                "loc" to (locLine.startsWith("true") ||
                    (!locLine.startsWith("false") && locLine.split(' ').lastOrNull()?.let { it != "0" && it != "null" } == true)),
            )
            val script = StringBuilder()
            val keyOf = mapOf(
                "data" to FrozenApp.DATA, "ap" to FrozenApp.HOTSPOT, "wifi" to FrozenApp.WIFI,
                "bt" to FrozenApp.BLUETOOTH, "loc" to FrozenApp.LOCATION,
                "air" to FrozenApp.AIRPLANE, "ps" to FrozenApp.POWERSAVE,
            )
            fun set(key: String, want: Toggle?, on: String, off: String, name: String) {
                val w = want ?: return
                if (skip(keyOf.getValue(key))) return
                val target = w == Toggle.ON
                if (now[key] == target) return
                // Bluetooth and location are only ever put back on: an unlock turning them on
                // has nothing to put back.
                if (key != "bt" && key != "loc") note(keyOf.getValue(key), if (now[key] == true) "on" else "off")
                script.appendLine(if (target) on else off)
                done += "$name ${if (target) "вкл" else "выкл"}"
            }
            set("data", rule.endData, DATA_ON, DATA_OFF, "моб. интернет")
            val endVia = rule.endHotspotVia?.takeIf { rule.endHotspot == Toggle.ON && !skip(FrozenApp.HOTSPOT) }
            if (endVia != null && endVia != Prefs.tetherMode) {
                note(FrozenApp.TETHER_MODE, Prefs.tetherMode.name)
                Prefs.tetherMode = endVia
                TetherWatch.sync()
            }
            set("ap", rule.endHotspot, hotspot(true), hotspot(false), "точка доступа")
            set("wifi", rule.endWifi, WIFI_ON, WIFI_OFF, "Wi-Fi")
            if (rule.wakeWifi && rule.endWifi == Toggle.OFF) Prefs.wakeArmed = true
            set("bt", rule.endBt, BT_ON, BT_OFF, "Bluetooth")
            if (rule.endBt == Toggle.OFF && !skip(FrozenApp.BLUETOOTH)) Prefs.selfBtAt = System.currentTimeMillis()
            set("loc", rule.endLoc, LOC_ON, LOC_OFF, "геолокация")
            set("air", rule.endAirplane, AIR_ON, AIR_OFF, "режим полёта")
            set("ps", rule.endPowerSave, PS_ON, PS_OFF, "энергосбережение")
            rule.endDataSim?.takeUnless { skip(FrozenApp.DATASIM) || it == DualSim.dataSlot() }?.let { slot ->
                DualSim.dataSlot()?.let { note(FrozenApp.DATASIM, it.toString()) }
                dataSimCmd(slot)?.let { script.appendLine(it); done += "интернет через SIM ${slot + 1}" }
            }
            if (rule.endWifi != null || rule.endHotspot != null || rule.endAirplane != null) Prefs.selfWifiAt = System.currentTimeMillis()
            if (script.isNotBlank()) Privileged.exec(script.toString())
            if (endVia != null) done += applyTether()
            val endTiles = rule.endTiles.filterKeys { !skip(FrozenApp.TILE + it) }.mapValues { it.value == Toggle.ON }
            if (endTiles.isNotEmpty()) done += Tiles.set(endTiles) { c, wasOn ->
                if (wasOn != endTiles[c]) note(FrozenApp.TILE + c, if (wasOn) "on" else "off")
            }
            val vpnBefore = if (record && rule.endVpn != null) runCatching { VpnControl.owner() }.getOrNull() else null
            if (record && rule.endVpn != null && (rule.endVpn == Toggle.OFF) == (vpnBefore != null)) note(FrozenApp.SYSVPN, vpnBefore ?: "off")
            when (rule.endVpn.takeUnless { skip(FrozenApp.SYSVPN) }) {
                Toggle.ON -> done += rule.vpnApp?.let { VpnControl.on(it) } ?: "VPN: не выбрано приложение"
                Toggle.OFF -> done += VpnControl.off()
                null -> Unit
            }
            for ((name, value) in rule.endSys) {
                val key = SysKey.of(name) ?: continue
                if (skip(FrozenApp.SYS + name)) continue
                SysSettings.read(key)?.let { note(FrozenApp.SYS + name, it) }
                runCatching { SysSettings.apply(key, value) }
                    .onSuccess { done += key.title.lowercase() + ": " + key.optionLabel(value) }
                    .onFailure { done += key.title.lowercase() + ": ошибка ${it.message}" }
            }
            if (noted.isNotEmpty() || taken.isNotEmpty()) {
                Prefs.frozen = Prefs.frozen.map { if (it.pkg in taken) it.copy(ruleId = rule.id) else it } + noted
            }
            Prefs.log("($why): $label — " + done.ifEmpty { listOf("всё уже как нужно") }.joinToString(", "))
        } catch (e: Exception) {
            Prefs.log("($why): $label — ошибка ${e.message}")
            Log.w(TAG, "end actions failed", e)
        }
    }

    /**
     * Unlocked inside a lock rule's time ([AppRule.splitEnd]): [spec] instead of the end. The
     * window stays open — what the rule recorded is kept for the end of the time — and locking
     * again starts the rule again.
     */
    private suspend fun unlockLocked(rule: AppRule, spec: EndSpec, reason: String) {
        val why = rule.logPrefix + reason
        if (!Privileged.available()) {
            Prefs.log("($why): нет root/Shizuku — при разблокировке ничего не сделано")
            Prefs.lockPaused = Prefs.lockPaused + rule.id
            return
        }
        if (StepQueue.cancel(rule.id, end = false)) Prefs.log("($why): остальные шаги не выполнены — разблокирован")
        StepQueue.cancel(rule.id, end = true)
        acted()
        Prefs.lockPaused = Prefs.lockPaused + rule.id
        // Its own window stays open through the unlock: not an heir of itself.
        val heir = heirs(except = rule.id)
        val entries = Prefs.frozen.filter { it.ruleId == rule.id && heir(it.pkg) == null }
        // "Вернуть как было" puts back everything; by hand, only the apps — the radios get the
        // block's own values, and their "before" waits for the end of the time.
        val back = when (spec.mode) {
            EndMode.NOTHING -> emptyList()
            EndMode.RESTORE -> entries.filter { it.isPseudo || !spec.keepApps }
            EndMode.CUSTOM -> entries.filter { !it.isPseudo && !spec.keepApps }
        }
        if (back.isNotEmpty()) {
            runCatching { restore(back) }
                .onSuccess { failed -> Prefs.log("($why): возвращено как было (${back.size})" + if (failed > 0) ", ошибок $failed" else "") }
                .onFailure { Prefs.log("($why): не удалось вернуть — ${it.message}") }
        }
        val until = rule.endHour?.let { "%02d:%02d".format(it, rule.endMinute ?: 0) }.orEmpty()
        if (spec.mode == EndMode.NOTHING) {
            Prefs.log("($why): ничего не меняю до $until или до следующей блокировки")
        } else {
            if (back.any { !it.isPseudo }) unstop(rule.unstopApps, why)
            val r = rule.withEnd(spec)
            if (r.endSteps.isNotEmpty()) runSteps(r, end = true, from = 0, why = why)
            else {
                // Set by hand for this moment: the latest event wins over other open rules.
                if (spec.mode == EndMode.CUSTOM) applyEnd(r, why, record = true)
                freezeApps(r, why, keys = r.endStopApps.filter { heir(it) == null }, methodOf = r::endMethodOf,
                    owner = endOwner(rule.id), message = App.ctx.getString(R.string.suspend_message, ""))
                if (r.endUnload) unloadBackground(why, keep = r.endLaunchApps, all = r.endUnloadAll)
                launchApps(r.endLaunchApps, why)
                OneShots.run(r.endNote, r.endHttp, r.endShell, why)
            }
        }
        // The end of the time is due even with the phone unlocked: its alarm.
        ConditionWatch.scheduleScreenAlarm(App.ctx)
    }

    /** Unlocked nights whose time is over, or whose rule changed: the end. True when any closed. */
    private suspend fun closeOverNights(): Boolean {
        val paused = Prefs.lockPaused
        if (paused.isEmpty()) return false
        val now = ZonedDateTime.now()
        val rules = Prefs.appRules.associateBy { it.id }
        var closed = false
        for (id in paused) {
            val r = rules[id]
            if (r != null && r.enabled && r.splitEnd && r.inLockWindow(now)) continue
            closeLocked(r, id, when {
                r == null -> "правило удалено"
                !r.enabled -> "правило выключено"
                else -> "${r.timeLabel} — время вышло"
            })
            closed = true
        }
        return closed
    }

    /**
     * Another rule still open over the same thing — 08–20 → 3G around 12–13 → 2G — gets it
     * back at an end instead of the state from before both. The newest open window wins.
     */
    private fun heirs(except: Long? = null): (String) -> AppRule? {
        val rulesById = Prefs.appRules.associateBy { it.id }
        // A rule still waiting for "Выполнить" has done nothing yet: it inherits nothing.
        val asked = Prefs.askedStarts
        val openOthers = Prefs.openWindows.entries.sortedByDescending { it.value }
            .mapNotNull { rulesById[it.key] }.filter { it.enabled && it.restores && it.id != except && it.id !in asked }
        return { key -> openOthers.firstOrNull { it.covers(key) } }
    }

    /**
     * Runs [rule]'s start steps, or its end steps when [end], from [from] on, one after the
     * other. At a pause the rest is handed to [StepQueue] and comes back to [continueSteps].
     */
    private suspend fun runSteps(rule: AppRule, end: Boolean, from: Int, why: String) {
        val steps = if (end) rule.endSteps else rule.steps
        for (i in from until steps.size) {
            val step = steps[i]
            Steps.pauseOf(step)?.let { seconds ->
                if (i == steps.lastIndex) return
                StepQueue.schedule(rule, end, i + 1, seconds)
                Prefs.log("($why): пауза ${Steps.words(seconds)}")
                return
            }
            runCatching { if (end) endStep(rule, step, why) else startStep(rule, step, why) }
                .onFailure {
                    Prefs.log("($why): шаг не выполнен — ${it.message}")
                    Log.w(TAG, "step $step failed", it)
                }
        }
    }

    /** What one start step touches, as recorded for putting it back. */
    private fun keysOf(step: String, tiles: Set<String>): Set<String> = when (step) {
        "NET" -> (0..1).map { FrozenApp.NET + it }.toSet()
        "DATA" -> setOf(FrozenApp.DATA)
        "WIFI" -> setOf(FrozenApp.WIFI)
        "HOTSPOT" -> setOf(FrozenApp.HOTSPOT, FrozenApp.TETHER_MODE)
        "VPN" -> setOf(FrozenApp.SYSVPN)
        "DATASIM" -> setOf(FrozenApp.DATASIM)
        "AIRPLANE" -> setOf(FrozenApp.AIRPLANE)
        "POWERSAVE" -> setOf(FrozenApp.POWERSAVE)
        "BT" -> setOf(FrozenApp.BLUETOOTH)
        "LOC" -> setOf(FrozenApp.LOCATION)
        "TILES" -> tiles.map { FrozenApp.TILE + it }.toSet()
        "SYS" -> SysKey.entries.map { FrozenApp.SYS + it.name }.toSet()
        else -> emptySet()
    }

    /** When the mobile network was last reconnected (elapsed realtime). */
    @Volatile private var reconnectedAt = 0L

    /**
     * Airplane mode for [RECONNECT_PAUSE_MS] and back: the modem drops the network and
     * registers afresh. Wi-Fi and Bluetooth come back as they were by themselves.
     *
     * At most once per [RECONNECT_EVERY_MS]: airplane mode ends "no internet" for a moment, so
     * on a network that stays dead its rule would come round again half a minute later — and
     * reconnect, and come round, for as long as the network is dead.
     */
    private suspend fun reconnect(why: String) {
        val ctx = App.ctx
        if (android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            Prefs.log("($why): режим полёта уже включён — не переподключаю")
            return
        }
        val since = android.os.SystemClock.elapsedRealtime() - reconnectedAt
        if (reconnectedAt != 0L && since < RECONNECT_EVERY_MS) {
            Prefs.log("($why): моб. сеть переподключалась ${Steps.words((since / 1000).toInt())} назад — не чаще раза в 5 мин")
            return
        }
        reconnectedAt = android.os.SystemClock.elapsedRealtime()
        // Airplane mode takes Wi-Fi and Bluetooth down: that is not leaving.
        Prefs.selfWifiAt = System.currentTimeMillis()
        Prefs.selfBtAt = System.currentTimeMillis()
        try {
            Privileged.exec(AIR_ON)
            kotlinx.coroutines.delay(RECONNECT_PAUSE_MS)
        } finally {
            Prefs.selfWifiAt = System.currentTimeMillis()
            Prefs.selfBtAt = System.currentTimeMillis()
            Privileged.exec(AIR_OFF)
        }
        Prefs.log("($why): моб. сеть переподключена")
    }

    private suspend fun startStep(rule: AppRule, step: String, why: String) {
        when (step) {
            "APPS" -> freezeApps(rule, why)
            "LAUNCH" -> launchApps(rule.launchApps, why)
            "NOTE" -> OneShots.run(rule.note, null, null, why)
            "HTTP" -> OneShots.run(null, rule.http, null, why)
            "SHELL" -> OneShots.run(null, null, rule.shell, why)
            "UNLOAD" -> if (rule.unload) unloadBackground(why, keep = rule.launchApps, all = rule.unloadAll)
            "RECONNECT" -> if (rule.reconnect) reconnect(why)
            "ICE" -> Freezer.ruleStart(rule, why)
            else -> keysOf(step, rule.tiles.keys).takeIf { it.isNotEmpty() }?.let { sideActions(rule, why, only = it) }
        }
    }

    private suspend fun endStep(rule: AppRule, step: String, why: String) {
        val heir = heirs(except = rule.id)
        when (step) {
            "APPS" -> freezeApps(rule, why, keys = rule.endStopApps.filter { heir(it) == null }, methodOf = rule::endMethodOf,
                owner = endOwner(rule.id), message = App.ctx.getString(R.string.suspend_message, ""))
            "LAUNCH" -> launchApps(rule.endLaunchApps, why)
            "NOTE" -> OneShots.run(rule.endNote, null, null, why)
            "HTTP" -> OneShots.run(null, rule.endHttp, null, why)
            "SHELL" -> OneShots.run(null, null, rule.endShell, why)
            "UNLOAD" -> if (rule.endUnload) unloadBackground(why, keep = rule.endLaunchApps, all = rule.endUnloadAll)
            // States to set exist only in a custom end; "as it was" has put them back already.
            else -> if (rule.endCustom) {
                val keys = keysOf(step, rule.endTiles.keys)
                // The unlock block's values are set by hand for this moment: they win over other
                // open rules, and the end of the time puts them back.
                val unlocking = rule.id in Prefs.lockPaused
                if (keys.isNotEmpty()) applyEnd(rule, why, skip = { key -> key !in keys || (!unlocking && heir(key) != null) }, record = unlocking)
            }
        }
    }

    /** A pause is over: the steps after it, unless the rule has moved on in the meantime. */
    suspend fun continueSteps(p: PendingSteps) = lock.withLock {
        // Taken off the list first: the timer and the alarm both come here, the first one runs.
        if (Prefs.pendingSteps.none { it == p }) return@withLock
        Prefs.pendingSteps = Prefs.pendingSteps - p
        val saved = Prefs.appRules.find { it.id == p.ruleId } ?: return@withLock
        // An unlock block pausing: its steps are run as the rule's end, see unlockLocked.
        val rule = saved.unlockEnd?.takeIf { p.end && saved.id in Prefs.lockPaused }?.let { saved.withEnd(it) } ?: saved
        val why = rule.logPrefix + "после паузы"
        if (!rule.enabled) return@withLock
        if (StepQueue.sig(rule, p.end) != p.sig) {
            Prefs.log("($why): правило изменено — остальные шаги отменены")
            return@withLock
        }
        // A start whose window has closed meanwhile was cancelled there; this is a late echo.
        if (!p.end && rule.restores && rule.id !in Prefs.openWindows) return@withLock
        if (!Privileged.available()) {
            // Nothing can be done without access: tried again in a minute.
            Prefs.pendingSteps = Prefs.pendingSteps + p.copy(due = System.currentTimeMillis() + 60_000L)
            return@withLock
        }
        eventState = 0
        acted()
        runSteps(rule, p.end, p.next, why)
        AlarmScheduler.rearm()
    }

    private fun zoned(ms: Long): ZonedDateTime =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())

    fun frozenCount(ruleId: Long): Int = Prefs.frozen.count { it.ruleId == ruleId }

    /**
     * Whether [rule] is in force now: its window is open. The handled mark is not a sign of
     * it — for a Wi-Fi or Bluetooth rule it only says the event was seen, and it outlives a
     * window ended by hand.
     */
    fun isActive(rule: AppRule): Boolean = isActive(rule, Prefs.openWindows)

    fun isActive(rule: AppRule, open: Map<Long, Long>): Boolean = rule.enabled && rule.id in open

    /** Apps (not radios) a rule holds right now. */
    fun heldCount(ruleId: Long): Int =
        Prefs.frozen.count { (it.ruleId == ruleId || it.ruleId == endOwner(ruleId)) && !it.isPseudo }

    private suspend fun freezeLocked(rule: AppRule, reason: String, manual: Boolean = false) {
        val why = rule.logPrefix + reason
        if (!manual && !allowedByChain(rule.timeLabel)) return
        // "И ещё если": without them the trigger alone does not start the rule; onExtras does
        // once they come, if the trigger still holds by then.
        if (!manual && rule.extras.isNotEmpty()) {
            val failing = ConditionWatch.failingExtra(rule)
            if (failing != null) {
                Prefs.extraRan = Prefs.extraRan - rule.id
                Prefs.log("($why): ждёт — нужно «${failing.label}»")
                return
            }
            if (!rule.restores) Prefs.extraRan = Prefs.extraRan + rule.id
        }
        // No root or Shizuku yet (just rebooted): nothing could be applied, and opening the window
        // anyway would leave the rule "on" with nothing done — its event will not come again. The
        // start is put off unmarked, and AccessWait runs it once access is there.
        if (!Privileged.available()) {
            Prefs.appHandled = Prefs.appHandled - rule.id
            Prefs.extraRan = Prefs.extraRan - rule.id
            Prefs.log("($why): нет root/Shizuku — сработает, когда доступ появится")
            AccessWait.arm()
            return
        }
        acted()
        // Started again: whatever was still pausing, of this start or of the last end, is over.
        StepQueue.cancel(rule.id, end = false)
        StepQueue.cancel(rule.id, end = true)
        // Locked again inside the time: the night picks up where the unlock left it.
        Prefs.lockPaused = Prefs.lockPaused - rule.id
        if (rule.restores) Prefs.openWindows = Prefs.openWindows + (rule.id to System.currentTimeMillis())
        Prefs.askedStarts = Prefs.askedStarts - rule.id
        // "Спросить": the window opens, what it does waits for the answer (see runAsked).
        if (!manual && rule.notice == RunNotice.ASK) {
            if (RunNotices.ask(rule)) {
                Prefs.askedStarts = Prefs.askedStarts + rule.id
                Prefs.log("($why): жду ответа в уведомлении")
                AlarmScheduler.rearm()
                return
            }
            Prefs.log("($why): уведомления выключены — выполняю без вопроса")
        }
        if (!manual && rule.notice == RunNotice.NOTIFY) RunNotices.ran(rule)
        // In use right now: the window opens, but what it does waits for the lock.
        val defer = !manual && rule.waitLocked && inUse()
        // Apps its last end held come back now: the window they were held outside of opens.
        val endHeld = Prefs.frozen.filter { it.ruleId == endOwner(rule.id) }
        if (endHeld.isNotEmpty()) {
            runCatching { restore(endHeld) }
                .onSuccess { Prefs.log("($why): возвращены приложения, остановленные в конце прошлого раза (${endHeld.size})") }
        }
        try {
            if (rule.steps.isNotEmpty()) {
                if (defer) {
                    Prefs.pendingStarts = Prefs.pendingStarts + (rule.id to PENDING_ALL)
                    Prefs.log("($why): ты пользуешься телефоном — сработает через минуту после того, как погасишь экран")
                    DeferWatch.sync()
                } else runSteps(rule, end = false, from = 0, why = why)
                return
            }
            if (defer && !rule.waitAppsOnly) {
                Prefs.pendingStarts = Prefs.pendingStarts + (rule.id to PENDING_ALL)
                Prefs.log("($why): ты пользуешься телефоном — сработает через минуту после того, как погасишь экран")
                DeferWatch.sync()
                return
            }
            sideActions(rule, why)
            if (rule.reconnect) reconnect(why)
            Freezer.ruleStart(rule, why)
            // Unloading is as much in the way of someone using the phone as stopping apps: it
            // waits with them. Before the launches, or it would take the apps just opened.
            val unloadNow = rule.unload && !(defer && rule.packages.isNotEmpty())
            if (unloadNow) unloadBackground(why, keep = rule.launchApps, all = rule.unloadAll)
            launchApps(rule.launchApps, why)
            OneShots.run(rule.note, rule.http, rule.shell, why)
            if (defer && rule.packages.isNotEmpty()) {
                Prefs.pendingStarts = Prefs.pendingStarts + (rule.id to PENDING_APPS)
                Prefs.log("($why): приложения остановятся через минуту после того, как погасишь экран")
                DeferWatch.sync()
            } else {
                freezeApps(rule, why)
            }
        } finally {
            // The end of an "after leaving" window is an alarm of its own.
            AlarmScheduler.rearm()
        }
    }

    /**
     * Stops [keys], each by [methodOf], holding them under [owner]: the rule's id while its
     * window is open, or [endOwner] of it for apps held from its end until its next start.
     */
    private suspend fun freezeApps(
        rule: AppRule,
        why: String,
        keys: List<String> = rule.packages,
        methodOf: (String) -> FreezeMethod = rule::methodOf,
        owner: Long = rule.id,
        message: String = suspendMessage(rule),
    ) {
        if (keys.isEmpty()) return
        val ctx = App.ctx
        val pm = ctx.packageManager
        val guarded = protectedPackages(ctx)
        val thawProtected = Freezer.protectedKeys()
        val infos = keys
            .filter { AppKey.valid(it) && AppKey.pkg(it) !in guarded && it !in thawProtected }
            .mapNotNull { k -> runCatching { k to pm.getApplicationInfo(AppKey.pkg(k), 0) }.getOrNull() }
        if (infos.isEmpty()) {
            Prefs.log("приложения ($why): нечего останавливать")
            return
        }
        val pkgs = infos.map { it.first }
        // Only the original's flag is visible to the app; a clone suspended by another tool is
        // not detectable here, so it is treated as not suspended before.
        val wasSuspended = infos
            .filter { (k, info) -> !AppKey.isClone(k) && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 }
            .map { it.first }.toSet()

        // A frozen app that held the VPN loses it and cannot bring it back by itself after being
        // unfrozen (measured: AdGuard stays off). Remember it, so the end of the window
        // reconnects it through the system.
        val vpnOwner = runCatching { VpnControl.owner() }.getOrNull()
            ?.takeIf { it in pkgs && methodOf(it) == FreezeMethod.SUSPEND && (rule.restores || owner != rule.id) }

        try {
            val heavy = pkgs.filter { methodOf(it) != FreezeMethod.STOP }
            val prev = if (heavy.isEmpty()) emptyMap() else readPrevious(heavy)

            // Recorded before the work, as if it will all succeed: a script cut short (a timeout
            // in deep sleep, with many apps) has frozen some apps already, and an app frozen with
            // no record would never be given back. Putting back what was not done is harmless;
            // the real outcome replaces this below.
            run {
                val existing = Prefs.frozen.associateBy { it.pkg }
                val since = System.currentTimeMillis()
                val planned = pkgs.mapNotNull { p ->
                    val old = existing[p]
                    val (prevOp, prevBucket) = prev[p] ?: (null to null)
                    val m = methodOf(p)
                    val appop = old?.appop ?: prevOp.takeIf { m == FreezeMethod.STOP_RESTRICT && it != "ignore" }
                    val bucket = old?.bucket ?: prevBucket.takeIf {
                        m == FreezeMethod.STOP_RESTRICT && it != "restricted" && !(Compat.SDK < 30 && it == "rare")
                    }
                    val suspended = (old?.suspended ?: false) || (m == FreezeMethod.SUSPEND && p !in wasSuspended)
                    if (appop == null && bucket == null && !suspended) old else FrozenApp(p, owner, since, appop, bucket, suspended)
                }
                Prefs.frozen = Prefs.frozen.filterNot { it.pkg in pkgs } + planned
            }

            val script = buildString {
                for (p in pkgs) {
                    val t = AppKey.shell(p)
                    when (methodOf(p)) {
                        FreezeMethod.STOP -> appendLine("am force-stop $t")
                        FreezeMethod.STOP_RESTRICT -> {
                            appendLine("am force-stop $t")
                            appendLine("cmd appops set $t RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1 && echo \"@A $p\"")
                            // "restricted" came with Android 11; "rare" is the strictest before it.
                            appendLine("am set-standby-bucket $t ${if (Compat.SDK >= 30) "restricted" else "rare"} >/dev/null 2>&1 && echo \"@B $p\"")
                        }
                        // Frozen together below, in one process per user.
                        FreezeMethod.SUSPEND -> Unit
                    }
                }
                // Freeze first, stop second: the shade restarts a stopped app within a second to
                // rebind its tile, and a restarted app runs — VPN included — even once frozen.
                // Measured with AdGuard; frozen first, it stayed down.
                val frozen = pkgs.filter { methodOf(it) == FreezeMethod.SUSPEND }
                if (frozen.isNotEmpty()) {
                    append(suspendBatch(frozen, message))
                    for (p in frozen) appendLine("am force-stop ${AppKey.shell(p)}")
                }
                for (p in pkgs) appendLine("${AppKey.aliveCheck(p)} && echo \"@L $p\"")
            }
            // A few seconds per app: suspending goes through app_process, slower still in deep sleep.
            val out = Privileged.exec(script, timeoutMs = 15_000L + 3_000L * pkgs.size)
            val marks = out.lineSequence()
                .filter { it.startsWith("@") }
                .map { it.substringBefore(' ') to it.substringAfter(' ').trim() }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }

            run {
                val existing = Prefs.frozen.associateBy { it.pkg }
                val since = System.currentTimeMillis()
                val updated = pkgs.mapNotNull { p ->
                    val old = existing[p]
                    val (prevOp, prevBucket) = prev[p] ?: (null to null)
                    val appop = old?.appop
                        ?: prevOp.takeIf { p in marks["@A"].orEmpty() && it != "ignore" }
                    val bucket = old?.bucket
                        ?: prevBucket.takeIf { p in marks["@B"].orEmpty() && it != "restricted" && !(Compat.SDK < 30 && it == "rare") }
                    val suspended = (old?.suspended ?: false) ||
                        (p in marks["@S"].orEmpty() && p !in wasSuspended)
                    if (appop == null && bucket == null && !suspended) old
                    else FrozenApp(p, owner, since, appop, bucket, suspended)
                }
                val vpnBack = vpnOwner?.let {
                    FrozenApp(FrozenApp.VPN_BACK + it, owner, since, null, null, false, "on")
                }
                Prefs.frozen = Prefs.frozen.filterNot { it.pkg in pkgs || it.pkg == vpnBack?.pkg } + updated +
                    listOfNotNull(vpnBack)
            }

            val alive = marks["@L"].orEmpty()
            Prefs.log(
                "приложения ($why): " + pkgs.groupingBy { methodOf(it) }.eachCount()
                    .entries.joinToString(", ") { (m, n) -> "${m.label.lowercase()} — $n" } +
                    if (alive.isNotEmpty()) ", не выгрузились: ${alive.size}" else ""
            )
            Log.i(TAG, "froze ${pkgs.size}, alive=$alive")
        } catch (e: Exception) {
            Prefs.log("приложения ($why): ошибка — ${e.message}")
            Log.w(TAG, "freeze failed", e)
        }
    }

    /**
     * Network mode, Wi-Fi, hotspot, Bluetooth and location. Each is recorded only when this
     * rule actually changes it, with the state to go back to — a radio the user already had
     * the way the rule wants is left alone at the end of the window too. An entry left by an
     * earlier rule keeps its original state, so overlapping rules still restore the real one.
     */
    private suspend fun sideActions(rule: AppRule, why: String, only: Set<String>? = null) {
        fun wants(key: String) = only == null || key in only
        val existing = Prefs.frozen.associateBy { it.pkg }
        val added = mutableListOf<FrozenApp>()
        val dropped = mutableListOf<String>()
        val done = mutableListOf<String>()
        val since = System.currentTimeMillis()
        fun record(pkg: String, prev: String) {
            // A rule that never ends sets the state for good: whatever an earlier rule meant to
            // put back is superseded rather than restored later.
            if (!rule.restores) { dropped += pkg; return }
            added += FrozenApp(pkg, rule.id, since, null, null, false, existing[pkg]?.prev ?: prev)
        }
        try {
            rule.netMode?.let { mode ->
                val slots = Sims.resolve(rule.netSlot)
                if (slots.isEmpty() && rule.netSlot != SLOT_ALL) done += "SIM ${rule.netSlot + 1} не активна — режим сети не менялся"
                for (slot in slots.filter { wants(FrozenApp.NET + it) }) {
                    val key = FrozenApp.NET + slot
                    val mask = runCatching { Telephony.readMask(slot) }.getOrDefault(-1L)
                    // The exact mask, not its generation: a stock "LTE only" put back as the
                    // 4G preset would quietly change the user's setting.
                    val prev = NetMode.exact(mask)?.name ?: if (mask > 0) "0x%X".format(mask) else null
                    val result = ModeController.apply(slot, mode, sourceOf(rule))
                    // A failed read-back does not mean nothing was written: whatever changed
                    // is still recorded, so the end of the window puts it back.
                    val changed = result.isSuccess ||
                        runCatching { Telephony.readMask(slot) }.getOrDefault(mask) != mask
                    if (changed) {
                        if (!rule.restores) record(key, "")
                        else if (prev != null && (mask != Profile.maskFor(mode, slot) || key in existing)) record(key, prev)
                    }
                    result.onSuccess { done += "SIM ${slot + 1} → ${mode.short}" }
                        .onFailure { done += "SIM ${slot + 1}: ошибка ${it.message}" }
                }
            }

            val state = Privileged.exec(STATE_SCRIPT).lines()
            fun flag(tag: String) = state.firstOrNull { it.startsWith("@$tag ") }?.substringAfter(' ')?.trim()
            val wifiOn = flag("WIFI").let { it != null && it != "0" }
            val apOn = flag("AP") == "1"
            val dataOn = flag("DATA") == "1"
            val btOn = flag("BT") == "1"
            val locLine = flag("LOC").orEmpty()
            val locOn = locLine.startsWith("true") ||
                (!locLine.startsWith("false") && locLine.split(' ').lastOrNull()?.let { it != "0" && it != "null" } == true)
            val airOn = flag("AIR") == "1"
            val psOn = flag("PS") == "1"

            val script = StringBuilder()
            rule.wifi?.takeIf { wants(FrozenApp.WIFI) }?.let { want ->
                val on = want == Toggle.ON
                if (on != wifiOn || FrozenApp.WIFI in existing) record(FrozenApp.WIFI, if (wifiOn) "on" else "off")
                if (on != wifiOn) {
                    script.appendLine(if (on) WIFI_ON else WIFI_OFF)
                    done += if (on) "Wi-Fi включён" else "Wi-Fi выключен"
                }
            }
            rule.mobileData?.takeIf { wants(FrozenApp.DATA) }?.let { want ->
                val on = want == Toggle.ON
                if (on != dataOn || FrozenApp.DATA in existing) record(FrozenApp.DATA, if (dataOn) "on" else "off")
                if (on != dataOn) {
                    script.appendLine(if (on) DATA_ON else DATA_OFF)
                    done += if (on) "мобильный интернет включён" else "мобильный интернет выключен"
                }
            }
            // The traffic mode first: switching it before the hotspot comes up lets the tether
            // broadcast apply it, and an already running hotspot is re-routed below.
            val via = rule.hotspotVia?.takeIf { rule.hotspot == Toggle.ON && wants(FrozenApp.TETHER_MODE) }
            if (via != null && via != Prefs.tetherMode) {
                record(FrozenApp.TETHER_MODE, Prefs.tetherMode.name)
                Prefs.tetherMode = via
                TetherWatch.sync()
            } else if (via != null && FrozenApp.TETHER_MODE in existing) {
                record(FrozenApp.TETHER_MODE, Prefs.tetherMode.name)
            }
            rule.hotspot?.takeIf { wants(FrozenApp.HOTSPOT) }?.let { want ->
                val on = want == Toggle.ON
                if (on != apOn || FrozenApp.HOTSPOT in existing) record(FrozenApp.HOTSPOT, if (apOn) "on" else "off")
                if (on != apOn) {
                    script.appendLine(hotspot(on))
                    done += if (on) "точка доступа включена" else "точка доступа выключена"
                }
            }
            if (rule.bluetoothOff && wants(FrozenApp.BLUETOOTH)) {
                if (btOn || FrozenApp.BLUETOOTH in existing) record(FrozenApp.BLUETOOTH, "on")
                if (btOn) {
                    script.appendLine(BT_OFF)
                    done += "Bluetooth выключен"
                    Prefs.selfBtAt = System.currentTimeMillis()
                }
            }
            if (rule.locationOff && wants(FrozenApp.LOCATION)) {
                if (locOn || FrozenApp.LOCATION in existing) record(FrozenApp.LOCATION, "on")
                if (locOn) { script.appendLine(LOC_OFF); done += "геолокация выключена" }
            }
            rule.airplane?.takeIf { wants(FrozenApp.AIRPLANE) }?.let { want ->
                val on = want == Toggle.ON
                if (on != airOn || FrozenApp.AIRPLANE in existing) record(FrozenApp.AIRPLANE, if (airOn) "on" else "off")
                if (on != airOn) {
                    script.appendLine(if (on) AIR_ON else AIR_OFF)
                    done += if (on) "режим полёта включён" else "режим полёта выключен"
                    // Airplane mode takes Wi-Fi and Bluetooth down: that is not leaving.
                    Prefs.selfBtAt = System.currentTimeMillis()
                }
            }
            rule.powerSave?.takeIf { wants(FrozenApp.POWERSAVE) }?.let { want ->
                val on = want == Toggle.ON
                if (on != psOn || FrozenApp.POWERSAVE in existing) record(FrozenApp.POWERSAVE, if (psOn) "on" else "off")
                if (on != psOn) {
                    script.appendLine(if (on) PS_ON else PS_OFF)
                    done += if (on) "энергосбережение включено" else "энергосбережение выключено"
                }
            }
            rule.dataSim?.takeIf { wants(FrozenApp.DATASIM) }?.let { slot ->
                val now = DualSim.dataSlot()
                if (slot != now) {
                    val cmd = dataSimCmd(slot)
                    if (cmd == null) done += "SIM ${slot + 1} не активна — интернет не переключён"
                    else {
                        if (now != null) record(FrozenApp.DATASIM, now.toString())
                        script.appendLine(cmd)
                        done += "интернет через SIM ${slot + 1}"
                    }
                } else if (FrozenApp.DATASIM in existing) record(FrozenApp.DATASIM, slot.toString())
            }
            if (rule.wifi != null || rule.hotspot != null || rule.airplane != null) Prefs.selfWifiAt = System.currentTimeMillis()
            if (script.isNotBlank()) Privileged.exec(script.toString())
            if (via != null) done += applyTether()

            // Tiles and the VPN side by side, so their waits overlap: a broadcast receiver has a
            // minute in total.
            val tileTargets = rule.tiles.filterKeys { wants(FrozenApp.TILE + it) }.mapValues { it.value == Toggle.ON }
            coroutineScope {
                val vpnJob = rule.vpn?.takeIf { wants(FrozenApp.SYSVPN) }?.let { want ->
                    async {
                        val before = VpnControl.owner()
                        val line = if (want == Toggle.ON) {
                            val app = rule.vpnApp
                            if (app == null) "VPN: не выбрано приложение" else VpnControl.on(app)
                        } else VpnControl.off()
                        val changed = if (want == Toggle.ON) before != rule.vpnApp else before != null
                        if (changed || FrozenApp.SYSVPN in existing) record(FrozenApp.SYSVPN, before ?: "off")
                        line
                    }
                }
                if (tileTargets.isNotEmpty()) {
                    done += Tiles.set(tileTargets) { c, wasOn ->
                        val key = FrozenApp.TILE + c
                        if (wasOn != tileTargets[c] || key in existing) record(key, if (wasOn) "on" else "off")
                    }
                }
                vpnJob?.await()?.let { done += it }
            }

            for ((name, value) in rule.sys) {
                val key = SysKey.of(name) ?: continue
                if (!wants(FrozenApp.SYS + name)) continue
                val before = SysSettings.read(key)
                val ok = runCatching { SysSettings.apply(key, value) }
                    .onFailure { done += key.title.lowercase() + ": ошибка ${it.message}" }.isSuccess
                if (!ok) continue
                done += key.title.lowercase() + ": " + key.optionLabel(value)
                if (before != null) record(FrozenApp.SYS + name, before)
            }
            Prefs.frozen = Prefs.frozen.filterNot { e -> e.pkg in dropped || added.any { it.pkg == e.pkg } } + added
            if (done.isNotEmpty()) Prefs.log("($why): ${done.joinToString(", ")}")
        } catch (e: Exception) {
            Prefs.log("($why): ошибка — ${e.message}")
            Log.w(TAG, "side actions failed", e)
        }
    }

    /**
     * Routes the hotspot per the current mode once it is up; the hotspot takes a few seconds to
     * start, and the tether broadcast alone may reach a process not yet listening for it.
     */
    private suspend fun applyTether(): String {
        for (i in 0 until 16) {
            if (TetherRoute.apInterface() != null) break
            kotlinx.coroutines.delay(500)
        }
        val line = runCatching { TetherRoute.apply() }.getOrElse { "раздача: ошибка ${it.message}" }
        HotspotControl.isOn()
        HotspotTiles.refresh()
        return line
    }

    private fun hotspot(on: Boolean): String =
        "${cli()} com.netmode.switcher.HotspotCli ${if (on) "start" else "stop"} >/dev/null 2>&1"

    /**
     * Freezes [keys] with one app_process per user rather than one per app: starting a Java
     * process costs a second or two, and twenty apps one by one ran past the shell's time limit
     * (frozen, but never recorded — so never given back). Through [SuspendCli] the system's "app
     * paused" dialog carries an unpause button; an app it could not freeze is tried alone with
     * pm, message but no button. Prints "@S key" for each frozen app.
     */
    private fun suspendBatch(keys: List<String>, message: String): String = buildString {
        val m = message.replace("'", "")
        val d = "${'$'}"
        for ((u, group) in keys.groupBy { AppKey.user(it) }) {
            appendLine("r=$d(${cli()} com.netmode.switcher.SuspendCli suspend $u '$m' ${group.joinToString(" ") { AppKey.pkg(it) }})")
            // "ok=" then the packages it could not freeze, comma-separated; anything else: none frozen.
            appendLine("case \"${d}r\" in ok=*) f=\",${d}{r#ok=},\" ;; *) f=all ;; esac")
            for (k in group) {
                val p = AppKey.pkg(k)
                appendLine("case \"${d}f\" in all|*,$p,*) pm suspend --user $u --dialogMessage '$m' $p >/dev/null 2>&1 && echo \"@S $k\" ;; *) echo \"@S $k\" ;; esac")
            }
        }
    }

    /** The reverse of [suspendBatch]; prints "@F key" for an app it could not unfreeze. */
    private fun unsuspendBatch(keys: List<String>): String = buildString {
        val d = "${'$'}"
        for ((u, group) in keys.groupBy { AppKey.user(it) }) {
            appendLine("r=$d(${cli()} com.netmode.switcher.SuspendCli unsuspend $u ${group.joinToString(" ") { AppKey.pkg(it) }})")
            appendLine("case \"${d}r\" in ok=*) f=\",${d}{r#ok=},\" ;; *) f=all ;; esac")
            for (k in group) {
                val p = AppKey.pkg(k)
                appendLine("case \"${d}f\" in all|*,$p,*) pm unsuspend --user $u $p >/dev/null 2>&1 || echo \"@F $k\" ;; esac")
            }
        }
    }

    private fun unsuspendCmd(key: String): String {
        val u = AppKey.user(key)
        val p = AppKey.pkg(key)
        return "{ ${cli()} com.netmode.switcher.SuspendCli unsuspend $u $p | grep -q '^ok=\$' || " +
            "pm unsuspend --user $u $p >/dev/null 2>&1; } || echo \"@F $key\""
    }

    private fun cli(): String = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin"

    /** Makes [slot]'s SIM the one for mobile data; null when no SIM is active there. */
    private fun dataSimCmd(slot: Int): String? {
        val sub = Compat.subId(slot)
        if (sub < 0) return null
        return "${cli()} com.netmode.switcher.SimCli data $sub | grep -q '^ok' || echo \"@F datasim\""
    }

    private fun suspendMessage(rule: AppRule): String {
        val until = rule.lastStart(ZonedDateTime.now())?.let { rule.endAfter(it) }
            ?.let { " до %02d:%02d".format(it.hour, it.minute) }
            ?: rule.leftEndAfter(ZonedDateTime.now())?.let { " до %02d:%02d".format(it.hour, it.minute) }
            ?: ""
        return App.ctx.getString(R.string.suspend_message, until)
    }

    /**
     * Brings one app back before its window ends — the user wants it now. It stays back until
     * the rule's next window; the current one is not re-applied.
     */
    suspend fun unfreezeApp(key: String): Boolean = lock.withLock {
        val entries = Prefs.frozen.filter { it.pkg == key }
        val script = if (entries.isEmpty()) unsuspendCmd(key) else null
        if (script != null) Privileged.exec(script) else restore(entries)
        Privileged.exec("pm unstop --user ${AppKey.user(key)} ${AppKey.pkg(key)} >/dev/null 2>&1")
        Prefs.log("«${AppPickerActivity.labels(listOf(key)).first()}» возвращено вручную")
        true
    }

    /** Apps (not radios) currently held by rules. */
    fun heldApps(): List<String> = Prefs.frozen.filterNot { it.isPseudo }.map { it.pkg }.distinct()

    /** Current appop mode and standby bucket per package, read in one shell call. */
    private suspend fun readPrevious(pkgs: List<String>): Map<String, Pair<String?, String?>> {
        val script = pkgs.joinToString("\n") { p ->
            val t = AppKey.shell(p)
            "echo \"@P $p|\$(cmd appops get $t RUN_ANY_IN_BACKGROUND 2>&1 | head -1)|\$(am get-standby-bucket $t 2>/dev/null)\""
        }
        return Privileged.exec(script, timeoutMs = 15_000L + 1_000L * pkgs.size).lineSequence()
            .filter { it.startsWith("@P ") }
            .associate { line ->
                val parts = line.removePrefix("@P ").split('|')
                val op = parts.getOrNull(1).orEmpty().trim()
                val mode = when {
                    op.startsWith("RUN_ANY_IN_BACKGROUND:") -> op.substringAfter(':').trim()
                    op.startsWith("No operations") -> "default"
                    else -> null
                }
                val bucket = parts.getOrNull(2)?.trim()?.toIntOrNull()?.let { BUCKET_NAMES[it] }
                parts[0] to (mode to bucket)
            }
    }

    /** Puts back what [entries] recorded and forgets them. Returns how many steps failed. */
    private suspend fun restore(entries: List<FrozenApp>): Int {
        val safe = entries.filter { AppKey.valid(it.pkg) }
        var netFailed = 0
        for (e in entries.filter { it.pkg.startsWith(FrozenApp.NET) }) {
            val slot = e.pkg.removePrefix(FrozenApp.NET).toIntOrNull() ?: continue
            val prev = e.prev ?: continue
            val ok = runCatching {
                val mode = runCatching { NetMode.valueOf(prev) }.getOrNull()
                val source = sourceOf(Prefs.appRules.find { it.id == e.ruleId })
                if (mode != null) ModeController.apply(slot, mode, source).isSuccess
                else ModeController.applyMask(slot, prev.removePrefix("0x").toLong(16)).isSuccess
            }.getOrDefault(false)
            if (!ok) netFailed++
        }
        val tileBack = entries.filter { it.pkg.startsWith(FrozenApp.TILE) && it.prev != null }
            .associate { it.pkg.removePrefix(FrozenApp.TILE) to (it.prev == "on") }
        val vpnBack = entries.filter { it.pkg.startsWith(FrozenApp.VPN) && it.prev != null }
            .associate { it.pkg.removePrefix(FrozenApp.VPN) to (it.prev == "on") }
        entries.firstOrNull { it.pkg == FrozenApp.SYSVPN }?.prev?.let { prev ->
            val line = runCatching { if (prev == "off") VpnControl.off() else VpnControl.on(prev) }
                .getOrElse { "VPN: ошибка ${it.message}" }
            Prefs.log("возврат: $line")
        }
        for (e in entries.filter { it.pkg.startsWith(FrozenApp.SYS) && it.prev != null }) {
            val key = SysKey.of(e.pkg.removePrefix(FrozenApp.SYS)) ?: continue
            runCatching { SysSettings.apply(key, e.prev!!) }
                .onSuccess { Prefs.log("возврат: ${key.title.lowercase()} — как было") }
                .onFailure { netFailed++ }
        }
        if (tileBack.isNotEmpty() || vpnBack.isNotEmpty()) {
            val lines = runCatching { Tiles.set(tileBack + vpnBack, vpn = vpnBack.keys) }.getOrDefault(emptyList())
            netFailed += lines.count { it.contains("не подтвердилось") }
            if (lines.isNotEmpty()) Prefs.log("возврат: ${lines.joinToString(", ")}")
        }
        val byPkg = entries.associateBy { it.pkg }
        if (byPkg.containsKey(FrozenApp.WIFI) || byPkg.containsKey(FrozenApp.HOTSPOT) || byPkg.containsKey(FrozenApp.AIRPLANE)) {
            Prefs.selfWifiAt = System.currentTimeMillis()
        }
        val script = buildString {
            // Hotspot first: bringing Wi-Fi back is the step that must not be undone by it.
            byPkg[FrozenApp.HOTSPOT]?.prev?.let { appendLine("{ ${hotspot(it == "on")}; } || echo \"@F ap\"") }
            byPkg[FrozenApp.DATA]?.prev?.let { appendLine("{ ${if (it == "on") DATA_ON else DATA_OFF}; } || echo \"@F data\"") }
            byPkg[FrozenApp.WIFI]?.prev?.let { appendLine("{ ${if (it == "on") WIFI_ON else WIFI_OFF}; } || echo \"@F wifi\"") }
            if (byPkg.containsKey(FrozenApp.BLUETOOTH)) appendLine("{ $BT_ON; } || echo \"@F bt\"")
            if (byPkg.containsKey(FrozenApp.LOCATION)) appendLine("{ $LOC_ON; } || echo \"@F loc\"")
            byPkg[FrozenApp.AIRPLANE]?.prev?.let { appendLine("{ ${if (it == "on") AIR_ON else AIR_OFF}; } || echo \"@F air\"") }
            byPkg[FrozenApp.POWERSAVE]?.prev?.let { appendLine("{ ${if (it == "on") PS_ON else PS_OFF}; } || echo \"@F ps\"") }
            byPkg[FrozenApp.DATASIM]?.prev?.toIntOrNull()?.let { slot -> dataSimCmd(slot)?.let { appendLine(it) } }
            for (e in safe) {
                val t = AppKey.shell(e.pkg)
                e.appop?.let { appendLine("cmd appops set $t RUN_ANY_IN_BACKGROUND $it >/dev/null 2>&1 || echo \"@F ${e.pkg}\"") }
                e.bucket?.let { appendLine("am set-standby-bucket $t $it >/dev/null 2>&1 || echo \"@F ${e.pkg}\"") }
            }
            // An app the freezer holds stays suspended: the rule only borrowed it.
            safe.filter { it.suspended && !Freezer.holdsSuspended(it.pkg) }.map { it.pkg }
                .takeIf { it.isNotEmpty() }?.let { append(unsuspendBatch(it)) }
        }
        var failed = netFailed + if (script.isBlank()) 0
        // Unfreezing too goes through app_process per app: its time grows with the list.
        else Privileged.exec(script, timeoutMs = 15_000L + 3_000L * safe.size).lineSequence().count { it.startsWith("@F ") }
        byPkg[FrozenApp.TETHER_MODE]?.prev?.let { prev ->
            Prefs.tetherMode = runCatching { TetherMode.valueOf(prev) }.getOrDefault(TetherMode.DIRECT)
            TetherWatch.sync()
            Prefs.log("возврат: " + applyTether())
        }
        // After the apps are unfrozen: reconnect the VPN of one that held it when it was frozen.
        for (e in entries.filter { it.pkg.startsWith(FrozenApp.VPN_BACK) }) {
            val pkg = e.pkg.removePrefix(FrozenApp.VPN_BACK)
            val line = runCatching { VpnControl.on(pkg) }.getOrElse { "VPN: ошибка ${it.message}" }
            Prefs.log("после разморозки: $line")
            if (!line.endsWith("вкл")) failed++
        }
        val gone = entries.map { it.pkg to it.ruleId }.toSet()
        Prefs.frozen = Prefs.frozen.filterNot { (it.pkg to it.ruleId) in gone }
        return failed
    }
}
