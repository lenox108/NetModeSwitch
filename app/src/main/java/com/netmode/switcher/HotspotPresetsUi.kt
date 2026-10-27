package com.netmode.switcher

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityHotspotBinding

/** Compact, editable launch buttons. Editing a button never starts or changes the hotspot. */
internal class HotspotPresetsUi(
    private val activity: AppCompatActivity,
    private val ui: ActivityHotspotBinding,
    private val launch: (HotspotPreset) -> Unit,
) {
    private var ready = false
    private var available = Prefs.hotspotAvailableBands
    private val buttons = mutableListOf<MaterialButton>()

    init {
        ui.addPreset.setOnClickListener { edit(null) }
        render()
    }

    fun setEnabled(value: Boolean) {
        ready = value
        buttons.forEach { it.isEnabled = value }
    }

    fun bindAvailable(value: Set<String>) {
        available = value
        render()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun render() {
        ui.presetGrid.removeAllViews()
        buttons.clear()
        val presets = Prefs.hotspotPresets.filter { it.band in available }
        ui.addPreset.isEnabled = HotspotPreset.bands.any { it in available }
        ui.presetHint.setText(if (presets.isEmpty()) R.string.ap_presets_empty else R.string.ap_presets_hint)
        presets.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, preset ->
                val button = (activity.layoutInflater.inflate(R.layout.hotspot_preset_button, row, false) as MaterialButton).apply {
                    text = "${preset.bandLabel}\n${preset.routeLabel}"
                    contentDescription = "Запустить раздачу: ${preset.title}. Удерживай, чтобы изменить кнопку"
                    setOnClickListener { launch(preset) }
                    setOnLongClickListener { edit(preset); true }
                    isEnabled = ready
                }
                buttons += button
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            ui.presetGrid.addView(row)
        }
    }

    private fun edit(original: HotspotPreset?) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun choices(title: String, values: List<Pair<String, String>>, selected: String): ChipGroup {
            box.addView(TextView(activity).apply { text = title; setPadding(0, dp(12), 0, dp(4)) })
            return ChipGroup(activity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                values.forEach { (key, label) ->
                    addView(Chip(activity).apply {
                        id = View.generateViewId()
                        tag = key
                        text = label
                        isCheckable = true
                        isChecked = key == selected
                        setEnsureMinTouchTargetSize(true)
                    })
                }
                box.addView(this)
            }
        }
        val mode = choices("Маршрут", listOf("VPN" to "VPN", "PROXY" to "Proxy", "DIRECT" to "Напрямую"), original?.mode?.name ?: "VPN")
        val supported = HotspotPreset.bands.filter { it in available }
        if (supported.isEmpty()) return
        val selectedBand = original?.band?.takeIf { it in supported } ?: "5".takeIf { it in supported } ?: supported.first()
        val band = choices("Диапазон", supported.map { it to HotspotPreset(it, TetherMode.DIRECT).bandLabel }, selectedBand)
        box.addView(TextView(activity).apply {
            text = "Имя, пароль и адрес прокси — из сохранённых настроек. При смене диапазона точка перезапустится, канал выберет Android."
            setPadding(0, dp(12), 0, dp(8))
        })
        val addTile = MaterialButton(activity).apply {
            text = "Сохранить и в шторку"
            setIconResource(R.drawable.ic_add)
        }
        box.addView(addTile)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (original == null) "Новая быстрая кнопка" else "Быстрая кнопка")
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (original != null) builder.setNeutralButton("Удалить") { _, _ ->
            Prefs.hotspotPresets = Prefs.hotspotPresets - original
            render()
        }
        val dialog = builder.create()
        fun saveSelection(): HotspotPreset? {
            val route = mode.findViewById<Chip>(mode.checkedChipId)?.tag as? String ?: return null
            val radio = band.findViewById<Chip>(band.checkedChipId)?.tag as? String ?: return null
            val next = HotspotPreset(radio, TetherMode.valueOf(route))
            val saved = Prefs.hotspotPresets
            if (next != original && next in saved) {
                Toast.makeText(activity, "Такая кнопка уже есть", Toast.LENGTH_SHORT).show()
                return null
            }
            Prefs.hotspotPresets = if (original == null) saved + next else saved.map { if (it == original) next else it }
            render()
            dialog.dismiss()
            return next
        }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                saveSelection()
            }
            addTile.setOnClickListener { saveSelection()?.let { HotspotPresetTiles.requestAdd(activity, it) } }
        }
        dialog.show()
    }
}
