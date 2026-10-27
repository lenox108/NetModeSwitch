package com.netmode.switcher

import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Every entry point uses the same editor and exactly the same matcher as live hiding. */
internal object NotifRuleEditor {
    fun show(activity: AppCompatActivity, rule: HideRule, remove: (() -> Unit)?, save: (HideRule) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        fun hint(res: Int) = TextView(activity).also {
            it.setText(res)
            it.setTextAppearance(R.style.NMS_Hint)
            content.addView(it)
        }
        fun field(res: Int, value: String, multiline: Boolean = false): TextInputEditText {
            val box = TextInputLayout(activity).apply {
                hint = activity.getString(res)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * dp).toInt() }
            }
            val edit = TextInputEditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                maxLines = if (multiline) 5 else 1
                setText(value)
            }
            box.addView(edit)
            content.addView(box)
            return edit
        }
        val channel = field(R.string.hide_edit_channel, rule.channel.orEmpty())
        val words = field(R.string.hide_title_words, rule.title.orEmpty())
        hint(R.string.hide_match_hint)
        val ongoing = MaterialSwitch(activity).apply {
            setText(R.string.hide_only_ongoing)
            isChecked = rule.ongoingOnly
        }.also { content.addView(it) }
        val exclusions = field(R.string.hide_exclusions, rule.exclusions.joinToString("\n"), true)
        hint(R.string.hide_exclusions_hint)
        hint(R.string.hide_preview_hint)
        val enabled = MaterialSwitch(activity).apply {
            setText(R.string.hide_rule_enabled)
            isChecked = rule.enabled
        }.also { content.addView(it) }
        val preview = TextView(activity).apply {
            setTextAppearance(R.style.NMS_Hint)
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }.also { content.addView(it) }
        fun draft() = rule.copy(channel = channel.text?.toString()?.trim()?.ifEmpty { null },
            title = words.text?.toString()?.trim()?.ifEmpty { null }, enabled = enabled.isChecked,
            ongoingOnly = ongoing.isChecked, exclusions = NotifRulePolicy.exclusions(exclusions.text?.toString().orEmpty()))
        fun updatePreview() {
            if (!NotifWatch.connected) {
                preview.setText(R.string.hide_preview_no_access)
                return
            }
            val next = draft().copy(enabled = true, pausedUntil = 0)
            val matched = NotifHide.shade().filter { next.matches(it) }
            preview.text = activity.getString(R.string.hide_preview_count, matched.size) +
                matched.take(8).joinToString("", prefix = "\n") { "• ${it.displayedTitle.ifBlank { activity.getString(R.string.hide_untitled) }}\n" } +
                if (matched.size > 8) activity.getString(R.string.hide_preview_more, matched.size - 8) else ""
        }
        listOf(channel, words, exclusions).forEach { it.doAfterTextChanged { updatePreview() } }
        ongoing.setOnCheckedChangeListener { _, _ -> updatePreview() }
        enabled.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(AppPickerActivity.labels(listOf(rule.app)).first())
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.hide_save_rule) { _, _ -> save(draft()) }
            .setNegativeButton(R.string.cancel, null)
        if (remove != null) dialog.setNeutralButton(R.string.hide_remove_rule) { _, _ -> remove() }
        dialog.show()
    }
}
