package com.netmode.switcher

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Finite band/route combinations give tiles stable identities, independent of screen edits. */
internal object HotspotPresetTiles {
    private data class Entry(val preset: HotspotPreset, val service: Class<out TileService>, val label: Int)
    private val entries = listOf(
        Entry(HotspotPreset("2", TetherMode.VPN), HotspotVpn24TileService::class.java, R.string.ap_tile_vpn_24),
        Entry(HotspotPreset("5", TetherMode.VPN), HotspotVpn5TileService::class.java, R.string.ap_tile_vpn_5),
        Entry(HotspotPreset("6", TetherMode.VPN), HotspotVpn6TileService::class.java, R.string.ap_tile_vpn_6),
        Entry(HotspotPreset("dual25", TetherMode.VPN), HotspotVpnDualTileService::class.java, R.string.ap_tile_vpn_dual),
        Entry(HotspotPreset("2", TetherMode.PROXY), HotspotProxy24TileService::class.java, R.string.ap_tile_proxy_24),
        Entry(HotspotPreset("5", TetherMode.PROXY), HotspotProxy5TileService::class.java, R.string.ap_tile_proxy_5),
        Entry(HotspotPreset("6", TetherMode.PROXY), HotspotProxy6TileService::class.java, R.string.ap_tile_proxy_6),
        Entry(HotspotPreset("dual25", TetherMode.PROXY), HotspotProxyDualTileService::class.java, R.string.ap_tile_proxy_dual),
        Entry(HotspotPreset("2", TetherMode.DIRECT), HotspotDirect24TileService::class.java, R.string.ap_tile_direct_24),
        Entry(HotspotPreset("5", TetherMode.DIRECT), HotspotDirect5TileService::class.java, R.string.ap_tile_direct_5),
        Entry(HotspotPreset("6", TetherMode.DIRECT), HotspotDirect6TileService::class.java, R.string.ap_tile_direct_6),
        Entry(HotspotPreset("dual25", TetherMode.DIRECT), HotspotDirectDualTileService::class.java, R.string.ap_tile_direct_dual),
    )
    internal val listening: MutableSet<PresetHotspotTileService> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val main = Handler(Looper.getMainLooper())
    private var observation: Job? = null
    private var requested = false
    private var adding = false
    @Volatile private var current = HotspotPresetState(null, TetherMode.DIRECT, "", emptySet())

    fun containsClass(name: String?) = entries.any { it.service.name == name }
    fun active(preset: HotspotPreset) = current.matches(preset)

