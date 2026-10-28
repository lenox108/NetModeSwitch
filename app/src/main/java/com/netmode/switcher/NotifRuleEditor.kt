package com.netmode.switcher

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Every entry point uses the same editor and exactly the same matcher as live hiding. */
internal object NotifRuleEditor {
    fun show(activity: AppCompatActivity, rule: HideRule, remove: (() -> Unit)?, save: (HideRule) -> Unit) {
        val builder = MaterialAlertDialogBuilder(activity)
        val context = builder.context
        val dp = activity.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        fun hint(res: Int) = TextView(context).also {
            it.setText(res)
            it.setTextAppearance(R.style.NMS_Hint)
            content.addView(it)
        }
        fun field(res: Int, value: String, multiline: Boolean = false): TextInputEditText {
            val box = TextInputLayout(context).apply {
                hint = activity.getString(res)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * dp).toInt() }
            }
            val edit = TextInputEditText(context).apply {
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
        val ongoing = MaterialSwitch(context).apply {
            setText(R.string.hide_only_ongoing)
            isChecked = rule.ongoingOnly
        }.also { content.addView(it) }
        val exclusions = field(R.string.hide_exclusions, rule.exclusions.joinToString("\n"), true)
        hint(R.string.hide_exclusions_hint)
        hint(R.string.hide_preview_hint)
        val enabled = MaterialSwitch(context).apply {
            setText(R.string.hide_rule_enabled)
            isChecked = rule.enabled
        }.also { content.addView(it) }
        val preview = TextView(context).apply {
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
        // The form gets the remaining height; actions are always a single fixed row.
        // Material's standard button bar can stack and scroll on narrow screens.
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = DialogActionLayout(context).apply {
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        body.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val appName = AppPickerActivity.labels(listOf(rule.app)).first()
        val dialog = builder
            .setTitle(appName)
            .setView(body)
            .create()
        fun button(id: Int) = MaterialButton(context, null, androidx.appcompat.R.attr.buttonBarPositiveButtonStyle).apply {
            this.id = id
            minWidth = 0
            minimumWidth = 0
            minimumHeight = (48 * dp).toInt()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(false)
            maxLines = 2
            ellipsize = null
            insetTop = 0
            insetBottom = 0
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        }
        if (remove != null) {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), 0)
            }
            header.addView(TextView(context).apply {
                text = appName
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(button(android.R.id.button3).apply {
                setIconResource(R.drawable.ic_delete)
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                contentDescription = activity.getString(R.string.hide_remove_rule)
                tooltipText = contentDescription
                setOnClickListener { remove(); dialog.dismiss() }
            }, LinearLayout.LayoutParams((48 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setCustomTitle(header)
        }
        actions.addView(button(android.R.id.button1).apply {
            setText(R.string.save)
            contentDescription = activity.getString(R.string.hide_save_rule)
            setOnClickListener { save(draft()); dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
        actions.addView(button(android.R.id.button2).apply {
            setText(R.string.cancel)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dialog.show()
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
