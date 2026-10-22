package com.netmode.switcher

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.netmode.switcher.databinding.ActivityLogBinding

/** Every event the app recorded, newest first; the main screen shows only the last few. */
class LogActivity : AppCompatActivity() {

    private lateinit var ui: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityLogBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.toolbar.menu.add(0, 1, 0, R.string.log_copy)
        ui.toolbar.menu.add(0, 2, 1, R.string.log_clear)
        ui.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("NetModeSwitch", Prefs.events.joinToString("\n")))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                2 -> MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.log_clear_confirm)
                    .setPositiveButton(R.string.log_clear) { _, _ -> Prefs.clearLog(); render() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        render()
    }

    private fun render() {
        val events = Prefs.events
        ui.text.text = if (events.isEmpty()) getString(R.string.no_events) else events.joinToString("\n\n")
    }
}
