package com.netmode.switcher

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Disposable-emulator only. Does not apply routes or start a hotspot. */
class RuleBandLab : AppCompatActivity() {
    private fun rule() = AppRule(9400, false, 0, 0, null, null, 0, emptyList(), FreezeMethod.STOP,
        trigger = Trigger.HOTSPOT, triggerHotspotVia = TetherMode.VPN, triggerHotspotBand = "5",
        note = "QA rule", endCustom = true, endHotspot = Toggle.ON, endHotspotVia = TetherMode.PROXY, endHotspotBand = "2")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = rule()
        for (via in listOf(null) + TetherMode.entries) for (band in listOf(null, "2", "5")) {
            val r = base.copy(triggerHotspotVia = via, triggerHotspotBand = band, hotspot = Toggle.ON,
                hotspotVia = via, hotspotBand = band, endHotspotBand = band,
                unlockEnd = EndSpec(mode = EndMode.CUSTOM, hotspot = Toggle.ON, hotspotBand = band))
            check(r == AppRule.fromJson(r.toJson())) { "Rule roundtrip: $via/$band" }
            check(r.endSpec == EndSpec.fromJson(r.endSpec.toJson()))
        }
        val old = base.toJson().apply { listOf("tApBand", "apBand", "eApBand", "tTile", "tTileLabel").forEach { remove(it) } }
        check(AppRule.fromJson(old).triggerHotspotBand == null)
        check(AppRule.fromJson(old.put("apBand", "99")).hotspotBand == null)
        val click = base.copy(trigger = Trigger.TILE_CLICK, triggerHotspotVia = null, triggerHotspotBand = null,
            triggerTile = "$packageName/.RuleBandQaTile", triggerTileLabel = "QA Tile", name = "QA tile press",
            enabled = true, endCustom = false, endHotspot = null, endHotspotVia = null, endHotspotBand = null)
        check(click == AppRule.fromJson(click.toJson()))
        when (intent.getStringExtra("op")) {
            "tile" -> { if (intent.getBooleanExtra("shizuku", false)) Prefs.backend = Backend.SHIZUKU; Prefs.appRules = listOf(click); RuleStore.changed() }
            "edit_tile" -> {
                Prefs.appRules = listOf(click.copy(enabled = false))
                openEditor(); return
            }
            "edit_action" -> {
                Prefs.appRules = listOf(base.copy(trigger = Trigger.TIME, hotspot = Toggle.ON,
                    hotspotVia = TetherMode.VPN, hotspotBand = "5", endHour = 7, endMinute = 0))
                openEditor(); return
            }
            "edit_hotspot" -> { Prefs.appRules = listOf(base); openEditor(); return }
        }
        val out = JSONObject().put("roundtrips", true).put("accessibility", AppWatchService.connected)
            .put("events", org.json.JSONArray(Prefs.events.take(20))).put("rules", org.json.JSONArray(Prefs.appRules.map { it.toJson() }))
        filesDir.resolve("rule-band-qa.json").writeText(out.toString(2))
        setContentView(TextView(this).apply { text = out.toString(2) })
    }

    private fun openEditor() {
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_ID, 9400L))
        finish()
    }
}

class RuleBandQaTile : TileService() {
    override fun onStartListening() { qsTile?.apply { label = "QA Tile"; state = Tile.STATE_INACTIVE; updateTile() } }
    override fun onClick() { qsTile?.apply { state = if (state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() } }
}

class TileBandStatusLab : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val method = TileClickWatch::class.java.getDeclaredMethod("shadeVisible").apply { isAccessible = true }
        val reader = TileClickWatch::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(TileClickWatch)
        val state = JSONObject().put("shadeVisible", method.invoke(TileClickWatch)).put("reader", reader?.toString())
            .put("accessibility", AppWatchService.connected).put("backend", Prefs.backend.name)
            .put("binder", rikka.shizuku.Shizuku.pingBinder())
            .put("permission", runCatching { rikka.shizuku.Shizuku.checkSelfPermission() }.getOrNull())
        context.filesDir.resolve("shade-status.json").writeText(state.toString())
    }
}
