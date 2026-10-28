package com.netmode.switcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** A Quick Settings tile of another app, addressed as "pkg/.Service". */
data class TileApp(val component: String, val pkg: String, val label: String, val vpn: Boolean, val tileLabel: String = label)

/**
 * Switches other apps on and off through their own Quick Settings tile — what the user would
 * do by hand, and the only thing that works for apps like Happ that connect only from their
 * tile or their own screen. Measured on OxygenOS 16:
 *  - `cmd statusbar click-tile` works as shell (Shizuku) and root, with the shade closed;
 *  - a tile that is not in the shade ignores the click, so it is added first (add-tile);
 *  - the shade's own tile state is stale while the shade is closed ("tile not listening"), so
 *    the real state is read elsewhere: for the VPN row, whether the app owns a connected VPN
 *    network; for any other tile, whether the app runs a foreground service (Happ in proxy
 *    mode: 1 on, 0 off — it has a VPN component too, so "is a VPN app" cannot decide this);
 *  - a VPN app handles the click a few seconds late and ignores clicks while it is still
 *    connecting (AdGuard: off in ~1 s, on in ~15 s), so there is exactly one click per change;
 *  - a tile click toggles, so it is only clicked when the state differs from the target.
 */
object Tiles {

    private const val ACTION_QS_TILE = "android.service.quicksettings.action.QS_TILE"
    private const val ACTION_VPN = "android.net.VpnService"

    /** Components only ever reach the shell after this check. */
    private val VALID = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")

    fun valid(component: String): Boolean = VALID.matches(component)

    fun pkg(component: String): String = component.substringBefore('/')

