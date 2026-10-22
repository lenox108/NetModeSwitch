package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.netmode.switcher.databinding.ActivityRulesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: access, the primary/secondary SIM scenario, background work, backup. */
class RulesActivity : AppCompatActivity() {

    private lateinit var ui: ActivityRulesBinding

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export().toByteArray()) }
                }
            }
            toast(if (ok.isSuccess) getString(R.string.backup_saved, Prefs.appRules.size) else getString(R.string.backup_failed, ok.exceptionOrNull()?.message))
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.backup_import_confirm)
            .setPositiveButton(R.string.backup_import) { _, _ ->
                lifecycleScope.launch {
                    val n = runCatching {
                        val text = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                        Backup.import(text)
                    }
                    toast(n.map { getString(R.string.backup_loaded, it) }.getOrElse { getString(R.string.backup_failed, it.message) })
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }

        ui.permEntry.title.setText(R.string.section_permissions)
        ui.permEntry.hint.setText(R.string.perm_entry_hint)
        ui.permEntry.root.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        ui.checkEntry.title.setText(R.string.check_title)
        ui.checkEntry.hint.setText(R.string.check_entry_hint)
        ui.checkEntry.root.setOnClickListener { startActivity(Intent(this, CheckActivity::class.java)) }

        val backends = listOf(Backend.AUTO, Backend.ROOT, Backend.SHIZUKU)
        ui.backend.onChange = { Prefs.backend = backends[it] }
        row(ui.reliableRow, R.string.reliable_wifi, R.string.reliable_summary)
        row(ui.notifyRow, R.string.status_notification, R.string.status_summary)
        row(ui.catchUpRow, R.string.catch_up, R.string.catch_up_summary)
        row(ui.dualRow, R.string.dual_switch, R.string.dual_summary)
        ui.dualHow.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.dual_title).setMessage(R.string.dual_hint)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        ui.catchUpRow.toggle.setOnCheckedChangeListener { _, on -> if (!loading) Prefs.catchUpOnBoot = on }
        ui.reliableRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.reliableWifi = on
            if (on) WifiWatchService.start(this, WifiWatchService.MODE_ALWAYS)
            else WifiMonitor.register()
        }
        ui.notifyRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.statusNotification = on
            if (on && !Compat.granted(this, android.Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            }
            WifiMonitor.register()
            StatusNotification.update()
        }

        ui.dualRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.dualSimEnabled = on
            paintDual()
            dualChanged("включено")
        }
        ui.dualPrimary.onChange = { Prefs.dualPrimary = Profile.available()[it]; dualChanged("изменены режимы") }
        ui.dualSecondary.onChange = { Prefs.dualSecondary = Profile.available()[it]; dualChanged("изменены режимы") }

        ui.exportBtn.setOnClickListener { exportFile.launch("netmodeswitch-rules.json") }
        ui.importBtn.setOnClickListener { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
        load()
    }

    /** Fills every control from the saved settings, without firing their listeners' side effects twice. */
    /** True while [load] sets the controls, so that is not taken for the user's changes. */
    private var loading = false

    private fun load() {
        loading = true
        val backends = listOf(Backend.AUTO, Backend.ROOT, Backend.SHIZUKU)
        ui.backend.setItems(listOf(getString(R.string.backend_auto), getString(R.string.backend_root), getString(R.string.backend_shizuku)),
            backends.indexOf(Prefs.backend).coerceAtLeast(0))
        ui.catchUpRow.toggle.isChecked = Prefs.catchUpOnBoot
        ui.reliableRow.toggle.isChecked = Prefs.reliableWifi
        ui.notifyRow.toggle.isChecked = Prefs.statusNotification
        ui.dualRow.toggle.isChecked = Prefs.dualSimEnabled
        paintDual()
        val modes = Profile.available()
        val labels = modes.map { it.label }
        ui.dualPrimary.setItems(labels, modes.indexOf(Prefs.dualPrimary).coerceAtLeast(0))
        ui.dualSecondary.setItems(labels, modes.indexOf(Prefs.dualSecondary).coerceAtLeast(0))
        loading = false
        explain()
    }

    /**
     * The modes stay visible with the scenario off, just inactive: otherwise nothing on the
     * screen says what switching it on would do.
     */
    private fun paintDual() {
        val on = Prefs.dualSimEnabled
        ui.dualBlock.alpha = if (on) 1f else 0.5f
        ui.dualPrimary.isEnabled = on
        ui.dualSecondary.isEnabled = on
    }

    private fun explain() {
        lifecycleScope.launch {
            val sims = Sims.active()
            ui.dualNow.text = if (sims.size < 2) getString(R.string.dual_now_one)
            else {
                val data = DualSim.dataSlot()?.takeIf { d -> sims.any { it.slot == d } } ?: sims.first().slot
                val primary = sims.first { it.slot == data }
                val secondary = sims.first { it.slot != data }
                getString(R.string.dual_now, primary.label, Prefs.dualPrimary.label, secondary.label, Prefs.dualSecondary.label)
            }
        }
    }

    private fun dualChanged(reason: String) {
        explain()
        WifiMonitor.syncAll()
        lifecycleScope.launch {
            val line = runCatching { DualSim.sync(reason) }.getOrElse { it.message }
            if (line != null) toast(line)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val missing = PermissionsActivity.missingCount(this@RulesActivity)
            val p = Profile.current
            ui.checkEntry.state.text = p?.let { android.text.format.DateFormat.format("dd.MM", it.at).toString() + " ✓" }
                ?: getString(R.string.check_not_yet)
            ui.checkEntry.state.setTextColor(MaterialColors.getColor(ui.checkEntry.state,
                if (p != null) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            ui.permEntry.state.text = if (missing == 0) getString(R.string.perm_all_ok) else getString(R.string.perm_missing_n, missing)
            ui.permEntry.state.setTextColor(MaterialColors.getColor(ui.permEntry.state,
                if (missing == 0) androidx.appcompat.R.attr.colorPrimary else android.R.attr.colorError))
        }
    }

    /** A setting row: the whole row toggles its switch. */
    private fun row(r: com.netmode.switcher.databinding.RowSwitchBinding, title: Int, summary: Int) {
        r.title.setText(title)
        r.summary.setText(summary)
        r.root.setOnClickListener { r.toggle.toggle() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
