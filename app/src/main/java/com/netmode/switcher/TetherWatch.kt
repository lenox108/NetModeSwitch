package com.netmode.switcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Re-applies [TetherRoute] when the hotspot comes up or goes down and when the VPN changes:
 * the rules name interfaces, and both the hotspot's and the VPN's are created anew each time.
 * Also watches direct TTL tethering and the tethered clients' IPv6 setting. The foreground service
 * keeps these callbacks alive, as it does for Wi-Fi rules.
 */
object TetherWatch {

    private var registered = false
    private var pending: Job? = null
    private var tweaksPending: Job? = null

    fun needed(): Boolean = Prefs.tetherMode != TetherMode.DIRECT || NetworkTweaks.needed()

    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = tweaksChanged()
        override fun onLost(network: Network) = tweaksChanged()
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = tweaksChanged()
    }

    private fun tweaksChanged() {
        if (!NetworkTweaks.needed()) return
        tweaksPending?.cancel()
        tweaksPending = App.scope.launch {
            delay(1_500)
            runCatching { NetworkTweaks.apply() }
                .onFailure { Prefs.log("TTL / IPv6: ошибка ${it.message}") }
        }
    }

    private val tether = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = schedule()
    }

    /**
     * VPN changes matter only to the VPN route: in proxy mode each one would restart the tunnel
     * and cut every client connection for nothing. A VPN that reconnects on the same network
     * with a new interface shows up as a link-properties change.
     */
    private val vpn = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = vpnChanged()
        override fun onLost(network: Network) = vpnChanged()
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
            if (Prefs.tetherMode == TetherMode.VPN && lp.interfaceName != null && lp.interfaceName != TetherRoute.lastVpn) schedule()
        }
    }

    private fun vpnChanged() {
        if (Prefs.tetherMode == TetherMode.VPN) schedule()
    }

    private var health: Job? = null

    /** Check rules as well as processes. apply() adopts a healthy route without restarting it. */
    private fun watchHealth() {
        health?.cancel()
        health = App.scope.launch {
            while (true) {
                delay(60_000)
                // OEM tethering setup can replace firewall chains. This
                // bounded check never requests tile listening and does not rebuild a VPN.
                if (Prefs.tetherMode == TetherMode.DIRECT) {
                    if (NetworkTweaks.needed()) runCatching { NetworkTweaks.apply() }
                    continue
                }
                val line = runCatching { TetherRoute.apply() }.getOrElse { "раздача: ошибка ${it.message}" }
                if (line != Prefs.lastTetherLine) Prefs.log(line)
                Prefs.lastTetherLine = line
            }
        }
    }

    /** Coalesces the bursts of events a hotspot start produces into one apply. */
    private fun schedule() {
        pending?.cancel()
        pending = App.scope.launch {
            delay(1_500)
            val line = runCatching { TetherRoute.apply() }.getOrElse { "раздача: ошибка ${it.message}" }
            // The tether broadcast is sticky and arrives at every process start: the same
            // "will start with the hotspot" line is not worth a log entry each time.
            if (line != Prefs.lastTetherLine) Prefs.log(line)
            Prefs.lastTetherLine = line
            runCatching { HotspotControl.isOn() }
            HotspotTiles.refresh()
        }
    }

    @Synchronized
    fun sync() {
        val ctx = App.ctx
        val want = needed()
        if (want && !registered) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(tether, IntentFilter("android.net.conn.TETHER_STATE_CHANGED"), Context.RECEIVER_EXPORTED)
            ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                vpn,
            )
            ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                networks,
            )
            registered = true
            watchHealth()
            WifiWatchService.start(ctx, WifiWatchService.MODE_ALWAYS)
        } else if (!want && registered) {
            health?.cancel()
            pending?.cancel()
            tweaksPending?.cancel()
            runCatching { ctx.unregisterReceiver(tether) }
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(vpn) }
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networks) }
            registered = false
        }
    }
}