    private fun syncAvailability() {
        val pm = App.ctx.packageManager
        val available = Prefs.hotspotAvailableBands
        entries.forEach { entry ->
            val component = ComponentName(App.ctx, entry.service)
            val desired = if (entry.preset.band in available) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != desired)
                pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }

    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hotspot_available_bands") { syncAvailability(); refresh() }
        else if (key == "tether_mode") refresh()
    }

    fun watch() {
        Prefs.listen(prefs)
        syncAvailability()
    }

    /** Paint live tiles directly. Listening itself never requests another listening cycle. */
    fun refresh() {
        main.post { listening.toList().forEach { it.repaint() } }
        if (listening.isNotEmpty()) observe(force = true)
    }

    /** A whole open shade shares one bounded read, even if it contains many preset tiles. */
    @Synchronized fun observe(force: Boolean = false) {
        if (observation?.isActive == true) {
            if (force) requested = true
            return
        }
        observation = App.scope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                delay(150)
                while (true) {
                    synchronized(this@HotspotPresetTiles) { requested = false }
                    current = runCatching {
                        val on = HotspotControl.isOn()
                        val saved = if (on) HotspotConfig.read() else null
                        val radio = if (on) HotspotMonitor.readRadio().optJSONArray("infos") else null
                        val frequencies = radio?.let { values -> (0 until values.length()).map { values.getJSONObject(it).optInt("frequency") } }.orEmpty()
                        HotspotPresetState(on, Prefs.tetherMode, saved?.radio.orEmpty(), HotspotBands.fromFrequencies(frequencies))
                    }.getOrElse { HotspotPresetState(null, Prefs.tetherMode, "", emptySet()) }
                    main.post { listening.toList().forEach { it.repaint() } }
                    val repeat = synchronized(this@HotspotPresetTiles) {
                        if (requested) true else { observation = null; false }
                    }
                    if (!repeat) break
                }
            } finally {
                synchronized(this@HotspotPresetTiles) { if (observation === owner) observation = null }
            }
        }
    }

    fun requestAdd(activity: AppCompatActivity, preset: HotspotPreset) {
        val entry = entries.firstOrNull { it.preset == preset } ?: return
        if (preset.band !in Prefs.hotspotAvailableBands) {
            Toast.makeText(activity, "Этот диапазон недоступен на телефоне", Toast.LENGTH_LONG).show()
            return
        }
        syncAvailability()
        if (android.os.Build.VERSION.SDK_INT < 33) {
            MaterialAlertDialogBuilder(activity).setMessage("Открой редактирование шторки и добавь плитку «${activity.getString(entry.label)}».")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (adding) return
        adding = true
        runCatching {
            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(activity, entry.service), activity.getString(entry.label),
                Icon.createWithResource(activity, R.drawable.ic_hotspot_tile), activity.mainExecutor,
            ) { result ->
                adding = false
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                    Toast.makeText(activity, "Эта плитка уже есть в шторке", Toast.LENGTH_SHORT).show()
                else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED && result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
                    Toast.makeText(activity, "Не удалось добавить плитку. Попробуй через редактирование шторки.", Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            adding = false
            Toast.makeText(activity, "Не удалось добавить плитку. Попробуй через редактирование шторки.", Toast.LENGTH_LONG).show()
        }
    }
}

internal data class HotspotPresetState(val on: Boolean?, val route: TetherMode, val radio: String, val activeBands: Set<String>) {
    fun matches(preset: HotspotPreset): Boolean {
        if (on != true || route != preset.mode || ApRadio.key(radio) != preset.band) return false
        val expected = if (preset.band == "dual25") setOf("2", "5") else setOf(preset.band)
        return activeBands.all { it in expected }
    }
}

abstract class PresetHotspotTileService : TileService() {
    protected abstract val band: String
    protected abstract val mode: TetherMode
    private var working = false
    private val preset get() = HotspotPreset(band, mode)

    override fun onStartListening() {
        super.onStartListening()
        HotspotPresetTiles.listening += this
        repaint()
        HotspotPresetTiles.observe()
    }

    override fun onStopListening() {
        HotspotPresetTiles.listening -= this
        super.onStopListening()
    }

    override fun onDestroy() {
        HotspotPresetTiles.listening -= this
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (working) return
        working = true
        repaint()
        App.scope.launch {
            val result = runCatching { HotspotControl.startPreset(preset) }.getOrElse { "Не удалось запустить раздачу: ${it.message}" }
            Handler(Looper.getMainLooper()).post {
                working = false
                repaint()
                Toast.makeText(this@PresetHotspotTileService, result, Toast.LENGTH_LONG).show()
            }
            HotspotPresetTiles.refresh()
        }
    }

    internal fun repaint() {
        val tile = qsTile ?: return
        val supported = band in Prefs.hotspotAvailableBands
        val active = HotspotPresetTiles.active(preset)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_hotspot_tile)
        tile.label = "${preset.routeLabel} · ${preset.bandLabel}"
        tile.subtitle = when { !supported -> "Недоступно"; working -> getString(R.string.working); active -> "Включено"; else -> "Запустить" }
        tile.contentDescription = "Раздача: ${preset.title}. " + if (active) "Включена" else "Быстрый запуск"
        tile.state = when { !supported -> Tile.STATE_UNAVAILABLE; active -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
        tile.updateTile()
    }
}

class HotspotVpn24TileService : PresetHotspotTileService() {
    override val band = "2"
    override val mode = TetherMode.VPN
}
class HotspotVpn5TileService : PresetHotspotTileService() {
    override val band = "5"
    override val mode = TetherMode.VPN
}
class HotspotVpn6TileService : PresetHotspotTileService() {
    override val band = "6"
    override val mode = TetherMode.VPN
}
class HotspotVpnDualTileService : PresetHotspotTileService() {
    override val band = "dual25"
    override val mode = TetherMode.VPN
}
class HotspotProxy24TileService : PresetHotspotTileService() {
    override val band = "2"
    override val mode = TetherMode.PROXY
}
class HotspotProxy5TileService : PresetHotspotTileService() {
    override val band = "5"
    override val mode = TetherMode.PROXY
}
class HotspotProxy6TileService : PresetHotspotTileService() {
    override val band = "6"
    override val mode = TetherMode.PROXY
}
class HotspotProxyDualTileService : PresetHotspotTileService() {
    override val band = "dual25"
    override val mode = TetherMode.PROXY
}
class HotspotDirect24TileService : PresetHotspotTileService() {
    override val band = "2"
    override val mode = TetherMode.DIRECT
}
class HotspotDirect5TileService : PresetHotspotTileService() {
    override val band = "5"
    override val mode = TetherMode.DIRECT
}
class HotspotDirect6TileService : PresetHotspotTileService() {
    override val band = "6"
    override val mode = TetherMode.DIRECT
}
class HotspotDirectDualTileService : PresetHotspotTileService() {
    override val band = "dual25"
    override val mode = TetherMode.DIRECT
}
