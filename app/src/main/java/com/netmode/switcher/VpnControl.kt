package com.netmode.switcher

import kotlinx.coroutines.delay

/**
 * The system VPN, whichever app provides it — the "VPN" row of a rule.
 *
 * With root it goes through the system VPN service ([VpnCli]), which needs no tile, no screen
 * and no unlocked phone: "off" revokes whatever VPN is connected, "on" has the system start the
 * chosen app's VpnService. Shell (Shizuku) is refused both calls (measured: "does not belong to
 * uid 2000", "no CONTROL_ALWAYS_ON_VPN"), so there the app's own tile is pressed instead.
 *
 * Some apps reconnect by themselves after a revoke — AdGuard treats it as another VPN taking
 * over and recovers 5 s later — so "off" watches for that and says so in the log.
 */
object VpnControl {

    private const val CONNECT_WAIT_MS = 30_000L
    private const val RECONNECT_WATCH_MS = 12_000L

    /** Package owning the connected VPN, or null. */
    suspend fun owner(): String? =
        Regex("VPN CONNECTED extra: VPN:([A-Za-z0-9_.]+)")
            .find(Privileged.exec("dumpsys connectivity 2>/dev/null | grep -m1 'VPN CONNECTED extra: VPN:'"))
            ?.groupValues?.get(1)

    private suspend fun root(): Boolean = Privileged.choose() == Backend.ROOT

    private fun cli(): String = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.VpnCli"

    private fun tileOf(pkg: String): String? = Tiles.list().firstOrNull { it.pkg == pkg }?.component

    private fun label(pkg: String): String = AppPickerActivity.labels(listOf(pkg)).first()

    /** Disconnects the current VPN. Returns a line for the log. */
    suspend fun off(): String {
        val pkg = owner() ?: return "VPN уже выкл"
        if (root()) {
            Privileged.exec("${cli()} off $pkg 0")
        } else {
            val tile = tileOf(pkg) ?: return "VPN ${label(pkg)}: без root выключается только плиткой, а её нет"
            return "VPN " + Tiles.set(mapOf(tile to false), vpn = setOf(tile)).joinToString()
        }
        // Watch a little longer than needed: an app that reconnects on its own does it within
        // seconds, and the log should say so rather than claim the VPN is off.
        var wentDown = false
        val deadline = System.currentTimeMillis() + RECONNECT_WATCH_MS
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val now = owner()
            if (now == null) wentDown = true
            else if (wentDown) return "VPN ${label(pkg)}: выключен, но приложение сразу подключилось снова само"
        }
        return if (owner() == null) "VPN ${label(pkg)} выкл" else "VPN ${label(pkg)}: не выключился"
    }

    /** Connects [pkg]'s VPN. Returns a line for the log. */
    suspend fun on(pkg: String): String {
        if (owner() == pkg) return "VPN ${label(pkg)} уже вкл"
        if (root()) {
            Privileged.exec("${cli()} on $pkg 0")
        } else {
            val tile = tileOf(pkg) ?: return "VPN ${label(pkg)}: без root включается только плиткой, а её нет"
            return "VPN " + Tiles.set(mapOf(tile to true), vpn = setOf(tile)).joinToString()
        }
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            if (owner() == pkg) return "VPN ${label(pkg)} вкл"
        }
        return "VPN ${label(pkg)}: не подключился"
    }

    /** Apps that provide a VPN, for the rule dialog. */
    fun apps(): List<Pair<String, String>> {
        val pm = App.ctx.packageManager
        return runCatching { pm.queryIntentServices(android.content.Intent("android.net.VpnService"), 0) }
            .getOrDefault(emptyList())
            .map { it.serviceInfo.packageName }
            .filter { it != App.ctx.packageName }
            .distinct()
            .map { it to label(it) }
            .sortedBy { it.second.lowercase() }
    }
}
