package com.netmode.switcher

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A rule's second VPN: for when its first one will not connect, or carries no internet. The first
 * one stays the main one: once the internet works again without a VPN — the reason it failed is
 * likely gone, e.g. a carrier's whitelist mode ended — the rule goes back to it.
 */
internal object VpnFallback {

    private const val CHECK_MS = 30_000L

    /** Two minutes of a tunnel with no internet, so that a passing hiccup never swaps it. */
    private const val STRIKES = 4

    /** On the backup: how often to look whether the way of the main VPN is open again. */
    private const val RETURN_CHECK_MS = 60_000L

    /** Two looks in a row, so one lucky check does not bring the main VPN back. */
    private const val RETURN_PASSES = 2

    /** Wait before trying the main VPN again; doubles while it keeps failing soon after a return. */
    internal const val HOLD_MIN_MS = 2 * 60_000L
    internal const val HOLD_MAX_MS = 30 * 60_000L

    /** A main VPN that lasted this long after a return is trusted again: the wait starts over. */
    internal const val STABLE_MS = 10 * 60_000L

    /** A real request through the tunnel, not the system's mark: that one lags behind. */
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_TIMEOUT_MS = 8_000

    private val guards = ConcurrentHashMap<Long, Job>()

    /**
     * A tunnel is only to blame when the phone has a network without it. Not "internet without
     * it": under a carrier's whitelist the network is up but the system's own check fails too, and
     * that is exactly when the backup is needed.
     */
    fun nextStrikes(strikes: Int, tunnelValidated: Boolean, internetWithoutTunnel: Boolean): Int = when {
        tunnelValidated -> 0
        internetWithoutTunnel -> strikes + 1
        else -> strikes
    }

    fun shouldSwitch(strikes: Int) = strikes >= STRIKES

    fun nextPasses(passes: Int, directInternet: Boolean) = if (directInternet) passes + 1 else 0

    fun shouldReturn(passes: Int) = passes >= RETURN_PASSES

    /** [mainLastedMs]: how long the main VPN held since the last return, null if it never came back. */
    fun nextHold(hold: Long, mainLastedMs: Long?): Long = when {
        mainLastedMs == null || mainLastedMs >= STABLE_MS -> HOLD_MIN_MS
        else -> (hold * 2).coerceAtMost(HOLD_MAX_MS)
    }

    /** Connects [primary], and [fallback] if that fails. Returns the lines for the log. */
    suspend fun connect(rule: AppRule, primary: String, fallback: String?): List<String> {
        val first = VpnControl.on(primary)
        if (fallback == null || !VpnControl.validPkg(fallback) || fallback == primary) return listOf(first)
        if (owner() == primary) {
            watch(rule, primary, fallback, onMain = true)
            return listOf(first)
        }
        val second = VpnControl.on(fallback)
        if (owner() == fallback) watch(rule, primary, fallback, onMain = false)
        return listOf(first, "запасной: $second")
    }

    /**
     * Lasts while the rule is in force and one of its two VPNs is up; a VPN the user changed is
     * left alone. On the main VPN it watches for a tunnel with no internet, on the backup for the
     * moment to go back.
     */
    private fun watch(rule: AppRule, primary: String, fallback: String, onMain: Boolean) {
        guards.remove(rule.id)?.cancel()
        guards[rule.id] = App.scope.launch {
            var main = onMain
            var strikes = 0
            var passes = 0
            var hold = HOLD_MIN_MS
            var returnedAt = 0L
            var returnAt = if (main) 0L else now() + HOLD_MIN_MS
            while (true) {
                delay(if (main) CHECK_MS else RETURN_CHECK_MS)
                if (!AppFreezer.isActive(rule)) break
                if (owner() != (if (main) primary else fallback)) break
                if (main) {
                    strikes = nextStrikes(strikes, tunnelWorks(), networkWithoutTunnel())
                    if (!shouldSwitch(strikes)) continue
                    hold = nextHold(hold, if (returnedAt == 0L) null else now() - returnedAt)
                    val line = swap(primary, fallback)
                    Prefs.log("(${rule.logPrefix}VPN без интернета): $line; обратно — не раньше чем через ${hold / 60_000} мин")
                    if (owner() != fallback) break
                    // The system's mark for the network below may be from before the failure.
                    recheckUnderlying(hasInternet = false)
                    main = false
                    strikes = 0
                    passes = 0
                    returnAt = now() + hold
                } else {
                    if (now() < returnAt) continue
                    passes = nextPasses(passes, directInternet())
                    if (!shouldReturn(passes)) continue
                    passes = 0
                    val line = swap(fallback, primary)
                    if (owner() == primary) {
                        Prefs.log("(${rule.logPrefix}интернет без VPN снова есть): основной $line")
                        main = true
                        strikes = 0
                        returnedAt = now()
                        continue
                    }
                    // The main one would not come up: back to the backup, and a longer wait.
                    val back = runCatching { VpnControl.on(fallback) }.getOrElse { "VPN: ошибка ${it.message}" }
                    hold = (hold * 2).coerceAtMost(HOLD_MAX_MS)
                    Prefs.log("(${rule.logPrefix}возврат на основной VPN): $line; снова запасной: $back")
                    if (owner() != fallback) break
                    returnAt = now() + hold
                }
            }
            guards.remove(rule.id)
        }
    }

