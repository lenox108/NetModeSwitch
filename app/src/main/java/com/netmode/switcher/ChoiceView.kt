package com.netmode.switcher

import android.content.Context
import android.util.AttributeSet
import android.widget.PopupMenu
import com.google.android.material.button.MaterialButton

/**
 * A compact "value ▾" picker for the rule dialog, in place of Spinner: Spinner's arrow
 * drawables leaked outside the dialog's scroll area and showed up as stray arrows next to
 * the Save button. Mirrors the bits of the Spinner API the dialog uses.
 */
class ChoiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.borderlessButtonStyle,
) : MaterialButton(context, attrs, defStyle) {

    private var labels: List<String> = emptyList()

    var selectedItemPosition: Int = 0
        private set

    /** Called after the user picks a value (not when set from code). */
    var onChange: ((Int) -> Unit)? = null

    init {
        isAllCaps = false
        minWidth = 0
        setPaddingRelative(24, paddingTop, 8, paddingBottom)
        setOnClickListener {
            val menu = PopupMenu(context, this)
            labels.forEachIndexed { i, l -> menu.menu.add(0, i, i, l) }
            menu.setOnMenuItemClickListener { item ->
                setSelection(item.itemId)
                onChange?.invoke(item.itemId)
                true
            }
            menu.show()
        }
    }

    fun setItems(items: List<String>, selected: Int) {
        labels = items
        setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }

    fun setSelection(position: Int) {
        selectedItemPosition = position
        text = labels.getOrNull(position)?.let { "$it  ▾" } ?: ""
    }
}
