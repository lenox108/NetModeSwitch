package com.netmode.switcher

import android.view.LayoutInflater
import android.view.View
import android.graphics.Rect
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.IceGroupsBinding
import kotlinx.coroutines.launch

/** The same group picker and explicit actions in the manager and the launcher folder. */
class IceGroupControls(
    private val activity: AppCompatActivity,
    private val ui: IceGroupsBinding,
    var selection: IceGroupSelection,
    private val changed: () -> Unit,
    private val perform: (List<String>, Boolean) -> Unit,
    private val thawFor: (List<String>) -> Unit,
) {
    private var painting = false
    private var chipLabels = emptyList<Pair<IceGroupSelection, String>>()
    // Binder calls: resolved once for this screen, off the "all groups" path. Freezer still re-checks.
    private val guarded by lazy { AppFreezer.protectedPackages() }

    init {
        ui.groups.setOnCheckedStateChangeListener { group, _ ->
            if (!painting) {
                selection = group.findViewById<Chip>(group.checkedChipId)?.tag as? IceGroupSelection ?: IceGroupSelection()
                changed()
            }
        }
        ui.groupFreeze.setOnClickListener { perform(thawed(selection), true) }
        ui.groupThaw.setOnClickListener { perform(keys(selection), false) }
        ui.groupMore.setOnClickListener { actions(selection) }
    }

    fun render(all: List<IceApp>) {
        val groups = Freezer.groups()
        if (selection.name != null && selection.name !in groups) selection = IceGroupSelection()
        ui.root.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        val options = listOf(IceGroupSelection() to activity.getString(R.string.ice_groups_all)) +
            groups.map { name -> IceGroupSelection(name) to activity.getString(R.string.ice_group_chip, name, all.count { it.group == name }) } +
            (IceGroupSelection(ungrouped = true) to activity.getString(R.string.ice_group_chip,
                activity.getString(R.string.ice_no_group), all.count { it.group == null }))
        painting = true
        if (options != chipLabels) {
            val x = ui.groupsScroll.scrollX
            ui.groups.removeAllViews()
            options.forEach { (choice, label) ->
                ui.groups.addView((LayoutInflater.from(activity).inflate(R.layout.chip_choice, ui.groups, false) as Chip).apply {
                    id = View.generateViewId()
                    text = label
                    tag = choice
                    if (!choice.isAll) setOnLongClickListener { actions(choice); true }
                })
            }
            chipLabels = options
            ui.groupsScroll.post {
                val selected = (0 until ui.groups.childCount).map { ui.groups.getChildAt(it) }
                    .firstOrNull { it.tag == selection }
                if (selection.isAll || selected == null) ui.groupsScroll.scrollTo(x, 0)
                else selected.requestRectangleOnScreen(Rect(0, 0, selected.width, selected.height), true)
            }
        }
        for (i in 0 until ui.groups.childCount) {
            val chip = ui.groups.getChildAt(i) as Chip
            chip.isChecked = chip.tag == selection
        }
        painting = false
        val members = selection.apps(all)
        ui.actions.visibility = if (selection.isAll) View.GONE else View.VISIBLE
        ui.summary.text = activity.getString(R.string.ice_group_summary, title(selection), members.count { it.frozen }, members.size)
        ui.summary.contentDescription = "${ui.summary.text}. ${activity.getString(R.string.ice_group_actions_hint)}"
        val thawed = if (selection.isAll) 0 else IceGroups.thawedKeys(selection, all, guarded).size
        ui.groupFreeze.isEnabled = thawed > 0
        // NMS.IceAction.Primary has a flat tint, so a disabled button would otherwise look active.
        ui.groupFreeze.alpha = if (thawed > 0) 1f else 0.38f
        ui.groupFreeze.text = if (thawed > 0) activity.getString(R.string.ice_group_freeze_thawed_n, thawed) else activity.getString(R.string.ice_freeze)
        ui.groupThaw.isEnabled = members.any { it.frozen }
        ui.groupFreeze.contentDescription = if (thawed > 0) activity.getString(R.string.ice_group_freeze_thawed_named, title(selection), thawed)
            else activity.getString(R.string.ice_group_freeze_named, title(selection))
        ui.groupFreeze.tooltipText = ui.groupFreeze.contentDescription
        ui.groupThaw.contentDescription = activity.getString(R.string.ice_group_thaw_named, title(selection))
    }

    private fun title(choice: IceGroupSelection): String = choice.name ?: activity.getString(R.string.ice_no_group)
    private fun keys(choice: IceGroupSelection): List<String> = choice.apps(Freezer.apps).map { it.key }
    private fun thawed(choice: IceGroupSelection): List<String> = IceGroups.thawedKeys(choice, Freezer.apps, guarded)

    private fun actions(choice: IceGroupSelection) {
        val options = mutableListOf(
            R.string.ice_thaw_all to { perform(keys(choice), false) },
            R.string.ice_thaw_for to { thawFor(keys(choice)) },
            R.string.ice_resume_auto to { App.scope.launch { Freezer.resumeAuto(keys(choice)) }; Unit },
            R.string.ice_group_order to { IceGroupOrderDialog.show(activity) },
        )
        if (thawed(choice).isNotEmpty()) options.add(0, R.string.ice_group_freeze_thawed to { perform(thawed(choice), true) })
        choice.name?.let { name ->
            options += R.string.ice_group_pin to { pin(name) }
            options += R.string.delete to {
                MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.ice_group_remove_title, name))
                    .setMessage(R.string.ice_group_remove_text)
                    .setPositiveButton(R.string.delete) { _, _ -> Freezer.removeGroup(name) }
                    .setNegativeButton(R.string.cancel, null).show()
                Unit
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.ice_group_n, title(choice), keys(choice).size))
            .setItems(options.map { activity.getString(it.first) }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun pin(name: String) {
        val actions = IceGroupShortcutAction.entries
        val labels = intArrayOf(R.string.ice_group_shortcut_open, R.string.ice_group_shortcut_freeze, R.string.ice_group_shortcut_thaw)
        MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.ice_group_pin_title, name))
            .setItems(labels.map { activity.getString(it) }.toTypedArray()) { _, i ->
                if (!IceShortcuts.pinGroup(activity, name, actions[i]))
                    Toast.makeText(activity, R.string.ice_pin_failed, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
