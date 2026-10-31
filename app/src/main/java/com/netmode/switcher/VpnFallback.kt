package com.netmode.switcher

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A rule's second VPN: for when its first one will not connect, or carries no internet. */
internal object VpnFallback {

    private const val CHECK_MS = 30_000L

    /** Two minutes of a tunnel with no internet, so that a passing hiccup never swaps it. */
    private const val STRIKES = 4

    private val guards = ConcurrentHashMap<Long, Job>()

    /** A tunnel is only to blame when there is internet without it. */
    fun nextStrikes(strikes: Int, tunnelValidated: Boolean, internetWithoutTunnel: Boolean): Int = when {
        tunnelValidated -> 0
        internetWithoutTunnel -> strikes + 1
        else -> strikes
    }

    fun shouldSwitch(strikes: Int) = strikes >= STRIKES

    /** Connects [primary], and [fallback] if that fails. Returns the lines for the log. */
    suspend fun connect(rule: AppRule, primary: String, fallback: String?): List<String> {
        val first = VpnControl.on(primary)
        if (fallback == null || !VpnControl.validPkg(fallback) || fallback == primary) return listOf(first)
        if (runCatching { VpnControl.owner() }.getOrNull() == primary) {
            watch(rule, primary, fallback)
            return listOf(first)
        }
        return listOf(first, "запасной: " + VpnControl.on(fallback))
    }

    /** Lasts while the rule is in force and [primary] stays up; a VPN the user changed is left alone. */
    private fun watch(rule: AppRule, primary: String, fallback: String) {
        guards.remove(rule.id)?.cancel()
        guards[rule.id] = App.scope.launch {
            var strikes = 0
            while (true) {
                delay(CHECK_MS)
                if (!AppFreezer.isActive(rule)) break
                if (runCatching { VpnControl.owner() }.getOrNull() != primary) break
                strikes = nextStrikes(strikes, validated(vpn = true), validated(vpn = false))
                if (shouldSwitch(strikes)) {
                    val off = runCatching { VpnControl.off(primary) }.getOrElse { "VPN: ошибка ${it.message}" }
                    val on = runCatching { VpnControl.on(fallback) }.getOrElse { "VPN: ошибка ${it.message}" }
                    Prefs.log("(${rule.logPrefix}VPN без интернета): $off, запасной: $on")
                    break
                }
            }
            guards.remove(rule.id)
        }
    }

    @Suppress("DEPRECATION")
    private fun validated(vpn: Boolean): Boolean {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.any {
            it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == vpn &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}

/** What a check step ([Steps.check]) looks at. */
internal object StepChecks {
    suspend fun passes(rule: AppRule, kind: String): Boolean = when (kind) {
        Steps.CHECK_VPN -> {
            val owner = runCatching { VpnControl.owner() }.getOrNull()
            vpnUp(owner, rule.vpnApp, rule.vpnFallback)
        }
        else -> internet()
    }

    /** With an app chosen, another VPN does not count: AdGuard being up says nothing of Amnezia. */
    fun vpnUp(owner: String?, primary: String?, fallback: String?): Boolean =
        owner != null && (primary == null || owner == primary || owner == fallback)

    private fun internet(): Boolean {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
