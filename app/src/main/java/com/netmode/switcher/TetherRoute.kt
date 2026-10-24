package com.netmode.switcher

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the traffic of devices on the hotspot goes. */
enum class TetherMode { DIRECT, VPN, PROXY }

/**
 * Sends hotspot clients' traffic through the phone's VPN or through a local SOCKS5 proxy
 * (Happ in proxy mode listens on 127.0.0.1:10808). Root only: it is policy routing and
 * iptables, which Shizuku's shell is not allowed to change.
 *
 * Android forwards tethered traffic straight to the upstream network, VPN or not. Here:
 *  - VPN: an `ip rule` sends everything arriving from the hotspot interface to the VPN's own
 *    routing table, with NAT on the VPN interface;
 *  - proxy: hev-socks5-tunnel (bundled, MIT) runs a tun device that turns packets into SOCKS5
 *    connections, and the same kind of rule points the hotspot at it.
 * In both modes client DNS is redirected into the tunnel, IPv6 is off on the hotspot and refused, and a
 * rule right behind the redirect makes their traffic unreachable when the tunnel is down —
 * nothing leaks out directly. Only traffic arriving from the hotspot is touched; the phone's
 * own traffic is not.
 */
object TetherRoute {

    private const val TAG = "netmode"
    private const val PREF = TetherScripts.PREF
    private const val TABLE = TetherScripts.TABLE
    private const val TUN = TetherScripts.TUN
    const val DIR = "/data/local/tmp/netmode"
    private const val DNS = "1.1.1.1"
    private const val MAP_DNS = "198.18.0.2"
    private val lock = Mutex()
    private val state by lazy { App.ctx.getSharedPreferences("tether_route_state", android.content.Context.MODE_PRIVATE) }
    private val lease by lazy {
        TetherLease(
            read = { state.getString("identity", null)?.let { TetherRecord(it, state.getString("line", null).orEmpty()) } },
            write = { record ->
                val editor = state.edit().clear()
                if (record != null) editor.putString("identity", record.identity).putString("line", record.line)
                editor.commit()
            },
        )
    }
    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")

    private fun hev() = App.ctx.applicationInfo.nativeLibraryDir + "/libhevtun.so"

    /**
     * The hotspot's interface while it is tethering, e.g. "wlan2". Every tethered interface is
     * looked at: with USB tethering on as well, "rndis0"/"ncm0" may be listed first.
     */
    suspend fun apInterface(): String? = tetherInterfaces().firstOrNull { AP_IFACE.containsMatchIn(it) }

    /** All active tethered downstreams, including USB and Bluetooth sharing. */
    suspend fun tetherInterfaces(): List<String> =
        // Tethering is its own service from Android 11; before, it is part of connectivity's dump.
        Regex("(\\S+) - TetheredState").findAll(Privileged.exec(
            "dumpsys ${if (Compat.SDK >= 30) "tethering" else "connectivity"} 2>/dev/null | grep 'TetheredState'"))
            .map { it.groupValues[1] }
            .filter { IFACE.matches(it) }.distinct().toList()

    private val AP_IFACE = Regex("^(wlan|softap|ap_br|swlan)")

    /** Interface of the connected VPN, e.g. "tun0", or null. */
    suspend fun vpnInterface(): String? {
        val cm = App.ctx.getSystemService(android.net.ConnectivityManager::class.java)
        vpnNetwork()?.let { network ->
            cm.getLinkProperties(network)?.interfaceName?.takeIf(IFACE::matches)?.let { return it }
        }
        // The whole network-agent line: the interface name sits far from "VPN CONNECTED".
        val line = Privileged.exec("dumpsys connectivity 2>/dev/null | grep -m1 'VPN CONNECTED'")
        return Regex("InterfaceName: ([A-Za-z0-9_.-]+)").find(line)?.groupValues?.get(1)?.takeIf { IFACE.matches(it) }
    }