    private suspend fun swap(from: String, to: String): String {
        val off = runCatching { VpnControl.off(from) }.getOrElse { "VPN: ошибка ${it.message}" }
        val on = runCatching { VpnControl.on(to) }.getOrElse { "VPN: ошибка ${it.message}" }
        return "$off, $on"
    }

    private suspend fun owner(): String? = runCatching { VpnControl.owner() }.getOrNull()

    private fun now() = SystemClock.elapsedRealtime()

    private fun cm() = App.ctx.getSystemService(ConnectivityManager::class.java)

    /** Through the VPN network itself, so the answer is about the tunnel whatever the default is. */
    @Suppress("DEPRECATION")
    private suspend fun tunnelWorks(): Boolean = withContext(Dispatchers.IO) {
        val cm = cm()
        val vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            ?: return@withContext false
        try {
            val c = vpn.openConnection(URL(PROBE_URL)) as HttpURLConnection
            c.connectTimeout = PROBE_TIMEOUT_MS
            c.readTimeout = PROBE_TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            try { c.responseCode == 204 } finally { c.disconnect() }
        } catch (e: SocketException) {
            // This app left out of the tunnel (the VPN app's per-app list): only the system's mark is left.
            if (e.message.orEmpty().let { "EPERM" in it || "Permission denied" in it }) validated(vpn = true) else false
        } catch (e: Exception) {
            false
        }
    }

    /** A mobile or Wi-Fi network under the tunnel, working or not: with none, no VPN is to blame. */
    @Suppress("DEPRECATION")
    private fun networkWithoutTunnel(): Boolean = runCatching {
        val cm = cm()
        cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.any {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrDefault(false)

    /**
     * The system's own check passes without the tunnel: the internet works directly again. The
     * system re-checks a failing network only now and then, so it is asked to look again first.
     */
    private fun directInternet(): Boolean {
        if (runCatching { validated(vpn = false) }.getOrDefault(false)) return true
        recheckUnderlying(hasInternet = true)
        return false
    }

    /** Has the system check the networks below the tunnel again; it only acts on a mark it disagrees with. */
    @Suppress("DEPRECATION")
    private fun recheckUnderlying(hasInternet: Boolean) = runCatching {
        val cm = cm()
        cm.allNetworks.filter {
            val caps = cm.getNetworkCapabilities(it)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.forEach { n: Network -> runCatching { cm.reportNetworkConnectivity(n, hasInternet) } }
    }

    @Suppress("DEPRECATION")
    private fun validated(vpn: Boolean): Boolean {
        val cm = cm()
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
        // A failed look is not "no VPN": what comes next may be starting a second one.
        Steps.CHECK_NO_VPN -> runCatching { VpnControl.owner() == null }.getOrDefault(false)
        Steps.CHECK_NET -> internet()
        Steps.CHECK_NO_NET -> !internet()
        Steps.CHECK_WIFI -> wifi()
        Steps.CHECK_NO_WIFI -> !wifi()
        Steps.CHECK_HOTSPOT -> runCatching { HotspotControl.isOn() }.getOrDefault(false)
        Steps.CHECK_NO_HOTSPOT -> runCatching { !HotspotControl.isOn() }.getOrDefault(false)
        Steps.CHECK_CHARGING -> charging()
        Steps.CHECK_NO_CHARGING -> !charging()
        Steps.CHECK_UNLOCKED -> unlocked()
        Steps.CHECK_LOCKED -> !unlocked()
        Steps.CHECK_NO_CALL -> !Freezer.inCall()
        Steps.CHECK_NO_ROAMING -> !ConditionWatch.roamingNow(App.ctx)
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun wifi(): Boolean = runCatching {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    private fun charging(): Boolean =
        App.ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true

    private fun unlocked(): Boolean =
        App.ctx.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !App.ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    /** With an app chosen, another VPN does not count: AdGuard being up says nothing of Amnezia. */
    fun vpnUp(owner: String?, primary: String?, fallback: String?): Boolean =
        owner != null && (primary == null || owner == primary || owner == fallback)

    private fun internet(): Boolean {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
