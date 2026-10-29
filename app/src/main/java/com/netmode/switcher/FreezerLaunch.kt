package com.netmode.switcher

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The freezer on the home screen: a pinned shortcut per app, one for the "Морозилка" folder, and
 * the most recently opened ones in the app icon's long-press menu, next to the scenes. Each
 * opens [ThawActivity], which thaws the app and opens it.
 */
object IceShortcuts {

    const val EXTRA_KEY = "key"
    const val EXTRA_FOLDER = "folder"
    const val EXTRA_ALL = "freeze_all"
    const val EXTRA_GROUP = "ice_group"
    const val EXTRA_GROUP_ACTION = "ice_group_action"

    private const val PREFIX = "ice-"
    private const val FOLDER_ID = "ice-folder"
    private const val ALL_ID = "ice-all"
    private const val GROUP_PREFIX = "ice-group-"

    /** Whether each pinned shortcut was last painted with the snowflake: only a change is sent. */
    private val painted = ConcurrentHashMap<String, Boolean>()

    @Volatile private var pending: Job? = null

    private fun id(key: String) = PREFIX + key

    fun thawIntent(ctx: Context, key: String): Intent =
        Intent(ctx, ThawActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_KEY, key)

    fun folderIntent(ctx: Context, name: String? = null): Intent =
        Intent(ctx, IceFolderActivity::class.java).setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { name?.let { putExtra(EXTRA_GROUP, it) } }

    private fun info(ctx: Context, a: IceApp): ShortcutInfo {
        val label = Freezer.label(a.key)
        val badge = Prefs.iceBadge && a.frozen
        painted[id(a.key)] = badge
        return ShortcutInfo.Builder(ctx, id(a.key))
            .setShortLabel(label.take(25))
            .setLongLabel(label)
            .setIcon(Icon.createWithBitmap(icon(ctx, a.key, badge)))
            .setIntent(thawIntent(ctx, a.key))
            .build()
    }

    private fun folder(ctx: Context): ShortcutInfo = ShortcutInfo.Builder(ctx, FOLDER_ID)
        .setShortLabel(ctx.getString(R.string.ice_title))
        .setIcon(Icon.createWithResource(ctx, R.drawable.ic_ice_shortcut))
        .setIntent(folderIntent(ctx))
        .build()

    private fun freezeAll(ctx: Context): ShortcutInfo = ShortcutInfo.Builder(ctx, ALL_ID)
        .setShortLabel(ctx.getString(R.string.ice_freeze_all))
        .setIcon(Icon.createWithResource(ctx, R.drawable.ic_ice_shortcut))
        .setIntent(Intent(ctx, ThawActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ALL, true))
        .build()

    private fun manager(ctx: Context): ShortcutManager? = ctx.getSystemService(ShortcutManager::class.java)

