package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.netmode.switcher.databinding.ActivityAppRulesBinding
import com.netmode.switcher.databinding.RowRuleBinding
import kotlinx.coroutines.launch

/** The list of rules. Editing happens in [RuleEditActivity]. */
class AppRulesActivity : AppCompatActivity() {

    private lateinit var ui: ActivityAppRulesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityAppRulesBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.toolbar.menu.add(R.string.sort_title).setOnMenuItemClickListener { chooseSort(); true }
        ui.held.setOnClickListener { chooseToReturn() }
        ui.addApps.setOnClickListener { startActivity(Intent(this, RuleEditActivity::class.java)) }
        ui.fromTemplate.setOnClickListener { fromTemplate() }
        renderTemplates()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun edit(rule: AppRule) =
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_ID, rule.id))

    /** Templates: a ready rule opened in the editor, saved only if the user saves it. */
    private fun fromTemplate() {
        val names = Templates.all.map { getString(it.title) + " — " + getString(it.short) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.from_template)
            .setItems(names.toTypedArray()) { _, i -> openTemplate(Templates.all[i]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openTemplate(t: Templates.Template) =
        startActivity(Intent(this, RuleEditActivity::class.java)
            .putExtra(RuleEditActivity.EXTRA_TEMPLATE, t.rule(this).toJson().toString()))

    private fun renderTemplates() {
        for (t in Templates.all) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                text = getString(t.title) + " — " + getString(t.short)
                setOnClickListener { openTemplate(t) }
            }
            ui.templates.addView(b)
        }
    }

    private fun iconOf(t: Trigger) = RuleTiles.icon(t)

    private fun render() {
        val rules = sorted()
        val ids = rules.map { it.id }
        val pendingStarts = Prefs.pendingStarts
        ui.appList.removeAllViews()
        rules.forEach { rule ->
            val row = RowRuleBinding.inflate(layoutInflater, ui.appList, false)
            row.icon.setImageResource(iconOf(rule.trigger))
            row.title.text = rule.name ?: rule.timeLabel.replaceFirstChar { it.uppercase() }
            val whenText = listOfNotNull(
                rule.timeLabel.takeIf { rule.name != null },
                rule.daysLabel.takeIf { rule.isTime },
            ).joinToString(" · ")
            row.whenLine.text = whenText
            row.whenLine.visibility = if (whenText.isEmpty()) View.GONE else View.VISIBLE
            val names = AppPickerActivity.labels(rule.packages)
            row.doLine.text = "→ " + listOfNotNull(
                rule.actionLabel.ifEmpty { null },
                names.takeIf { it.isNotEmpty() }?.let { it.take(4).joinToString(", ") + if (it.size > 4) "…" else "" },
            ).joinToString("\n")
            row.endLine.text = listOfNotNull(rule.unlockLabel, when {
                !rule.restores -> getString(R.string.end_never)
                rule.endLabel != null -> rule.endLabel
                else -> getString(R.string.end_back)
            }).joinToString("\n")
            val held = AppFreezer.heldCount(rule.id)
            val active = AppFreezer.isActive(rule)
            val waiting = rule.id in pendingStarts
            val unlocked = rule.id in Prefs.lockPaused
            val asked = rule.id in Prefs.askedStarts
            row.status.visibility = if (active || held > 0 || waiting || asked) View.VISIBLE else View.GONE
            row.status.text = listOfNotNull(
                getString(R.string.waiting_answer).takeIf { asked },
                getString(R.string.waiting_lock).takeIf { waiting },
                getString(R.string.unlocked_until, "%02d:%02d".format(rule.endHour ?: 0, rule.endMinute ?: 0)).takeIf { unlocked },
                getString(R.string.active_now).takeIf { active && !waiting && !unlocked && !asked },
                getString(R.string.held_now, held).takeIf { held > 0 },
            ).joinToString(" · ")
            row.root.alpha = if (rule.enabled) 1f else 0.55f
            row.enabled.isChecked = rule.enabled
            row.enabled.setOnCheckedChangeListener { _, on ->
                RuleStore.save(rule.copy(enabled = on))
                render()
            }
            row.root.setOnClickListener { edit(rule) }
            row.root.setOnLongClickListener { menu(rule, it); true }
            row.drag.visibility = if (rules.size > 1) View.VISIBLE else View.GONE
            dragBy(row.drag, row.root) { from, to -> moved(ids, from, to) }
            ui.appList.addView(row.root)
        }
        ui.appEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        val heldApps = AppFreezer.heldApps()
        ui.held.visibility = if (heldApps.isEmpty()) View.GONE else View.VISIBLE
        ui.held.text = getString(R.string.held_block, heldApps.size)
    }

    private fun chooseSort() {
        val all = RuleSort.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(all.map { getString(it.label) }.toTypedArray(), all.indexOf(Prefs.ruleSort)) { d, i ->
                // Into "by hand" from another sort: it starts from the order on screen now.
                if (all[i] == RuleSort.MANUAL && Prefs.ruleSort != RuleSort.MANUAL) Prefs.ruleOrder = sorted().map { it.id }
                Prefs.ruleSort = all[i]
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sorted(): List<AppRule> {
        val open = Prefs.openWindows
        return RuleOrder.sort(Prefs.appRules, Prefs.ruleSort, Prefs.ruleOrder) { AppFreezer.isActive(it, open) }
    }

    /** A rule dragged from [from] to [to] of [ids], the list as shown: that order is the user's now. */
    private fun moved(ids: List<Long>, from: Int, to: Int) {
        Prefs.ruleOrder = RuleOrder.move(ids, from, to)
        if (Prefs.ruleSort != RuleSort.MANUAL) {
            Prefs.ruleSort = RuleSort.MANUAL
            snack(getString(R.string.sort_now_manual))
        }
        render()
    }

    /**
     * Drag [row] by [handle] to another place in the list. The other rows only slide aside
     * while dragging; the order changes when the finger lifts — taking a view out of its
     * parent mid-gesture would cancel the gesture. Same as the steps in [RuleEditActivity].
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun dragBy(handle: View, row: View, done: (Int, Int) -> Unit) {
        val list = ui.appList
        // Near the screen's edge: without this the drag is taken for the system's "back".
        if (Compat.SDK >= 29) handle.addOnLayoutChangeListener { h, l, t, r, b, _, _, _, _ ->
            h.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, r - l, b - t))
        }
        var downY = 0f
        var target = -1
        handle.setOnTouchListener { view, e ->
            val from = list.indexOfChild(row)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    downY = e.rawY
                    target = from
                    row.elevation = 8 * resources.displayMetrics.density
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (target < 0) return@setOnTouchListener true
                    row.translationY = e.rawY - downY
                    val mid = row.top + row.translationY + row.height / 2f
                    var to = from
                    for (k in 0 until list.childCount) {
                        val c = list.getChildAt(k)
                        val cMid = c.top + c.height / 2f
                        if (k > from && mid > cMid) to = maxOf(to, k)
                        if (k < from && mid < cMid) to = minOf(to, k)
                    }
                    if (to != target) {
                        target = to
                        val lp = row.layoutParams as ViewGroup.MarginLayoutParams
                        val step = row.height + lp.topMargin + lp.bottomMargin
                        for (k in 0 until list.childCount) {
                            if (k == from) continue
                            val shift = when (k) {
                                in (from + 1)..to -> -step
                                in to until from -> step
                                else -> 0
                            }
                            list.getChildAt(k).animate().translationY(shift.toFloat()).setDuration(120).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Redrawn on the way out, the old handle is sent a CANCEL of its own: ignored.
                    if (target < 0 || from < 0) return@setOnTouchListener true
                    for (k in 0 until list.childCount) list.getChildAt(k).apply { animate().cancel(); translationY = 0f }
                    row.elevation = 0f
                    val to = target
                    target = -1
                    if (e.actionMasked == MotionEvent.ACTION_UP && to != from) done(from, to)
                    true
                }
                else -> false
            }
        }
    }

    /** Long press: the quick actions without opening the rule. */
    private fun menu(rule: AppRule, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.apply_now).isEnabled = rule.enabled
        m.menu.add(0, 2, 1, R.string.release_now)
        m.menu.add(0, 3, 2, R.string.duplicate)
        m.menu.add(0, 4, 3, R.string.delete)
        if (rule.trigger == Trigger.MANUAL) m.menu.add(0, 5, 4, R.string.scene_shortcut)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> lifecycleScope.launch {
                    AppFreezer.freezeNow(rule)
                    snack(getString(R.string.applied))
                    render()
                }
                2 -> lifecycleScope.launch {
                    val n = runCatching { AppFreezer.releaseNow(rule.id) }.getOrDefault(0)
                    snack(getString(R.string.released, n))
                    render()
                }
                3 -> {
                    RuleStore.save(rule.copy(id = Prefs.newId(), enabled = false, name = (rule.name ?: rule.timeLabel) + " (копия)"))
                    render()
                }
                4 -> MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.delete_confirm, rule.name ?: rule.timeLabel))
                    .setPositiveButton(R.string.delete) { _, _ -> RuleStore.delete(rule.id); render() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                5 -> if (!Scenes.pin(this, rule)) snack(getString(R.string.scene_shortcut_unsupported))
            }
            true
        }
        m.show()
    }

    private fun snack(text: String) = Snackbar.make(ui.root, text, Snackbar.LENGTH_SHORT).show()

    /** Lets the user bring back individual apps before their window ends. */
    private fun chooseToReturn() {
        val keys = AppFreezer.heldApps()
        if (keys.isEmpty()) return
        val labels = AppPickerActivity.labels(keys).toTypedArray()
        val checked = BooleanArray(keys.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_to_return)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.return_selected) { _, _ ->
                lifecycleScope.launch {
                    keys.filterIndexed { i, _ -> checked[i] }.forEach { runCatching { AppFreezer.unfreezeApp(it) } }
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
