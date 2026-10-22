package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.launch

/**
 * Rules set to wait while the phone is in use: once it is locked (screen off, or back on
 * the lock screen) a one-minute alarm is set, and unlocking cancels it. The minute keeps a
 * quick glance at the lock screen from counting as "done with the phone".
 *
 * Listens only while something is actually waiting.
 */
object DeferWatch {

    private const val DELAY_MS = 60_000L
    private var registered = false

    /** When the running minute ends; 0 when none runs. */
    @Volatile private var dueAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> schedule(context)
                // Unlocked: in use again, the wait starts over at the next lock.
                Intent.ACTION_USER_PRESENT -> cancel(context)
            }
        }
    }

    fun sync() {
        val ctx = App.ctx
        val want = Prefs.pendingStarts.isNotEmpty()
        if (want && !registered) {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, Context.RECEIVER_EXPORTED)
            registered = true
            WifiWatchService.start(ctx, WifiWatchService.MODE_ALWAYS)
        } else if (!want && registered) {
            runCatching { ctx.unregisterReceiver(receiver) }
            registered = false
            cancel(ctx)
        }
        // Already locked (the process restarted, or the rule fired on the lock screen):
        // nothing more will arrive to start the minute, so start it now.
        if (want && !AppFreezer.inUse()) schedule(ctx)
    }

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 500, Intent(ctx, DeferReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Starts the minute once. The phone sends "screen off" again and again while it dozes;
     * restarting the minute on each would push it back for as long as the screen stays off.
     */
    private fun schedule(ctx: Context) {
        val now = System.currentTimeMillis()
        if (dueAt > now) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = now + DELAY_MS
        dueAt = at
        android.util.Log.i("netmode", "defer: minute started, due in ${DELAY_MS / 1000}s")
        if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
    }

    private fun cancel(ctx: Context) {
        if (dueAt != 0L) android.util.Log.i("netmode", "defer: unlocked, minute cancelled")
        dueAt = 0L
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(ctx))
    }
}

/** A minute after locking: applies what waited; see [DeferWatch]. */
class DeferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        App.scope.launch {
            try {
                android.util.Log.i("netmode", "defer: minute over, in use=${AppFreezer.inUse()}")
                runCatching { AppFreezer.runPending() }.onFailure { android.util.Log.w("netmode", "defer failed", it) }
            } finally {
                pending.finish()
            }
        }
    }
}
