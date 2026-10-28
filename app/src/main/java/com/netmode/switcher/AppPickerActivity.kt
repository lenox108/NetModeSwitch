package com.netmode.switcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.netmode.switcher.databinding.ActivityAppPickerBinding
import com.netmode.switcher.databinding.RowAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks packages for an [AppRule]. Takes and returns the selection in [EXTRA_PACKAGES].
 *
 * "System" here means a system package without a launcher icon — services and providers.
 * Preinstalled apps the user actually opens (a browser, a video app) keep their icon and stay
 * visible, which is what someone saving battery is after.
 */
class AppPickerActivity : AppCompatActivity() {

    private data class Entry(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val alarm: Boolean,
        val info: ApplicationInfo,
    )

    private lateinit var ui: ActivityAppPickerBinding
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private val selected = linkedSetOf<String>()
    private val methods = mutableMapOf<String, FreezeMethod>()

    /** What a newly ticked app gets; follows the last method the user picked. */
    private var defaultMethod = FreezeMethod.STOP_RESTRICT
    private val icons = LruCache<String, Drawable>(120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        val saved = savedInstanceState
        selected += saved?.getStringArray(EXTRA_PACKAGES) ?: intent.getStringArrayExtra(EXTRA_PACKAGES).orEmpty()
        val names = saved?.getStringArray(EXTRA_METHODS) ?: intent.getStringArrayExtra(EXTRA_METHODS).orEmpty()
        selected.zip(names.toList()).forEach { (p, m) ->
            runCatching { FreezeMethod.valueOf(m) }.getOrNull()?.let { methods[p] = it }
        }
        intent.getStringExtra(EXTRA_DEFAULT)?.let { d ->
            runCatching { FreezeMethod.valueOf(d) }.getOrNull()?.let { defaultMethod = it }
        }

        ui.hideSystem.isChecked = Prefs.hideSystemApps
        ui.hideSystem.setOnCheckedChangeListener { _, on -> Prefs.hideSystemApps = on; filter() }
        ui.search.doAfterTextChanged { filter() }
        ui.list.adapter = adapter
        ui.filter.setOnCheckedStateChangeListener { _, _ -> filter() }
        ui.clear.setOnClickListener {
            selected.clear()
            filter()
            updateDone()
        }
        ui.done.setOnClickListener {
            val list = selected.toList()
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PACKAGES, list.toTypedArray())
                    .putExtra(EXTRA_METHODS, list.map { methodOf(it).name }.toTypedArray()),
            )
            finish()
        }
        updateDone()

        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { load() }
            ui.progress.visibility = View.GONE
            filter()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val list = selected.toList()
        outState.putStringArray(EXTRA_PACKAGES, list.toTypedArray())
        outState.putStringArray(EXTRA_METHODS, list.map { methodOf(it).name }.toTypedArray())
    }

    /**
     * Installed apps, plus their clones from other user profiles. A clone is listed only when
     * the original has a launcher icon: clone profiles also carry a set of system packages
     * nobody means to pick.
     */
    private suspend fun load(): List<Entry> {
        val pm = packageManager
        val guarded = AppFreezer.protectedPackages(this)
        val alarms = AppFreezer.alarmApps(this)
        val base = pm.getInstalledApplications(0)
            .filter { it.packageName !in guarded && it.flags and ApplicationInfo.FLAG_PERSISTENT == 0 }
            .associateBy { it.packageName }
        val launchable = base.keys.filter { pm.getLaunchIntentForPackage(it) != null }.toSet()

        val originals = base.values.map { info ->
            Entry(
                pkg = info.packageName,
                label = info.loadLabel(pm).toString(),
                system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.packageName !in launchable,
                alarm = info.packageName in alarms,
                info = info,
            )
        }
        val clones = runCatching { AppKey.cloneKeys() }.getOrDefault(emptyList())
            .filter { AppKey.pkg(it) in launchable }
            .mapNotNull { key ->
                val info = base[AppKey.pkg(key)] ?: return@mapNotNull null
                Entry(key, info.loadLabel(pm).toString() + " " + getString(R.string.clone_mark), false,
                    AppKey.pkg(key) in alarms, info)
            }
        return (originals + clones)
            .sortedWith(compareBy<Entry> { it.pkg !in selected }.thenBy { it.label.lowercase() })
    }

    private fun filter() {
        val q = ui.search.text?.toString()?.trim().orEmpty()
        shown = all.filter { e ->
            (!ui.showSelected.isChecked || e.pkg in selected) &&
                (!ui.showUnselected.isChecked || e.pkg !in selected) &&
                (!ui.hideSystem.isChecked || !e.system || e.pkg in selected) &&
                (q.isEmpty() || e.label.contains(q, true) || e.pkg.contains(q, true))
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateDone() {
        ui.done.text = getString(R.string.done_n, selected.size)
        ui.showSelected.text = getString(R.string.filter_selected, selected.size)
        ui.clear.isEnabled = selected.isNotEmpty()
    }

    /**
     * The whole row and the checkbox itself both toggle. A checkbox inside a ListView row
     * otherwise swallows the tap without reaching the list's item listener.
     */
    private fun methodOf(pkg: String) = methods[pkg] ?: defaultMethod

    private fun toggle(e: Entry, row: RowAppBinding) {
        if (!selected.remove(e.pkg)) {
            selected += e.pkg
            methods[e.pkg] = methodOf(e.pkg)
        }
        row.check.isChecked = e.pkg in selected
        paintMethod(e, row)
        updateDone()
        if (ui.showSelected.isChecked || ui.showUnselected.isChecked) filter()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { RowAppBinding.bind(it) }
                ?: RowAppBinding.inflate(layoutInflater, parent, false)
            val e = shown[position]
            row.label.text = e.label
            row.pkg.text = when {
                e.alarm -> getString(R.string.alarm_app_warning) + "\n" + e.pkg
                e.system -> getString(R.string.system_app) + " · " + e.pkg
                AppKey.isClone(e.pkg) -> AppKey.pkg(e.pkg) + " · " + getString(R.string.clone_user, AppKey.user(e.pkg))
                else -> e.pkg
            }
            row.pkg.setTextColor(
                if (e.alarm) getColor(android.R.color.holo_red_light)
                else row.label.currentTextColor
            )
            row.check.isChecked = e.pkg in selected
            row.root.setOnClickListener { toggle(e, row) }
            row.check.setOnClickListener { toggle(e, row) }
            row.method.setOnClickListener { chooseMethod(e, row) }
            paintMethod(e, row)
            bindIcon(row, e)
            return row.root
        }
    }

    /** Picking apps for the "app is open" trigger: no stop method to choose. */
    private val plain by lazy { intent.getBooleanExtra(EXTRA_PLAIN, false) }

    private fun paintMethod(e: Entry, row: RowAppBinding) {
        row.method.visibility = if (e.pkg in selected && !plain) View.VISIBLE else View.GONE
        row.method.text = methodOf(e.pkg).label
    }

    /** Per-app choice; also becomes the default for the next app ticked. */
    private fun chooseMethod(e: Entry, row: RowAppBinding) {
        // Each method with what it actually does, so the choice is not a guess.
        val hints = listOf(R.string.method_stop_hint, R.string.method_restrict_hint, R.string.method_suspend_hint)
        val items = FreezeMethod.entries.mapIndexed { i, m ->
            val text = android.text.SpannableStringBuilder(m.label)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, text.length, 0)
            val from = text.length
            text.append("\n").append(getString(hints[i]))
            text.setSpan(android.text.style.RelativeSizeSpan(0.85f), from, text.length, 0)
            text
        }.toTypedArray<CharSequence>()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(e.label)
            .setSingleChoiceItems(items, FreezeMethod.entries.indexOf(methodOf(e.pkg))) { d, i ->
                methods[e.pkg] = FreezeMethod.entries[i]
                defaultMethod = FreezeMethod.entries[i]
                paintMethod(e, row)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Icons load off the main thread; a recycled row just drops a stale result. */
    private fun bindIcon(row: RowAppBinding, e: Entry) {
        row.icon.tag = e.pkg
        val cached = icons.get(e.pkg)
        if (cached != null) {
            row.icon.setImageDrawable(cached)
            return
        }
        row.icon.setImageDrawable(null)
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                runCatching { e.info.loadIcon(packageManager) }.getOrNull()
            } ?: return@launch
            icons.put(e.pkg, d)
            if (row.icon.tag == e.pkg) row.icon.setImageDrawable(d)
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"

        /** Method names, parallel to [EXTRA_PACKAGES]. */
        const val EXTRA_METHODS = "methods"
        const val EXTRA_PLAIN = "plain"
        const val EXTRA_DEFAULT = "default_method"

        /** Labels for a list of packages, for the rules screen summary. */
        fun labels(pkgs: List<String>): List<String> {
            val pm = App.ctx.packageManager
            return pkgs.map { key ->
                val label = runCatching {
                    Compat.appInfo(pm, AppKey.pkg(key)).loadLabel(pm).toString()
                }.getOrDefault(AppKey.pkg(key))
                if (AppKey.isClone(key)) "$label ${App.ctx.getString(R.string.clone_mark)}" else label
            }
        }
    }
}
