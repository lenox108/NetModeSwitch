package com.netmode.switcher

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityCheckBinding
import kotlinx.coroutines.launch

/** The phone check: runs [Calibration] step by step and shows what works here. */
class CheckActivity : AppCompatActivity() {

    private lateinit var ui: ActivityCheckBinding
    private val rows = linkedMapOf<String, Pair<View, (CheckState?, String) -> Unit>>()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityCheckBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { if (!running) finish() }
        ui.start.setOnClickListener { confirm() }
        ui.share.setOnClickListener { share() }
        showSaved()
        if (intent.getBooleanExtra(EXTRA_START, false) && savedInstanceState == null) confirm()
    }

    @Deprecated("Back is blocked while modes are being switched")
    override fun onBackPressed() {
        if (!running) super.onBackPressed()
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    /** A row: state mark (a spinner while running), title, note under it. */
    private fun row(key: String, title: String): (CheckState?, String) -> Unit {
        rows[key]?.let { return it.second }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val mark = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val spinner = ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) } }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t = TextView(this, null, 0, R.style.NMS_RowLabel).apply {
            text = title
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 14f
        }
        val n = TextView(this, null, 0, R.style.NMS_Hint)
        texts.addView(t)
        texts.addView(n)
        line.addView(spinner)
        line.addView(mark)
        line.addView(texts)
        ui.steps.addView(line)
        val update: (CheckState?, String) -> Unit = { state, note ->
            spinner.visibility = if (state == null) View.VISIBLE else View.GONE
            mark.visibility = if (state == null) View.GONE else View.VISIBLE
            mark.text = state?.mark.orEmpty()
            n.text = note
            n.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        }
        // What the user can fix leads straight to where it is fixed.
        if (key in FIXABLE) line.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        rows[key] = line to update
        return update
    }

    /** The last check, if there was one. */
    private fun showSaved() {
        val p = Profile.current
        ui.share.isEnabled = p != null
        if (p == null) {
            ui.summary.setText(R.string.check_never)
            ui.start.setText(R.string.check_start)
            return
        }
        ui.start.setText(R.string.check_again)
        ui.steps.removeAllViews()
        rows.clear()
        row("device", getString(R.string.check_device))(CheckState.OK, "${p.device} · ${p.rom} · Android ${p.android}")
        row("access", getString(R.string.check_access))(if (p.backend == "нет") CheckState.FAIL else CheckState.OK, p.backend)
        row("method", getString(R.string.check_method))(if (p.method != null) CheckState.OK else CheckState.FAIL,
            p.method?.label ?: getString(R.string.check_method_none))
        for ((slot, m) in p.modes.toSortedMap()) for ((mode, r) in m) {
            row("mode${slot}_${mode.name}", "SIM ${slot + 1}: ${mode.label}")(r.state, r.note)
        }
        p.checks.forEachIndexed { i, c -> row(FIX_TITLES[c.title] ?: "c$i", c.title)(c.state, c.note) }
        ui.summary.text = summary(p)
    }

    private fun summary(p: DeviceProfile): String {
        val modes = p.modes.values.firstOrNull()
        val okModes = modes?.count { it.value.state == CheckState.OK } ?: 0
        val problems = p.checks.count { it.state == CheckState.FAIL }
        return getString(R.string.check_summary,
            android.text.format.DateFormat.format("dd.MM HH:mm", p.at),
            okModes, modes?.size ?: 0, problems)
    }

    private fun confirm() {
        if (running) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.check_title)
            .setMessage(R.string.check_confirm)
            .setPositiveButton(R.string.check_start) { _, _ -> start() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun start() {
        running = true
        ui.start.isEnabled = false
        ui.share.isEnabled = false
        ui.summary.setText(R.string.check_running)
        ui.steps.removeAllViews()
        rows.clear()
        // The check takes a couple of minutes; the screen staying on keeps it moving.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val p = runCatching {
                Calibration.run { key, title, state, note ->
                    runOnUiThread {
                        row(key, title)(state, note)
                        if (state == null) ui.scroll.post { ui.scroll.smoothScrollTo(0, ui.steps.bottom) }
                    }
                }
            }.getOrNull()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            running = false
            ui.start.isEnabled = true
            ui.start.setText(R.string.check_again)
            ui.share.isEnabled = p != null
            ui.summary.text = p?.let { summary(it) } ?: getString(R.string.check_failed)
            ui.scroll.post { ui.scroll.smoothScrollTo(0, 0) }
        }
    }

    private fun share() {
        val text = Profile.current?.report() ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            getString(R.string.check_share),
        ))
    }

    companion object {
        const val EXTRA_START = "start"
        private val FIXABLE = setOf("alarms", "battery", "bt", "usage", "notify", "access")
        /** Saved checks keep only their titles; these map back to the fixable keys. */
        private val FIX_TITLES = mapOf(
            "Точные будильники (правила по времени)" to "alarms", "Работа в фоне" to "battery",
            "Bluetooth-устройства" to "bt", "Открытое приложение (условие «Приложение»)" to "usage",
            "Уведомление с режимами SIM" to "notify",
        )
    }
}
