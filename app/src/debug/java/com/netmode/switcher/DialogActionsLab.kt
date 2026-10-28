package com.netmode.switcher

import android.app.TimePickerDialog
import android.content.DialogInterface
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only visual/callback audit using every label combination from the app's dialogs. */
class DialogActionsLab : AppCompatActivity() {
    private val results = JSONArray()
    private lateinit var status: TextView
    private fun cases(): List<List<Pair<Int, String>>> = listOf(
        listOf(DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_NEUTRAL to getString(R.string.ice_reorder), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to "Сохранить файл", DialogInterface.BUTTON_NEUTRAL to "Поделиться", DialogInterface.BUTTON_NEGATIVE to getString(android.R.string.ok)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.backup_import), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.check_start), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.check_start), DialogInterface.BUTTON_NEGATIVE to getString(R.string.check_offer_later)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.delete), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.done_btn), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.hide_block_do), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.hide_remove_rule), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.hide_unblock), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.ice_open), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.ice_remove), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.ice_view_done), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.log_clear), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.return_selected), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.save), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.save), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel), DialogInterface.BUTTON_NEUTRAL to "Удалить"),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.w_cfg_save), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.w_cfg_save), DialogInterface.BUTTON_NEUTRAL to getString(R.string.pair_default)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.w_cfg_save), DialogInterface.BUTTON_NEUTRAL to getString(R.string.w_opacity_all), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.wake_forget), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(android.R.string.ok)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(android.R.string.ok), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.api_copy), DialogInterface.BUTTON_NEUTRAL to getString(R.string.api_new_key), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.open_settings), DialogInterface.BUTTON_NEGATIVE to getString(R.string.later)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.save_anyway), DialogInterface.BUTTON_NEGATIVE to getString(R.string.cancel)),
        listOf(DialogInterface.BUTTON_POSITIVE to getString(R.string.w_cfg_save))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getBooleanExtra("translucent", false)) setTheme(R.style.Theme_NetModeSwitch_Translucent)
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Checking dialog actions…" }
        setContentView(status)
        if (intent.getBooleanExtra("time", false)) {
            TimePickerDialog(this, { _, _, _ -> status.text = "Time selected" }, 12, 30, true).show()
            return
        }
        if (intent.getBooleanExtra("validation", false)) {
            val edit = EditText(this).apply { hint = "Имя"; setSingleLine() }
            val dialog = MaterialAlertDialogBuilder(this).setTitle("Имя сочетания").setView(edit)
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
            dialog.setOnShowListener {
                val save = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                save.isEnabled = false
                edit.doAfterTextChanged { save.isEnabled = !it.isNullOrBlank() }
                save.setOnClickListener { status.text = "Validated: ${edit.text}"; dialog.dismiss() }
                edit.requestFocus()
                edit.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }, 200)
            }
            dialog.show()
            return
        }
        if (intent.hasExtra("preview")) show(intent.getIntExtra("preview", 0), intent.getIntExtra("body", 0), false)
        else show(0, 0, true)
    }

    private fun show(index: Int, body: Int, automatic: Boolean) {
        val cases = cases()
        if (index >= cases.size) {
            status.text = "Done: ${results.length()}"
            File(filesDir, "dialog-actions-results.json").writeText(results.toString(2))
            return
        }
        val builder = MaterialAlertDialogBuilder(this).setTitle("Проверка кнопок")
        val text = (1..60).joinToString("\n") { "Строка $it: длинное содержимое окна" }
        when (body) {
            0 -> builder.setMessage(text)
            1 -> builder.setSingleChoiceItems((1..60).map { "Вариант $it" }.toTypedArray(), 0) { _, _ -> }
            else -> builder.setView(ScrollView(builder.context).apply {
                addView(TextView(builder.context).apply { this.text = text })
            })
        }
        var clicked = 0
        for ((role, label) in cases[index]) {
            val listener = DialogInterface.OnClickListener { _, which -> clicked = which }
            when (role) {
                DialogInterface.BUTTON_POSITIVE -> builder.setPositiveButton(label, listener)
                DialogInterface.BUTTON_NEGATIVE -> builder.setNegativeButton(label, listener)
                DialogInterface.BUTTON_NEUTRAL -> builder.setNeutralButton(label, listener)
            }
        }
        val dialog = builder.show()
        if (!automatic) return
        dialog.window!!.decorView.postDelayed({
            val item = JSONObject().put("case", index).put("body", body)
            try {
                val buttons = cases[index].map { dialog.getButton(it.first) }
                val positions = buttons.map { b ->
                    val rect = Rect()
                    check(b.getGlobalVisibleRect(rect) && rect.width() == b.width && rect.height() == b.height) { "Clipped button: ${b.text}, visible=$rect, size=${b.width}x${b.height}" }
                    var parent = b.parent
                    while (parent is View) {
                        check(parent !is ScrollView && parent !is NestedScrollView) { "Scrollable action ancestor" }
                        parent = parent.parent
                    }
                    check(b.parent is DialogActionLayout) { "Standard action panel was not replaced" }
                    val layout = b.layout
                    check(layout != null && layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == b.text.length) { "Incomplete label: ${b.text}" }
                    check((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }) { "Ellipsized label" }
                    check(layout.height <= b.height - b.compoundPaddingTop - b.compoundPaddingBottom) { "Label height clipped" }
                    val location = IntArray(2); b.getLocationOnScreen(location)
                    JSONObject().put("text", b.text).put("x", location[0]).put("y", location[1])
                        .put("width", b.width).put("height", b.height).put("lines", layout.lineCount)
                }
                val top = positions.maxOf { it.getInt("y") }
                val bottom = positions.minOf { it.getInt("y") + it.getInt("height") }
                check(top < bottom) { "Actions are stacked" }
                item.put("buttons", JSONArray(positions))
                fun scroll(view: View) {
                    when (view) {
                        is android.widget.ListView -> view.setSelection(35)
                        is ScrollView -> view.scrollTo(0, 900)
                        is NestedScrollView -> view.scrollTo(0, 900)
                        is ViewGroup -> for (i in 0 until view.childCount) scroll(view.getChildAt(i))
                    }
                }
                scroll(dialog.window!!.decorView)
                buttons.forEachIndexed { i, b ->
                    val location = IntArray(2); b.getLocationOnScreen(location)
                    check(location[0] == positions[i].getInt("x") && location[1] == positions[i].getInt("y")) { "Actions moved with content" }
                }
                val role = cases[index][body % cases[index].size].first
                dialog.getButton(role).performClick()
                dialog.window!!.decorView.post {
                    item.put("callback_ok", clicked == role).put("dismissed", !dialog.isShowing)
                    results.put(item)
                    show(if (body == 2) index + 1 else index, (body + 1) % 3, true)
                }
            } catch (e: Exception) {
                item.put("error", e.toString())
                results.put(item)
                dialog.dismiss()
                show(if (body == 2) index + 1 else index, (body + 1) % 3, true)
            }
        }, 150)
    }
}
