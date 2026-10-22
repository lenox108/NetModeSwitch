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
import kotlinx.coroutines.sync.withLock

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

    /** Every watcher brought in line with the rules as they now stand. */
    fun syncAll() {
        runCatching { register() }.onFailure { Log.w(TAG, "wifi watch sync failed", it) }
        runCatching { TetherWatch.sync() }.onFailure { Log.w(TAG, "tether watch sync failed", it) }
        runCatching { NetWatch.sync() }.onFailure { Log.w(TAG, "net watch sync failed", it) }
        runCatching { SimWatch.sync() }.onFailure { Log.w(TAG, "SIM watch sync failed", it) }
        runCatching { ConditionWatch.sync() }.onFailure { Log.w(TAG, "condition watch sync failed", it) }
        runCatching { DeferWatch.sync() }.onFailure { Log.w(TAG, "defer watch sync failed", it) }
    }

    fun register() {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        val pi = pendingIntent()

        // Registration is keyed by PendingIntent identity; drop the old one before changing it.
        runCatching { cm.unregisterNetworkCallback(pi) }

        val data = AppFreezer.hasDataRules()
        if (data) DataWatch.register() else DataWatch.unregister()

        val active = AppFreezer.hasWifiRules()
        if (!active) {
            Log.i(TAG, "no enabled Wi-Fi rules")
            // The data observer lives in this process, so the service keeps it alive.
            if (data || TetherWatch.needed() || NetWatch.needed() || ConditionWatch.needed() || Prefs.statusNotification) {
                WifiWatchService.start(App.ctx, WifiWatchService.MODE_ALWAYS)
            }
            else WifiWatchService.stop(App.ctx)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, pi) }
            .onFailure { Log.e(TAG, "Wi-Fi callback registration failed", it) }

        // Kept alongside the PendingIntent rather than instead of it: the callback can restart
        // the app if the service is ever killed, and duplicate events are already deduplicated.
        if (Prefs.reliableWifi || data || Prefs.statusNotification || TetherWatch.needed()) WifiWatchService.start(App.ctx, WifiWatchService.MODE_ALWAYS)
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

    /** The two connect sources can fire at once; the lastSsid check-and-set is one step. */
    private val gate = kotlinx.coroutines.sync.Mutex()

    suspend fun onConnected(context: Context) {
        val ssid = currentSsid() ?: return
        val alreadyHandled = gate.withLock {
            val was = Prefs.lastSsid
            // A different network while the last one is still on record: its disconnect was
            // missed (Wi-Fi switched off by a rule, then back on somewhere else). Handled now,
            // or its windows would never close.
            if (was != null && !was.equals(ssid, ignoreCase = true)) {
                Prefs.log("Wi-Fi: отключение от «$was» пропущено — учитываю сейчас")
                AppFreezer.onWifiLost(was, adapterOff = false)
                Prefs.lastSsid = null
            }
            if (Prefs.appRules.none { it.enabled && it.matchesSsid(ssid) }) return
            // Two sources report a connect — the PendingIntent callback and, once running, the
            // watch service's own callback — and the system may repeat either. lastSsid is the
            // record that this network has already been acted on.
            ssid.equals(Prefs.lastSsid, ignoreCase = true).also { Prefs.lastSsid = ssid }
        }

        if (!alreadyHandled) WifiWake.onConnected(ssid)
        AppFreezer.onWifiConnected(ssid, newConnection = !alreadyHandled)

        // Only pay for a foreground service when a rule actually acts on disconnect.
        if (AppFreezer.needsLoss(ssid) && !WifiWatchService.start(context, WifiWatchService.MODE_WATCH_LOSS)) {
            Prefs.log("Слежение за отключением не запустилось — отключи оптимизацию батареи")
        }
    }

    /**
     * Returns true when the disconnect was handled and a WATCH_LOSS service may stop.
     *
     * Switching the adapter off is not the same as walking out of range, and night-time power
     * saving does exactly the former, so each rule decides whether it counts as leaving. A rule
     * that does not is left applied, and lastSsid is kept so the reconnect that follows does
     * not apply it a second time.
     */
    suspend fun onLost(): Boolean {
        val was = Prefs.lastSsid ?: return true
        val adapterOff = !adapterEnabled()

        // A rule of this app switched Wi-Fi off or took it over for the hotspot: that is not
        // the user leaving, so nothing reacts and the network stays remembered.
        if (AppFreezer.changedWifiRecently()) {
            Prefs.log("Wi-Fi переключён правилом — отключение от «$was» не учитываю")
            return true
        }

        val now = currentSsid()
        if (now != null && now.equals(was, ignoreCase = true)) {
            // Still associated, yet the network is gone: at the edge of range the router stops
            // answering, the network is torn down for re-provisioning, and the disconnect that
            // follows seconds later brings no second onLost. Looked at again until it settles.
            followUp(was)
            return false
        }

        followUpJob?.cancel()
        if (!AppFreezer.onWifiLost(was, adapterOff)) Prefs.lastSsid = null
        return true
    }

    @Volatile private var followUpJob: kotlinx.coroutines.Job? = null

    private fun followUp(was: String) {
        followUpJob?.cancel()
        followUpJob = App.scope.launch {
            repeat(FOLLOW_UP_TRIES) {
                delay(FOLLOW_UP_STEP_MS)
                // A Wi-Fi network is back: its own callbacks take over from here.
                if (wifiNetworkUp()) return@launch
                if (!was.equals(Prefs.lastSsid, ignoreCase = true)) return@launch
                val now = currentSsid()
                if (now == null || !now.equals(was, ignoreCase = true)) {
                    Prefs.log("Wi-Fi: «$was» пропала без отключения — считаю отключением")
                    onLost()
                    return@launch
                }
            }
        }
    }

    private fun wifiNetworkUp(): Boolean = runCatching {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    private const val FOLLOW_UP_TRIES = 12
    private const val FOLLOW_UP_STEP_MS = 10_000L

    /**
     * After a reboot: the network on record may be gone with no disconnect event ever coming
     * (switched off at home, switched on at work). Treated as a disconnect when so.
     */
    suspend fun recheck() {
        val was = Prefs.lastSsid ?: return
        val now = currentSsid()
        if (now != null && now.equals(was, ignoreCase = true)) return
        Prefs.log("после перезагрузки нет «$was» — считаю отключением")
        if (!AppFreezer.onWifiLost(was, adapterOff = !adapterEnabled())) Prefs.lastSsid = null
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
