package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ordered rules: "Что сделать" and "В конце" as lists of steps run top to bottom, with pauses
 * between them — "mobile data off, wait a minute, Wi-Fi on". A step is the name of an action
 * as the rule editor knows it (NET, DATA, WIFI, …) or a pause, "P<seconds>". A rule with no
 * steps runs its actions in the fixed order they always ran in.
 */
object Steps {

    private const val PAUSE = "P"

    /** Longest pause the editor offers: 23:59:59. */
    const val MAX_PAUSE_S = 24 * 3600 - 1

    fun pause(seconds: Int) = PAUSE + seconds

    fun pauseOf(step: String): Int? = if (step.startsWith(PAUSE)) step.drop(PAUSE.length).toIntOrNull() else null

    const val CHECK_VPN = "VPN"
    const val CHECK_NO_VPN = "NOVPN"
    const val CHECK_NET = "NET"
    const val CHECK_NO_NET = "NONET"
    const val CHECK_WIFI = "WIFI"
    const val CHECK_NO_WIFI = "NOWIFI"
    const val CHECK_HOTSPOT = "HOTSPOT"
    const val CHECK_NO_HOTSPOT = "NOHOTSPOT"
    const val CHECK_CHARGING = "CHARGING"
    const val CHECK_NO_CHARGING = "NOCHARGING"
    const val CHECK_UNLOCKED = "UNLOCKED"
    const val CHECK_LOCKED = "LOCKED"
    const val CHECK_NO_CALL = "NOCALL"
    const val CHECK_NO_ROAMING = "NOROAMING"
    private const val CHECK = "?"

    /**
     * A condition as the editor, the rule's summary and the log word it. [isFailure]: the rule
     * meant to get this and did not, worth a notice. The rest are plain branches: not charging
     * is no error.
     */
    class CheckInfo(@androidx.annotation.StringRes val label: Int, val summary: String, val unmet: String, val isFailure: Boolean = false)

    /** In the order the editor lists them. */
    val CHECKS: Map<String, CheckInfo> = linkedMapOf(
        CHECK_VPN to CheckInfo(R.string.check_step_vpn, "если VPN есть", "VPN не подключён", isFailure = true),
        CHECK_NO_VPN to CheckInfo(R.string.check_step_no_vpn, "если VPN нет", "VPN ещё подключён"),
        CHECK_NET to CheckInfo(R.string.check_step_net, "если интернет есть", "нет интернета", isFailure = true),
        CHECK_NO_NET to CheckInfo(R.string.check_step_no_net, "если интернета нет", "интернет есть"),
        CHECK_WIFI to CheckInfo(R.string.check_step_wifi, "если есть Wi-Fi", "Wi-Fi не подключён"),
        CHECK_NO_WIFI to CheckInfo(R.string.check_step_no_wifi, "если нет Wi-Fi", "Wi-Fi подключён"),
        CHECK_HOTSPOT to CheckInfo(R.string.check_step_hotspot, "если раздача включена", "раздача выключена"),
        CHECK_NO_HOTSPOT to CheckInfo(R.string.check_step_no_hotspot, "если раздача выключена", "раздача включена"),
        CHECK_CHARGING to CheckInfo(R.string.check_step_charging, "если на зарядке", "телефон не на зарядке"),
        CHECK_NO_CHARGING to CheckInfo(R.string.check_step_no_charging, "если не на зарядке", "телефон на зарядке"),
        CHECK_UNLOCKED to CheckInfo(R.string.check_step_unlocked, "если разблокирован", "телефон заблокирован"),
        CHECK_LOCKED to CheckInfo(R.string.check_step_locked, "если заблокирован", "телефон разблокирован"),
        CHECK_NO_CALL to CheckInfo(R.string.check_step_no_call, "если нет звонка", "идёт звонок"),
        CHECK_NO_ROAMING to CheckInfo(R.string.check_step_no_roaming, "если не в роуминге", "телефон в роуминге"),
    )

    /** How long a condition may take to come true; 0 looks once. */
    val CHECK_WAITS = listOf(0, 15, 30, 60, 120)

    /** Goes on only when [kind] holds, at once or after waiting up to [seconds]; else the rest is dropped. */
    fun check(kind: String, seconds: Int) = "$CHECK$kind:$seconds"

    fun checkOf(step: String): Pair<String, Int>? {
        if (!step.startsWith(CHECK)) return null
        val kind = step.drop(CHECK.length).substringBefore(':')
        val seconds = step.substringAfter(':', "").toIntOrNull() ?: return null
        return if (kind in CHECKS) kind to seconds else null
    }

    /** A step of the order itself, not one of the rule's actions. */
    fun isMark(step: String) = pauseOf(step) != null || checkOf(step) != null

