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

    /** Slack for a late alarm — Doze can hold one back by minutes. */
    private const val GRACE_MINUTES = 10L

    fun rearm() {
        val am = App.ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent()
        am.cancel(pi)

        val enabled = Prefs.schedules.filter { it.enabled }
        if (enabled.isEmpty()) return

        val now = ZonedDateTime.now()
        val next = enabled.minOf { it.nextAfter(now) }
        val at = next.toInstant().toEpochMilli()

        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Degraded but still useful; the rules screen surfaces the missing permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "next schedule alarm at $next")
    }

    /** The rule that should be in force right now, or null if none fired recently. */
    fun dueNow(now: ZonedDateTime = ZonedDateTime.now()): ScheduleRule? {
        val since = now.minusMinutes(GRACE_MINUTES)
        return Prefs.schedules
            .filter { it.enabled }
            .mapNotNull { rule -> rule.lastAtOrBefore(now)?.let { it to rule } }
            .filter { it.first.isAfter(since) }
            .maxByOrNull { it.first }      // last event wins
            ?.second
    }

    /** The rule in force at boot, looking back a whole day rather than minutes. */
    fun catchUpRule(now: ZonedDateTime = ZonedDateTime.now()): ScheduleRule? {
        val since = now.minusHours(24)
        return Prefs.schedules
            .filter { it.enabled }
            .mapNotNull { rule -> rule.lastAtOrBefore(now)?.let { it to rule } }
            .filter { it.first.isAfter(since) }
            .maxByOrNull { it.first }
            ?.second
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
                val rule = AlarmScheduler.dueNow()
                if (rule == null) {
                    Prefs.log("расписание: сработало, но подходящего правила нет")
                } else {
                    ModeController.apply(rule.mode, ModeController.Source.SCHEDULE)
                        .onSuccess { Prefs.log("расписание ${rule.timeLabel}: ${it.label}") }
                        .onFailure { Prefs.log("расписание ${rule.timeLabel}: ошибка — ${it.message}") }
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

                if (Prefs.catchUpOnBoot && intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    val rule = AlarmScheduler.catchUpRule()
                    if (rule != null) {
                        ModeController.apply(rule.mode, ModeController.Source.BOOT)
                            .onSuccess { Prefs.log("догон после загрузки: ${it.label}") }
                            .onFailure { Prefs.log("догон после загрузки: ошибка — ${it.message}") }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
