package com.netmode.switcher

import android.os.SystemClock
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.launch
import java.util.Locale

/** Shade clicks only. Never infer a click from an app's VPN/foreground-service state. */
internal object TileClickWatch {
    private data class Learning(val component: String, val until: Long, val done: (String) -> Unit)
    private var learning: Learning? = null
    private var lastEvent = -1L
    private var reader: Job? = null
    private var service: AccessibilityService? = null
    private var log = TileClickLog(System.currentTimeMillis())
    private val delivered = mutableMapOf<String, Pair<String, Long>>()
    private val suppressed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun needed() = Prefs.appRules.any { it.enabled && it.hasActions && it.trigger == Trigger.TILE_CLICK }

    fun sync() {
        val want = needed()
        if (want) log = TileClickLog(System.currentTimeMillis())
        if (!want) { reader?.cancel(); reader = null }
        App.scope.launch { runCatching { AppWatchService.enable(false) } }
    }

    fun connected(service: AccessibilityService) { this.service = service; windowChanged() }
    fun disconnected() { service = null; reader?.cancel(); reader = null }

    private fun shadeVisible(): Boolean {
        val root = service?.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.android.systemui") return false
        return listOf("quick_settings_container", "quick_settings_panel", "qs_frame", "quick_qs_panel")
            .any { root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$it").isNotEmpty() }
    }

    /** Android may omit click events for sensitive QS nodes. Read its exact component log
     * only while the shade is visible; no shell loop while the screen is off or apps are open. */
    fun windowChanged() {
        if ((!needed() && learning == null) || !shadeVisible()) { reader?.cancel(); reader = null; return }
        if (reader?.isActive == true) return
        reader = App.scope.launch {
            try {
                while ((needed() || learning != null) && shadeVisible()) {
                    val snapshot = Privileged.exec("dumpsys activity service com.android.systemui/.SystemUIService QSLog | grep -F 'Tile clicked.' | tail -60", 6_000)
                    for (component in log.read(snapshot, System.currentTimeMillis())) {
                        val capture = learning
                        if (capture != null && SystemClock.elapsedRealtime() < capture.until && capture.component == component) {
                            learning = null
                            suppress(listOf(component))
                            val label = Tiles.list(includeOwn = true).firstOrNull { it.component == component }?.tileLabel ?: component
                            capture.done(label)
                            Toast.makeText(App.ctx, "Плитка запомнена: $label", Toast.LENGTH_LONG).show()
                        } else if (capture == null) dispatch(component, "log")
                    }
                    delay(2_000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Accessibility remains the fallback on unsupported OEMs. */ }
        }
    }

    private fun dispatch(component: String, source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < (suppressed[component] ?: 0L)) return
        val last = delivered[component]
        if (last != null && last.first != source && now - last.second < 3_500) return
        delivered[component] = source to now
        App.scope.launch { runCatching { AppFreezer.onTileClick(component) }
            .onFailure { Prefs.log("Нажатие плитки: ${it.message}") } }
    }

    fun learn(component: String, done: (String) -> Unit) {
        require(Tiles.valid(component))
        learning = Learning(component, SystemClock.elapsedRealtime() + 60_000, done)
    }

    fun cancelLearning() { learning = null }

    fun suppress(components: Collection<String>) {
        val until = SystemClock.elapsedRealtime() + components.size * 3_000L + 4_000L
        components.forEach { suppressed[it] = until }
    }

    internal fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Exact title or the leading title in an accessibility description; never a substring. */
    internal fun matches(label: String, texts: Collection<String>): Boolean {
        val wanted = normalize(label)
        return wanted.isNotEmpty() && texts.any { normalize(it) == wanted || normalize(it.substringBefore(',')) == wanted }
    }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || event.packageName?.toString() != "com.android.systemui") return
        if (event.eventTime == lastEvent) return
        if (!needed() && learning == null) return
        val source = event.source ?: return
        // Walk up only to the tile, never collect the entire shade or notification text.
        var tile: AccessibilityNodeInfo? = source
        var inTile = false
        val texts = mutableListOf<String>()
        val titles = mutableListOf<String>()
        for (depth in 0..5) {
            val node = tile ?: break
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty().substringAfter(":id/")
            if (id in setOf("qs_tile_large", "qs_tile_small")) { inTile = true; break }
            if (cls.contains("QSTile", true) || cls.contains("TileView", true)) { inTile = true; break }
            // Some Androids expose QSTileView itself as a Switch/Button to accessibility.
            if (cls in listOf("android.widget.Switch", "android.widget.Button") && hasTileLabel(node)) { inTile = true; break }
            tile = node.parent
        }
        if (!inTile || tile == null) return
        collect(tile, texts, titles, 0)
        event.contentDescription?.toString()?.let(texts::add)
        texts += event.text.map { it.toString() }
        lastEvent = event.eventTime
        val now = SystemClock.elapsedRealtime()
        val capture = learning
        if (capture != null) {
            learning = null
            if (now < capture.until) {
                val label = titles.firstOrNull() ?: texts.firstOrNull()?.substringBefore(',')
                if (!label.isNullOrBlank()) {
                    suppress(listOf(capture.component))
                    capture.done(label.trim())
                    Toast.makeText(App.ctx, "Плитка запомнена: ${label.trim()}", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        val components = Prefs.appRules.filter { it.enabled && it.hasActions && it.trigger == Trigger.TILE_CLICK }
            .filter { r -> r.triggerTileLabel?.let { matches(it, texts) } == true }
            .mapNotNull { it.triggerTile }.distinct()
        // Identical titles cannot identify two different components safely.
        if (components.size != 1) return
        val component = components.single()
        val known = Tiles.list(includeOwn = true)
        if (known.none { it.component == component }) return
        if (known.any { it.component != component && matches(it.tileLabel, texts) }) return
        dispatch(component, "accessibility")
    }

    private fun hasTileLabel(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val id = child.viewIdResourceName.orEmpty().substringAfter(":id/")
            if (id in setOf("tile_label", "label_container", "qs_tile_label")) return true
        }
        return false
    }

    private fun collect(node: AccessibilityNodeInfo, texts: MutableList<String>, titles: MutableList<String>, depth: Int) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            texts += it
            if (node.viewIdResourceName?.substringAfter(":id/") !in setOf("secondary_label", "tile_secondary_label")) titles += it
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
        if (depth >= 5) return
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, texts, titles, depth + 1) }
    }
}
