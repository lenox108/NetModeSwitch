package com.netmode.switcher

import android.app.AlarmManager
import android.app.AlertDialog
import android.os.PowerManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.netmode.switcher.databinding.ActivityRulesBinding
import com.netmode.switcher.databinding.RowRuleBinding

class RulesActivity : AppCompatActivity() {

    private lateinit var ui: ActivityRulesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(ui.root)
        title = getString(R.string.rules_title)

        ui.addSchedule.setOnClickListener {
            showScheduleDialog(null, onSave = { saveSchedule(it) })
        }
        ui.addWifi.setOnClickListener {
            showWifiDialog(null, onSave = { saveWifi(it) })
        }

        ui.catchUp.isChecked = Prefs.catchUpOnBoot
        ui.catchUp.setOnCheckedChangeListener { _, on -> Prefs.catchUpOnBoot = on }

        ui.ignoreWifiOff.isChecked = Prefs.ignoreWifiOff
        ui.ignoreWifiOff.setOnCheckedChangeListener { _, on -> Prefs.ignoreWifiOff = on }

        ui.reliableWifi.isChecked = Prefs.reliableWifi
        ui.reliableWifi.setOnCheckedChangeListener { _, on ->
            Prefs.reliableWifi = on
            if (on) WifiWatchService.start(this, WifiWatchService.MODE_ALWAYS)
            else WifiWatchService.stop(this)
        }

        ui.exactAlarms.setOnClickListener {
            // See MainActivity: the system list omits apps that are already exempt.
            val granted = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            val intent = if (granted) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            } else {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
            runCatching { startActivity(intent) }
        }
        ui.batteryOpt.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val schedules = Prefs.schedules.sortedWith(compareBy({ it.hour }, { it.minute }))
        fill(ui.scheduleList, schedules.size) { index, row ->
            val rule = schedules[index]
            row.title.text = "${rule.timeLabel}  →  ${rule.mode.label}"
            row.subtitle.text = rule.daysLabel
            row.enabled.isChecked = rule.enabled
            row.enabled.setOnCheckedChangeListener { _, on ->
                saveSchedule(rule.copy(enabled = on))
            }
            row.root.setOnClickListener {
                showScheduleDialog(
                    rule,
                    onSave = { saveSchedule(it) },
                    onDelete = { deleteSchedule(rule) },
                )
            }
        }
        ui.scheduleEmpty.visibility = if (schedules.isEmpty()) ViewGroup.VISIBLE else ViewGroup.GONE

        val wifi = Prefs.wifiRules
        fill(ui.wifiList, wifi.size) { index, row ->
            val rule = wifi[index]
            row.title.text = rule.ssid
            row.subtitle.text = rule.actionLabel
            row.enabled.isChecked = rule.enabled
            row.enabled.setOnCheckedChangeListener { _, on -> saveWifi(rule.copy(enabled = on)) }
            row.root.setOnClickListener {
                showWifiDialog(rule, onSave = { saveWifi(it) }, onDelete = { deleteWifi(rule) })
            }
        }
        ui.wifiEmpty.visibility = if (wifi.isEmpty()) ViewGroup.VISIBLE else ViewGroup.GONE

        val canExact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ui.exactAlarms.text = "%s: %s".format(
            getString(R.string.exact_alarms),
            getString(if (canExact) R.string.granted else R.string.denied),
        )
        ui.batteryOpt.text = "%s: %s".format(
            getString(R.string.battery_opt),
            getString(if (isBatteryExempt()) R.string.battery_off else R.string.battery_on),
        )
    }

    /**
     * Rebuilds a list in place. With a handful of rules a RecyclerView adapter would be more
     * machinery than the problem deserves.
     */
    private fun fill(parent: ViewGroup, count: Int, bind: (Int, RowRuleBinding) -> Unit) {
        parent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        repeat(count) { index ->
            val row = RowRuleBinding.inflate(inflater, parent, false)
            bind(index, row)
            parent.addView(row.root)
        }
    }

    private fun saveSchedule(rule: ScheduleRule) {
        Prefs.schedules = Prefs.schedules.filterNot { it.id == rule.id } + rule
        AlarmScheduler.rearm()
        render()
    }

    private fun deleteSchedule(rule: ScheduleRule) {
        Prefs.schedules = Prefs.schedules.filterNot { it.id == rule.id }
        AlarmScheduler.rearm()
        render()
    }

    private fun saveWifi(rule: WifiRule) {
        Prefs.wifiRules = Prefs.wifiRules.filterNot { it.id == rule.id } + rule
        WifiMonitor.register()
        if (rule.onDisconnect != null && !isBatteryExempt()) warnBatteryNeeded()
        render()
    }

    private fun isBatteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /**
     * Android silently skips the request dialog when the exemption is already granted, which
     * looks exactly like a dead button. Send the user to app info in that case so the tap
     * always leads somewhere and the current state is visible.
     */
    private fun openBatterySettings() {
        val intent = if (isBatteryExempt()) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    /**
     * Without the exemption Android refuses the background foreground-service start, and the
     * disconnect half of the rule would quietly never fire.
     */
    private fun warnBatteryNeeded() {
        AlertDialog.Builder(this)
            .setMessage(R.string.battery_needed)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteWifi(rule: WifiRule) {
        Prefs.wifiRules = Prefs.wifiRules.filterNot { it.id == rule.id }
        WifiMonitor.register()
        render()
    }
}
