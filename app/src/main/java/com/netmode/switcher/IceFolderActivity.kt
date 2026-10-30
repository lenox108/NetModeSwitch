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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityIceFolderBinding
import com.netmode.switcher.databinding.CellIceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A regular task: the freezer stays available while its apps are on screen. */
class IceFolderActivity : AppCompatActivity() {
    private lateinit var ui: ActivityIceFolderBinding
    private var renderFolder: (() -> Unit)? = null
    private var folderGroups: IceGroupControls? = null
    /** The order fixed at opening: a launch or a thaw must not move the icons under the finger. */
    private var settledOrder: List<String>? = null
    /** Changes wait for the list to stop: rebinding it would interrupt the scroll. */
    private var scrolling = false
    private var renderPending = false
    private val folderPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ice_folder_sort" || key == "ice_sort" || key == "ice_order") runOnUiThread { settledOrder = null }
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
        folderGroups = null
        super.onDestroy()
    }

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
            IceOrderDialog.choose(this, Prefs.iceFolderSort) { Prefs.iceFolderSort = it }
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
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val c = convertView?.tag as? CellIceBinding
                    ?: CellIceBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
                val (a, name) = shown[position]
                IceGrid.style(c, ui.grid, a, showState = false)
                c.label.text = if (a.favorite) getString(R.string.ice_favorite_name, name) else name
                icons.bind(c.icon, a.key)
                c.badge.visibility = if (a.frozen) View.VISIBLE else View.GONE
                // The status may read the rules from storage: only TalkBack needs it, not each bind.
                c.root.contentDescription = if (accessibility.isEnabled) name + ", " + IceStatus.text(this@IceFolderActivity, a) else name
                c.root.setOnClickListener { open(a.key) }
                c.root.setOnLongClickListener { folderAppActions(a.key); true }
                return c.root
            }
        }
        ui.grid.adapter = adapter
        IceGrid.apply(ui.grid, list = false)
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
            IceGrid.apply(ui.grid, list = false)
            adapter.notifyDataSetChanged()
            icons.preload(shown.map { it.first.key })
        }
        ui.search.doAfterTextChanged { renderFolder?.invoke() }
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
