package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch


private const val TILE_ON = 0xFF2E7D32.toInt()
private const val TILE_OFF = 0xFFC62828.toInt()
private const val TILE_KEEP = 0xFF757575.toInt()


/**
 * Chooses tiles to press and the state each should end up in. Tapping an app cycles
 * не менять → вкл → выкл.
 */
fun AppCompatActivity.pickTiles(current: Map<String, Toggle>, done: (Map<String, Toggle>) -> Unit) =
    lifecycleScope.launch {
        // The tiles already in the shade come first, in the shade's own order.
        val shade = runCatching { Tiles.inShade() }.getOrDefault(emptyList())
        val apps = Tiles.list(this@pickTiles).sortedBy { a -> shade.indexOf(a.component).let { if (it < 0) Int.MAX_VALUE else it } }
        showTilePicker(apps, shade.toSet(), current, done)
    }

private fun AppCompatActivity.showTilePicker(
    apps: List<TileApp>,
    shade: Set<String>,
    current: Map<String, Toggle>,
    done: (Map<String, Toggle>) -> Unit,
) {
    if (apps.isEmpty()) {
        Toast.makeText(this, R.string.no_tiles, Toast.LENGTH_LONG).show()
        return
    }
    val chosen = current.toMutableMap()
    val list = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }
    for (a in apps) {
        val row = com.netmode.switcher.databinding.RowTileBinding.inflate(layoutInflater, list, false)
        row.label.text = a.label
        row.sub.text = listOfNotNull(
            getString(R.string.tile_in_shade).takeIf { a.component in shade },
            "VPN".takeIf { a.vpn },
        ).joinToString(" · ")
        row.sub.visibility = if (row.sub.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        // Checked = solid fill with white text: green on, red off, grey leave alone.
        for ((btn, color) in listOf(row.keep to TILE_KEEP, row.on to TILE_ON, row.off to TILE_OFF)) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            btn.backgroundTintList = android.content.res.ColorStateList(states, intArrayOf(color, android.graphics.Color.TRANSPARENT))
            btn.setTextColor(android.content.res.ColorStateList(states, intArrayOf(android.graphics.Color.WHITE, color)))
            btn.strokeColor = android.content.res.ColorStateList.valueOf(color)
        }
        row.state.check(when (chosen[a.component]) { Toggle.ON -> R.id.on; Toggle.OFF -> R.id.off; null -> R.id.keep })
        row.state.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            when (id) {
                R.id.on -> chosen[a.component] = Toggle.ON
                R.id.off -> chosen[a.component] = Toggle.OFF
                else -> chosen.remove(a.component)
            }
        }
        list.addView(row.root)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.tiles_title)
        .setView(android.widget.ScrollView(this).apply { addView(list) })
        .setPositiveButton(R.string.done_btn) { _, _ -> done(chosen.toMap()) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

fun AppCompatActivity.pickVpnApp(done: (String) -> Unit) {
    val apps = VpnControl.apps()
    if (apps.isEmpty()) {
        Toast.makeText(this, R.string.no_vpn_apps, Toast.LENGTH_LONG).show()
        return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.vpn_app_choose)
        .setItems(apps.map { it.second }.toTypedArray()) { _, i -> done(apps[i].first) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** A paired device: address to act on, name to show. */
data class BtDevice(val address: String, val name: String)
