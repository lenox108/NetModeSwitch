package com.netmode.switcher

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Dialog actions stay visible in one row; long labels wrap within their own button. */
class DialogActionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        (child as? Button)?.apply {
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(48)
            isAllCaps = false
            textSize = 14f
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val buttons = (0 until childCount).map { getChildAt(it) }
            .filterIsInstance<Button>().filter { it.visibility != View.GONE }
        if (buttons.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val natural = buttons.map { b ->
                val label = b.transformationMethod?.getTransformation(b.text, b) ?: b.text
                (ceil(b.paint.measureText(label.toString())).toInt() + b.compoundPaddingLeft + b.compoundPaddingRight)
                    .coerceAtLeast(dp(48))
            }
            val widths = natural.toMutableList()
            if (natural.sum() > available) {
                var remaining = available
                val pending = buttons.indices.toMutableSet()
                // Preserve short labels (OK/Cancel); longer labels share the remaining width.
                while (pending.isNotEmpty()) {
                    val share = remaining / pending.size
                    val fitting = pending.filter { natural[it] <= share }
                    if (fitting.isEmpty()) {
                        pending.forEach { index ->
                            widths[index] = remaining / pending.size
                        }
                        widths[pending.last()] += remaining % pending.size
                        break
                    }
                    fitting.forEach { index -> remaining -= natural[index]; pending.remove(index) }
                }
            }
            buttons.forEachIndexed { index, b ->
                (b.layoutParams as LayoutParams).apply {
                    width = widths[index]
                    height = LayoutParams.WRAP_CONTENT
                    weight = 0f
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(0, 0, 0, 0)
                    marginStart = 0
                    marginEnd = 0
                    resolveLayoutDirection(this@DialogActionLayout.layoutDirection)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // AlertDialogLayout reserves this height before giving the message/list the rest.
    // Returning the complete measured row also protects multi-line labels in short windows.
    override fun getMinimumHeight(): Int = maxOf(super.getMinimumHeight(), measuredHeight)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
