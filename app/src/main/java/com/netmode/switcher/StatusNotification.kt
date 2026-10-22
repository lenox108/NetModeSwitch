package com.netmode.switcher

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * The watcher service's notification, which doubles as a status line when the user asks for
 * it: the mode of each SIM and a switch button per SIM. Without that setting it stays the
 * plain minimal "watching" notification the service needs anyway.
 */
object StatusNotification {

    const val ID = 1
    const val CHANNEL = "wifi_watch"

    fun build(ctx: Context): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setContentIntent(open)
        if (!Prefs.statusNotification) {
            return b.setContentTitle(ctx.getString(R.string.watching_wifi))
                .setContentText(ctx.getString(R.string.app_name))
                .build()
        }
        val sims = Sims.known.ifEmpty { listOf(SimInfo(0, "")) }
        b.setContentTitle(sims.joinToString("  ·  ") { "SIM ${it.slot + 1}: ${ModeController.modeOf(it.slot)?.short ?: "—"}" })
            .setContentText(ctx.getString(R.string.status_rules_line, Prefs.appRules.count { it.enabled }, Prefs.openWindows.size))
        for (sim in sims) {
            val target = ModeController.toggleTarget(sim.slot, ModeController.modeOf(sim.slot))
            b.addAction(Notification.Action.Builder(null,
                ctx.getString(R.string.status_toggle, sim.slot + 1, target.short), ModeActionReceiver.toggle(ctx, sim.slot)).build())
        }
        return b.build()
    }

    /** Repaints the notification (if the service is up) and the widgets after a mode change. */
    fun update() {
        val ctx = App.ctx
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.activeNotifications.any { it.id == ID }) nm.notify(ID, build(ctx))
        }
        Widgets.update()
    }
}

/** Buttons of the notification and the widget: switch one SIM's mode. */
class ModeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra(EXTRA_SLOT, -1).takeIf { it >= 0 } ?: return
        val pending = goAsync()
        App.scope.launch {
            try {
                ModeController.toggle(slot, ModeController.Source.MANUAL)
                    .onFailure { Prefs.log("SIM ${slot + 1}: ${it.message}") }
                StatusNotification.update()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_SLOT = "slot"

        fun toggle(ctx: Context, slot: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, 400 + slot,
            Intent(ctx, ModeActionReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
