package com.netmode.switcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast

/**
 * Scenes ([Trigger.MANUAL] rules) on the home screen: a pinned shortcut each, and the first few
 * in the app icon's long-press menu. A press goes through the rules widget's button, so the
 * scene is started or ended exactly as from there, presses in a row included.
 */
object Scenes {

    const val EXTRA_ID = "id"

    /** The app icon's menu holds this many; launchers show four or five. */
    private const val IN_MENU = 4

    private fun id(ruleId: Long) = "scene-$ruleId"

    private fun label(rule: AppRule) = rule.name ?: rule.timeLabel

    private fun shortcut(ctx: Context, rule: AppRule): ShortcutInfo = ShortcutInfo.Builder(ctx, id(rule.id))
        .setShortLabel(label(rule).take(25))
        .setLongLabel(label(rule))
        .setIcon(Icon.createWithResource(ctx, R.drawable.ic_scene_shortcut))
        .setIntent(Intent(ctx, SceneActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ID, rule.id))
        .build()

    /** Asks the launcher to put [rule] on the home screen; false when it cannot. */
    fun pin(ctx: Context, rule: AppRule): Boolean {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(shortcut(ctx, rule), null) }.getOrDefault(false)
    }

    /**
     * Brings the shortcuts in line with the rules: the menu lists the enabled scenes, pinned
     * ones follow a rename, and the pinned shortcut of a deleted scene is greyed out — a
     * launcher keeps it until the user removes it.
     */
    fun sync(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val scenes = Prefs.appRules.filter { it.trigger == Trigger.MANUAL }
        sm.dynamicShortcuts = scenes.filter { it.enabled }.take(IN_MENU).map { shortcut(ctx, it) }
        val byId = scenes.associateBy { id(it.id) }
        val pinned = sm.pinnedShortcuts.filter { it.id.startsWith("scene-") }
        val kept = pinned.mapNotNull { byId[it.id] }
        if (kept.isNotEmpty()) {
            sm.enableShortcuts(kept.map { id(it.id) })
            sm.updateShortcuts(kept.map { shortcut(ctx, it) })
        }
        val gone = pinned.map { it.id }.filter { it !in byId }
        if (gone.isNotEmpty()) sm.disableShortcuts(gone, ctx.getString(R.string.scene_gone))
    }

    /** A press: starts the scene, or ends it when it is on. Returns what to tell the user. */
    fun press(ctx: Context, ruleId: Long): String {
        val rule = Prefs.appRules.find { it.id == ruleId } ?: return ctx.getString(R.string.scene_gone)
        val name = label(rule)
        val active = Want.rules[ruleId] ?: (AppFreezer.isActive(rule) || ruleId in Prefs.pendingStarts)
        if (!active && !rule.enabled) return ctx.getString(R.string.scene_disabled, name)
        WidgetActionReceiver.rule(ctx, ruleId).send()
        return when {
            active -> ctx.getString(R.string.scene_off, name)
            !rule.restores -> ctx.getString(R.string.scene_run, name)
            rule.level > 0 -> ctx.getString(R.string.scene_on_for, name, Steps.words(rule.level * 60))
            else -> ctx.getString(R.string.scene_on, name)
        }
    }
}

/** What a scene's shortcut opens: nothing to see, it presses the scene and is gone. */
class SceneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(Scenes.EXTRA_ID, 0L)
        Toast.makeText(applicationContext, Scenes.press(applicationContext, id), Toast.LENGTH_SHORT).show()
        finish()
    }
}
