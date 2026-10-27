package com.netmode.switcher

import android.view.View
import com.google.android.material.button.MaterialButtonToggleGroup
import com.netmode.switcher.databinding.ActivityHotspotBinding

/** One selection across the visible supported bands; preserve an unsupported saved setup. */
internal class HotspotBandButtons(private val ui: ActivityHotspotBinding) {
    private val buttons = linkedMapOf(
        "2" to ui.band2, "5" to ui.band5, "6" to ui.band6,
        "auto25" to ui.bandAuto, "dual25" to ui.bandDual, "keep" to ui.bandKeep,
    )
    private var painting = false
    private var available = emptySet<String>()
    var selectedKey = "2"
        private set
    var onChange: (() -> Unit)? = null

    init {
        val listener = MaterialButtonToggleGroup.OnButtonCheckedListener { group, id, checked ->
            if (!painting) {
                val key = buttons.entries.firstOrNull { it.value.id == id }?.key
                if (key != null && checked) choose(key)
                // Tapping the selected button keeps it selected. Switching within a row has
                // already populated checkedButtonIds, so it is not mistaken for deselection.
                else if (key == selectedKey && group.checkedButtonIds.isEmpty()) paint()
            }
        }
        ui.bandPrimary.addOnButtonCheckedListener(listener)
        ui.bandExtra.addOnButtonCheckedListener(listener)
        ui.bandKeep.setOnClickListener { choose("keep") }
        setEnabled(false)
    }

    fun bind(key: String, supported: Set<String>) {
        available = supported
        selectedKey = key.takeIf { it in supported } ?: "keep"
        buttons.forEach { (value, button) ->
            button.visibility = if (value in supported || (value == "keep" && selectedKey == "keep")) View.VISIBLE else View.GONE
        }
        ui.bandPrimary.visibility = if (supported.any { it in listOf("2", "5", "6") }) View.VISIBLE else View.GONE
        ui.bandExtra.visibility = if (supported.any { it in listOf("auto25", "dual25") }) View.VISIBLE else View.GONE
        setEnabled(true)
        paint()
    }

    fun setEnabled(enabled: Boolean) {
        buttons.forEach { (key, button) -> button.isEnabled = enabled && (key in available || key == "keep") }
    }

    private fun choose(key: String) {
        val changed = key != selectedKey
        selectedKey = key
        paint()
        if (changed) onChange?.invoke()
    }

    private fun paint() {
        painting = true
        try {
            val id = buttons.getValue(selectedKey).id
            if (selectedKey in listOf("2", "5", "6")) {
                ui.bandExtra.clearChecked()
                ui.bandPrimary.check(id)
            } else {
                ui.bandPrimary.clearChecked()
                if (selectedKey == "keep") ui.bandExtra.clearChecked() else ui.bandExtra.check(id)
            }
            ui.bandKeep.isChecked = selectedKey == "keep"
        } finally { painting = false }
    }
}