    fun list(ctx: Context = App.ctx, includeOwn: Boolean = false): List<TileApp> {
        val pm = ctx.packageManager
        val vpnPkgs = runCatching {
            pm.queryIntentServices(Intent(ACTION_VPN), 0).map { it.serviceInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        return runCatching { pm.queryIntentServices(Intent(ACTION_QS_TILE), 0) }.getOrDefault(emptyList())
            .mapNotNull { r ->
                val si = r.serviceInfo
                if (!includeOwn && si.packageName == ctx.packageName) return@mapNotNull null
                val component = ComponentName(si.packageName, si.name).flattenToShortString()
                if (!valid(component)) return@mapNotNull null
                val app = si.applicationInfo.loadLabel(pm).toString()
                val svc = si.loadLabel(pm).toString()
                TileApp(component, si.packageName, if (svc.isBlank() || svc == app) app else "$app · $svc", si.packageName in vpnPkgs, svc.ifBlank { app })
            }
            .sortedWith(compareBy<TileApp>({ !it.vpn }, { it.label.lowercase() }))
    }

    /** Tiles currently in the user's shade, in their order. */
    suspend fun inShade(): List<String> =
        Privileged.exec("settings get secure sysui_qs_tiles").trim()
            .split(',')
            .mapNotNull { spec -> spec.trim().removePrefix("custom(").takeIf { it != spec.trim() }?.removeSuffix(")") }
            .filter { valid(it) }

    /** Labels are asked for on every repaint of the rule screen: the list is kept a minute. */
    @Volatile private var cached: Pair<Long, List<TileApp>>? = null

    fun label(component: String): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val list = cached?.takeIf { now - it.first < 60_000L }?.second ?: list().also { cached = now to it }
        return list.firstOrNull { it.component == component }?.label ?: pkg(component)
    }

    /**
     * Reads the real on/off state of each tile's app in one shell call; components in [vpn]
     * are judged by their VPN connection.
     */
    suspend fun states(components: Collection<String>, vpn: Set<String> = emptySet()): Map<String, Boolean> {
        val targets = components.filter { valid(it) }
        if (targets.isEmpty()) return emptyMap()
        val script = targets.joinToString("\n") { c ->
            val p = pkg(c)
            val probe = if (c in vpn) "dumpsys connectivity 2>/dev/null | grep -c 'VPN CONNECTED extra: VPN:$p'"
            else "dumpsys activity services $p 2>/dev/null | grep -c 'isForeground=true'"
            "echo \"@T $c \$($probe)\""
        }
        return Privileged.exec(script).lineSequence()
            .filter { it.startsWith("@T ") }
            .associate { line ->
                val parts = line.removePrefix("@T ").trim().split(' ')
                parts[0] to ((parts.getOrNull(1)?.toIntOrNull() ?: 0) > 0)
            }
    }

    /**
     * Brings each tile's app to the wanted state, clicking only where it differs, and waits for
     * the change to show. Returns a line per app for the log; [before] receives the state found
     * before any click.
     */
    suspend fun set(
        wanted: Map<String, Boolean>,
        vpn: Set<String> = emptySet(),
        before: (String, Boolean) -> Unit = { _, _ -> },
    ): List<String> {
        val targets = wanted.filterKeys { valid(it) }
        if (targets.isEmpty()) return emptyList()
        val now = states(targets.keys, vpn)
        targets.forEach { (c, _) -> now[c]?.let { before(c, it) } }
        val out = mutableListOf<String>()
        fun name(c: String) = (if (c in vpn) "VPN " else "") + label(c)
        targets.filter { (c, want) -> now[c] == want }.forEach { (c, want) ->
            out += "${name(c)} уже ${if (want) "вкл" else "выкл"}"
        }
        var pending = targets.filter { (c, want) -> now[c] != want }
        if (pending.isEmpty()) return out

        // One click, a wait long enough for the slowest case (a VPN connecting), and one more
        // click only for what still has not changed: an app busy with a previous change
        // drops the click, while a second click on a change still in flight would undo it.
        val added = mutableSetOf<String>()
        val confirmed = mutableSetOf<String>()
        repeat(2) {
            added += click(pending.keys)
            val window = if (pending.keys.any { it in vpn }) VPN_WAIT_MS else APP_WAIT_MS
            val deadline = System.currentTimeMillis() + window
            var result = states(pending.keys, vpn)
            while (System.currentTimeMillis() < deadline && pending.any { (c, w) -> result[c] != w }) {
                kotlinx.coroutines.delay(2_000)
                result = states(pending.keys, vpn)
            }
            confirmed += pending.filter { (c, w) -> result[c] == w }.keys
            pending = pending.filter { (c, w) -> result[c] != w }
            if (pending.isEmpty()) return@repeat
        }
        targets.filterKeys { it !in now || now[it] != targets[it] }.forEach { (c, want) ->
            val n = name(c) + if (c in added) " (плитка добавлена в шторку)" else ""
            out += if (c in confirmed) "$n ${if (want) "вкл" else "выкл"}"
            else "$n: нажатие плитки не подтвердилось"
        }
        return out
    }

    /** Clicks each tile, adding it to the shade first if needed. Returns the tiles added. */
    private suspend fun click(components: Collection<String>): Set<String> {
        // Two tiles clicked in the same instant interfere — the shade binds one tile's service
        // while the other click arrives, and one of them is dropped. Measured on OxygenOS 16.
        TileClickWatch.suppress(components)
        val script = components.joinToString("\nsleep 2\n") { c ->
            "t='$c'; settings get secure sysui_qs_tiles | grep -qF \"custom(\$t)\" || " +
                "{ cmd statusbar add-tile \"\$t\" >/dev/null 2>&1; echo \"@ADD \$t\"; sleep 1; }; " +
                "cmd statusbar click-tile \"\$t\" >/dev/null 2>&1"
        }
        return Privileged.exec(script).lineSequence()
            .filter { it.startsWith("@ADD ") }.map { it.removePrefix("@ADD ").trim() }.toSet()
    }

    private const val VPN_WAIT_MS = 25_000L
    private const val APP_WAIT_MS = 10_000L
}
