package com.netmode.switcher

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch

/**
 * One tile per SIM slot. Tiles are identified by component name, so each slot needs its own
 * service class; everything but the slot number lives here.
 */
abstract class BaseNetModeTileService : TileService() {

    protected abstract val slot: Int

    // Remembered for the main screen, which offers to add the tile only while it is missing.
    override fun onTileAdded() {
        super.onTileAdded()
        Prefs.setTileShown(slot, true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Prefs.setTileShown(slot, false)
    }

    override fun onStartListening() {
        super.onStartListening()
        // Listening means it is in the shade: also covers tiles added before this was recorded.
        Prefs.setTileShown(slot, true)
        paint(ModeController.modeOf(slot), busy = false)
        App.scope.launch {
            Sims.active()
            ModeController.read(slot)
            // ВАЖНО: красим напрямую, а НЕ через NetModeTiles.refresh().
            //
            // refresh() зовёт requestListeningState(), то есть просит систему перевести тайл
            // в listening — и система отвечает вызовом onStartListening(). Вызванный отсюда,
            // он замыкает сам себя: чтение режима → запрос listening → снова onStartListening.
            // Выхода из круга нет, он живёт до смерти процесса, а каждый оборот — это
            // root-команда `cmd phone` к телефонии.
            //
            // На телефоне владельца (19.09.2026) это дало 32 оборота в секунду в течение
            // шести часов и severe-троттлинг.
            paint(ModeController.modeOf(slot), busy = false)
        }
    }

    override fun onClick() {
        super.onClick()
        paint(ModeController.modeOf(slot), busy = true)
        // Deliberately not lifecycleScope: this service dies the instant the shade closes,
        // which would cancel a root rebind mid-flight.
        App.scope.launch {
            ModeController.toggle(slot, ModeController.Source.TILE)
            // Тоже без refresh(): тайл в этот момент ЗАВЕДОМО слушает, его только что нажали.
            paint(ModeController.modeOf(slot), busy = false)
        }
    }

    private fun paint(mode: NetMode?, busy: Boolean) {
        val tile = qsTile ?: return
        tile.icon = mode?.let { NetModeTiles.glyph(it) } ?: Icon.createWithResource(this, R.drawable.ic_tile)
        // Nothing but the generation: the shade truncates anything longer, and the tile's
        // active/inactive state already carries the rest of the meaning.
        tile.label = mode?.short ?: getString(R.string.tile_label)
        tile.subtitle = when {
            busy -> getString(R.string.working)
            // With one SIM the carrier name is noise; with two it is the only way to tell
            // the tiles apart in the shade.
            Sims.known.size > 1 -> Sims.name(slot)
            else -> null
        }
        tile.state = if (mode?.high == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

class Sim1TileService : BaseNetModeTileService() {
    override val slot = 0
}

class Sim2TileService : BaseNetModeTileService() {
    override val slot = 1
}

object NetModeTiles {
    /**
     * The generation itself as the tile's icon — "4G", "2G" — so the shade shows the mode at a
     * glance even where it cuts the label. The shade tints only the shape, so white on clear.
     */
    fun glyph(mode: NetMode): Icon {
        val text = "${mode.top}G"
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.toFloat()
        }
        // As large as fits the width, centred on the cap height.
        paint.textSize *= size * 0.94f / paint.measureText(text)
        val bounds = android.graphics.Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        android.graphics.Canvas(bmp).drawText(text, size / 2f, size / 2f - bounds.exactCenterY(), paint)
        return Icon.createWithBitmap(bmp)
    }

    /** Pulls the tiles into a listening state so a change from an alarm or Wi-Fi repaints them. */
    fun refresh() {
        listOf(Sim1TileService::class.java, Sim2TileService::class.java).forEach { cls ->
            runCatching {
                TileService.requestListeningState(App.ctx, ComponentName(App.ctx, cls))
            }
        }
    }
}
