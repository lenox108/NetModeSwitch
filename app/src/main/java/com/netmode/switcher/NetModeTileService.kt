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
            // ВАЖНО: красим напрямую, а НЕ через requestUpdate().
            //
            // requestUpdate() зовёт requestListeningState(), то есть просит систему перевести
            // тайл в listening — и система отвечает вызовом onStartListening(). Вызванный
            // отсюда, он замыкает сам себя: чтение режима → запрос listening → снова
            // onStartListening → снова чтение. Выхода из круга нет, он живёт до смерти
            // процесса, а каждый оборот — это root-команда `cmd phone` к телефонии.
            //
            // На телефоне владельца (19.09.2026) это дало 32 оборота в секунду в течение
            // шести часов: 35 % ядра в root-шелле на форки `cmd phone`, 12 % в самом
            // приложении, плюс SystemUI и logd, обслуживающие поток «refreshState: tile not
            // listening, ignore». Телефон ушёл в severe-троттлинг, ядра 90–99 °C, экран
            // система сбросила со 120 на 60 Гц.
            paint(ModeController.current.value, busy = false)
        }
    }

    override fun onClick() {
        super.onClick()
        paint(ModeController.current.value, busy = true)
        // Deliberately not lifecycleScope: this service dies the instant the shade closes,
        // which would cancel a root rebind mid-flight.
        App.scope.launch {
            ModeController.toggle(ModeController.Source.TILE)
            // Тоже без requestUpdate: тайл в этот момент ЗАВЕДОМО слушает (его только что
            // нажали), а ModeController.apply сам дёргает requestUpdate для внешних поводов.
            paint(ModeController.current.value, busy = false)
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
