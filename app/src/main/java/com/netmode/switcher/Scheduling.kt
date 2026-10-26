package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * One alarm for all rules, not one per rule: the receiver applies whatever is due and then
 * re-arms for the next earliest firing. Fewer alarms means less to go wrong under Doze.
 */
object AlarmScheduler {

    private const val REQUEST = 100
    private const val TAG = "netmode"


    fun rearm() {
        val am = App.ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent()
        am.cancel(pi)

        val now = ZonedDateTime.now()
        val candidates = Prefs.appRules.filter { it.enabled && it.isTime && it.hasActions }.map { it.nextEvent(now) } +
            leftEnds(now) + windowEdges(now)
        val next = candidates.minOrNull() ?: return
        val at = next.toInstant().toEpochMilli()

        if (Compat.canExact(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Degraded but still useful; the rules screen surfaces the missing permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "next schedule alarm at $next")
    }

    /** Where the "с … до …" windows of extra conditions open or close next. */
    private fun windowEdges(now: ZonedDateTime): List<ZonedDateTime> =
        Prefs.appRules.filter { it.enabled && it.hasActions }
            .flatMap { r -> r.extras.filter { it.kind == ExtraKind.TIME } }
            .flatMap { e -> listOf(e.from, e.to) }.distinct()
            .map { m -> nextOccurrence(now, m / 60, m % 60) { true } }

    /** Pending end times of open windows with a clock of their own: after leaving Wi-Fi, a scene's time. */
    private fun leftEnds(now: ZonedDateTime): List<ZonedDateTime> {
        val rules = Prefs.appRules.filter { it.enabled && (it.hasLeftEndTime || it.trigger == Trigger.MANUAL) }.associateBy { it.id }
        // Open windows, not held entries: a window that changed nothing still has to end.
        return Prefs.openWindows.mapNotNull { (id, opened) ->
            val since = java.time.Instant.ofEpochMilli(opened).atZone(now.zone)
            rules[id]?.let { it.leftEndAfter(since) ?: it.manualEndAfter(since) }?.takeIf { it.isAfter(now) }
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        App.ctx,
        REQUEST,
        Intent(App.ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        App.scope.launch {
            try {
                val acted = runCatching { AppFreezer.reconcile() }.getOrDefault(false)
                // A window of an extra condition opening or closing is a change of its own.
                val extras = runCatching { AppFreezer.onExtras(setOf(Trigger.TIME)) }.getOrDefault(false)
                if (!acted && !extras && Prefs.appRules.none { r -> r.extras.any { it.kind == ExtraKind.TIME } }) {
                    Prefs.log("расписание: сработало, но подходящего правила нет")
                }
            } finally {
                AlarmScheduler.rearm()
                pending.finish()
            }
        }
    }
}

/**
 * Alarms do not survive a reboot and the Wi-Fi callback is dropped with the process, so both
 * are re-established here. MY_PACKAGE_REPLACED matters too: an update tears everything down.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        App.scope.launch {
            try {
                AlarmScheduler.rearm()
                WifiMonitor.register()
                if (Prefs.reliableWifi) WifiWatchService.start(context, WifiWatchService.MODE_ALWAYS)

                // Releasing a window that ended while the phone was off is always right; only a
                // missed start is subject to the catch-up switch.
                val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
                // Without access yet, reconcile arms AccessWait, which runs all of this again later.
                runCatching { AppFreezer.reconcile(boot = boot, catchUp = Prefs.catchUpOnBoot) }
                // Pauses that ran out while the phone was off: the rest of their steps, now.
                runCatching { StepQueue.runDue() }
                runCatching { AppFreezer.onExtras(null) }
                // Thawed apps freeze again after a reboot; the freezer's alarm is re-armed.
                runCatching { Freezer.sync(boot = intent.action == Intent.ACTION_BOOT_COMPLETED) }
                AlarmScheduler.rearm()
            } finally {
                pending.finish()
            }
            // Connections a window depended on may be gone after a reboot, with no event to
            // say so. Checked once the car and the home network have had time to reconnect.
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                kotlinx.coroutines.delay(90_000)
                runCatching { AppFreezer.afterBoot() }
                runCatching { WifiRules.recheck() }
            }
        }
    }
}
