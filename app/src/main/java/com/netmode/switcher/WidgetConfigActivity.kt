package com.netmode.switcher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * What a widget shows, asked when it is added and again from the launcher's "Настроить":
 * the button's job, the control panel's quick switches, the rule list's rules. Only a dialog
 * over the home screen; closing it without choosing keeps the defaults.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // Until a choice is made, backing out must not leave a half-set-up widget behind.
        setResult(RESULT_CANCELED, result())
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className
        val content: (() -> Unit)? = when (provider) {
            ButtonWidget::class.java.name -> ::button
            RulesWidget::class.java.name -> ::rules
            ModeWidget::class.java.name -> ::quick
            else -> null
        }
        when {
            // A new button has to know its job first; the look can wait for "Настроить".
            provider == ButtonWidget::class.java.name && Prefs.widgetConfig(id) == null -> content?.invoke()
            else -> menu(provider, content)
        }
    }

    /** "Настроить" on a widget: what it shows, or how see-through it is. */
    private fun menu(provider: String?, content: (() -> Unit)?) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (content != null) items += getString(when (provider) {
            ButtonWidget::class.java.name -> R.string.w_cfg_button
            RulesWidget::class.java.name -> R.string.w_cfg_rules
            else -> R.string.w_cfg_quick
        }) to content
        items += getString(R.string.w_cfg_opacity) to ::opacity
        items += getString(R.string.w_cfg_text) to ::textColour
        items += getString(R.string.w_cfg_scale) to ::textSize
        dialog()
            .setTitle(R.string.w_cfg_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** The backing from solid to none; the content stays as it is. */
    private fun opacity() {
        val levels = listOf(100, 75, 50, 25, 0)
        val labels = levels.map { if (it == 0) getString(R.string.w_opacity_none) else getString(R.string.w_opacity_n, it) }
        var picked = levels.indexOf(Prefs.widgetAlpha(id)).takeIf { it >= 0 } ?: 0
        dialog()
            .setTitle(R.string.w_cfg_opacity)
            .setSingleChoiceItems(labels.toTypedArray(), picked) { _, i -> picked = i }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                Prefs.setWidgetAlpha(id, levels[picked])
                saved()
            }
            .setNeutralButton(R.string.w_opacity_all) { _, _ ->
                Widgets.providers.flatMap { Widgets.ids(this, it).toList() }.forEach { Prefs.setWidgetAlpha(it, levels[picked]) }
                saved()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** Text by the wallpaper, or fixed: the wallpaper's hint is wrong next to a light clock widget. */
    private fun textColour() {
        val modes = listOf("auto", "light", "dark", "theme")
        val labels = listOf(R.string.w_text_auto, R.string.w_text_light, R.string.w_text_dark, R.string.w_text_theme).map { getString(it) }
        var picked = modes.indexOf(Prefs.widgetText(id)).takeIf { it >= 0 } ?: 0
        dialog()
            .setTitle(R.string.w_cfg_text)
            .setSingleChoiceItems(labels.toTypedArray(), picked) { _, i -> picked = i }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                Prefs.setWidgetText(id, modes[picked])
                saved()
            }
            .setNeutralButton(R.string.w_opacity_all) { _, _ ->
                Widgets.providers.flatMap { Widgets.ids(this, it).toList() }.forEach { Prefs.setWidgetText(it, modes[picked]) }
                saved()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** Larger or smaller text; past 120% it no longer fits the buttons. */
    private fun textSize() {
        val levels = listOf(40, 50, 60, 70, 80, 90, 100, 110, 120)
        val labels = levels.map { getString(R.string.w_scale_n, it) }
        var picked = levels.indexOf(Prefs.widgetScale(id)).takeIf { it >= 0 } ?: levels.indexOf(100)
        dialog()
            .setTitle(R.string.w_cfg_scale)
            .setSingleChoiceItems(labels.toTypedArray(), picked) { _, i -> picked = i }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                Prefs.setWidgetScale(id, levels[picked])
                saved()
            }
            .setNeutralButton(R.string.w_opacity_all) { _, _ ->
                Widgets.providers.flatMap { Widgets.ids(this, it).toList() }.forEach { Prefs.setWidgetScale(it, levels[picked]) }
                saved()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun saved() {
        setResult(RESULT_OK, result())
        Widgets.watch()
        Widgets.update()
        finish()
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    private fun done(value: String?) {
        Prefs.setWidgetConfig(id, value)
        setResult(RESULT_OK, result())
        Widgets.watch()
        Widgets.refresh()
        finish()
    }

    private fun dialog() = MaterialAlertDialogBuilder(this).setOnCancelListener { finish() }

    /** One tap does one thing: a SIM's mode, the hotspot in a route, or a rule. */
    private fun button() {
        val sims = Sims.known.ifEmpty { Sims.slots().map { SimInfo(it, "") } }
        val choices = mutableListOf<Pair<String, String>>()
        sims.forEach { choices += "sim:${it.slot}" to getString(R.string.w_cfg_sim, Widgets.simName(it)) }
        TetherMode.entries.forEach { choices += "ap:${it.name}" to getString(R.string.w_ap_in, ButtonWidget.apName(this, it)) }
        choices += "unload" to getString(R.string.w_unload)
        choices += "unload:all" to getString(R.string.w_unload_all)
        Prefs.appRules.forEach { choices += "rule:${it.id}" to getString(R.string.w_cfg_rule, it.name ?: it.timeLabel) }
        val current = choices.indexOfFirst { it.first == Prefs.widgetConfig(id) }
        dialog()
            .setTitle(R.string.w_cfg_button)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), current) { d, i ->
                d.dismiss()
                done(choices[i].first)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** Up to four switches under the mode buttons, in the order picked. */
    private fun quick() {
        val all = Quick.entries
        val chosen = Quick.parse(Prefs.widgetConfig(id)).toMutableList()
        val checked = BooleanArray(all.size) { all[it] in chosen }
        dialog()
            .setTitle(R.string.w_cfg_quick)
            .setMultiChoiceItems(all.map { getString(it.label) }.toTypedArray(), checked) { d, i, on ->
                if (on && chosen.size >= MAX_QUICK) {
                    // A fifth would not fit: the tick is taken back.
                    (d as androidx.appcompat.app.AlertDialog).listView.setItemChecked(i, false)
                    checked[i] = false
                    return@setMultiChoiceItems
                }
                checked[i] = on
                if (on) chosen += all[i] else chosen -= all[i]
            }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                done(if (chosen.isEmpty()) NONE else chosen.joinToString(",") { it.name })
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** Which rules to list; none ticked means all of them, new ones included. */
    private fun rules() {
        val rules = Prefs.appRules
        if (rules.isEmpty()) return done(null)
        val chosen = Prefs.widgetConfig(id)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
        val checked = BooleanArray(rules.size) { rules[it].id in chosen }
        dialog()
            .setTitle(R.string.w_cfg_rules)
            .setMultiChoiceItems(rules.map { it.name ?: it.timeLabel }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                done(rules.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.id.toString() }.ifEmpty { null })
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    companion object {
        private const val MAX_QUICK = 4

        /** The panel with no quick switches at all, which an empty value would not say. */
        const val NONE = "-"
    }
}
