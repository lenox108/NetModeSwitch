package com.netmode.switcher

import android.app.AlarmManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.netmode.switcher.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding

    /** Guards the toggle group while it is being set from code rather than tapped. */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.toolbar.inflateMenu(R.menu.main)
        ui.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> refresh()
                R.id.action_diagnose -> diagnose()
            }
            true
        }

        ui.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncing) return@addOnButtonCheckedListener
            val target = if (id == R.id.btnFull) NetMode.FULL else NetMode.THREE_G
            apply { ModeController.apply(target, ModeController.Source.MANUAL) }
        }
        ui.toggle.setOnClickListener { apply { ModeController.toggle(ModeController.Source.MANUAL) } }

        ui.rulesCard.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        ui.tileCard.setOnClickListener { addTile() }

        ui.chipRoot.setOnClickListener { refresh() }
        ui.chipAlarm.setOnClickListener {
            // An app exempt from battery optimisation is implicitly allowed exact alarms and
            // Android hides it from the "Alarms & reminders" list, so sending the user there
            // would be a dead end. App info shows the state that actually applies.
            val granted = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            val intent = if (granted) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            } else {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
            runCatching { startActivity(intent) }
        }
        ui.chipBattery.setOnClickListener {
            // The request dialog never appears once the exemption is granted, so an already
            // exempt app opens app info instead of doing nothing at all.
            val exempt = getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
            val action = if (exempt) Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
        renderStatus()
        renderLog()
    }

    private fun apply(action: suspend () -> Result<NetMode>) {
        ui.mode.text = getString(R.string.working)
        lifecycleScope.launch {
            action().onSuccess { show(it) }.onFailure { fail(it) }
            renderLog()
        }
    }

    private fun refresh() {
        ui.mode.text = getString(R.string.working)
        lifecycleScope.launch {
            ModeController.read().onSuccess { show(it) }.onFailure { fail(it) }
            renderStatus()
        }
    }

    private fun show(mode: NetMode?) {
        ui.mode.text = mode?.label ?: getString(R.string.unknown)
        syncing = true
        when (mode) {
            NetMode.FULL -> ui.modeGroup.check(R.id.btnFull)
            NetMode.THREE_G -> ui.modeGroup.check(R.id.btn3g)
            null -> ui.modeGroup.clearChecked()
        }
        syncing = false

        lifecycleScope.launch {
            val mask = runCatching { Telephony.readMask() }.getOrDefault(-1L)
            ui.detail.text = "mask 0x%X · %s · SIM %d"
                .format(mask, Raf.describe(mask), ModeController.subId())
        }
    }

    private fun fail(error: Throwable) {
        ui.mode.text = getString(R.string.unknown)
        ui.detail.text = error.message ?: error.toString()
        syncing = true
        ui.modeGroup.clearChecked()
        syncing = false
    }

    private fun renderStatus() {
        lifecycleScope.launch {
            mark(ui.chipRoot, R.string.chip_root, Root.available())
        }
        mark(ui.chipAlarm, R.string.chip_alarms, getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        mark(
            ui.chipBattery,
            R.string.chip_battery,
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )

        val schedules = Prefs.schedules.count { it.enabled }
        val wifi = Prefs.wifiRules.count { it.enabled }
        ui.rulesSummary.text = "%s · %s".format(
            resources.getQuantityString(R.plurals.n_schedules, schedules, schedules),
            resources.getQuantityString(R.plurals.n_wifi, wifi, wifi),
        )
    }

    /** A tick or a cross beats a colour alone — the state stays readable at a glance. */
    private fun mark(chip: Chip, labelRes: Int, ok: Boolean) {
        chip.text = "${getString(labelRes)} ${if (ok) "✓" else "✕"}"
        chip.isChipIconVisible = false
    }

    private fun renderLog() {
        val events = Prefs.events
        ui.log.text = if (events.isEmpty()) getString(R.string.no_events)
        else events.take(6).joinToString("\n")
    }

    private fun addTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, NetModeTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { /* result codes are informational; the system throttles repeated asks */ }
    }

    private fun diagnose() {
        lifecycleScope.launch {
            val text = runCatching {
                buildString {
                    appendLine("root: ${if (Root.available()) "есть" else "НЕТ"}")
                    appendLine("subId: ${ModeController.subId()}  slot: ${Telephony.slot()}")
                    val mask = Telephony.readMask()
                    appendLine("mask: 0x%X (%d)  %s".format(mask, mask, Raf.describe(mask)))
                    appendLine("эталон 4G: 0x%X   3G: 0x%X".format(NetMode.FULL.mask, NetMode.THREE_G.mask))
                    appendLine()
                    appendLine(Root.exec("cmd phone get-allowed-network-types-for-users").trim())
                    appendLine()
                    appendLine("SSID: ${WifiSsid.current() ?: "—"}")
                }
            }.getOrElse { "ОШИБКА: ${it.message}" }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.diagnostics)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
