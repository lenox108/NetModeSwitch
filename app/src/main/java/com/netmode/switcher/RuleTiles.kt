package com.netmode.switcher

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A rule's own button in the shade. A tile is a component declared in the manifest, so there
 * is a fixed number of them, each pointed at a rule by [Prefs.ruleTiles]. A press goes through
 * the rules widget's button, exactly as a press there or on a scene's shortcut: it starts the
 * rule, or ends it when it is on.
 */
object RuleTiles {

    val classes = listOf(
        RuleTile1Service::class.java,
        RuleTile2Service::class.java,
        RuleTile3Service::class.java,
        RuleTile4Service::class.java,
        RuleTile5Service::class.java,
    )

    fun component(ctx: Context, slot: Int) = ComponentName(ctx, classes[slot])

    fun slotOf(ruleId: Long): Int? = Prefs.ruleTiles.entries.firstOrNull { it.value == ruleId }?.key

    fun ruleIn(slot: Int): AppRule? = Prefs.ruleTiles[slot]?.let { id -> Prefs.appRules.find { it.id == id } }

    /** The rule's slot: its own, else the first free one; null when every one is taken. */
    fun assign(ruleId: Long): Int? {
        val map = Prefs.ruleTiles
        map.entries.firstOrNull { it.value == ruleId }?.let { return it.key }
        val free = classes.indices.firstOrNull { it !in map } ?: return null
        Prefs.ruleTiles = map + (free to ruleId)
        return free
    }

    /** Points [slot] at [ruleId], taking the rule off any other slot it had. */
    fun bind(slot: Int, ruleId: Long) {
        Prefs.ruleTiles = Prefs.ruleTiles.filterValues { it != ruleId } + (slot to ruleId)
    }

    fun release(slot: Int) {
        if (slot in Prefs.ruleTiles) Prefs.ruleTiles = Prefs.ruleTiles - slot
    }

    fun forget(ruleId: Long) = forget(setOf(ruleId))

    fun forget(ruleIds: Set<Long>) {
        val tiles = Prefs.ruleTiles
        if (tiles.values.any { it in ruleIds }) Prefs.ruleTiles = tiles.filterValues { it !in ruleIds }
    }

    fun label(rule: AppRule) = rule.name ?: rule.timeLabel

    /** On, or about to be: what a press asked for shows before the work is done. */
    fun active(rule: AppRule): Boolean =
        Want.rules[rule.id] ?: (AppFreezer.isActive(rule) || rule.id in Prefs.pendingStarts)

    fun icon(t: Trigger) = when (t) {
        Trigger.MANUAL -> R.drawable.ic_t_manual
        Trigger.NOTIFY_APP -> R.drawable.ic_t_notify
        Trigger.USB -> R.drawable.ic_t_usb
        Trigger.CAR -> R.drawable.ic_t_car
        Trigger.VPN -> R.drawable.ic_a_vpn
        Trigger.HOTSPOT -> R.drawable.ic_hotspot
        Trigger.ALARM -> R.drawable.ic_t_alarm
        Trigger.SUN -> R.drawable.ic_t_sun
        Trigger.TRAFFIC -> R.drawable.ic_t_traffic
        Trigger.WIFI_OTHER -> R.drawable.ic_t_wifi
        Trigger.TIME -> R.drawable.ic_t_time
        Trigger.WIFI, Trigger.WIFI_LEFT -> R.drawable.ic_t_wifi
        Trigger.BLUETOOTH -> R.drawable.ic_t_bt
        Trigger.MOBILE_DATA -> R.drawable.ic_t_data
        Trigger.NET_MODE -> R.drawable.ic_t_net
        Trigger.BATTERY -> R.drawable.ic_t_battery
        Trigger.CHARGING -> R.drawable.ic_t_charging
        Trigger.SCREEN_OFF -> R.drawable.ic_t_screen
        Trigger.APP_OPEN -> R.drawable.ic_t_app
        Trigger.PLACE -> R.drawable.ic_t_place
        Trigger.ROAMING -> R.drawable.ic_t_roaming
        Trigger.WEAK_SIGNAL -> R.drawable.ic_t_signal
        Trigger.CALL -> R.drawable.ic_t_call
        Trigger.HEADPHONES -> R.drawable.ic_t_headphones
        Trigger.DND -> R.drawable.ic_t_dnd
        Trigger.NO_INTERNET -> R.drawable.ic_t_nonet
        Trigger.LOCKED -> R.drawable.ic_t_lock
    }

    @Volatile private var pending: Job? = null

