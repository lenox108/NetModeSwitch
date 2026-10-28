package com.netmode.switcher

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/** Disposable-emulator fixture. Route commands change the preference only, never the firewall. */
class HotspotRuleLab : AppCompatActivity() {
    private fun rule(id: Long, via: TetherMode?, enabled: Boolean = true) = AppRule(
        id = id, enabled = enabled, hour = 0, minute = 0, endHour = null, endMinute = null,
        days = 0, packages = emptyList(), method = FreezeMethod.STOP, trigger = Trigger.HOTSPOT,
        triggerHotspotVia = via, name = "QA ${via?.name ?: "ANY"}",
        note = "QA start ${via?.name ?: "ANY"}", endNote = "QA end ${via?.name ?: "ANY"}",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCommand()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCommand()
    }

    private fun runCommand() {
        when (intent.getStringExtra("op")) {
            "seed" -> {
                Prefs.tetherMode = TetherMode.DIRECT
                Prefs.appHandled = emptyMap()
                Prefs.openWindows = emptyMap()
                Prefs.appRules = listOf(rule(9100, null)) + TetherMode.entries.mapIndexed { i, m -> rule(9101L + i, m) }
                RuleStore.changed()
            }
            "route" -> Prefs.tetherMode = TetherMode.valueOf(intent.getStringExtra("mode")!!)
            "edit" -> {
                val via = intent.getStringExtra("mode")?.let { TetherMode.valueOf(it) }
                Prefs.appRules = listOf(rule(9200, via, enabled = false))
                startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_ID, 9200L))
                finish()
                return
            }
        }
        // Android's real JSONObject exercises backup compatibility as well as all route values.
        val roundtrips = listOf(null) + TetherMode.entries
        for (mode in roundtrips) {
            val r = rule(9300, mode).copy(hotspot = Toggle.ON, hotspotVia = TetherMode.PROXY,
                endCustom = true, endHotspot = Toggle.ON, endHotspotVia = TetherMode.DIRECT)
            check(r == AppRule.fromJson(r.toJson())) { "Roundtrip failed: $mode" }
        }
        val old = rule(9300, null).toJson().apply { remove("tApVia") }
        check(AppRule.fromJson(old).triggerHotspotVia == null)
        check(AppRule.fromJson(old.put("tApVia", "future-mode")).triggerHotspotVia == null)
        val holds = ConditionWatch::class.java.getDeclaredMethod("holds", AppRule::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val state = JSONObject().put("roundtrips", true).put("route", Prefs.tetherMode.name)
            .put("rules", JSONArray().apply {
                Prefs.appRules.forEach { r -> put(JSONObject().put("id", r.id).put("label", r.timeLabel)
                    .put("via", r.triggerHotspotVia?.name ?: JSONObject.NULL).put("enabled", r.enabled)
                    .put("matches", holds.invoke(ConditionWatch, r, 0L) ?: JSONObject.NULL)
                    .put("handled", Prefs.appHandled[r.id] ?: 0L).put("open", r.id in Prefs.openWindows)) }
            })
        filesDir.resolve("hotspot-rule-qa.json").writeText(state.toString(2))
        setContentView(TextView(this).apply { text = state.toString(2) })
    }
}
