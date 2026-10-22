package com.netmode.switcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds network-mode changes to the "Сеть" rule trigger and the primary/secondary SIM
 * scenario.
 *
 * Changes made through the app arrive from ModeController directly. Changes made elsewhere
 * (the system's own network-type screen) are caught by observing the SIM database, where the
 * allowed network types are stored — an event, not polling — and then read back per slot.
 * Switching the mobile-data SIM in the system comes as a broadcast.
 */
object NetWatch {

    private var registered = false
    private var pending: Job? = null

    /** Called by ModeController when a slot's mode differs from the last one seen. */
    fun changed(slot: Int, mode: NetMode) {
        // The phone check switches modes on purpose; no rule may react to that.
        if (Calibration.running) return
        App.scope.launch { runCatching { AppFreezer.onNetModeChanged(slot, mode) } }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            pending?.cancel()
            pending = App.scope.launch {
                delay(800)
                runCatching { ModeController.readAll() }
            }
        }
    }

    private val dataSim = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            App.scope.launch { runCatching { DualSim.sync("сменилась SIM для интернета") } }
        }
    }

    fun needed(): Boolean =
        Prefs.dualSimEnabled || Prefs.appRules.any { it.enabled && it.hasActions && it.trigger == Trigger.NET_MODE }

    fun sync() {
        val ctx = App.ctx
        val want = needed()
        if (want && !registered) {
            runCatching {
                ctx.contentResolver.registerContentObserver(Uri.parse("content://telephony/siminfo"), true, observer)
            }
            ctx.registerReceiver(
                dataSim,
                IntentFilter("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"),
                Context.RECEIVER_EXPORTED,
            )
            registered = true
            WifiWatchService.start(ctx, WifiWatchService.MODE_ALWAYS)
        } else if (!want && registered) {
            runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
            runCatching { ctx.unregisterReceiver(dataSim) }
            registered = false
        }
    }
}