    /**
     * Repaints the bound tiles after a change made outside them; calls close together repaint
     * once. Never call it from a tile: asking for a listening state from inside
     * onStartListening() calls onStartListening() again, forever (see HANDOFF_2026-09-19.md).
     */
    fun refresh() {
        val slots = Prefs.ruleTiles.keys.filter { it in classes.indices }
        if (slots.isEmpty() && listening.isEmpty()) return
        pending?.cancel()
        pending = App.scope.launch {
            delay(300)
            // A tile in the open shade is already listening: asking again would not repaint it.
            val live = listening.toList()
            if (live.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).post { live.forEach { it.repaint() } }
            val idle = slots - live.map { it.slotNo }.toSet()
            idle.forEach { runCatching { TileService.requestListeningState(App.ctx, component(App.ctx, it)) } }
        }
    }

    /** Tiles shown in the open shade right now. */
    internal val listening: MutableSet<BaseRuleTileService> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val ruleKeys = setOf("app_rules", "open_windows", "pending_starts", "rule_tiles")

    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in ruleKeys) refresh() }

    /** Rules start and end in the background: their tiles follow for as long as the process lives. */
    fun watch() {
        Prefs.listen(prefs)
        // Whatever changed while the process was gone.
        refresh()
    }
}

abstract class BaseRuleTileService : TileService() {

    protected abstract val slot: Int

    override fun onTileRemoved() {
        super.onTileRemoved()
        // Out of the shade, the slot is free for another rule.
        RuleTiles.release(slot)
    }

    internal val slotNo: Int get() = slot

    override fun onStartListening() {
        super.onStartListening()
        RuleTiles.listening += this
        // Only reads what is stored: no shell, and never RuleTiles.refresh() from here.
        repaint()
    }

    override fun onStopListening() {
        RuleTiles.listening -= this
        super.onStopListening()
    }

    override fun onDestroy() {
        RuleTiles.listening -= this
        super.onDestroy()
    }

    internal fun repaint() = paint(RuleTiles.ruleIn(slot)?.let { RuleTiles.active(it) })

    override fun onClick() {
        super.onClick()
        val rule = RuleTiles.ruleIn(slot)
        if (rule == null) {
            pick()
            return
        }
        val active = RuleTiles.active(rule)
        if (!active && !rule.enabled) return paint(false)
        Scenes.press(App.ctx, rule.id)
        // The target at once; the change of the rule's state repaints the outcome.
        paint(!active, busy = true)
    }

    private fun pick() {
        val intent = Intent(this, RuleTilePickActivity::class.java)
            .putExtra(RuleTilePickActivity.EXTRA_SLOT, slot)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isLocked) unlockAndRun { open(intent) } else open(intent)
    }

    /** Android 14 takes a PendingIntent only; before, it has only the Intent form. */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun paint(on: Boolean?, busy: Boolean = false) {
        val tile = qsTile ?: return
        val rule = RuleTiles.ruleIn(slot)
        if (rule == null) {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_t_manual)
            tile.label = getString(R.string.rule_tile_n, slot + 1)
            tile.subtitle = getString(R.string.rule_tile_unbound)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
            return
        }
        tile.icon = Icon.createWithResource(this, RuleTiles.icon(rule.trigger))
        tile.label = RuleTiles.label(rule)
        tile.subtitle = when {
            busy -> getString(R.string.working)
            on != true && !rule.enabled -> getString(R.string.rule_tile_disabled)
            !rule.restores -> getString(R.string.rule_tile_run)
            on == true -> getString(R.string.rule_tile_on)
            else -> getString(R.string.rule_tile_off)
        }
        tile.state = if (on == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

class RuleTile1Service : BaseRuleTileService() {
    override val slot = 0
}

class RuleTile2Service : BaseRuleTileService() {
    override val slot = 1
}

class RuleTile3Service : BaseRuleTileService() {
    override val slot = 2
}

class RuleTile4Service : BaseRuleTileService() {
    override val slot = 3
}

class RuleTile5Service : BaseRuleTileService() {
    override val slot = 4
}

/** A tile with no rule yet: a press asks which rule it is for, over whatever is on screen. */
class RuleTilePickActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        val rules = Prefs.appRules
        if (slot !in RuleTiles.classes.indices || rules.isEmpty()) {
            if (slot in RuleTiles.classes.indices) android.widget.Toast.makeText(this, R.string.rule_tile_no_rules, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rule_tile_pick, slot + 1))
            .setItems(rules.map { RuleTiles.label(it) }.toTypedArray()) { _, i ->
                RuleTiles.bind(slot, rules[i].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_SLOT = "slot"
    }
}
