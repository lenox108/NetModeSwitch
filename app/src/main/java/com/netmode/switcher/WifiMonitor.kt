package com.netmode.switcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wi-Fi watching without a persistent notification.
 *
 * The PendingIntent form of registerNetworkCallback lives inside ConnectivityService, so it
 * survives the app process being killed and relaunches us on a match. Its one limitation is
 * that it only reports networks becoming *available* — there is no process-free "onLost".
 * Disconnects are therefore handled by [WifiWatchService], started only while a rule that
 * actually needs them is in force.
 */
object WifiMonitor {

    private const val REQUEST = 200
    private const val TAG = "netmode"

    fun register() {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        val pi = pendingIntent()

        // Registration is keyed by PendingIntent identity; drop the old one before changing it.
        runCatching { cm.unregisterNetworkCallback(pi) }

        val active = Prefs.wifiRules.any { it.enabled }
        if (!active) {
            Log.i(TAG, "no enabled Wi-Fi rules")
            WifiWatchService.stop(App.ctx)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, pi) }
            .onFailure { Log.e(TAG, "Wi-Fi callback registration failed", it) }

        // Kept alongside the PendingIntent rather than instead of it: the callback can restart
        // the app if the service is ever killed, and duplicate events are already deduplicated.
        if (Prefs.reliableWifi) WifiWatchService.start(App.ctx, WifiWatchService.MODE_ALWAYS)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        App.ctx,
        REQUEST,
        Intent(App.ctx, WifiEventReceiver::class.java),
        // MUTABLE is required: the system fills in EXTRA_NETWORK on delivery.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/** Shared by the PendingIntent receiver and the watch service. */
object WifiRules {

    suspend fun onConnected(context: Context) {
        val ssid = currentSsid() ?: return
        val rule = Prefs.wifiRules.firstOrNull { it.enabled && it.matches(ssid) } ?: return

        // Two sources report a connect — the PendingIntent callback and, once running, the
        // watch service's own callback — and the system may repeat either. lastSsid is the
        // record that this network has already been acted on.
        val alreadyHandled = ssid.equals(Prefs.lastSsid, ignoreCase = true)
        Prefs.lastSsid = ssid

        if (!alreadyHandled) {
            rule.onConnect?.let { target ->
                ModeController.apply(target, ModeController.Source.WIFI)
                    .onSuccess { Prefs.log("Wi-Fi \"$ssid\" подключён: ${it.label}") }
                    .onFailure { Prefs.log("Wi-Fi \"$ssid\": ошибка — ${it.message}") }
            }
        }

        // Only pay for a foreground service when a rule actually acts on disconnect.
        if (rule.onDisconnect != null && !WifiWatchService.start(context, WifiWatchService.MODE_WATCH_LOSS)) {
            Prefs.log("Слежение за отключением не запустилось — отключи оптимизацию батареи")
        }
    }

    /** Returns true when the disconnect was handled and a WATCH_LOSS service may stop. */
    suspend fun onLost(): Boolean {
        val was = Prefs.lastSsid ?: return true

        if (Prefs.ignoreWifiOff && !adapterEnabled()) {
            // lastSsid is deliberately kept: nothing was changed, so the reconnect that follows
            // must not re-apply the connect action either.
            Prefs.log("Wi-Fi выключен целиком — режим не меняю")
            return true
        }

        val now = currentSsid()
        if (now != null && now.equals(was, ignoreCase = true)) return false  // still on it

        val rule = Prefs.wifiRules.firstOrNull { it.enabled && it.matches(was) }
        Prefs.lastSsid = null
        val target = rule?.onDisconnect ?: return true

        ModeController.apply(target, ModeController.Source.WIFI)
            .onSuccess { Prefs.log("Wi-Fi \"$was\" отключён: ${it.label}") }
            .onFailure { Prefs.log("Wi-Fi \"$was\": ошибка — ${it.message}") }
        return true
    }

    private fun adapterEnabled(): Boolean = runCatching {
        App.ctx.getSystemService(WifiManager::class.java).isWifiEnabled
    }.getOrDefault(true)

    /** The shell read can lag the callback by a moment right after association. */
    private suspend fun currentSsid(): String? {
        repeat(3) { attempt ->
            WifiSsid.current()?.let { return it }
            if (attempt < 2) delay(700)
        }
        return null
    }
}

class WifiEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        App.scope.launch {
            try {
                WifiRules.onConnected(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
