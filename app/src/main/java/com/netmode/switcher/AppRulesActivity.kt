package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.netmode.switcher.databinding.ActivityAppRulesBinding
import com.netmode.switcher.databinding.RowRuleBinding
import kotlinx.coroutines.launch

/** The list of rules. Editing happens in [RuleEditActivity]. */
class AppRulesActivity : AppCompatActivity() {

    private lateinit var ui: ActivityAppRulesBinding
    private val selected = linkedSetOf<Long>()
    private var selecting = false
    private var upIcon: Drawable? = null

    /** The group chosen above the list; null shows every rule. */
    private var groupFilter: String? = null

    private val importRule = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val text = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
            }
            text.onSuccess { offerRule(it) }.onFailure { snack(getString(R.string.rule_import_failed, it.message)) }
        }
    }
    private val endSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityAppRulesBinding.inflate(layoutInflater)
        setContentView(ui.root)
        selecting = savedInstanceState?.getBoolean("selecting") ?: false
        savedInstanceState?.getLongArray("selected")?.let { selected.addAll(it.toList()) }
        groupFilter = savedInstanceState?.getString("group")
        upIcon = ui.toolbar.navigationIcon
        ui.toolbar.setNavigationOnClickListener { if (selecting) clearSelection() else finish() }
        onBackPressedDispatcher.addCallback(this, endSelection)
        ui.selectAll.setOnClickListener {
            if (ui.selectAll.isChecked) selected.addAll(sorted().map { it.id }) else selected.clear()
            paintSelection()
        }
        ui.deleteSelected.setOnClickListener { confirmDelete(selected.toList()) }
        ui.held.setOnClickListener { chooseToReturn() }
        ui.addApps.setOnClickListener { startActivity(Intent(this, RuleEditActivity::class.java)) }
        ui.fromTemplate.setOnClickListener { fromTemplate() }
        renderTemplates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selecting", selecting)
        outState.putLongArray("selected", selected.toLongArray())
        outState.putString("group", groupFilter)
        super.onSaveInstanceState(outState)
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
        renderGroups()
        val rules = sorted()
        val entries = Prefs.events.map(Journal::parse)
        val now = java.time.ZonedDateTime.now()
        val ids = rules.map { it.id }
        selected.retainAll(ids.toSet())
        if (rules.isEmpty()) selecting = false
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
            val icons = RuleCard.actionIcons(rule)
            row.actionIcons.removeAllViews()
            row.actionIcons.visibility = if (icons.isEmpty()) View.GONE else View.VISIBLE
            val iconSize = (18 * resources.displayMetrics.density).toInt()
            icons.take(MAX_ACTION_ICONS).forEach { res ->
                row.actionIcons.addView(android.widget.ImageView(this).apply {
                    setImageResource(res)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(row.doLine, com.google.android.material.R.attr.colorOnSurfaceVariant))
                }, android.widget.LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconSize / 3 })
            }
            row.doLine.text = "→ " + listOfNotNull(
                rule.actionLabel.ifEmpty { null },
                names.takeIf { it.isNotEmpty() }?.let { it.take(4).joinToString(", ") + if (it.size > 4) "…" else "" },
            ).joinToString("\n")
            row.doLine.visibility = if (rule.actionLabel.isEmpty() && names.isEmpty()) View.GONE else View.VISIBLE
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
            paintNext(row, rule, active, entries, now)
            paintRun(row, rule, active || waiting || held > 0)
            row.root.tag = rule.id
            row.root.alpha = if (rule.enabled || selecting) 1f else 0.55f
            row.enabled.isChecked = rule.enabled
            row.enabled.visibility = if (selecting) View.GONE else View.VISIBLE
            row.enabled.setOnCheckedChangeListener { _, on ->
                RuleStore.save(rule.copy(enabled = on))
                render()
            }
            row.check.contentDescription = getString(R.string.rules_select_named, row.title.text)
            row.check.setOnClickListener { flip(rule.id) }
            row.root.setOnClickListener { if (selecting) flip(rule.id) else edit(rule) }
            row.root.setOnLongClickListener {
                if (selecting) flip(rule.id) else menu(rule, it)
                true
            }
            // Dragged within a group, the rules out of sight would lose their place.
            row.drag.visibility = if (!selecting && rules.size > 1 && groupFilter == null) View.VISIBLE else View.GONE
            if (!selecting) dragBy(row.drag, row.root) { from, to -> moved(ids, from, to) }
            ui.appList.addView(row.root)
        }
        ui.appEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        if (rules.isEmpty()) ui.scroll.post { ui.scroll.scrollTo(0, 0) }
        val heldApps = AppFreezer.heldApps()
        ui.held.visibility = if (heldApps.isEmpty()) View.GONE else View.VISIBLE
        ui.held.text = getString(R.string.held_block, heldApps.size)
        paintSelection()
    }

    /** When it runs next, and how it went last time: the two things worth seeing at a glance. */
    private fun paintNext(row: RowRuleBinding, rule: AppRule, active: Boolean, entries: List<JournalEntry>, now: java.time.ZonedDateTime) {
        val last = RuleCard.lastOf(entries, RuleOrder.title(rule))
        val next = if (rule.enabled && rule.isTime && !active) RuleCard.nextLabel(rule.nextStart(now), now) else null
        val problem = last?.problem == true
        row.nextLine.text = listOfNotNull(
            next?.let { getString(R.string.rule_next, it) },
            last?.let { getString(if (problem) R.string.rule_last_problem else R.string.rule_last, it.time) },
        ).joinToString("\n")
        row.nextLine.visibility = if (row.nextLine.text.isEmpty()) View.GONE else View.VISIBLE
        row.nextLine.setTextColor(com.google.android.material.color.MaterialColors.getColor(row.nextLine,
            if (problem) android.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** One tap runs a rule or takes it back; the snackbar's button undoes a mistaken tap. */
    private fun paintRun(row: RowRuleBinding, rule: AppRule, running: Boolean) {
        val title = RuleOrder.title(rule)
        row.run.visibility = if (!selecting && (rule.enabled || running)) View.VISIBLE else View.GONE
        row.run.setImageResource(if (running) R.drawable.ic_close else R.drawable.ic_t_manual)
        row.run.contentDescription = getString(if (running) R.string.rule_stop else R.string.rule_run, title)
        row.run.setOnClickListener { button ->
            button.isEnabled = false
            lifecycleScope.launch {
                if (running) {
                    val n = runCatching { AppFreezer.releaseNow(rule.id) }.getOrDefault(0)
                    snack(getString(R.string.released, n))
                } else {
                    AppFreezer.freezeNow(rule)
                    Snackbar.make(ui.root, R.string.applied, Snackbar.LENGTH_LONG).setAction(R.string.rule_undo) {
                        lifecycleScope.launch { runCatching { AppFreezer.releaseNow(rule.id) }; render() }
                    }.show()
                }
                render()
            }
        }
    }

    private fun renderGroups() {
        val groups = RuleCard.groups(Prefs.appRules.map { it.group })
        if (groupFilter != null && groups.none { it.equals(groupFilter, ignoreCase = true) }) groupFilter = null
        ui.groupScroll.visibility = if (groups.isEmpty() || selecting) View.GONE else View.VISIBLE
        ui.groupFilters.removeAllViews()
        if (groups.isEmpty()) return
        (listOf(null) + groups).forEach { group ->
            val chip = layoutInflater.inflate(R.layout.chip_choice, ui.groupFilters, false) as com.google.android.material.chip.Chip
            chip.text = group ?: getString(R.string.rules_group_all)
            chip.id = View.generateViewId()
            ui.groupFilters.addView(chip)
            chip.isChecked = group.equals(groupFilter, ignoreCase = true)
            chip.setOnClickListener { groupFilter = group; render() }
        }
    }

    private fun startSelection(id: Long? = null) {
        selecting = true
        id?.let { selected.add(it) }
        render()
    }

    private fun clearSelection() {
        selecting = false
        selected.clear()
        render()
    }

    private fun flip(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        paintSelection()
    }

    /** Update checks in place so picking a row does not move the scroll or accessibility focus. */
    private fun paintSelection() {
        val count = ui.appList.childCount
        endSelection.isEnabled = selecting
        ui.toolbar.title = if (selecting) getString(R.string.rules_selected_n, selected.size) else getString(R.string.section_apps)
        if (selecting) ui.toolbar.setNavigationIcon(R.drawable.ic_close) else ui.toolbar.navigationIcon = upIcon
        ui.toolbar.setNavigationContentDescription(if (selecting) R.string.rules_cancel_selection else androidx.appcompat.R.string.abc_action_bar_up_description)
        ui.toolbar.menu.clear()
        if (!selecting) {
            ui.toolbar.menu.add(R.string.rules_select).apply {
                isEnabled = count > 0
                setOnMenuItemClickListener { startSelection(); true }
            }
            ui.toolbar.menu.add(R.string.sort_title).setOnMenuItemClickListener { chooseSort(); true }
            ui.toolbar.menu.add(R.string.rule_import).setOnMenuItemClickListener {
                importRule.launch(arrayOf("application/json", "text/plain", "*/*")); true
            }
            ui.toolbar.menu.add(R.string.rule_import_paste).setOnMenuItemClickListener {
                val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
                offerRule(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty())
                true
            }
            ui.toolbar.menu.add(R.string.rules_delete_all).apply {
                isEnabled = count > 0
                setOnMenuItemClickListener { confirmDelete(Prefs.appRules.map { it.id }, all = true); true }
            }
        }
        ui.selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        ui.addApps.visibility = if (selecting) View.GONE else View.VISIBLE
        ui.fromTemplate.visibility = if (selecting) View.GONE else View.VISIBLE
        ui.intro.setText(if (selecting) R.string.rules_selection_hint else R.string.apps_intro)
        // The hint is for finding the long press: once there are rules each card shows its actions.
        ui.intro.visibility = if (selecting || count == 0) View.VISIBLE else View.GONE
        ui.deleteSelected.isEnabled = selected.isNotEmpty()
        ui.selectAll.checkedState = when {
            selected.isEmpty() -> MaterialCheckBox.STATE_UNCHECKED
            selected.size == count -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
        for (i in 0 until count) {
            val row = RowRuleBinding.bind(ui.appList.getChildAt(i))
            val checked = (row.root.tag as Long) in selected
            row.check.visibility = if (selecting) View.VISIBLE else View.GONE
            row.check.isChecked = checked
            row.root.strokeWidth = if (checked) (2 * resources.displayMetrics.density).toInt() else 0
        }
    }

    /** Capture the IDs before opening the dialog; confirmation only removes that set. */
    private fun confirmDelete(ids: List<Long>, all: Boolean = false) {
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (all) R.string.rules_delete_all else R.string.rules_delete_selected)
            .setMessage(getString(if (all) R.string.rules_delete_all_confirm else R.string.rules_delete_selected_confirm, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                RuleStore.delete(ids)
                clearSelection()
                snack(getString(R.string.rules_deleted_n, ids.size))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            .filter { RuleCard.inGroup(it.group, groupFilter) }
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
        m.menu.add(0, 6, 0, R.string.rules_select)
        m.menu.add(0, 1, 0, R.string.apply_now).isEnabled = rule.enabled
        m.menu.add(0, 2, 1, R.string.release_now)
        m.menu.add(0, 3, 2, R.string.duplicate)
        m.menu.add(0, 4, 3, R.string.delete)
        m.menu.add(0, 7, 5, R.string.rule_share)
        if (rule.trigger == Trigger.MANUAL) m.menu.add(0, 5, 4, R.string.scene_shortcut)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                6 -> startSelection(rule.id)
                7 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, RuleShare.encode(rule)), getString(R.string.rule_share)))
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

    /** Nothing from outside runs unseen: its commands are shown and the rule arrives switched off. */
    private fun offerRule(text: String) {
        val rule = runCatching { RuleShare.decode(text) }.getOrElse {
            snack(getString(R.string.rule_import_failed, it.message))
            return
        }
        val lines = listOf(rule.timeLabel, rule.actionLabel).filter { it.isNotEmpty() } + RuleShare.risks(rule) +
            getString(R.string.rule_import_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rule_import_title, RuleOrder.title(rule)))
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.rule_import_add) { _, _ ->
                RuleStore.save(rule.copy(id = Prefs.newId(), enabled = false))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private companion object {
        const val MAX_ACTION_ICONS = 8
    }
}
