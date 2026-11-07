package com.netmode.switcher

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityIceFolderBinding
import com.netmode.switcher.databinding.CellIceBinding
import com.netmode.switcher.databinding.RowIceBinding
import com.netmode.switcher.IceGrid.Look
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A regular task: the freezer stays available while its apps are on screen. */
class IceFolderActivity : AppCompatActivity() {
    private lateinit var ui: ActivityIceFolderBinding
    private var renderFolder: (() -> Unit)? = null
    /** Swaps the rows for another look: views of the old look are not reused. */
    private var applyLook: (() -> Unit)? = null
    private var folderGroups: IceGroupControls? = null
    /** The order fixed at opening: a launch or a thaw must not move the icons under the finger. */
    private var settledOrder: List<String>? = null
    /** Changes wait for the list to stop: rebinding it would interrupt the scroll. */
    private var scrolling = false
    private var renderPending = false
    private val folderPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ice_folder_sort" || key == "ice_sort" || key == "ice_order") runOnUiThread { settledOrder = null }
        if (key == "ice_folder_view") runOnUiThread { applyLook?.invoke() }
        if (key == Prefs.KEY_ICE_APPS || key == "ice_groups" || key == "ice_folder_sort" || key == "ice_sort" || key == "ice_order" || key in IceGrid.preferenceKeys) {
            runOnUiThread { renderFolder?.invoke() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(IceShortcuts.EXTRA_GROUP)
        if (name != null && name !in Freezer.groups()) {
            toast(getString(R.string.ice_group_gone))
            finish()
            return
        }
        folder(savedInstanceState, name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val name = intent.getStringExtra(IceShortcuts.EXTRA_GROUP)
        if (name != null) {
            if (name !in Freezer.groups()) {
                toast(getString(R.string.ice_group_gone))
                return
            }
            val selection = IceGroupSelection(name)
            if (folderGroups?.selection != selection) {
                folderGroups?.selection = selection
                ui.search.text?.clear()
                ui.grid.setSelection(0)
            }
        }
        settledOrder = null
        renderFolder?.invoke()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        folderGroups?.selection?.let {
            outState.putString("folder_group", it.name)
            outState.putBoolean("folder_ungrouped", it.ungrouped)
        }
        settledOrder?.let { outState.putStringArray("folder_order", it.toTypedArray()) }
        super.onSaveInstanceState(outState)
    }

    /**
     * Back from an app opened here (or from anywhere else): nothing is under the finger now,
     * so the chosen sort applies again — "Последние открытые" puts that app first at once.
     * Not onStart: after a rotation the restored order must stay.
     */
    override fun onRestart() {
        super.onRestart()
        settledOrder = null
    }

    override fun onResume() {
        super.onResume()
        renderFolder?.invoke()
        if (renderFolder != null) lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { Freezer.sync() } }
            renderFolder?.invoke()
        }
    }

    override fun onPause() {
        scrolling = false
        super.onPause()
    }

    override fun onDestroy() {
        Prefs.unlisten(folderPrefs)
        renderFolder = null
        applyLook = null
        folderGroups = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    /** Launching an app must leave this activity and its search/group/scroll state intact. */
    private fun open(key: String) {
        if (Freezer.find(key) == null) {
            toast(getString(R.string.ice_gone))
            renderFolder?.invoke()
            return
        }
        val work = App.scope.async { runCatching { Freezer.launch(key, "из папки морозилки") } }
        lifecycleScope.launch {
            if (!work.await().getOrDefault(false)) toast(getString(R.string.ice_open_failed, Freezer.label(key)))
            renderFolder?.invoke()
        }
    }

    /**
     * The "Морозилка" folder: group chips, search, and explicit actions. Frozen apps have
     * snowflakes; every app keeps its original icon colours.
     * A tap opens the app, thawing it first; a long press opens its actions in this folder.
     */
    private fun folder(savedInstanceState: Bundle?, initialGroup: String? = null) {
        if (Freezer.apps.isEmpty() && Freezer.groups().isEmpty()) {
            toast(getString(R.string.ice_empty_short))
            startActivity(Intent(this, FreezerActivity::class.java))
            finish()
            return
        }
        ui = ActivityIceFolderBinding.inflate(layoutInflater)
        setContentView(ui.root)
        val selection = if (savedInstanceState != null)
            IceGroupSelection(savedInstanceState.getString("folder_group"), savedInstanceState.getBoolean("folder_ungrouped"))
        else IceGroupSelection(initialGroup)
        val groups = IceGroupControls(this, ui.groupControls, selection,
            changed = { renderFolder?.invoke() },
            perform = { keys, freeze -> folderPerform(keys, freeze) }, thawFor = ::folderThawFor)
        folderGroups = groups
        settledOrder = savedInstanceState?.getStringArray("folder_order")?.toList()
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.toolbar.inflateMenu(R.menu.ice_folder)
        ui.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_view -> Prefs.iceFolderView = when (IceGrid.folderLook) {
                    Look.LIST -> Look.COMPACT
                    Look.COMPACT -> Look.GRID
                    Look.GRID -> Look.LIST
                }.pref
                else -> IceOrderDialog.choose(this, Prefs.iceFolderSort) { order -> Prefs.iceFolderSort = order }
            }
            true
        }
        ui.manage.setOnClickListener {
            startActivity(Intent(this, FreezerActivity::class.java)
                .putExtra(IceShortcuts.EXTRA_GROUP, groups.selection.name)
                .putExtra(FreezerActivity.EXTRA_UNGROUPED, groups.selection.ungrouped))
        }
        ui.freezeAll.setOnClickListener { folderPerform(Freezer.apps.map { it.key }, true) }

