package com.netmode.switcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.netmode.switcher.databinding.RowIceBinding

/** Compact rows show the observed freeze mode, including when their action buttons are hidden. */
object IceModeIndicator {
    fun bind(row: RowIceBinding, app: IceApp, compact: Boolean) {
        val ctx = row.root.context
        val mode = app.frozenAs
        val compactFrozen = compact && mode != null
        row.toggle.iconSize = dp(ctx, if (compactFrozen) 36 else 22)
        row.toggle.icon = if (compactFrozen) snowflake(ctx, mode!!, 36, 22)
            else AppCompatResources.getDrawable(ctx, R.drawable.ic_ice)
        row.toggle.iconTint = if (compactFrozen) null else ColorStateList.valueOf(MaterialColors.getColor(row.root,
            if (app.frozen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
        row.toggle.backgroundTintList = ColorStateList.valueOf(
            if (!compact && app.frozen) MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorSecondaryContainer)
            else Color.TRANSPARENT)
        TooltipCompat.setTooltipText(row.toggle, if (compact) IceStatus.text(ctx, app) else null)

        // Narrow columns and selection mode have no action buttons. Keep a small, non-clickable indicator.
        val showMode = compactFrozen && row.toggle.visibility != View.VISIBLE
        row.freezeMode.visibility = if (showMode) View.VISIBLE else View.GONE
        row.freezeMode.setImageDrawable(if (showMode) snowflake(ctx, mode!!, 24, 16) else null)
    }

    private fun snowflake(ctx: Context, mode: IceMode, diameterDp: Int, iconDp: Int): Drawable {
        val soft = mode == IceMode.SOFT
        val foreground = ContextCompat.getColor(ctx, if (soft) R.color.ice_soft_foreground else R.color.ice_deep_foreground)
        val background = ContextCompat.getColor(ctx, if (soft) R.color.ice_soft_container else R.color.ice_deep_container)
        val diameter = dp(ctx, diameterDp)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(diameter, diameter)
            setColor(background)
            if (soft) setStroke(dp(ctx, 1).coerceAtLeast(1), foreground)
        }
        val icon = requireNotNull(AppCompatResources.getDrawable(ctx, R.drawable.ic_ice)).mutate().apply {
            setTint(foreground)
        }
        val inset = (diameter - dp(ctx, iconDp)) / 2
        // MaterialButton clears its icon tint. Keep the two colours of this composed icon intact.
        return object : LayerDrawable(arrayOf(circle, icon)) {
            override fun setTintList(tint: ColorStateList?) = Unit
        }.apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    private fun dp(ctx: Context, value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
}
