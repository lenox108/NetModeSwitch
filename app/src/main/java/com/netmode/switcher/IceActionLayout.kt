package com.netmode.switcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Equal compact actions, stacked only when their full labels cannot fit side by side. */
class IceActionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val buttons = (0 until childCount).map { getChildAt(it) }
            .filterIsInstance<MaterialButton>().filter { it.visibility != View.GONE }
        if (buttons.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val gap = (8 * resources.displayMetrics.density).roundToInt()
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val cellWidth = (width - gap * (buttons.size - 1)) / buttons.size
            val stacked = buttons.any { b ->
                val label = b.transformationMethod?.getTransformation(b.text, b) ?: b.text
                val iconWidth = if (b.icon == null) 0 else b.iconSize + b.iconPadding
                ceil(b.paint.measureText(label.toString())).toInt() +
                    b.paddingLeft + b.paddingRight + iconWidth > cellWidth
            }
            orientation = if (stacked) VERTICAL else HORIZONTAL
            buttons.forEachIndexed { index, b ->
                (b.layoutParams as LayoutParams).apply {
                    this.width = if (stacked) LayoutParams.MATCH_PARENT else 0
                    height = LayoutParams.WRAP_CONTENT
                    weight = if (stacked) 0f else 1f
                    marginStart = if (!stacked && index > 0) gap else 0
                    marginEnd = 0
                    topMargin = if (stacked && index > 0) gap else 0
                    bottomMargin = 0
                    // Resolve the new relative margins before LinearLayout measures this pass.
                    resolveLayoutDirection(this@IceActionLayout.layoutDirection)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
