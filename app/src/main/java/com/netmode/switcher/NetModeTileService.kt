package com.netmode.switcher

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch

class NetModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        paint(ModeController.current.value, busy = false)
        App.scope.launch {
            ModeController.read()
            requestUpdate()
        }
    }

    override fun onClick() {
        super.onClick()
        paint(ModeController.current.value, busy = true)
        // Deliberately not lifecycleScope: this service dies the instant the shade closes,
        // which would cancel a root rebind mid-flight.
        App.scope.launch {
            ModeController.toggle(ModeController.Source.TILE)
            requestUpdate()
        }
    }

    private fun paint(mode: NetMode?, busy: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        // Nothing but the generation: the shade truncates anything longer, and the tile's
        // active/inactive state already carries the rest of the meaning.
        tile.label = mode?.short ?: getString(R.string.tile_label)
        tile.subtitle = if (busy) getString(R.string.working) else null
        tile.state = when (mode) {
            NetMode.FULL -> Tile.STATE_ACTIVE
            NetMode.THREE_G -> Tile.STATE_INACTIVE
            null -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    companion object {
        /** Pulls the tile into a listening state so a change from an alarm or Wi-Fi repaints it. */
        fun requestUpdate() {
            runCatching {
                requestListeningState(
                    App.ctx,
                    ComponentName(App.ctx, NetModeTileService::class.java)
                )
            }
        }
    }
}
