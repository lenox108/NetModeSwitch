package com.netmode.switcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.netmode.switcher.databinding.ActivitySmsCodeBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Коды из СМС": the switch, the notification fallback, exclusions and a try-it box. */
class SmsCodeActivity : AppCompatActivity() {

    private lateinit var ui: ActivitySmsCodeBinding

    /** True while [load] sets the switches, so that is not taken for the user's changes. */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySmsCodeBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }

        ui.smsCodeRow.title.setText(R.string.sms_code_switch)
        ui.smsNotifRow.title.setText(R.string.sms_code_notif_switch)
        // While an option is on but cannot work, a tap on its row fixes that instead of switching it off.
        ui.smsCodeRow.root.setOnClickListener {
            if (Prefs.smsCodeEnabled && !SmsCodes.smsGranted(this)) ensureSms() else ui.smsCodeRow.toggle.toggle()
        }
        ui.smsNotifRow.root.setOnClickListener {
            if (Prefs.smsCodeFromNotif && !notifReady()) ensureListener() else ui.smsNotifRow.toggle.toggle()
        }
        ui.smsCodeRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.smsCodeEnabled = on
            SmsCodes.applyReceiver(this)
            if (on) ensureSms()
            paint()
        }
        ui.smsNotifRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.smsCodeFromNotif = on
            if (on) ensureListener()
            paint()
        }
        ui.smsClear.onChange = { Prefs.smsCodeClearSec = SmsCodes.CLEAR_CHOICES[it] }
        ui.smsPasteRow.title.setText(R.string.sms_code_paste_switch)
        ui.smsPasteRow.root.setOnClickListener {
            if (Prefs.smsCodeClearOnPaste && !AppWatchService.connected) ensureAccessibility() else ui.smsPasteRow.toggle.toggle()
        }
        ui.smsPasteRow.toggle.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.smsCodeClearOnPaste = on
            if (on) ensureAccessibility()
            paint()
        }
        ui.smsExcludeEntry.title.setText(R.string.sms_code_exclude)
        ui.smsExcludeEntry.hint.setText(R.string.sms_code_exclude_hint)
        ui.smsExcludeEntry.root.setOnClickListener { editExclusions() }
        ui.smsTestEntry.title.setText(R.string.sms_code_test)
        ui.smsTestEntry.hint.setText(R.string.sms_code_test_hint)
        ui.smsTestEntry.state.text = "›"
        ui.smsTestEntry.root.setOnClickListener { testParser() }
        load()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun load() {
        loading = true
        ui.smsCodeRow.toggle.isChecked = Prefs.smsCodeEnabled
        ui.smsNotifRow.toggle.isChecked = Prefs.smsCodeFromNotif
        ui.smsPasteRow.toggle.isChecked = Prefs.smsCodeClearOnPaste
        ui.smsClear.setItems(SmsCodes.CLEAR_CHOICES.map(::clearLabel),
            SmsCodes.CLEAR_CHOICES.indexOf(Prefs.smsCodeClearSec).coerceAtLeast(0))
        loading = false
        paint()
    }

    private fun clearLabel(sec: Int): String = when {
        sec == 0 -> getString(R.string.sms_code_clear_never)
        sec < 60 -> getString(R.string.sms_code_clear_sec, sec)
        else -> getString(R.string.sms_code_clear_min, sec / 60)
    }

    /** Bound, and allowed to see the texts Android hides from listeners when they hold a code. */
    private fun notifReady() = NotifWatch.connected && SmsCodes.sensitiveAllowed(this)

    private fun paint() {
        val on = Prefs.smsCodeEnabled
        val granted = SmsCodes.smsGranted(this)
        summary(ui.smsCodeRow.summary, on && !granted, R.string.sms_code_summary_no_perm, R.string.sms_code_summary)
        val notifBad = Prefs.smsCodeFromNotif && !notifReady()
        summary(ui.smsNotifRow.summary, notifBad, R.string.sms_code_notif_summary_off, R.string.sms_code_notif_summary)
        ui.smsCodeBlock.alpha = if (on) 1f else 0.5f
        ui.smsNotifRow.root.isEnabled = on
        ui.smsNotifRow.toggle.isEnabled = on
        ui.smsClear.isEnabled = on
        ui.smsPasteRow.root.isEnabled = on
        ui.smsPasteRow.toggle.isEnabled = on
        summary(ui.smsPasteRow.summary, Prefs.smsCodeClearOnPaste && !AppWatchService.connected,
            R.string.sms_code_paste_summary_off, R.string.sms_code_paste_summary)
        val n = Prefs.smsCodeExclude.size
        ui.smsExcludeEntry.state.text = if (n == 0) getString(R.string.sms_code_exclude_none) else n.toString()
    }

    private fun summary(v: android.widget.TextView, bad: Boolean, badText: Int, okText: Int) {
        v.setText(if (bad) badText else okText)
        v.setTextColor(MaterialColors.getColor(v,
            if (bad) android.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** Root or Shizuku grant it quietly; without them, the system dialog. */
    private fun ensureSms() {
        lifecycleScope.launch {
            if (!SmsCodes.grantPrivileged(this@SmsCodeActivity)) {
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQ_SMS)
            } else if (!Compat.granted(this@SmsCodeActivity, Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
            paint()
        }
    }

    private fun ensureListener() {
        lifecycleScope.launch {
            if (!NotifWatch.connected) {
                NotifWatch.enable()
                delay(1500)
            }
            if (!NotifWatch.connected) {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            } else if (!SmsCodes.allowSensitive(this@SmsCodeActivity)) {
                toast(getString(R.string.sms_code_sensitive_failed))
            }
            delay(1500)
            paint()
        }
    }

    /** The app's accessibility service hears the paste; root or Shizuku switch it on, else its settings page. */
    private fun ensureAccessibility() {
        lifecycleScope.launch {
            if (!AppWatchService.connected) {
                runCatching { AppWatchService.enable(true) }
                delay(1500)
            }
            if (!AppWatchService.connected) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            paint()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SMS) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // A sideloaded app may get no dialog for SMS until restricted settings are allowed.
            MaterialAlertDialogBuilder(this).setTitle(R.string.sec_sms_code).setMessage(R.string.sms_code_denied)
                .setPositiveButton(R.string.sms_code_open_app_info) { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton(R.string.cancel, null).show()
        } else if (!Compat.granted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        paint()
    }

    private fun textBox(text: String, lines: Int): Pair<FrameLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            setText(text)
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        }
        val pad = (resources.displayMetrics.density * 24).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        return box to input
    }

    private fun editExclusions() {
        val (box, input) = textBox(Prefs.smsCodeExclude.joinToString("\n"), 4)
        MaterialAlertDialogBuilder(this).setTitle(R.string.sms_code_exclude).setMessage(R.string.sms_code_exclude_dialog)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.smsCodeExclude = input.text?.toString().orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                paint()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun testParser() {
        val (box, input) = textBox("", 3)
        MaterialAlertDialogBuilder(this).setTitle(R.string.sms_code_test).setMessage(R.string.sms_code_test_hint)
            .setView(box)
            .setPositiveButton(R.string.sms_code_test) { _, _ ->
                val code = SmsCodeParser.find(input.text?.toString())
                toast(if (code != null) getString(R.string.sms_code_test_found, code) else getString(R.string.sms_code_test_none))
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_SMS = 11
        const val REQ_NOTIFY = 12
    }
}
