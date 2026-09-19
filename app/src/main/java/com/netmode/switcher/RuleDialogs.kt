package com.netmode.switcher

import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.netmode.switcher.databinding.DialogScheduleBinding
import com.netmode.switcher.databinding.DialogWifiBinding
import kotlinx.coroutines.launch

/** null means "do nothing" — a Wi-Fi rule may act on only one of the two events. */
private val MODE_OPTIONS = listOf(null, NetMode.FULL, NetMode.THREE_G)

private fun AppCompatActivity.fillModes(spinner: Spinner, selected: NetMode?) {
    val labels = MODE_OPTIONS.map { it?.label ?: getString(R.string.do_nothing) }
    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spinner.setSelection(MODE_OPTIONS.indexOf(selected).coerceAtLeast(0))
}

fun AppCompatActivity.showScheduleDialog(
    existing: ScheduleRule?,
    onSave: (ScheduleRule) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val v = DialogScheduleBinding.inflate(layoutInflater)
    v.time.setIs24HourView(true)
    val chips = listOf(v.d1, v.d2, v.d3, v.d4, v.d5, v.d6, v.d7)

    if (existing != null) {
        v.time.hour = existing.hour
        v.time.minute = existing.minute
        chips.forEachIndexed { i, chip -> chip.isChecked = existing.days and (1 shl i) != 0 }
        if (existing.mode == NetMode.FULL) v.modeFull.isChecked = true else v.mode3g.isChecked = true
    } else {
        v.modeFull.isChecked = true
    }

    val dialog = AlertDialog.Builder(this)
        .setView(v.root)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
        .apply { if (onDelete != null) setNeutralButton(R.string.delete) { _, _ -> onDelete() } }
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var days = 0
            chips.forEachIndexed { i, chip -> if (chip.isChecked) days = days or (1 shl i) }
            onSave(
                ScheduleRule(
                    id = existing?.id ?: Prefs.newId(),
                    enabled = existing?.enabled ?: true,
                    hour = v.time.hour,
                    minute = v.time.minute,
                    days = days,
                    mode = if (v.modeFull.isChecked) NetMode.FULL else NetMode.THREE_G,
                )
            )
            dialog.dismiss()
        }
    }
    dialog.show()
}

fun AppCompatActivity.showWifiDialog(
    existing: WifiRule?,
    onSave: (WifiRule) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val v = DialogWifiBinding.inflate(layoutInflater)
    v.ssid.setText(existing?.ssid.orEmpty())
    fillModes(v.onConnect, existing?.onConnect ?: NetMode.THREE_G)
    fillModes(v.onDisconnect, existing?.onDisconnect)

    v.useCurrent.setOnClickListener {
        lifecycleScope.launch {
            val ssid = WifiSsid.current()
            if (ssid != null) v.ssid.setText(ssid)
            else Toast.makeText(this@showWifiDialog, R.string.ssid_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    val dialog = AlertDialog.Builder(this)
        .setView(v.root)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
        .apply { if (onDelete != null) setNeutralButton(R.string.delete) { _, _ -> onDelete() } }
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ssid = v.ssid.text.toString().trim()
            if (ssid.isEmpty()) {
                Toast.makeText(this, R.string.ssid_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave(
                WifiRule(
                    id = existing?.id ?: Prefs.newId(),
                    enabled = existing?.enabled ?: true,
                    ssid = ssid,
                    onConnect = MODE_OPTIONS[v.onConnect.selectedItemPosition],
                    onDisconnect = MODE_OPTIONS[v.onDisconnect.selectedItemPosition],
                )
            )
            dialog.dismiss()
        }
    }
    dialog.show()
}
