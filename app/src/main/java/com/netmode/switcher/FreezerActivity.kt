package com.netmode.switcher

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.netmode.switcher.databinding.ActivityFreezerBinding
import com.netmode.switcher.databinding.CellIceBinding
import com.netmode.switcher.databinding.RowIceBinding
import com.netmode.switcher.databinding.SheetIceAppBinding
import com.netmode.switcher.IceGrid.Look
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The freezer's screen: its apps with their state, a search, "все / заморожены / разморожены"
 * and the groups. The snowflake on a row freezes or thaws the app; the row opens its card.
 */
class FreezerActivity : AppCompatActivity() {

    private enum class Filter(val title: Int) { ALL(R.string.ice_filter_all), FROZEN(R.string.ice_filter_frozen), THAWED(R.string.ice_filter_thawed), FAVORITES(R.string.ice_favorites) }
    private enum class ModeFilter(val title: Int, val mode: IceMode?) {
        ALL(R.string.ice_filter_modes_all, null), SOFT(R.string.ice_mode_soft, IceMode.SOFT), DEEP(R.string.ice_mode_deep, IceMode.DEEP)
    }

    private var look = IceGrid.look

    private lateinit var ui: ActivityFreezerBinding
    private var filter = Filter.ALL
    private var modeFilter = ModeFilter.ALL
    /** The group shown; null: all of them. */
    private var group: String? = null
    private var shown = listOf<IceApp>()
    private var labels = mapOf<String, String>()
    private val icons = LruCache<String, Drawable>(120)

    /** Apps picked for one action together through the app's actions menu. */
    private val selected = linkedSetOf<String>()
    private val selecting get() = selected.isNotEmpty()

    /** "Back" while picking ends the picking, not the screen. */
    private val endSelection = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    /** The toolbar's "up" arrow, put back when picking ends. */
    private var upIcon: Drawable? = null

