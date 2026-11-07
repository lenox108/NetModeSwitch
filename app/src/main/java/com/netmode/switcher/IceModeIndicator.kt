package com.netmode.switcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.netmode.switcher.databinding.RowIceBinding

/** Rows and icons show the observed freeze mode: soft and deep snowflakes differ in colour. */
object IceModeIndicator {
    fun bind(row: RowIceBinding, app: IceApp, compact: Boolean, status: String) {
        val ctx = row.root.context
        val mode = app.frozenAs
        val compactFrozen = compact && mode != null
        // A recycled row usually looks the same already; its icons are costly to build again.
        val look = Triple(compact, app.frozen, mode)
        val changed = row.toggle.tag != look
        if (changed) {
            row.toggle.tag = look
            row.toggle.iconSize = dp(ctx, if (compactFrozen) 36 else 22)
            row.toggle.icon = if (compactFrozen) snowflake(ctx, mode!!, 36, 22)
                else AppCompatResources.getDrawable(ctx, R.drawable.ic_ice)
            row.toggle.iconTint = if (compactFrozen) null else ColorStateList.valueOf(MaterialColors.getColor(row.root,
                if (app.frozen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            row.toggle.backgroundTintList = ColorStateList.valueOf(
                if (!compact && app.frozen) MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorSecondaryContainer)
                else Color.TRANSPARENT)
        }
        TooltipCompat.setTooltipText(row.toggle, if (compact) status else null)

        // Narrow columns and selection mode have no action buttons. Keep a small, non-clickable indicator.
        val showMode = compactFrozen && row.toggle.visibility != View.VISIBLE
        row.freezeMode.visibility = if (showMode) View.VISIBLE else View.GONE
        if (!showMode) row.freezeMode.setImageDrawable(null)
        else if (changed || row.freezeMode.drawable == null) row.freezeMode.setImageDrawable(snowflake(ctx, mode!!, 24, 16))
    }

    /** A non-clickable marker for rows without action buttons (the launcher folder's lists). */
    fun bindMarker(view: ImageView, app: IceApp, diameterDp: Int) {
        val mode = app.frozenAs
        view.visibility = if (mode != null) View.VISIBLE else View.GONE
        if (mode == null) {
            view.tag = null
            view.setImageDrawable(null)
            return
        }
        val look = mode to diameterDp
        if (view.tag == look && view.drawable != null) return
        view.tag = look
        val size = dp(view.context, diameterDp)
        val params = view.layoutParams
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            view.layoutParams = params
        }
        view.setImageDrawable(snowflake(view.context, mode, diameterDp, diameterDp * 2 / 3))
    }

    /** The snowflake in the corner of a frozen app's icon; a ring keeps it apart from the icon. */
    fun bindBadge(badge: ImageView, app: IceApp) {
        val mode = app.frozenAs
        badge.visibility = if (mode != null) View.VISIBLE else View.GONE
        if (mode == null || (badge.tag == mode && badge.drawable != null)) return
        badge.tag = mode
        val ctx = badge.context
        badge.background = null
        badge.setPadding(0, 0, 0, 0)
        badge.setImageDrawable(snowflake(ctx, mode, 22, 14,
            ring = MaterialColors.getColor(badge, com.google.android.material.R.attr.colorSurface)))
    }

    /** [ring]: an outer edge in this colour; soft snowflakes otherwise keep their own outline. */
    private fun snowflake(ctx: Context, mode: IceMode, diameterDp: Int, iconDp: Int, ring: Int? = null): Drawable {
        val soft = mode == IceMode.SOFT
        val foreground = ContextCompat.getColor(ctx, if (soft) R.color.ice_soft_foreground else R.color.ice_deep_foreground)
        val background = ContextCompat.getColor(ctx, if (soft) R.color.ice_soft_container else R.color.ice_deep_container)
        val diameter = dp(ctx, diameterDp)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(diameter, diameter)
            setColor(background)
            if (ring != null && !soft) setStroke((dp(ctx, 3) / 2).coerceAtLeast(1), ring)
            else if (soft) setStroke(dp(ctx, 1).coerceAtLeast(1), foreground)
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