    /** Asks the launcher to put [key]'s shortcut on the home screen; false when it cannot. */
    fun pin(ctx: Context, key: String): Boolean {
        val a = Freezer.find(key) ?: return false
        val sm = manager(ctx) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(info(ctx, a), null) }.getOrDefault(false)
    }

    fun pinFolder(ctx: Context): Boolean {
        val sm = manager(ctx) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(folder(ctx), null) }.getOrDefault(false)
    }

    private fun group(ctx: Context, name: String, action: IceGroupShortcutAction): ShortcutInfo {
        val label = when (action) {
            IceGroupShortcutAction.OPEN -> name
            IceGroupShortcutAction.FREEZE -> ctx.getString(R.string.ice_group_shortcut_freeze_label, name)
            IceGroupShortcutAction.THAW -> ctx.getString(R.string.ice_group_shortcut_thaw_label, name)
        }
        return ShortcutInfo.Builder(ctx, GROUP_PREFIX + action.name + "-" + Uri.encode(name))
            .setShortLabel(label.take(25)).setLongLabel(label)
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_ice_shortcut))
            .setIntent(if (action == IceGroupShortcutAction.OPEN) folderIntent(ctx, name)
                else Intent(ctx, ThawActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_GROUP, name).putExtra(EXTRA_GROUP_ACTION, action.name))
            .build()
    }

    fun pinGroup(ctx: Context, name: String, action: IceGroupShortcutAction): Boolean {
        if (name !in Freezer.groups()) return false
        val sm = manager(ctx) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(group(ctx, name, action), null) }.getOrDefault(false)
    }

    fun isPinned(ctx: Context, key: String): Boolean =
        runCatching { manager(ctx)?.pinnedShortcuts?.any { it.id == id(key) && it.isEnabled } == true }.getOrDefault(false)

    /** For the app icon's menu, [room] places at most: the last opened, then "freeze all". */
    fun menu(ctx: Context, room: Int): List<ShortcutInfo> {
        val list = Freezer.apps
        if (room <= 0 || list.isEmpty()) return emptyList()
        val recent = list.filter { it.launchedAt > 0 }.sortedByDescending { it.launchedAt }.take(room - 1)
        return recent.map { info(ctx, it) } + freezeAll(ctx)
    }

    /**
     * Follows the freezer: the snowflake on pinned shortcuts as apps freeze and thaw, a removed
     * app's shortcut greyed out, the menu's last opened. Calls close together are one.
     */
    fun refresh() {
        pending?.cancel()
        pending = App.scope.launch {
            delay(500)
            val ctx = App.ctx
            val sm = manager(ctx) ?: return@launch
            runCatching {
                val byId = Freezer.apps.associateBy { id(it.key) }
                val allPinned = sm.pinnedShortcuts
                val pinned = allPinned.filter { it.id.startsWith(PREFIX) && !it.id.startsWith(GROUP_PREFIX) && it.id != FOLDER_ID && it.id != ALL_ID }
                val stale = pinned.mapNotNull { s -> byId[s.id]?.takeIf { painted[s.id] != (Prefs.iceBadge && it.frozen) || !s.isEnabled } }
                if (stale.isNotEmpty()) {
                    sm.enableShortcuts(stale.map { id(it.key) })
                    sm.updateShortcuts(stale.map { info(ctx, it) })
                }
                val gone = pinned.map { it.id }.filter { it !in byId }
                if (gone.isNotEmpty()) sm.disableShortcuts(gone, ctx.getString(R.string.ice_gone))
                val groupIds = Freezer.groups().flatMap { name -> IceGroupShortcutAction.entries.map { group(ctx, name, it).id } }.toSet()
                val groupPins = allPinned.filter { it.id.startsWith(GROUP_PREFIX) }
                val keptGroups = groupPins.filter { it.id in groupIds && !it.isEnabled }.map { it.id }
                if (keptGroups.isNotEmpty()) sm.enableShortcuts(keptGroups)
                val goneGroups = groupPins.filter { it.id !in groupIds && it.isEnabled }.map { it.id }
                if (goneGroups.isNotEmpty()) sm.disableShortcuts(goneGroups, ctx.getString(R.string.ice_group_gone))
            }
            runCatching { Scenes.publishMenu(ctx) }
        }
    }

    fun forget(ctx: Context, keys: List<String>) {
        val sm = manager(ctx) ?: return
        runCatching { sm.disableShortcuts(keys.map { id(it) }, ctx.getString(R.string.ice_gone)) }
        keys.forEach { painted -= id(it) }
    }

    /** The app's own icon, the snowflake in a corner while it is frozen. */
    fun icon(ctx: Context, key: String, badge: Boolean): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pm = ctx.packageManager
        val d = Freezer.info(pm, key)?.loadIcon(pm) ?: pm.defaultActivityIcon
        d.setBounds(0, 0, size, size)
        d.draw(c)
        if (badge) {
            val r = size * 0.21f
            val cx = size - r - 2f
            val cy = size - r - 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(cx, cy, r + 4f, paint)
            paint.color = 0xFFDAE2FF.toInt()
            c.drawCircle(cx, cy, r, paint)
            ctx.getDrawable(R.drawable.ic_ice_badge)?.let { s ->
                val h = (r * 0.68f).toInt()
                s.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
                s.draw(c)
            }
        }
        return bmp
    }

    /** The app's icon greyed out as the launcher shows a suspended app; for the screens. */
    fun greyed(d: Drawable, frozen: Boolean): Drawable {
        val out = d.mutate()
        if (frozen) {
            // Dimmed enough to tell, not so much that the icon cannot be made out.
            out.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            out.alpha = 200
        } else {
            out.colorFilter = null
            out.alpha = 255
        }
        return out
    }
}

/**
 * What the freezer's shortcuts open, over the home screen: thaws the app and opens it (asking
 * first only when [Prefs.iceNoConfirm] is off), shows the "Морозилка" folder, or freezes all.
 */
class ThawActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(IceShortcuts.EXTRA_KEY)
        when {
            intent.hasExtra(IceShortcuts.EXTRA_GROUP) -> {
                val name = intent.getStringExtra(IceShortcuts.EXTRA_GROUP)
                if (name == null || name !in Freezer.groups()) {
                    toast(getString(R.string.ice_group_gone))
                    finish()
                    return
                }
                when (intent.getStringExtra(IceShortcuts.EXTRA_GROUP_ACTION)) {
                    null, IceGroupShortcutAction.OPEN.name -> openFolder(name)
                    IceGroupShortcutAction.FREEZE.name -> groupShortcut(name, true)
                    IceGroupShortcutAction.THAW.name -> groupShortcut(name, false)
                    else -> finish()
                }
            }
            intent.getBooleanExtra(IceShortcuts.EXTRA_ALL, false) -> freezeAll()
            intent.getBooleanExtra(IceShortcuts.EXTRA_FOLDER, false) -> openFolder()
            key != null -> if (Prefs.iceNoConfirm) open(key) else confirm(key)
            else -> finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun confirm(key: String) {
        if (Freezer.find(key) == null) return gone()
        MaterialAlertDialogBuilder(this)
            .setTitle(Freezer.label(key))
            .setMessage(R.string.ice_confirm)
            .setPositiveButton(R.string.ice_open) { _, _ -> open(key) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun gone() {
        toast(getString(R.string.ice_gone))
        finish()
    }

    /** The work runs in the app's scope: this screen closing must not cut it short. */
    private fun open(key: String) {
        if (Freezer.find(key) == null) return gone()
        val work = App.scope.async { Freezer.launch(key, "ярлык") }
        lifecycleScope.launch {
            if (!work.await()) toast(getString(R.string.ice_open_failed, Freezer.label(key)))
            finish()
        }
    }

    private fun freezeAll() {
        val work = App.scope.async { Freezer.freezeAll("ярлык") }
        lifecycleScope.launch {
            val n = work.await()
            toast(when {
                n < 0 -> getString(R.string.ice_no_access)
                n == 0 -> getString(R.string.ice_all_frozen)
                else -> getString(R.string.ice_frozen_n, n)
            })
            finish()
        }
    }

    private fun openFolder(name: String? = null) {
        startActivity(IceShortcuts.folderIntent(this, name))
        finish()
    }

    private fun groupShortcut(name: String, freeze: Boolean) {
        val keys = IceGroupSelection(name).apps(Freezer.apps).map { it.key }
        if (keys.isEmpty()) {
            toast(getString(R.string.ice_group_empty))
            finish()
            return
        }
        val work = App.scope.async { runCatching { Freezer.manual(keys, freeze, "ярлык группы") } }
        lifecycleScope.launch {
            val result = work.await().getOrNull()
            toast(when {
                result == null -> getString(R.string.ice_tile_failed)
                result.changed < 0 -> getString(R.string.ice_no_access)
                result.requested == 0 -> getString(R.string.ice_no_changes)
                result.changed < result.requested -> getString(R.string.ice_tile_partial, result.changed, result.requested)
                result.freeze -> getString(R.string.ice_frozen_n, result.changed)
                else -> getString(R.string.ice_thawed_n, result.changed)
            })
            finish()
        }
    }

}

/** Lit when everything is frozen. A tap thaws it all, or freezes any remaining thawed apps. */
object FreezerTile {

    internal val listening: MutableSet<FreezerTileService> = ConcurrentHashMap.newKeySet()
    internal val busy = AtomicBoolean(false)

    @Volatile private var pending: Job? = null

    /**
     * Repaints the tile while the shade shows it. Never from the tile itself: asking for a
     * listening state from onStartListening() loops (see HANDOFF_2026-09-19.md).
     */
    fun refresh() {
        if (listening.isEmpty()) return
        pending?.cancel()
        pending = App.scope.launch {
            delay(300)
            val live = listening.toList()
            android.os.Handler(android.os.Looper.getMainLooper()).post { live.forEach { it.repaint() } }
        }
    }
}

class FreezerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        FreezerTile.listening += this
        // Only what is stored: no shell here.
        repaint()
    }

    override fun onStopListening() {
        FreezerTile.listening -= this
        super.onStopListening()
    }

    override fun onDestroy() {
        FreezerTile.listening -= this
        super.onDestroy()
    }

    internal fun repaint() {
        val tile = qsTile ?: return
        val list = Freezer.apps
        val thawed = list.count { !it.frozen }
        val state = IceTileState.of(list)
        val busy = FreezerTile.busy.get()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ice)
        tile.label = getString(R.string.ice_title)
        tile.subtitle = when {
            busy -> getString(R.string.working)
            state == IceTileState.EMPTY -> getString(R.string.ice_tile_empty)
            state == IceTileState.FROZEN -> getString(R.string.ice_tile_all_frozen)
            state == IceTileState.THAWED -> getString(R.string.ice_tile_all_thawed)
            else -> getString(R.string.ice_tile_mixed, list.size - thawed, list.size)
        }
        tile.contentDescription = getString(R.string.ice_title) + ", " + tile.subtitle + ". " +
            getString(if (state == IceTileState.EMPTY) R.string.ice_manage
                else if (state.freezeOnClick) R.string.ice_freeze_all else R.string.ice_thaw_all)
        tile.state = when {
            busy -> Tile.STATE_UNAVAILABLE
            state == IceTileState.FROZEN -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Freezer.apps.isEmpty()) {
            val intent = Intent(this, FreezerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isLocked) unlockAndRun { open(intent) } else open(intent)
            return
        }
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        // Ignore another tap while shell commands run; otherwise two taps undo each other.
        if (!FreezerTile.busy.compareAndSet(false, true)) return
        repaint()
        FreezerTile.refresh()
        App.scope.launch {
            try {
                val result = Freezer.toggleAll("плитка")
                val text = when {
                    result.changed < 0 -> getString(R.string.ice_no_access)
                    result.requested == 0 -> getString(R.string.ice_empty_short)
                    result.changed < result.requested -> getString(R.string.ice_tile_partial, result.changed, result.requested)
                    result.freeze -> getString(R.string.ice_frozen_n, result.changed)
                    else -> getString(R.string.ice_thawed_n, result.changed)
                }
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Prefs.log("морозилка (плитка): ошибка — ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, R.string.ice_tile_failed, Toast.LENGTH_LONG).show() }
            } finally {
                FreezerTile.busy.set(false)
                FreezerTile.refresh()
            }
        }
    }

    /** Android 14 takes a PendingIntent only; before, it has only the Intent form. */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, FreezerTileService::class.java)
    }
}