    private var sheet: BottomSheetDialog? = null
    private var sheetUi: SheetIceAppBinding? = null
    private var sheetKey: String? = null
    /** While a card is painted from code, its listeners stay quiet. */
    private var painting = false
    private var statusJob: Job? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_PACKAGES)
        if (result.resultCode != RESULT_OK || picked == null) return@registerForActivityResult
        val have = Freezer.apps.map { it.key }
        val added = picked.filter { it !in have }
        val removed = have.filter { it !in picked }
        val work = App.scope.async {
            if (removed.isNotEmpty()) Freezer.remove(removed)
            Freezer.add(added)
        }
        lifecycleScope.launch {
            val n = work.await()
            val text = when {
                added.isNotEmpty() && !Privileged.available() -> getString(R.string.ice_added_no_access, n)
                added.isNotEmpty() -> getString(R.string.ice_added_n, n)
                removed.isNotEmpty() -> getString(R.string.ice_removed_n, removed.size)
                else -> null
            }
            text?.let { Snackbar.make(ui.root, it, Snackbar.LENGTH_LONG).setAnchorView(ui.add).show() }
        }
    }

    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_ICE_APPS || key == "ice_groups" || key == "ice_mode" || key == "ice_delay" || key == "ice_sort" || key == "ice_order") runOnUiThread { render() }
        if (key == Prefs.KEY_ICE_UNDO) runOnUiThread { paintToolbar() }
        if (key in IceGrid.preferenceKeys) runOnUiThread { look = IceGrid.look; applyLook() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = Filter.entries.firstOrNull { it.name == savedInstanceState?.getString("filter") } ?: Filter.ALL
        modeFilter = ModeFilter.entries.firstOrNull { it.name == savedInstanceState?.getString("modeFilter") } ?: ModeFilter.ALL
        group = savedInstanceState?.getString("group")
        ui = ActivityFreezerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { if (selecting) clearSelection() else finish() }
        ui.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_freeze_all -> freezeAll()
                R.id.action_settings -> startActivity(Intent(this, FreezerSettingsActivity::class.java))
                R.id.action_folder -> pinFolder(this)
                R.id.action_tile -> addTile(this)
                R.id.action_view -> IceGrid.settings(this)
                R.id.action_order -> chooseOrder()
                R.id.action_undo -> undoLast()
                R.id.action_thaw_for -> chooseThawFor(Freezer.apps.map { it.key })
                R.id.sel_thaw_for -> { val keys = selected.toList(); clearSelection(); chooseThawFor(keys) }
                R.id.sel_favorite -> { Freezer.setFavorite(selected.toList(), true); clearSelection() }
                R.id.sel_unfavorite -> { Freezer.setFavorite(selected.toList(), false); clearSelection() }
                R.id.sel_card -> selected.singleOrNull()?.let { k -> clearSelection(); openSheet(k) }
                R.id.sel_freeze -> bulkFreeze(true)
                R.id.sel_thaw -> bulkFreeze(false)
                R.id.sel_all -> { selected += shown.map { it.key }; paintSelection() }
                R.id.sel_group -> bulkGroup()
                R.id.sel_mode -> bulkMode()
                R.id.sel_delay -> bulkDelay()
                R.id.sel_remove -> bulkRemove()
            }
            true
        }
        ui.groupActions.setOnClickListener { group?.let { groupActions(it) } }
        ui.selAll.setOnClickListener { selected += shown.map { it.key }; paintSelection() }
        ui.selThaw.setOnClickListener { bulkFreeze(false) }
        ui.selFreeze.setOnClickListener { bulkFreeze(true) }
        onBackPressedDispatcher.addCallback(this, endSelection)
        upIcon = ui.toolbar.navigationIcon
        paintToolbar()
        ui.search.doAfterTextChanged { render() }
        ui.list.adapter = adapter
        IceGrid.watchBounds(ui.list)
        applyLook()
        ui.add.setOnClickListener { pick() }
        Filter.entries.forEach { f ->
            ui.filters.addView(chip(ui.filters, getString(f.title)).apply {
                tag = f
                isChecked = f == filter
            })
        }
        ui.filters.setOnCheckedStateChangeListener { g, _ ->
            (g.findViewById<Chip>(g.checkedChipId)?.tag as? Filter)?.let { filter = it; render() }
        }
        ui.modeFilter.setOnClickListener { showModeFilter() }
        ui.groups.setOnCheckedStateChangeListener { g, _ ->
            if (painting) return@setOnCheckedStateChangeListener
            val c = g.findViewById<Chip>(g.checkedChipId)
            group = c?.tag as? String
            render()
        }
        Prefs.listen(prefs)
        render()
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_APP_KEY)?.let { openSheet(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("filter", filter.name)
        outState.putString("modeFilter", modeFilter.name)
        outState.putString("group", group)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Prefs.unlisten(prefs)
        sheet?.dismiss()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                adapter.notifyDataSetChanged()
                sheetKey?.let { key -> Freezer.find(key)?.let { sheetUi?.status?.text = status(it) } }
                ui.toolbar.menu.findItem(R.id.action_undo)?.isEnabled = Freezer.undoAvailable()
            }
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Freezer.sync() }
                Privileged.available()
            }
            ui.noAccess.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        statusJob = null
        super.onStop()
    }

    private fun pick() {
        picker.launch(Intent(this, AppPickerActivity::class.java)
            .putExtra(AppPickerActivity.EXTRA_PLAIN, true)
            .putExtra(AppPickerActivity.EXTRA_PACKAGES, Freezer.apps.map { it.key }.toTypedArray()))
    }

    private fun freezeAll() = perform(Freezer.apps.map { it.key }, freeze = true)

    private fun chip(parent: ViewGroup, text: String): Chip =
        (LayoutInflater.from(this).inflate(R.layout.chip_choice, parent, false) as Chip).apply {
            id = View.generateViewId()
            this.text = text
        }

    private fun showModeFilter() {
        PopupMenu(this, ui.modeFilter, Gravity.END).apply {
            ModeFilter.entries.forEach { f ->
                menu.add(0, f.ordinal, f.ordinal, f.title).isChecked = f == modeFilter
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                modeFilter = ModeFilter.entries[item.itemId]
                render()
                true
            }
            show()
        }
    }

    private fun render() {
        val activeMode = modeFilter != ModeFilter.ALL
        ui.modeFilter.setText(if (activeMode) modeFilter.title else R.string.ice_mode_filter_label)
        ui.modeFilter.contentDescription = getString(R.string.ice_mode_filter_description, getString(modeFilter.title))
        ui.modeFilter.tooltipText = ui.modeFilter.contentDescription
        ui.modeFilter.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(ui.root,
            if (activeMode) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow))
        val all = Freezer.apps
        labels = all.associate { it.key to Freezer.label(it.key) }
        val groups = Freezer.groups()
        if (group != null && group !in groups) group = null
        painting = true
        ui.groups.removeAllViews()
        ui.groupsBox.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        ui.groups.addView(chip(ui.groups, getString(R.string.ice_groups_all)).apply { tag = null; isChecked = group == null })
        groups.forEach { g ->
            ui.groups.addView(chip(ui.groups, g).apply {
                tag = g
                isChecked = g == group
                setOnLongClickListener { groupActions(g); true }
            })
        }
        painting = false
        ui.groupActions.visibility = if (group == null) View.GONE else View.VISIBLE
        ui.groupActions.text = group?.let { getString(R.string.ice_group_n, it, all.count { a -> a.group == it }) }
        val frozen = all.count { it.frozen }
        ui.filters.children().forEach { c ->
            val f = c.tag as Filter
            c.text = getString(f.title) + " · " + when (f) {
                Filter.ALL -> all.size
                Filter.FROZEN -> frozen
                Filter.THAWED -> all.size - frozen
                Filter.FAVORITES -> all.count { it.favorite }
            }
        }
        val q = ui.search.text?.toString()?.trim().orEmpty()
        shown = IceOrder.sort(all.filter { a ->
            when (filter) {
                Filter.ALL -> true
                Filter.FROZEN -> a.frozen
                Filter.THAWED -> !a.frozen
                Filter.FAVORITES -> a.favorite
            } && (modeFilter.mode == null || (a.frozenAs ?: Freezer.modeOf(a)) == modeFilter.mode) &&
                (group == null || a.group == group) &&
                (q.isEmpty() || labels[a.key].orEmpty().contains(q, true) || a.key.contains(q, true))
        }, Prefs.iceSort, Prefs.iceOrder) { labels[it].orEmpty() }
        // Picked apps that are gone from the freezer are no longer picked.
        selected.retainAll(all.map { it.key }.toSet())
        paintToolbar()
        ui.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        ui.empty.setText(if (all.isEmpty()) R.string.ice_empty else R.string.ice_nothing_found)
        adapter.notifyDataSetChanged()
        sheetKey?.let { k -> Freezer.find(k)?.let { paintSheet(it) } ?: sheet?.dismiss() }
    }

    private fun ChipGroup.children(): List<Chip> = (0 until childCount).mapNotNull { getChildAt(it) as? Chip }

    private fun status(a: IceApp): String = IceStatus.text(this, a)

    private fun displayName(a: IceApp, name: String): String =
        if (a.favorite) getString(R.string.ice_favorite_name, name) else name

    private fun statusColor(a: IceApp): Int = MaterialColors.getColor(ui.root,
        if (a.frozen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorTertiary)

    private fun loadIcon(a: IceApp, target: android.widget.ImageView) {
        target.tag = a.key
        val cached = icons.get(a.key)
        if (cached != null) {
            target.setImageDrawable(IceShortcuts.greyed(cached.constantState?.newDrawable()?.mutate() ?: cached, a.frozen))
            return
        }
        target.setImageDrawable(null)
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                runCatching { Freezer.info(packageManager, a.key)?.loadIcon(packageManager) }.getOrNull()
            } ?: return@launch
            icons.put(a.key, d)
            if (target.tag == a.key) target.setImageDrawable(IceShortcuts.greyed(d.constantState?.newDrawable()?.mutate() ?: d, a.frozen))
        }
    }

    private fun applyLook() {
        if (look == Look.GRID) {
            ui.list.setPadding(dp(8), dp(4), dp(8), dp(96))
        } else {
            ui.list.setPadding(0, 0, 0, dp(96))
        }
        IceGrid.apply(ui.list, list = look != Look.GRID)
        // The views of the other look are not reused.
        ui.list.adapter = adapter
        paintToolbar()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (look == Look.GRID) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (look == Look.GRID) return cell(position, convertView, parent)
            val row = convertView?.let { RowIceBinding.bind(it) } ?: RowIceBinding.inflate(layoutInflater, parent, false)
            val a = shown[position]
            val name = labels[a.key] ?: a.key
            row.label.text = displayName(a, name)
            row.status.text = status(a)
            row.status.setTextColor(statusColor(a))
            // Compact: one line, a smaller icon, more apps on screen.
            val compact = look == Look.COMPACT
            IceGrid.style(row, ui.list, compact, selecting)
            row.label.setTextColor(if (compact) statusColor(a) else MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorOnSurface))
            loadIcon(a, row.icon)
            row.open.setOnClickListener { if (selecting) flip(a.key) else openSheet(a.key) }
            row.open.setOnLongClickListener { appActions(a.key); true }
            row.root.setOnClickListener { if (selecting) flip(a.key) else openSheet(a.key) }
            row.root.setOnLongClickListener { appActions(a.key); true }
            row.check.visibility = if (selecting) View.VISIBLE else View.GONE
            row.check.isChecked = a.key in selected
            row.root.setBackgroundColor(if (a.key in selected)
                MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorSecondaryContainer)
            else android.graphics.Color.TRANSPARENT)
            // While picking, a tap picks: the row's own buttons step aside.
            row.toggle.contentDescription = getString(if (a.frozen) R.string.ice_thaw_named else R.string.ice_freeze_named, name)
            val tint = MaterialColors.getColor(row.root,
                if (a.frozen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
            row.toggle.iconTint = android.content.res.ColorStateList.valueOf(tint)
            row.toggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (a.frozen) MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorSecondaryContainer)
                else android.graphics.Color.TRANSPARENT)
            row.toggle.setOnClickListener { toggle(a.key) }
            row.launch.contentDescription = getString(R.string.ice_open_named, name)
            row.launch.setOnClickListener { launch(a.key) }
            return row.root
        }
    }

    /** The icon view: a tap opens the app (or picks), a long press opens its actions. */
    private fun cell(position: Int, convertView: View?, parent: ViewGroup): View {
        val c = convertView?.let { CellIceBinding.bind(it) } ?: CellIceBinding.inflate(layoutInflater, parent, false)
        IceGrid.style(c, ui.list)
        val a = shown[position]
        val name = labels[a.key] ?: a.key
        c.label.text = displayName(a, name)
        loadIcon(a, c.icon)
        c.badge.visibility = if (a.frozen) View.VISIBLE else View.GONE
        c.check.visibility = if (selecting) View.VISIBLE else View.GONE
        c.check.isChecked = a.key in selected
        c.root.setBackgroundColor(if (a.key in selected)
            MaterialColors.getColor(c.root, com.google.android.material.R.attr.colorSecondaryContainer)
        else android.graphics.Color.TRANSPARENT)
        c.root.contentDescription = name + ", " + status(a)
        c.root.setOnClickListener { if (selecting) flip(a.key) else launch(a.key) }
        c.root.setOnLongClickListener { appActions(a.key); true }
        return c.root
    }

    /** Single-app actions in every list look; selection keeps its usual toggle gesture. */
    private fun appActions(key: String) {
        if (selecting) { flip(key); return }
        IceAppMenu.show(this, key,
            open = { launch(key) },
            freeze = { perform(listOf(key), freeze = it) },
            thawFor = { chooseThawFor(listOf(key)) },
            card = { openSheet(key) },
            remove = { askRemove(key) },
            select = { flip(key) })
    }

    /** Thaws (if frozen) and opens the app straight from the list, as its shortcut does. */
    private fun launch(key: String, then: () -> Unit = {}) {
        val work = App.scope.async { Freezer.launch(key, "из морозилки") }
        lifecycleScope.launch {
            if (work.await()) then() else toast(getString(R.string.ice_open_failed, Freezer.label(key)))
        }
    }

    // --- several apps at once ------------------------------------------------------------------

    private fun flip(key: String) {
        if (!selected.remove(key)) selected += key
        paintSelection()
    }

    private fun clearSelection() {
        selected.clear()
        paintSelection()
    }

    private fun paintSelection() {
        paintToolbar()
        adapter.notifyDataSetChanged()
    }

    /** The picking toolbar ("Выбрано: N" and its actions) or the screen's own. */
    private fun paintToolbar() {
        val picking = selecting
        endSelection.isEnabled = picking
        ui.toolbar.menu.clear()
        ui.toolbar.inflateMenu(if (picking) R.menu.freezer_select else R.menu.freezer)
        ui.toolbar.title = if (picking) getString(R.string.ice_selected_n, selected.size) else getString(R.string.ice_title)
        if (picking) ui.toolbar.setNavigationIcon(R.drawable.ic_close) else ui.toolbar.navigationIcon = upIcon
        ui.toolbar.subtitle = if (picking || Freezer.apps.isEmpty()) null else
            getString(R.string.ice_toolbar_summary, Freezer.apps.size, Freezer.apps.count { it.frozen })
        ui.selectionHint.visibility = if (!picking && Freezer.apps.isNotEmpty()) View.VISIBLE else View.GONE
        ui.selectionHint.setText(if (look == Look.GRID) R.string.ice_grid_hint else R.string.ice_selection_tip)
        ui.toolbar.menu.findItem(R.id.sel_card)?.isVisible = selected.size == 1
        ui.toolbar.menu.findItem(R.id.action_undo)?.isEnabled = Freezer.undoAvailable()
        if (picking) ui.add.hide() else ui.add.show()
        ui.selectBar.visibility = if (picking) View.VISIBLE else View.GONE
        // The selection bar is below the list; only the floating Add button needs an inset.
        val bottom = dp(if (picking) 8 else 96)
        if (ui.list.paddingBottom != bottom) {
            ui.list.setPadding(ui.list.paddingLeft, ui.list.paddingTop, ui.list.paddingRight, bottom)
        }
        // Everything shown is picked already: the button would do nothing.
        ui.selAll.isEnabled = shown.any { it.key !in selected }
    }

    private fun bulkFreeze(freeze: Boolean) {
        val keys = selected.toList()
        clearSelection()
        perform(keys, freeze)
    }

    private fun perform(keys: List<String>, freeze: Boolean, minutes: Int? = null) {
        val work = App.scope.async { runCatching { Freezer.manual(keys, freeze, "вручную", minutes) } }
        lifecycleScope.launch {
            val result = work.await().getOrElse { toast(getString(R.string.ice_tile_failed)); return@launch }
            val message = when {
                result.changed < 0 -> getString(R.string.ice_no_access)
                result.requested == 0 -> getString(R.string.ice_no_changes)
                result.changed < result.requested -> getString(R.string.ice_tile_partial, result.changed, result.requested)
                result.freeze -> getString(R.string.ice_frozen_n, result.changed)
                else -> getString(R.string.ice_thawed_n, result.changed)
            }
            render()
            val bar = Snackbar.make(sheetUi?.root ?: ui.root, message, Snackbar.LENGTH_LONG)
            val undoAt = result.undoAt
            if (result.changed > 0 && undoAt != null && Freezer.undoAvailable()) bar.setAction(R.string.ice_undo_short) { undoLast(undoAt) }
            bar.show()
        }
    }

    private fun chooseThawFor(keys: List<String>) {
        val options = listOf(15, 60, 240, -1)
        val labels = options.map { if (it < 0) getString(R.string.ice_until_manual) else Steps.words(it * 60) } + getString(R.string.ice_delay_own)
        MaterialAlertDialogBuilder(this).setTitle(R.string.ice_thaw_for)
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == options.size) askDelay(this) { perform(keys, false, it) }
                else perform(keys, false, options[index])
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun undoLast(expectedAt: Long? = null) {
        if (!Freezer.undoAvailable()) { toast(getString(R.string.ice_undo_expired)); return }
        val work = App.scope.async { runCatching { Freezer.undo(expectedAt) } }
        lifecycleScope.launch {
            val result = work.await().getOrElse { toast(getString(R.string.ice_tile_failed)); return@launch }
            toast(if (result.noAccess) getString(R.string.ice_no_access) else
                getString(R.string.ice_undo_result, result.restored, result.skipped, result.failed))
            render()
            paintToolbar()
        }
    }

    private fun chooseOrder() {
        val options = IceSort.entries
        MaterialAlertDialogBuilder(this).setTitle(R.string.ice_order)
            .setSingleChoiceItems(options.map { getString(it.title) }.toTypedArray(), options.indexOf(Prefs.iceSort)) { d, i ->
                if (options[i] == IceSort.MANUAL && Prefs.iceOrder.isEmpty())
                    Prefs.iceOrder = IceOrder.sort(Freezer.apps, Prefs.iceSort, Prefs.iceOrder, Freezer::label).map { it.key }
                Prefs.iceSort = options[i]
                d.dismiss()
            }.setNeutralButton(R.string.ice_reorder) { _, _ -> IceOrderDialog.show(this) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun groupActions(name: String) {
        val keys = Freezer.apps.filter { it.group == name }.map { it.key }
        val actions = intArrayOf(R.string.ice_freeze_all, R.string.ice_thaw_all, R.string.ice_thaw_for,
            R.string.ice_resume_auto, R.string.delete)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ice_group_n, name, keys.size))
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, index ->
                when (index) {
                    0 -> perform(keys, true)
                    1 -> perform(keys, false)
                    2 -> chooseThawFor(keys)
                    3 -> App.scope.launch { Freezer.resumeAuto(keys) }
                    4 -> askRemoveGroup(name)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun bulkGroup() {
        val keys = selected.toList()
        val groups = Freezer.groups()
        val items = listOf(getString(R.string.ice_no_group)) + groups + getString(R.string.ice_group_new)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ice_selected_n, keys.size))
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> Freezer.setGroups(keys, null)
                    items.lastIndex -> askGroupName { name -> Freezer.addGroup(name); Freezer.setGroups(keys, name) }
                    else -> Freezer.setGroups(keys, groups[i - 1])
                }
                clearSelection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bulkMode() {
        val keys = selected.toList()
        val defName = getString(if (Prefs.iceDefaultMode == IceMode.DEEP) R.string.ice_mode_deep else R.string.ice_mode_soft).lowercase()
        val items = listOf(getString(R.string.ice_mode_default_named, defName), getString(R.string.ice_mode_soft), getString(R.string.ice_mode_deep))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ice_sec_mode)
            .setItems(items.toTypedArray()) { _, i ->
                App.scope.launch { Freezer.setModes(keys, IceMode.entries[i]) }
                clearSelection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bulkDelay() {
        val keys = selected.toList()
        val options = listOf<Int?>(null) + Freezer.DELAYS
        val items = options.map { d ->
            if (d == null) getString(R.string.ice_delay_default, delayLabel(this, Prefs.iceDefaultDelay)) else delayLabel(this, d)
        } + getString(R.string.ice_delay_own)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ice_sec_delay)
            .setItems(items.toTypedArray()) { _, i ->
                if (i == options.size) askDelay(this) { Freezer.setDelays(keys, it) } else Freezer.setDelays(keys, options[i])
                clearSelection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bulkRemove() {
        val keys = selected.toList()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ice_remove)
            .setMessage(getString(R.string.ice_remove_n_confirm, keys.size))
            .setPositiveButton(R.string.ice_remove) { _, _ ->
                clearSelection()
                App.scope.launch { Freezer.remove(keys) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggle(key: String) {
        val a = Freezer.find(key) ?: return
        perform(listOf(key), freeze = !a.frozen)
    }

    private fun askRemove(key: String, then: () -> Unit = {}) {
        MaterialAlertDialogBuilder(this)
            .setTitle(Freezer.label(key))
            .setMessage(R.string.ice_remove_confirm)
            .setPositiveButton(R.string.ice_remove) { _, _ ->
                then()
                App.scope.launch { Freezer.remove(listOf(key)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- an app's card ------------------------------------------------------------------------

    private fun openSheet(key: String) {
        val a = Freezer.find(key) ?: return
        sheet?.dismiss()
        val s = SheetIceAppBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this, R.style.NMS_IceSheetTheme)
        dialog.setContentView(s.root)
        dialog.setOnDismissListener { if (sheet === dialog) { sheet = null; sheetUi = null; sheetKey = null } }
        sheet = dialog
        sheetUi = s
        sheetKey = key

        s.launch.setOnClickListener { launch(key) { dialog.dismiss() } }
        s.pin.setOnClickListener {
            if (!IceShortcuts.pin(this, key)) toast(getString(R.string.ice_pin_failed))
        }
        s.toggle.setOnClickListener { toggle(key) }
        s.thawFor.setOnClickListener { chooseThawFor(listOf(key)) }
        s.thawInfo.setOnClickListener { sheetHelp(R.string.ice_thaw_for, getString(R.string.ice_thaw_for_hint)) }
        s.modeInfo.setOnClickListener {
            sheetHelp(R.string.ice_sec_mode, getString(R.string.ice_mode_soft) + "\n" +
                getString(R.string.ice_mode_soft_hint) + "\n\n" + getString(R.string.ice_mode_deep) + "\n" +
                getString(R.string.ice_mode_deep_hint))
        }
        s.groupInfo.setOnClickListener { sheetHelp(R.string.ice_sec_group, getString(R.string.ice_group_hint)) }
        s.resumeAuto.setOnClickListener { App.scope.launch { Freezer.resumeAuto(listOf(key)) } }
        s.favorite.setOnClickListener { Freezer.find(key)?.let { Freezer.setFavorite(listOf(key), !it.favorite) } }
        s.remove.setOnClickListener { askRemove(key) { dialog.dismiss() } }
        paintSheet(a)
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun sheetHelp(title: Int, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun paintSheet(a: IceApp) {
        val s = sheetUi ?: return
        painting = true
        val name = labels[a.key] ?: Freezer.label(a.key)
        s.label.text = name
        s.pkg.text = if (AppKey.isClone(a.key)) AppKey.pkg(a.key) + " · " + getString(R.string.clone_user, AppKey.user(a.key)) else a.key
        s.status.text = status(a)
        s.status.setTextColor(statusColor(a))
        loadIcon(a, s.icon)
        s.pin.setText(if (IceShortcuts.isPinned(this, a.key)) R.string.ice_pinned else R.string.ice_pin)
        s.toggle.setText(if (a.frozen) R.string.ice_thaw else R.string.ice_freeze)
        s.resumeAuto.visibility = if (!a.frozen && a.keepUntil != 0L) View.VISIBLE else View.GONE
        s.favorite.setIconResource(if (a.favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        s.favorite.contentDescription = getString(if (a.favorite) R.string.ice_favorite_remove else R.string.ice_favorite_add)
        s.favorite.tooltipText = s.favorite.contentDescription
        val defName = getString(if (Prefs.iceDefaultMode == IceMode.DEEP) R.string.ice_mode_deep else R.string.ice_mode_soft).lowercase()
        s.modes.setOnCheckedStateChangeListener(null)
        s.modes.removeAllViews()
        IceMode.entries.forEach { m ->
            val text = when (m) {
                IceMode.DEFAULT -> getString(R.string.ice_choice_default)
                IceMode.SOFT -> getString(R.string.ice_mode_soft)
                IceMode.DEEP -> getString(R.string.ice_mode_deep)
            }
            s.modes.addView(sheetChip(s.modes, text).apply {
                tag = m
                isChecked = a.mode == m
                if (m == IceMode.DEFAULT) contentDescription = getString(R.string.ice_default_mode_accessibility, defName)
            })
        }
        s.modes.setOnCheckedStateChangeListener { g, _ ->
            if (painting) return@setOnCheckedStateChangeListener
            val m = g.findViewById<Chip>(g.checkedChipId)?.tag as? IceMode ?: return@setOnCheckedStateChangeListener
            App.scope.launch { Freezer.setMode(a.key, m) }
        }
        s.modeHint.text = (if (a.mode == IceMode.DEFAULT) getString(R.string.ice_mode_now, defName) + " " else "") +
            getString(if (Freezer.modeOf(a) == IceMode.DEEP) R.string.ice_mode_deep_summary else R.string.ice_mode_soft_summary)

        s.delays.setOnCheckedStateChangeListener(null)
        s.delays.removeAllViews()
        // A delay of its own, set by hand, shows as a chip of its own between the presets and "Своё…".
        val own = a.delay?.takeIf { it !in Freezer.DELAYS }
        val options = listOf<Int?>(null) + Freezer.DELAYS + listOfNotNull(own)
        options.forEach { d ->
            s.delays.addView(sheetChip(s.delays, if (d == null) getString(R.string.ice_choice_default) else delayLabel(this, d)).apply {
                tag = d ?: DEFAULT_TAG
                isChecked = a.delay == d
                if (d == null) contentDescription = getString(R.string.ice_default_delay_accessibility, delayLabel(this@FreezerActivity, Prefs.iceDefaultDelay))
            })
        }
        s.delays.addView(ownDelayChip(s.delays) { minutes -> Freezer.setDelay(a.key, minutes) })
        s.delayHint.text = getString(R.string.ice_delay_inherited, delayLabel(this, Prefs.iceDefaultDelay))
        s.delayHint.visibility = if (a.delay == null) View.VISIBLE else View.GONE
        s.delays.setOnCheckedStateChangeListener { g, _ ->
            if (painting) return@setOnCheckedStateChangeListener
            val t = g.findViewById<Chip>(g.checkedChipId)?.tag ?: return@setOnCheckedStateChangeListener
            Freezer.setDelay(a.key, (t as? Int)?.takeIf { it != DEFAULT_TAG })
        }

        s.groups.setOnCheckedStateChangeListener(null)
        s.groups.removeAllViews()
        Freezer.groups().forEach { g ->
            s.groups.addView(chip(s.groups, g).apply { tag = g; isChecked = a.group == g })
        }
        s.groups.addView(chip(s.groups, getString(R.string.ice_group_new)).apply {
            isCheckable = false
            setChipIconResource(R.drawable.ic_add)
            isChipIconVisible = true
            setOnClickListener { askGroup(a.key) }
        })
        s.groups.setOnCheckedStateChangeListener { g, _ ->
            if (painting) return@setOnCheckedStateChangeListener
            Freezer.setGroup(a.key, g.findViewById<Chip>(g.checkedChipId)?.tag as? String)
        }
        painting = false
    }

    private fun sheetChip(parent: ChipGroup, text: String): Chip = chip(parent, text).apply {
        textSize = 13f
        chipStartPadding = dp(8).toFloat()
        chipEndPadding = dp(8).toFloat()
        textStartPadding = dp(6).toFloat()
        textEndPadding = dp(6).toFloat()
    }

    private fun ownDelayChip(parent: ChipGroup, done: (Int) -> Unit): Chip = chip(parent, getString(R.string.ice_delay_own)).apply {
        isCheckable = false
        setOnClickListener { askDelay(this@FreezerActivity, done) }
    }

    private fun askGroup(key: String) = askGroupName { name ->
        Freezer.addGroup(name)
        Freezer.setGroup(key, name)
    }

    private fun askGroupName(done: (String) -> Unit) {
        val input = TextInputEditText(this).apply { setSingleLine() }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ice_group_new)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) done(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRemoveGroup(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ice_group_remove_title, name))
            .setMessage(R.string.ice_group_remove_text)
            .setPositiveButton(R.string.delete) { _, _ -> Freezer.removeGroup(name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Open the chosen app's card from the folder without searching for it again. */
        const val EXTRA_APP_KEY = "ice_app_card"

        /** The "as in the settings" delay chip; a delay is never this. */
        private const val DEFAULT_TAG = -100

        fun pinFolder(ctx: android.content.Context) {
            if (!IceShortcuts.pinFolder(ctx)) Toast.makeText(ctx, R.string.ice_pin_failed, Toast.LENGTH_SHORT).show()
        }

        /** "Сразу", "Никогда", "5 мин", "1 ч 30 мин". */
        fun delayLabel(ctx: android.content.Context, d: Int): String = when (d) {
            Freezer.NEVER -> ctx.getString(R.string.ice_delay_never)
            0 -> ctx.getString(R.string.ice_delay_now)
            else -> Steps.words(d * 60)
        }

        /** A delay of one's own, hours and minutes, up to a day; [done] gets the minutes. */
        fun askDelay(ctx: android.content.Context, done: (Int) -> Unit) {
            val dp = ctx.resources.displayMetrics.density
            fun picker(max: Int) = android.widget.NumberPicker(ctx).apply {
                minValue = 0
                maxValue = max
                setFormatter { "%02d".format(it) }
            }
            val h = picker(23)
            val m = picker(59).apply { value = 10 }
            val box = android.widget.LinearLayout(ctx).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }
            for ((p, unit) in listOf(h to R.string.pause_h, m to R.string.pause_m)) {
                box.addView(p)
                box.addView(android.widget.TextView(ctx, null, 0, R.style.NMS_RowLabel).apply {
                    setText(unit)
                    setPadding((6 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                })
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ice_delay_own_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val total = h.value * 60 + m.value
                    if (total == 0) Toast.makeText(ctx, R.string.ice_delay_zero, Toast.LENGTH_SHORT).show() else done(total)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        /** The system's "add the tile" dialog from Android 13; before, how to add it by hand. */
        fun addTile(ctx: android.content.Context) {
            if (Build.VERSION.SDK_INT < 33) return Compat.tilesByHand(ctx)
            ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                FreezerTileService.component(ctx), ctx.getString(R.string.ice_title),
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_ice), ctx.mainExecutor,
            ) { result ->
                if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    Toast.makeText(ctx, ctx.getString(R.string.tile_already, ctx.getString(R.string.ice_title)), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