    /** "00:01:30" */
    fun clock(seconds: Int) = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    /** "1 ч 5 мин", "1 мин 30 с", "45 с" — for the log. */
    fun words(seconds: Int): String = listOfNotNull(
        (seconds / 3600).takeIf { it > 0 }?.let { "$it ч" },
        (seconds / 60 % 60).takeIf { it > 0 }?.let { "$it мин" },
        (seconds % 60).takeIf { it > 0 || seconds == 0 }?.let { "$it с" },
    ).joinToString(" ")

    /**
     * The order the actions ran in before steps existed. A rule whose actions stand in this
     * order, with no pause, keeps no steps and runs as before: radios in one go, the tiles and
     * the VPN side by side.
     */
    val START_ORDER = listOf("NET", "WIFI", "DATA", "HOTSPOT", "BT", "LOC", "AIRPLANE", "POWERSAVE", "DATASIM", "RECONNECT", "TILES", "VPN", "SYS", "ICE", "UNLOAD", "LAUNCH", "NOTE", "HTTP", "SHELL", "APPS")
    val END_ORDER = listOf("NET", "DATA", "HOTSPOT", "WIFI", "BT", "LOC", "AIRPLANE", "POWERSAVE", "DATASIM", "TILES", "VPN", "SYS", "APPS", "UNLOAD", "LAUNCH", "NOTE", "HTTP", "SHELL")

    /** What is worth saving of [steps]: nothing when they add nothing to the old fixed order. */
    fun toSave(steps: List<String>, legacy: List<String>): List<String> {
        val trimmed = steps.dropLastWhile { isMark(it) }
        if (trimmed.none { isMark(it) } && trimmed == trimmed.sortedBy { legacy.indexOf(it) }) return emptyList()
        return trimmed
    }
}

/** The rest of a rule's start ([end] false) or end, waiting out a pause: from step [next], at [due]. */
data class PendingSteps(val ruleId: Long, val end: Boolean, val next: Int, val due: Long, val sig: Int, val waited: Boolean = false) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", ruleId).put("end", end).put("next", next).put("due", due).put("sig", sig).put("waited", waited)

    companion object {
        fun fromJson(o: org.json.JSONObject) = PendingSteps(
            o.getLong("id"), o.getBoolean("end"), o.getInt("next"), o.getLong("due"), o.getInt("sig"), o.optBoolean("waited", false),
        )
    }
}

/**
 * Pauses between steps. Each is kept in the settings and backed by an alarm, so the rest of a
 * rule still runs when the system has unloaded the app in the meantime. While the app lives, a
 * short pause is also timed in-process under a wake lock: an alarm in deep sleep may come
 * minutes late, a minute's pause should not.
 */
object StepQueue {

    /** Pauses up to this long are timed in-process; longer ones rely on the alarm alone. */
    private const val HELD_MAX_MS = 15 * 60_000L

    /** Identifies the steps a pause belongs to: a rule edited during it drops the rest. */
    fun sig(rule: AppRule, end: Boolean): Int = (if (end) rule.endSteps else rule.steps).hashCode()

    fun schedule(rule: AppRule, end: Boolean, next: Int, seconds: Int, waited: Boolean = false) {
        val ms = seconds * 1000L
        val p = PendingSteps(rule.id, end, next, System.currentTimeMillis() + ms, sig(rule, end), waited)
        Prefs.pendingSteps = Prefs.pendingSteps.filterNot { it.ruleId == rule.id && it.end == end } + p
        arm()
        if (ms > HELD_MAX_MS) return
        App.scope.launch {
            val wake = App.ctx.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "netmode:pause")
            wake.acquire(ms + 3 * 60_000L)
            try {
                delay(ms)
                runDue()
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
    }

    /** Drops what was left of [ruleId]'s start or end; true when something was waiting. */
    fun cancel(ruleId: Long, end: Boolean): Boolean {
        val before = Prefs.pendingSteps
        val after = before.filterNot { it.ruleId == ruleId && it.end == end }
        if (after.size == before.size) return false
        Prefs.pendingSteps = after
        arm()
        return true
    }

    /** Runs every pause that is over. Each runs once, whichever of timer and alarm comes first. */
    suspend fun runDue() {
        val now = System.currentTimeMillis()
        for (p in Prefs.pendingSteps.filter { it.due <= now + 1_000L }.sortedBy { it.due }) {
            runCatching { AppFreezer.continueSteps(p) }
                .onFailure { android.util.Log.w("netmode", "steps after a pause failed", it) }
        }
        arm()
    }

    /** One alarm, for the earliest pause to end. */
    fun arm() {
        val ctx = App.ctx
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 600, Intent(ctx, StepReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = Prefs.pendingSteps.minOfOrNull { it.due } ?: return am.cancel(pi)
        if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

/** A pause is over: the steps after it run. */
class StepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        // The steps may take longer than a broadcast may; the wake lock carries them.
        val wake = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "netmode:steps")
        wake.acquire(3 * 60_000L)
        val work = App.scope.launch {
            try {
                StepQueue.runDue()
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
        App.scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { work.join() }
            pending.finish()
        }
    }
}
