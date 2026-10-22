package com.netmode.switcher

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-tap hotspot with its traffic through the proxy or the VPN — the two buttons on the
 * hotspot screen and the two shade tiles.
 */
object HotspotControl {

    /** Last known state, so a tile can paint at once without waiting for a shell call. */
    @Volatile var lastOn: Boolean = false
        private set

    suspend fun isOn(): Boolean =
        (TetherRoute.apInterface() != null).also { lastOn = it }

    /** Whether the hotspot is up in [mode] right now, as last seen. */
    fun activeIn(mode: TetherMode): Boolean = lastOn && Prefs.tetherMode == mode

    /**
     * Hotspot off → on in [mode]. On in [mode] → off, and back to direct so that switching the
     * hotspot on the usual way later does not quietly tunnel it. On in another mode → switch
     * the mode without restarting the hotspot. Returns a line for the log.
     */
    /** One toggle at a time: a second tap while the first is starting would undo it. */
    private val busy = kotlinx.coroutines.sync.Mutex()

    suspend fun toggle(mode: TetherMode): String {
        if (!busy.tryLock()) return "точка доступа: подожди, предыдущая команда ещё выполняется"
        try {
            return toggleLocked(mode)
        } finally {
            busy.unlock()
        }
    }

    private suspend fun toggleLocked(mode: TetherMode): String {
        if (Privileged.choose() != Backend.ROOT) return "раздача через ${TetherRoute.label(mode)}: нужен root"
        val on = isOn()
        val line = when {
            on && Prefs.tetherMode == mode -> {
                Privileged.exec("${cli()} stop")
                for (i in 0 until 10) {
                    if (!isOn()) break
                    delay(300)
                }
                // Only once it is really down: clients of a hotspot that stayed up must not
                // fall through to the direct route.
                if (isOn()) {
                    "точка доступа не выключилась — раздача через ${TetherRoute.label(mode)} оставлена"
                } else {
                    Prefs.tetherMode = TetherMode.DIRECT
                    TetherRoute.apply()
                    "точка доступа выключена"
                }
            }
            on -> {
                Prefs.tetherMode = mode
                TetherWatch.sync()
                TetherRoute.apply()
            }
            else -> {
                Prefs.tetherMode = mode
                TetherWatch.sync()
                Privileged.exec("${cli()} start")
                // The tether broadcast re-applies too; this covers a process that is not
                // registered for it yet.
                for (i in 0 until 20) {
                    if (isOn()) break
                    delay(500)
                }
                val route = TetherRoute.apply()
                if (lastOn) "точка доступа включена · $route" else "точка доступа не включилась"
            }
        }
        Prefs.log(line)
        HotspotTiles.refresh()
        return line
    }

    private fun cli() = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.HotspotCli"
}

abstract class BaseHotspotTileService : TileService() {

    protected abstract val mode: TetherMode
    protected abstract val labelRes: Int

    override fun onStartListening() {
        super.onStartListening()
        paint(busy = false)
        App.scope.launch {
            HotspotControl.isOn()
            // Painted directly, never through HotspotTiles.refresh(): asking for a listening
            // state from inside onStartListening() calls onStartListening() again, forever.
            paint(busy = false)
        }
    }

    override fun onClick() {
        super.onClick()
        paint(busy = true)
        App.scope.launch {
            HotspotControl.toggle(mode)
            paint(busy = false)
        }
    }

    private fun paint(busy: Boolean) {
        val tile = qsTile ?: return
        val active = HotspotControl.activeIn(mode)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_hotspot_tile)
        tile.label = getString(labelRes)
        tile.subtitle = if (busy) getString(R.string.working) else getString(if (active) R.string.ap_tile_on else R.string.ap_tile_off)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

class HotspotProxyTileService : BaseHotspotTileService() {
    override val mode = TetherMode.PROXY
    // Short in the shade, where "Раздача · Proxy" is cut off; the manifest keeps the long
    // name for the list tiles are added from.
    override val labelRes = R.string.ap_tile_short_proxy
}

class HotspotVpnTileService : BaseHotspotTileService() {
    override val mode = TetherMode.VPN
    override val labelRes = R.string.ap_tile_short_vpn
}

object HotspotTiles {
    /** Repaints both tiles after a change made outside them (never call from a tile). */
    fun refresh() {
        Widgets.update()
        listOf(HotspotProxyTileService::class.java, HotspotVpnTileService::class.java).forEach { cls ->
            runCatching { TileService.requestListeningState(App.ctx, ComponentName(App.ctx, cls)) }
        }
    }
}
