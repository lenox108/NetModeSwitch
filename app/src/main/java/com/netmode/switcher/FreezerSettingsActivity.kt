package com.netmode.switcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.netmode.switcher.databinding.ActivityFreezerSettingsBinding
import com.netmode.switcher.databinding.RowSwitchBinding
import kotlinx.coroutines.launch

/** The freezer's own settings: how and when it freezes, how it opens, its shortcuts. */
class FreezerSettingsActivity : AppCompatActivity() {

    private lateinit var ui: ActivityFreezerSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityFreezerSettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.viewSettings.setOnClickListener { IceGrid.settings(this) }

        ui.softCard.setOnClickListener { setMode(IceMode.SOFT) }
        ui.deepCard.setOnClickListener { setMode(IceMode.DEEP) }
        paintMode()

        paintDelays()

        row(ui.screenOff, R.string.ice_screen_off, R.string.ice_screen_off_hint, Prefs.iceScreenOff) { Prefs.iceScreenOff = it }
        row(ui.keepBusy, R.string.ice_keep_busy, R.string.ice_keep_busy_hint, Prefs.iceKeepBusy) { Prefs.iceKeepBusy = it }
        row(ui.noConfirm, R.string.ice_no_confirm, R.string.ice_no_confirm_hint, Prefs.iceNoConfirm) { Prefs.iceNoConfirm = it }
        row(ui.nativeInstant, R.string.ice_native, R.string.ice_native_hint, Prefs.iceNativeInstant) { on ->
            Prefs.iceNativeInstant = on
            lifecycleScope.launch {
                runCatching { AppWatchService.enable(on) }.onFailure {
                    Snackbar.make(ui.root, R.string.app_instant_failed, Snackbar.LENGTH_LONG).show()
                }
            }
        }
        row(ui.badge, R.string.ice_badge, R.string.ice_badge_hint, Prefs.iceBadge) { Prefs.iceBadge = it; IceShortcuts.refresh() }
        row(ui.respectRules, R.string.ice_respect_rules, R.string.ice_respect_rules_hint, Prefs.iceRespectRules) { Prefs.iceRespectRules = it }
        row(ui.bootCheck, R.string.ice_boot_check, R.string.ice_boot_check_hint, Prefs.iceBootCheck) { Prefs.iceBootCheck = it }

        ui.pinFolder.setOnClickListener { FreezerActivity.pinFolder(this) }
        ui.addTile.setOnClickListener { FreezerActivity.addTile(this) }
    }

    /** The presets, a delay set by hand if there is one, then "Своё…". */
    private fun paintDelays() {
        ui.delays.setOnCheckedStateChangeListener(null)
        ui.delays.removeAllViews()
        fun chip(text: String) = (LayoutInflater.from(this).inflate(R.layout.chip_choice, ui.delays, false) as Chip).apply {
            id = View.generateViewId()
            this.text = text
        }
        val cur = Prefs.iceDefaultDelay
        (Freezer.DELAYS + listOfNotNull(cur.takeIf { it !in Freezer.DELAYS })).forEach { d ->
            ui.delays.addView(chip(FreezerActivity.delayLabel(this, d)).apply { tag = d; isChecked = cur == d })
        }
        ui.delays.addView(chip(getString(R.string.ice_delay_own)).apply {
            isCheckable = false
            setOnClickListener { FreezerActivity.askDelay(this@FreezerSettingsActivity) { setDelay(it); paintDelays() } }
        })
        ui.delays.setOnCheckedStateChangeListener { g, _ ->
            (g.findViewById<Chip>(g.checkedChipId)?.tag as? Int)?.let { setDelay(it) }
        }
    }

    private fun setDelay(minutes: Int) {
        Prefs.iceDefaultDelay = minutes
        IceWatch.sync()
    }

    private fun setMode(mode: IceMode) {
        if (Prefs.iceDefaultMode == mode) return
        Prefs.iceDefaultMode = mode
        paintMode()
        // Apps that follow the default and are frozen the other way go over to it now.
        App.scope.launch { runCatching { Freezer.refreeze("режим по умолчанию изменён") } }
    }

    private fun paintMode() {
        val deep = Prefs.iceDefaultMode == IceMode.DEEP
        ui.softCard.isChecked = !deep
        ui.deepCard.isChecked = deep
        ui.softRadio.isChecked = !deep
        ui.deepRadio.isChecked = deep
    }

    private fun row(r: RowSwitchBinding, title: Int, summary: Int, value: Boolean, set: (Boolean) -> Unit) {
        r.title.setText(title)
        r.summary.setText(summary)
        r.toggle.isChecked = value
        r.toggle.setOnCheckedChangeListener { _, on -> set(on) }
        r.root.setOnClickListener { r.toggle.toggle() }
    }
}
