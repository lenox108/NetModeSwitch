package com.netmode.switcher

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Explicit up/down controls work with touch, keyboard and TalkBack in every freezer view. */
object IceOrderDialog {
    /** The sort chooser of the freezer screen and of the folder, each with a setting of its own. */
    fun choose(ctx: Context, current: IceSort, apply: (IceSort) -> Unit) {
        val options = IceSort.entries
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.sort_title)
            .setSingleChoiceItems(options.map { ctx.getString(it.title) }.toTypedArray(), options.indexOf(current)) { d, i ->
                if (options[i] == IceSort.MANUAL && Prefs.iceOrder.isEmpty())
                    Prefs.iceOrder = IceOrder.sort(Freezer.apps, current, Prefs.iceOrder, Freezer::label).map { it.key }
                apply(options[i])
                d.dismiss()
            }.setNeutralButton(R.string.ice_reorder) { _, _ -> show(ctx, current, apply) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun show(ctx: Context, current: IceSort, apply: (IceSort) -> Unit) {
        val apps = IceOrder.sort(Freezer.apps, current, Prefs.iceOrder, Freezer::label)
        val byKey = apps.associateBy { it.key }
        var keys = apps.map { it.key }
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        scroll.addView(list)
        fun paint() {
            val y = scroll.scrollY
            list.removeAllViews()
            list.addView(TextView(ctx).apply { setText(R.string.ice_order_hint); setPadding(0, dp(8), 0, dp(16)) })
            keys.forEachIndexed { i, key ->
                val a = byKey.getValue(key)
                val name = Freezer.label(key)
                val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply {
                    text = if (a.favorite) ctx.getString(R.string.ice_favorite_name, name) else name
                    textSize = 16f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                listOf(-1 to "↑", 1 to "↓").forEach { (step, mark) ->
                    row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = mark
                        contentDescription = ctx.getString(if (step < 0) R.string.ice_move_up else R.string.ice_move_down, name)
                        isEnabled = i + step in keys.indices && byKey[keys[i + step]]?.favorite == a.favorite
                        minimumWidth = 0
                        setPadding(0, 0, 0, 0)
                        setOnClickListener { keys = IceOrder.move(keys, i, i + step); paint() }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                list.addView(row)
            }
            scroll.post { scroll.scrollTo(0, y) }
        }
        paint()
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.ice_order).setView(scroll)
            .setPositiveButton(R.string.ice_view_done) { _, _ ->
                val installed = Freezer.apps.map { it.key }
                Prefs.iceOrder = keys.filter { it in installed } + installed.filter { it !in keys }
                apply(IceSort.MANUAL)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