    private fun vpnNetwork(): android.net.Network? = runCatching {
        val cm = App.ctx.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()

    /** Local SOCKS5 listeners on 127.0.0.1, "port (process)", for the proxy picker. */
    suspend fun localProxies(): List<Pair<Int, String>> =
        Privileged.exec("ss -ltnp 2>/dev/null | grep '127.0.0.1:'").lineSequence().mapNotNull { line ->
            val port = Regex("127\\.0\\.0\\.1:(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val proc = Regex("\\(\\(\"([^\"]+)\"").find(line)?.groupValues?.get(1).orEmpty()
            port to proc
        }.distinctBy { it.first }.toList()

    /**
     * Stops the tunnel only if the recorded pid is still it — after a reboot or a crash the pid
     * may belong to anything — and waits for it to go, so a new tunnel never races the old
     * one's device.
     */
    private val killHev = """
        p=${'$'}(cat $DIR/hev.pid 2>/dev/null)
        if [ -n "${'$'}p" ] && grep -q libhevtun /proc/${'$'}p/cmdline 2>/dev/null; then
          kill ${'$'}p 2>/dev/null
          i=0; while [ -d /proc/${'$'}p ] && [ ${'$'}i -lt 20 ]; do sleep 0.1; i=${'$'}((i+1)); done
          [ -d /proc/${'$'}p ] && kill -9 ${'$'}p 2>/dev/null
        fi
        rm -f $DIR/hev.pid
    """.trimIndent()

    private val socksPid get() = "${App.ctx.filesDir.absolutePath}/socks.pid"

    /**
     * The SOCKS relay of the removed "Ускорение" (up to 3.25) ran outside the app's cgroup and
     * outlives an update: stopped here, once, with the files it left.
     */
    private val killOldRelay get() = "p=${'$'}(cat $socksPid 2>/dev/null); [ -n \"${'$'}p\" ] && grep -q SocksCli /proc/${'$'}p/cmdline 2>/dev/null && kill ${'$'}p; " +
        "rm -f ${App.ctx.filesDir.absolutePath}/socks.*"

    private const val IPV6_OFF = "$DIR/ipv6_off"

    /**
     * IPv6 off on the hotspot interface only: clients then get no IPv6 address or IPv6 DNS from
     * the phone, so nothing of theirs can go around the tunnel — Android's DNS proxy answers
     * IPv6 queries over the carrier, and offload may forward IPv6 past ip6tables. The phone's
     * own IPv6 (AdGuard, the VPN, IPv6-only carriers) is not touched. Best effort: the refusal
     * in NMS_FWD6 stays in place either way. The interface is created anew with each hotspot
     * start, so this ends with the hotspot too.
     */
    private fun ipv6Off(ap: String) = "mkdir -p $DIR; chmod 700 $DIR; " +
        "sysctl -qw net.ipv6.conf.$ap.disable_ipv6=1 2>/dev/null && echo $ap > $IPV6_OFF || :"

    /** Gives the hotspot its IPv6 back when the route goes direct while it is still up. */
    private val ipv6Restore = """
        i=${'$'}(cat $IPV6_OFF 2>/dev/null)
        case "${'$'}i" in
          ''|*[!A-Za-z0-9_.-]*) :;;
          *) [ ! -d /proc/sys/net/ipv6/conf/${'$'}i ] || sysctl -qw net.ipv6.conf.${'$'}i.disable_ipv6=0 2>/dev/null || :;;
        esac
        rm -f $IPV6_OFF
    """.trimIndent()

    /** The temporary hold chains deliberately survive route teardown. */
    private fun teardownScript(): String = TetherScripts.clearRoute() + "\n" + killHev + "\n" + killOldRelay

    private suspend fun checked(body: String, marker: String) {
        val output = Privileged.exec(TetherScripts.checked(body, marker), 30_000)
        check(output.lineSequence().any { it.trim() == marker }) {
            output.lineSequence().filterNot { it.startsWith("@") }.joinToString(" ").take(240)
                .ifBlank { "не удалось настроить или проверить маршрут" }
        }
    }

    /** Null when the route is whole, else what is wrong with it, for the journal. */
    private suspend fun problem(path: TetherPath): String? {
        val output = Privileged.exec(TetherScripts.checked(TetherScripts.verify(path), "@ROUTE_OK"))
        if (output.lineSequence().none { it.trim() == "@ROUTE_OK" }) return "правила маршрута изменены или интерфейс не работает"
        val held = Privileged.exec("if iptables -w 5 -C FORWARD -j NMS_HOLD 2>/dev/null || ip6tables -w 5 -C FORWARD -j NMS_HOLD6 2>/dev/null; then echo held; fi")
        if (held.contains("held")) return "осталась временная блокировка"
        if (path.out == TUN && !hevAlive()) return "туннель не работает"
        val v6 = Privileged.exec("[ -f $IPV6_OFF ] && [ \"${'$'}(cat /proc/sys/net/ipv6/conf/${path.ap}/disable_ipv6 2>/dev/null)\" != 1 ] && echo on")
        if (v6.contains("on")) return "IPv6 на точке доступа снова включён"
        return null
    }

    private suspend fun healthy(path: TetherPath): Boolean = problem(path) == null

    /**
     * Brings routing in line with [Prefs.tetherMode] for the hotspot as it is now. Returns a
     * line for the log/screen. Without root it does nothing and says so.
     */
    suspend fun apply(): String = lock.withLock {
        NetworkTweaks.apply()
        val mode = Prefs.tetherMode
        val proxy = Prefs.tetherProxy
        val proxyUser = Prefs.tetherProxyUser
        val proxyPass = Prefs.tetherProxyPass
        if (mode == TetherMode.DIRECT) {
            lease.forget()
            // Only touches the shell when something of ours is left: this runs at every
            // process start.
            if (Privileged.choose() == Backend.ROOT) {
                // An if, not "|| exit": libsu runs every command in one shared shell, and exit
                // would end that shell for everything queued behind it.
                Privileged.exec("if ip rule | grep -qE '^($PREF|${PREF + 1}):' || iptables -S NMS_FWD >/dev/null 2>&1 || iptables -S NMS_HOLD >/dev/null 2>&1 || ip6tables -S NMS_HOLD6 >/dev/null 2>&1 || ip6tables -S NMS_FWD6 >/dev/null 2>&1 || [ -f $DIR/hev.pid ] || [ -f $socksPid ] || [ -f $IPV6_OFF ]; then\n" + teardownScript() + "\n" + TetherScripts.clearHold() + "\n" + ipv6Restore + "\nfi")
            }
            return@withLock "раздача: напрямую"
        }
        if (Privileged.choose() != Backend.ROOT) return@withLock "раздача через ${label(mode)}: нужен root"
        val ap = apInterface()
        if (ap == null) {
            lease.forget()
            withContext(NonCancellable) { Privileged.exec(teardownScript() + "\n" + TetherScripts.clearHold() + "\n" + ipv6Restore) }
            return@withLock "раздача через ${label(mode)}: включится вместе с точкой доступа"
        }
        val vpn = if (mode == TetherMode.VPN) vpnInterface() else null
        val path = if (vpn != null) TetherPath(ap, vpn, vpn, true, DNS)
            else TetherPath(ap, TUN, TABLE.toString(), false, MAP_DNS)
        // Persisted identity allows a new app process to adopt the existing tunnel. Boot id,
        // interface generations and APK path prevent adopting a stale or older implementation.
        val generation = Privileged.exec("cat /proc/sys/kernel/random/boot_id; cat /sys/class/net/$ap/ifindex" +
            if (vpn != null) "; cat /sys/class/net/$vpn/ifindex" else "")
        val identity = tetherIdentity(BuildConfig.VERSION_CODE.toString(), App.ctx.applicationInfo.sourceDir,
            generation, mode.name, ap, vpn.orEmpty(),
            if (mode == TetherMode.PROXY) proxy else "",
            if (mode == TetherMode.PROXY) proxyUser else "",
            if (mode == TetherMode.PROXY) proxyPass else "")
        val saved = lease.saved()
        var failed: String? = null
        lease.adopt(identity) { problem(path).also { failed = it } == null }?.let { line ->
            lastVpn = vpn
            return@withLock line
        }
        // A rebuild cuts every client connection: each one goes into the journal with its cause.
        when {
            failed != null -> Prefs.log("раздача: маршрут перестроен — $failed")
            saved -> Prefs.log("раздача: маршрут перестроен — изменились точка доступа, VPN или настройки")
        }

        // Finish the mutation even when its initiating activity is closed. Install a hold
        // before removing any old rules; on failure it stays until a verified route is ready.
        withContext(NonCancellable) {
            lease.forget()
            try {
                checked(TetherScripts.hold(ap), "@HELD")
                Privileged.exec(teardownScript())
                checked(TetherScripts.block(ap), "@BLOCKED")
                Privileged.exec(ipv6Off(ap))

                val line = when (mode) {
                    TetherMode.VPN -> {
                        vpn ?: return@withContext "раздача через VPN: VPN не подключён — раздача заблокирована"
                        lastVpn = vpn
                        checked(TetherScripts.open(path) + "\nip -4 rule add pref $PREF iif $ap lookup $vpn", "@VPN_CONFIGURED")
                        Log.i(TAG, "tether $ap -> vpn $vpn")
                        "раздача через VPN ($vpn)"
                    }
                    TetherMode.PROXY -> {
                        val (name, port) = proxy.let { it.substringBeforeLast(':') to it.substringAfterLast(':').toIntOrNull() }
                        // hev-socks5-tunnel takes an IP only, so a domain is resolved here. The phone's
                        // own traffic is not redirected, so the lookup cannot loop into the tunnel.
                        val host = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                java.net.InetAddress.getByName(name.trim('[', ']')).hostAddress
                            }
                        }.getOrNull()
                        if (port == null || port !in 1..65535 || host == null) return@withContext "раздача через Proxy: неверный адрес $name"
                        startHev(host, port, proxyUser, proxyPass, ap)?.let { return@withContext "раздача через Proxy: $it" }
                        Log.i(TAG, "tether $ap -> socks $host:$port")
                        "раздача через Proxy ($name:$port)"
                    }
                    TetherMode.DIRECT -> "раздача: напрямую"
                }
                checked(TetherScripts.verify(path), "@VERIFIED")
                checked(TetherScripts.clearHold(), "@RELEASED")
                lease.remember(identity, line) { healthy(path) }
                line
            } catch (e: Exception) {
                // Reinstall the hold if a late verification failed after releasing it.
                val held = runCatching { checked(TetherScripts.hold(ap), "@HELD"); true }.getOrDefault(false)
                Log.e(TAG, "tether setup failed", e)
                "раздача через ${label(mode)}: ошибка — ${e.message}" + if (held) "; раздача заблокирована" else "; проверь правила раздачи"
            }
        }
    }

    /**
     * Starts hev-socks5-tunnel towards the SOCKS5 server at [host]:[port] and points the
     * hotspot [ap] at it. Null when up, or what went wrong (the hotspot stays blocked then).
     */
    private suspend fun startHev(host: String, port: Int, proxyUser: String, proxyPass: String, ap: String): String? {
        // Built line by line: inserted into an indented template, the login lines would set the
        // indent trimIndent takes off, shifting every other line — the tunnel then started without
        // the login (it offered the proxy "no auth" only) while everything else looked fine.
        val cfg = buildString {
            appendLine("tunnel:")
            appendLine("  name: $TUN")
            appendLine("  mtu: 8500")
            appendLine("  ipv4: 198.18.0.1")
            appendLine("socks5:")
            appendLine("  port: $port")
            appendLine("  address: ${hevYamlString(host)}")
            appendLine("  udp: 'udp'")
            if (proxyUser.isNotEmpty()) {
                appendLine("  username: ${hevYamlString(proxyUser)}")
                appendLine("  password: ${hevYamlString(proxyPass)}")
            }
            appendLine("mapdns:")
            appendLine("  address: $MAP_DNS")
            appendLine("  port: 53")
            appendLine("  network: 100.64.0.0")
            appendLine("  netmask: 255.192.0.0")
            append("  cache-size: 10000")
        }
        checked(
            // The config holds the proxy password: readable by root only.
            "mkdir -p $DIR; chmod 700 $DIR\n" + TetherScripts.privateConfig("$DIR/hev.yml", cfg) + "\n" +
                "nohup ${NetworkScripts.quote(hev())} $DIR/hev.yml > $DIR/hev.log 2>&1 & echo ${'$'}! > $DIR/hev.pid",
            "@HEV_STARTED"
        )
        // The tun device appears a moment after start.
        for (i in 0 until 20) {
            if (Privileged.exec("ip link show $TUN >/dev/null 2>&1 && echo up").contains("up")) break
            delay(250)
        }
        if (!hevAlive()) {
            return "туннель не запустился — раздача заблокирована"
        }
        checked(
            // DNS goes to the tunnel's own resolver: it answers with mapped addresses and
            // hands connections to the proxy by name. Proxies like Happ's SOCKS inbound
            // do not relay UDP, so plain DNS through the proxy never got an answer.
            TetherScripts.open(TetherPath(ap, TUN, TABLE.toString(), false, MAP_DNS)) + "\n" +
                "ip link set $TUN up; sysctl -qw net.ipv4.conf.$TUN.rp_filter=0\n" +
                "ip route add default dev $TUN table $TABLE\n" +
                "ip rule add pref $PREF iif $ap lookup $TABLE",
            "@HEV_CONFIGURED"
        )
        return null
    }

    /** The VPN interface the route was last built for; a different one means rebuild. */
    @Volatile var lastVpn: String? = null
        private set

    /** Whether the proxy tunnel process is running and its device is up. */
    suspend fun hevAlive(): Boolean = Privileged.exec(
        "p=${'$'}(cat $DIR/hev.pid 2>/dev/null); [ -n \"${'$'}p\" ] && grep -q libhevtun /proc/${'$'}p/cmdline 2>/dev/null && ip link show $TUN >/dev/null 2>&1 && echo up"
    ).contains("up")

    /** Current rules as the kernel sees them, for the screen's status line. */
    suspend fun active(): Boolean = Privileged.exec("ip rule | grep -c '^$PREF:'").trim().let { it != "0" && it.isNotEmpty() }

    /** Read-only and small: no speed-test downloads and no tunnel restart. */
    suspend fun diagnostics(): String = lock.withLock {
        if (Privileged.choose() != Backend.ROOT) return@withLock "Нужен root"
        val ap = apInterface() ?: return@withLock "Включи точку доступа для проверки"
        val mode = Prefs.tetherMode
        val vpn = if (mode == TetherMode.VPN) vpnInterface() else null
        buildList {
            add("Маршрут: ${label(mode)}")
            if (mode == TetherMode.VPN && vpn == null) add("VPN не подключён")
            else if (mode != TetherMode.DIRECT) {
                val path = if (vpn != null) TetherPath(ap, vpn, vpn, true, DNS)
                    else TetherPath(ap, TUN, TABLE.toString(), false, MAP_DNS)
                add(problem(path)?.let { "Маршрут: нужна повторная настройка — $it" } ?: "Маршрут: проверка пройдена")
                if (vpn != null) {
                    val mtu = Privileged.exec("cat /sys/class/net/$vpn/mtu").trim().toIntOrNull()
                    if (mtu != null) add("MTU VPN: $mtu")
                }
            }
            add("Это проверка состояния, а не замер скорости. Размер пакетов TCP подстраивается под MTU VPN.")
        }.joinToString("\n\n")
    }

    fun label(m: TetherMode) = when (m) { TetherMode.VPN -> "VPN"; TetherMode.PROXY -> "Proxy"; TetherMode.DIRECT -> "напрямую" }
}