        val icons = IceIcons(this)
        val labels = mutableMapOf<String, String>()
        fun label(key: String) = labels.getOrPut(key) { Freezer.label(key) }
        var labelsReady = false
        val accessibility = getSystemService(AccessibilityManager::class.java)
        var shown = emptyList<Pair<IceApp, String>>()
        var look = IceGrid.folderLook
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (look == Look.GRID) 1 else 0
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                if (look != Look.GRID) return row(position, convertView, parent)
                val c = convertView?.tag as? CellIceBinding
                    ?: CellIceBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
                val (a, name) = shown[position]
                IceGrid.style(c, ui.grid, a, showState = false)
                c.label.text = if (a.favorite) getString(R.string.ice_favorite_name, name) else name
                icons.bind(c.icon, a.key)
                IceModeIndicator.bindBadge(c.badge, a)
                // The status may read the rules from storage: only TalkBack needs it, not each bind.
                c.root.contentDescription = if (accessibility.isEnabled) name + ", " + IceStatus.text(this@IceFolderActivity, a) else name
                c.root.setOnClickListener { open(a.key) }
                c.root.setOnLongClickListener { folderAppActions(a.key); true }
                return c.root
            }

            /** List looks: the same rows as the freezer screen, with the mode marker in place of its buttons. */
            private fun row(position: Int, convertView: View?, parent: ViewGroup): View {
                val r = convertView?.tag as? RowIceBinding
                    ?: RowIceBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
                val (a, name) = shown[position]
                val compact = look == Look.COMPACT
                IceGrid.style(r, ui.grid, compact, selecting = false)
                r.launch.visibility = View.GONE
                r.toggle.visibility = View.GONE
                val state = IceStatus.text(this@IceFolderActivity, a)
                val color = MaterialColors.getColor(r.root,
                    if (a.frozen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorTertiary)
                r.label.text = if (a.favorite) getString(R.string.ice_favorite_name, name) else name
                r.label.setTextColor(if (compact) color else MaterialColors.getColor(r.root, com.google.android.material.R.attr.colorOnSurface))
                r.status.text = state
                r.status.setTextColor(color)
                icons.bind(r.icon, a.key)
                IceModeIndicator.bindMarker(r.freezeMode, a, if (compact) 24 else 28)
                r.open.contentDescription = "$name, $state"
                r.open.setOnClickListener { open(a.key) }
                r.open.setOnLongClickListener { folderAppActions(a.key); true }
                r.root.setOnClickListener { open(a.key) }
                r.root.setOnLongClickListener { folderAppActions(a.key); true }
                return r.root
            }
        }
        fun paintLook() {
            val item = ui.toolbar.menu.findItem(R.id.action_view) ?: return
            item.setIcon(when (look) {
                Look.LIST -> R.drawable.ic_view_list
                Look.COMPACT -> R.drawable.ic_view_compact
                Look.GRID -> R.drawable.ic_view_grid
            })
            item.title = getString(R.string.ice_folder_view, getString(look.title))
        }
        fun layoutLook() {
            val pad = if (look == Look.GRID) dp(8) else 0
            ui.grid.setPadding(pad, dp(if (look == Look.GRID) 8 else 0), pad, dp(8))
            ui.grid.verticalSpacing = dp(if (look == Look.GRID) 4 else 0)
            IceGrid.apply(ui.grid, list = look != Look.GRID)
        }
        applyLook = {
            look = IceGrid.folderLook
            paintLook()
            layoutLook()
            ui.grid.adapter = adapter
            renderFolder?.invoke()
        }
        ui.grid.adapter = adapter
        paintLook()
        layoutLook()
        IceGrid.watchBounds(ui.grid)
        ui.grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (!scrolling && renderPending) renderFolder?.invoke()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
        renderFolder = render@{
            if (!labelsReady) return@render
            if (scrolling) {
                renderPending = true
                return@render
            }
            renderPending = false
            val apps = Freezer.apps
            groups.render(apps)
            val sorted = IceOrder.sort(apps, Prefs.iceFolderSort, Prefs.iceOrder, ::label)
            val order = settledOrder ?: sorted.map { it.key }.also { settledOrder = it }
            val all = IceOrder.settled(sorted, order).filter(groups.selection::contains).map { it to label(it.key) }
            val members = all.map { it.first }
            val q = ui.search.text?.toString()?.trim().orEmpty()
            shown = if (q.isEmpty()) all else all.filter { (a, name) -> name.contains(q, true) || a.key.contains(q, true) }
            ui.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            ui.empty.setText(if (members.isEmpty()) R.string.ice_group_empty else R.string.ice_nothing_found)
            ui.freezeAll.isEnabled = apps.isNotEmpty()
            ui.freezeAll.visibility = if (groups.selection.isAll) View.VISIBLE else View.GONE
            ui.folderHint.visibility = if (groups.selection.isAll) View.VISIBLE else View.GONE
            IceGrid.apply(ui.grid, list = look != Look.GRID)
            adapter.notifyDataSetChanged()
            icons.preload(shown.map { it.first.key })
        }
        ui.search.doAfterTextChanged { renderFolder?.invoke() }
        // List rows show countdowns: refresh them while the folder is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(30_000)
                    if (look != Look.GRID) renderFolder?.invoke()
                }
            }
        }
        // The grid sits in a frame with the empty text: ask the grid itself whether it is at the top.
        ui.refresh.setOnChildScrollUpCallback { _, _ -> ui.grid.canScrollVertically(-1) }
        ui.refresh.setOnRefreshListener {
            lifecycleScope.launch {
                val fresh = withContext(Dispatchers.IO) {
                    runCatching { Freezer.sync() }
                    val keys = Freezer.apps.map { it.key }
                    keys.zip(AppPickerActivity.labels(keys)).toMap()
                }
                labels.clear()
                labels += fresh
                settledOrder = null
                ui.refresh.isRefreshing = false
                renderFolder?.invoke()
            }
        }
        Prefs.listen(folderPrefs)
        // Asking the system for a name takes time: on the main thread it would hold the opening.
        lifecycleScope.launch {
            val keys = Freezer.apps.map { it.key }
            labels += withContext(Dispatchers.IO) { keys.zip(AppPickerActivity.labels(keys)).toMap() }
            labelsReady = true
            renderFolder?.invoke()
        }
    }

    private fun folderAppActions(key: String) {
        IceAppMenu.show(this, key,
            open = { open(key) },
            freeze = { folderPerform(key, it) },
            thawFor = { folderThawFor(listOf(key)) },
            card = {
                startActivity(Intent(this, FreezerActivity::class.java).putExtra(FreezerActivity.EXTRA_APP_KEY, key))
            },
            remove = {
                MaterialAlertDialogBuilder(this)
                    .setTitle(Freezer.label(key))
                    .setMessage(R.string.ice_remove_confirm)
                    .setPositiveButton(R.string.ice_remove) { _, _ -> App.scope.launch { Freezer.remove(listOf(key)) } }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
    }

    private fun folderPerform(key: String, freeze: Boolean, minutes: Int? = null) {
        folderPerform(listOf(key), freeze, minutes)
    }

    private fun folderPerform(keys: List<String>, freeze: Boolean, minutes: Int? = null) {
        val work = App.scope.async { runCatching { Freezer.manual(keys, freeze, "из папки морозилки", minutes) } }
        lifecycleScope.launch {
            val result = work.await().getOrElse {
                toast(getString(R.string.ice_tile_failed))
                return@launch
            }
            toast(when {
                result.changed < 0 -> getString(R.string.ice_no_access)
                result.requested == 0 -> getString(R.string.ice_no_changes)
                result.changed < result.requested -> getString(R.string.ice_tile_partial, result.changed, result.requested)
                result.freeze -> getString(R.string.ice_frozen_n, result.changed)
                else -> getString(R.string.ice_thawed_n, result.changed)
            })
            renderFolder?.invoke()
        }
    }

    private fun folderThawFor(keys: List<String>) {
        val options = listOf(15, 60, 240, -1)
        val labels = options.map { if (it < 0) getString(R.string.ice_until_manual) else Steps.words(it * 60) } + getString(R.string.ice_delay_own)
        MaterialAlertDialogBuilder(this).setTitle(R.string.ice_thaw_for)
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == options.size) FreezerActivity.askDelay(this) { folderPerform(keys, false, it) }
                else folderPerform(keys, false, options[index])
            }.setNegativeButton(R.string.cancel, null).show()
    }

}
