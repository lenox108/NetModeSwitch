package com.netmode.switcher

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Explicit moves also work at large font sizes and with TalkBack. Saving is atomic. */
object IceGroupOrderDialog {
    fun show(ctx: Context) {
        var names = Freezer.groups()
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        scroll.addView(list)
        fun paint() {
            val y = scroll.scrollY
            list.removeAllViews()
            list.addView(TextView(ctx).apply { setText(R.string.ice_group_order_hint); setPadding(0, dp(8), 0, dp(16)) })
            names.forEachIndexed { i, name ->
                val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply { text = name; textSize = 16f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                listOf(-1 to "↑", 1 to "↓").forEach { (step, mark) ->
                    row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = mark
                        contentDescription = ctx.getString(if (step < 0) R.string.ice_move_up else R.string.ice_move_down, name)
                        isEnabled = i + step in names.indices
                        minimumWidth = 0
                        setPadding(0, 0, 0, 0)
                        setOnClickListener { names = IceOrder.move(names, i, i + step); paint() }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                list.addView(row)
            }
            scroll.post { scroll.scrollTo(0, y) }
        }
        paint()
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.ice_group_order).setView(scroll)
            .setPositiveButton(R.string.ice_view_done) { _, _ -> Freezer.orderGroups(names) }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
