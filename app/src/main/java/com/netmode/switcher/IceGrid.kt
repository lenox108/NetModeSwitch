package com.netmode.switcher

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.NumberPicker
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.CellIceBinding
import com.netmode.switcher.databinding.DialogIceViewBinding
import com.netmode.switcher.databinding.RowIceBinding
import kotlin.math.ceil

/** Shared layout settings for the freezer screen and its launcher folder. */
object IceGrid {
    enum class Look(val pref: String, val title: Int) {
        LIST("list", R.string.ice_view_list),
        COMPACT("compact", R.string.ice_view_compact),
        GRID("grid", R.string.ice_view_grid),
    }

    enum class Size(val pref: String, val title: Int, val icon: Int, val cell: Int, val text: Float, val row: Int, val compactRow: Int) {
        S("s", R.string.ice_size_s, 40, 68, 11f, 36, 28),
        M("m", R.string.ice_size_m, 52, 84, 12f, 44, 32),
        L("l", R.string.ice_size_l, 64, 100, 13f, 52, 40),
    }

    const val LIST_MAX_COLUMNS = 3
    const val MAX_COLUMNS = 8
    const val MAX_ROWS = 12
    val preferenceKeys = setOf("ice_view", "ice_columns", "ice_rows", "ice_icon_size", "ice_labels")
    val look: Look get() = Look.entries.firstOrNull { it.pref == Prefs.iceView } ?: Look.LIST
    val size: Size get() = Size.entries.firstOrNull { it.pref == Prefs.iceIconSize } ?: Size.M

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun settings(ctx: Context) {
        val ui = DialogIceViewBinding.inflate(LayoutInflater.from(ctx))
        fun choices(group: ChipGroup, options: List<Pair<String, Int>>, current: String) {
            options.forEach { (key, title) ->
                group.addView((LayoutInflater.from(ctx).inflate(R.layout.chip_choice, group, false) as Chip).apply {
                    id = View.generateViewId()
                    tag = key
                    setText(title)
                    isChecked = key == current
                })
            }
        }
        fun picker(p: NumberPicker, max: Int, value: Int) {
            p.minValue = 0
            p.maxValue = max
            p.displayedValues = (listOf(ctx.getString(R.string.ice_view_columns_auto)) + (1..max).map { it.toString() }).toTypedArray()
            p.wrapSelectorWheel = false
            p.value = value
        }
        choices(ui.kinds, Look.entries.map { it.pref to it.title }, look.pref)
        choices(ui.sizes, Size.entries.map { it.pref to it.title }, size.pref)
        picker(ui.columns, MAX_COLUMNS, Prefs.iceColumns)
        picker(ui.rows, MAX_ROWS, Prefs.iceRows)
        ui.labels.isChecked = Prefs.iceLabels
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ice_view)
            .setView(ui.root)
            .setPositiveButton(R.string.ice_view_done) { _, _ ->
                Prefs.iceView = ui.kinds.findViewById<Chip>(ui.kinds.checkedChipId).tag as String
                Prefs.iceColumns = ui.columns.value
                Prefs.iceRows = ui.rows.value
                Prefs.iceIconSize = ui.sizes.findViewById<Chip>(ui.sizes.checkedChipId).tag as String
                Prefs.iceLabels = ui.labels.isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun apply(grid: GridView, list: Boolean) {
        // Rows differ in height: a pixel-based scrollbar would jump as they pass.
        grid.isSmoothScrollbarEnabled = false
        val cols = Prefs.iceColumns
        grid.numColumns = when {
            list -> cols.coerceIn(1, LIST_MAX_COLUMNS)
            cols > 0 -> cols
            else -> {
                grid.columnWidth = dp(grid.context, size.cell - if (Prefs.iceLabels) 0 else 12)
                GridView.AUTO_FIT
            }
        }
    }

    /** Rebind heights after rotation, an inset change or the keyboard resizing the app area. */
    fun watchBounds(grid: GridView) {
        grid.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                grid.post { (grid.adapter as? BaseAdapter)?.notifyDataSetChanged() }
            }
        }
    }

    private fun height(grid: GridView, minimum: Int): Int {
        val rows = Prefs.iceRows
        if (rows == 0 || grid.height == 0) return ViewGroup.LayoutParams.WRAP_CONTENT
        return IceGridGeometry.rowHeight(grid.height - grid.paddingTop - grid.paddingBottom,
            rows, grid.verticalSpacing, minimum)
    }

    private fun width(grid: GridView): Int =
        ((grid.width - grid.paddingLeft - grid.paddingRight - (grid.numColumns.coerceAtLeast(1) - 1) * grid.horizontalSpacing) /
            grid.numColumns.coerceAtLeast(1)).coerceAtLeast(1)

    fun style(c: CellIceBinding, grid: GridView, app: IceApp, showState: Boolean = true) {
        val ctx = grid.context
        val s = size
        val labels = Prefs.iceLabels
        c.label.textSize = s.text
        c.label.visibility = if (labels) View.VISIBLE else View.GONE
        c.state.visibility = if (labels && showState) View.VISIBLE else View.GONE
        c.state.textSize = (s.text - 1f).coerceAtLeast(10f)
        // Text set on a hidden view still makes the recycled cell measure itself again.
        if (labels && showState) {
            c.state.setText(if (app.frozen) R.string.ice_cell_frozen else R.string.ice_cell_thawed)
            c.state.setTextColor(com.google.android.material.color.MaterialColors.getColor(c.root,
                if (app.frozen) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorTertiary))
        }
        val stateHeight = if (labels && showState) ceil(c.state.paint.fontSpacing.toDouble()).toInt() * 2 + dp(ctx, 2) else 0
        val line = ceil(c.label.paint.fontSpacing.toDouble()).toInt()
        val minHeight = dp(ctx, 40) + (if (labels) line + dp(ctx, 4) else 0) + stateHeight
        val h = height(grid, minHeight)
        val fixed = h > 0
        val pad = dp(ctx, if (fixed || !labels) 4 else 10)
        val room = if (fixed) h - pad * 2 - stateHeight - (if (labels) line + dp(ctx, 8) else 0) else Int.MAX_VALUE
        val icon = minOf(dp(ctx, s.icon), room - dp(ctx, 8),
            if (grid.width > 0) width(grid) - dp(ctx, 16) else dp(ctx, s.icon)).coerceAtLeast(dp(ctx, 16))
        c.iconBox.resize(icon + dp(ctx, 8), icon + dp(ctx, 8))
        c.icon.resize(icon, icon)
        val lines = if (fixed && h < icon + dp(ctx, 24) + line * 2 + stateHeight) 1 else 2
        if (c.label.maxLines != lines) c.label.maxLines = lines
        if (c.label.minLines != lines) c.label.minLines = lines
        c.root.gravity = Gravity.CENTER
        c.root.minHeight(minHeight)
        c.root.resize(c.root.layoutParams.width, h)
        c.root.setPadding(dp(ctx, 4), pad, dp(ctx, 4), pad)
    }

    /** Narrow list columns keep their actions in the app card and the selection toolbar. */
    fun style(row: RowIceBinding, grid: GridView, compact: Boolean, selecting: Boolean) {
        val ctx = grid.context
        val narrow = grid.numColumns > 1 && width(grid) < dp(ctx, 280)
        row.status.visibility = if (compact) View.GONE else View.VISIBLE
        val statusLines = if (narrow) 1 else 2
        if (row.status.maxLines != statusLines) row.status.maxLines = statusLines
        row.label.textSize = if (narrow) 13f else 16f
        val textHeight = ceil(row.label.paint.fontSpacing.toDouble()).toInt() +
            if (compact) 0 else ceil(row.status.paint.fontSpacing.toDouble()).toInt() * row.status.maxLines + dp(ctx, 2)
        val h = height(grid, maxOf(dp(ctx, if (compact) 48 else 64), textHeight + dp(ctx, 8)))
        val icon = dp(ctx, if (narrow) 28 else if (compact) size.compactRow else size.row)
        row.icon.resize(icon, icon)
        row.icon.visibility = if (narrow && selecting) View.GONE else View.VISIBLE
        val textParams = row.textBox.layoutParams as ViewGroup.MarginLayoutParams
        val textMargin = dp(ctx, if (narrow) 6 else 14)
        if (textParams.marginStart != textMargin) {
            textParams.marginStart = textMargin
            row.textBox.layoutParams = textParams
        }
        row.open.minHeight(dp(ctx, if (compact) 44 else 56))
        row.open.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        row.root.minHeight(dp(ctx, if (compact) 48 else 64))
        row.root.resize(row.root.layoutParams.width, h)
        row.root.setPadding(dp(ctx, if (narrow) 6 else 16), 0, dp(ctx, if (narrow) 4 else 8), 0)
        row.launch.visibility = if (selecting || narrow) View.GONE else View.VISIBLE
        row.toggle.visibility = if (selecting || narrow) View.GONE else View.VISIBLE
    }

    // These setters ask for a layout even when nothing changes; scrolling binds rows all the time.
    private fun View.resize(w: Int, h: Int) {
        val params = layoutParams
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        layoutParams = params
    }

    private fun View.minHeight(value: Int) {
        if (minimumHeight != value) minimumHeight = value
    }
}

/** The requested row count never makes a cell shorter than its contents and touch target. */
internal object IceGridGeometry {
    fun rowHeight(available: Int, rows: Int, spacing: Int, minimum: Int): Int =
        maxOf(minimum, (available - spacing * (rows.coerceAtLeast(1) - 1)) / rows.coerceAtLeast(1))
}
