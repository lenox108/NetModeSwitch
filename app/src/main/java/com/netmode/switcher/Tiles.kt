package com.netmode.switcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
 *    network (also for Amnezia tiles); for other tiles, whether the app runs a foreground
 *    service (Happ in proxy mode: 1 on, 0 off — it has a VPN component too, so "is a VPN app"
 *    cannot decide this);
 *  - a VPN app handles the click a few seconds late and ignores clicks while it is still
 *    connecting (AdGuard: off in ~1 s, on in ~15 s), so there is exactly one click per change;
 *  - a tile click toggles, so it is only clicked when the state differs from the target.
 */
object Tiles {

    private val transitions = TileTransitions()

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
     * Amnezia and explicit [vpn] targets use their connected VPN, not a background service.
     * An absent entry means unknown, including permission errors and unsupported dump formats.
     */
    suspend fun states(components: Collection<String>, vpn: Set<String> = emptySet()): Map<String, Boolean> {
        val targets = components.filter { valid(it) }
        if (targets.isEmpty()) return emptyMap()
        val vpnTargets = targets.filter { TileStateProbe.usesVpn(it, vpn) }.toSet()
        val network = if (vpnTargets.isEmpty()) null else readDump("dumpsys connectivity 2>&1")
        return targets.mapNotNull { c ->
            val state = if (c in vpnTargets) {
                val uid = runCatching { App.ctx.packageManager.getApplicationInfo(pkg(c), 0).uid }.getOrNull()
                network?.let { TileStateProbe.vpn(it, pkg(c), uid) }
            } else readDump("dumpsys activity services ${pkg(c)} 2>&1")?.let(TileStateProbe::foreground)
            state?.let { c to it }
        }.toMap()
    }

    private suspend fun readDump(command: String): String? = try {
        Privileged.exec(command)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

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
        // Under the always-on a rule set, the system reconnects right after the tile's "off".
        for ((c, want) in targets) if (!want) VpnControl.release(pkg(c))
        return transitions.set(targets,
            read = { c -> states(listOf(c), vpn)[c] },
            prepare = ::ensureAdded,
            click = { c ->
                TileClickWatch.suppress(listOf(c))
                Privileged.exec("cmd statusbar click-tile '$c' 2>&1")
                // Simultaneous clicks can interfere while SystemUI binds another service.
                delay(2_000)
            },
            await = { c, want ->
                val window = if (TileStateProbe.usesVpn(c, vpn)) VPN_WAIT_MS else APP_WAIT_MS
                val deadline = android.os.SystemClock.elapsedRealtime() + window
                var state = states(listOf(c), vpn)[c]
                while (state != want && android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(2_000)
                    state = states(listOf(c), vpn)[c]
                }
                state == want
            },
            before = before,
        ).map { result ->
            val c = result.component
            val name = (if (TileStateProbe.usesVpn(c, vpn)) "VPN " else "") + label(c) +
                if (result.added) " (плитка добавлена в шторку)" else ""
            val state = if (targets[c] == true) "вкл" else "выкл"
            when (result.outcome) {
                TileTransitions.Outcome.ALREADY -> "$name уже $state"
                TileTransitions.Outcome.CHANGED -> "$name $state"
                TileTransitions.Outcome.UNKNOWN -> "$name: состояние неизвестно, нажатие пропущено"
                TileTransitions.Outcome.UNCONFIRMED -> "$name: изменение не подтвердилось, повторного нажатия нет"
            }
        }
    }

    /** Prepare separately so the app state can be rechecked before the actual toggle. */
    private suspend fun ensureAdded(component: String): Boolean =
        Privileged.exec("t='$component'; settings get secure sysui_qs_tiles | grep -qF \"custom(\$t)\" || " +
            "{ cmd statusbar add-tile \"\$t\" >/dev/null 2>&1 && echo '@ADD'; sleep 1; }")
            .lineSequence().any { it == "@ADD" }

    private const val VPN_WAIT_MS = 25_000L
    private const val APP_WAIT_MS = 10_000L
}
