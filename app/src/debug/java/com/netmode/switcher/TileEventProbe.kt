package com.netmode.switcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only event inspection, enabled only in a disposable emulator. */
class TileEventProbe : AccessibilityService() {
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || event.packageName?.toString() != "com.android.systemui") return
        val nodes = JSONArray()
        var n = event.source
        repeat(8) {
            val node = n ?: return@repeat
            nodes.put(JSONObject().put("class", node.className).put("id", node.viewIdResourceName)
                .put("text", node.text).put("desc", node.contentDescription).put("children", node.childCount))
            n = node.parent
        }
        val data = JSONObject().put("time", event.eventTime).put("class", event.className)
            .put("text", JSONArray(event.text.map { it.toString() })).put("desc", event.contentDescription).put("nodes", nodes)
        filesDir.resolve("tile-event-probe.jsonl").appendText(data.toString() + "\n")
    }
}
