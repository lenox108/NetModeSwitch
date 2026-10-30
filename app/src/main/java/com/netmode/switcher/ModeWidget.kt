package com.netmode.switcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.netmode.switcher.Widgets.autoBox
import com.netmode.switcher.Widgets.backdrop
import com.netmode.switcher.Widgets.baseColors
import com.netmode.switcher.Widgets.bg
import com.netmode.switcher.Widgets.color
import com.netmode.switcher.Widgets.sizes
import com.netmode.switcher.Widgets.tint
import kotlinx.coroutines.launch

/** Base for the four widgets: the first paint, then a real read, and the listeners. */
abstract class BaseWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.watch()
        Widgets.paintAll()
        // A fresh process knows neither the SIMs nor their modes yet: read, then paint again.
        val pending = goAsync()
        App.scope.launch {
            try {
                runCatching {
                    Sims.active()
                    ModeController.readAll()
                    HotspotControl.isOn()
                }
                Widgets.paintAll()
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.update()

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach {
        Prefs.setWidgetConfig(it, null)
        Prefs.setWidgetAlpha(it, null)
        Prefs.setWidgetText(it, null)
        Prefs.setWidgetScale(it, null)
    }

    override fun onDisabled(context: Context) = Widgets.watch()
}

/**
 * "Сеть": the modes of the SIMs. Narrow it is two rows; wide it adds what is in force now;
 * tall it is a control panel with every mode per SIM and the chosen quick switches.
 */
class ModeWidget : BaseWidget() {

    companion object {
        /** Kept for callers from before the other widgets existed. */
        fun updateAll(ctx: Context) = Widgets.update()

        fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray, snap: Widgets.Snap) {
            if (ids.isEmpty()) return
            val status = Widgets.statusLine(snap)
            for (id in ids) {
                val views = Widgets.sized(manager, id,
                    SizeF(110f, 40f) to rows(ctx, id),
                    SizeF(300f, 40f) to wide(ctx, id, status),
                    SizeF(240f, 150f) to panel(ctx, id, status),
                )
                manager.updateAppWidget(id, views)
            }
        }

        private fun modeText(slot: Int): String = Want.mode(slot)?.short ?: "—"

        private fun rows(ctx: Context, id: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_net_rows)
            v.backdrop(id)
            v.baseColors(R.id.name1 to R.color.w_text, R.id.name2 to R.color.w_text)
            v.sizes(id, R.id.name1 to 14f, R.id.name2 to 14f, R.id.mode1 to 14f, R.id.mode2 to 14f)
            val sims = Widgets.sims()
            val data = if (sims.size > 1) DualSim.dataSlot() else null
            listOf(Triple(R.id.row1, R.id.name1, R.id.mode1), Triple(R.id.row2, R.id.name2, R.id.mode2))
                .forEachIndexed { i, (row, name, mode) ->
                    val sim = sims.getOrNull(i)
                    if (sim == null) {
                        v.setViewVisibility(row, View.GONE)
                        return@forEachIndexed
                    }
                    v.setViewVisibility(row, View.VISIBLE)
                    v.setTextViewText(name, Widgets.simName(sim) + if (sim.slot == data) " · интернет" else "")
                    v.setTextViewText(mode, modeText(sim.slot))
                    val high = Want.mode(sim.slot)?.high == true
                    v.bg(mode, if (high) R.drawable.w_chip else R.drawable.w_chip_off)
                    v.color(mode, if (high) R.color.w_on_chip else R.color.w_on_btn)
                    v.setOnClickPendingIntent(row, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.TOGGLE, sim.slot))
                }
            return v
        }

        private fun wide(ctx: Context, id: Int, status: String): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_net_wide)
            v.backdrop(id)
            v.baseColors(R.id.s1l to R.color.w_text2, R.id.s2l to R.color.w_text2, R.id.s1v to R.color.w_accent,
                R.id.s2v to R.color.w_accent, R.id.n_now_t to R.color.w_text2, R.id.n_status to R.color.w_text)
            v.bg(R.id.n_div, R.color.w_line)
            v.sizes(id, R.id.s1l to 11f, R.id.s2l to 11f, R.id.s1v to 20f, R.id.s2v to 20f, R.id.n_now_t to 11f, R.id.n_status to 13f)
            val sims = Widgets.sims()
            listOf(Triple(R.id.sim1, R.id.s1l, R.id.s1v), Triple(R.id.sim2, R.id.s2l, R.id.s2v))
                .forEachIndexed { i, (block, label, value) ->
                    val sim = sims.getOrNull(i)
                    if (sim == null) {
                        v.setViewVisibility(block, View.GONE)
                        return@forEachIndexed
                    }
                    v.setViewVisibility(block, View.VISIBLE)
                    v.setTextViewText(label, Widgets.simName(sim))
                    v.setTextViewText(value, modeText(sim.slot))
                    v.setOnClickPendingIntent(block, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.TOGGLE, sim.slot))
                }
            v.setTextViewText(R.id.n_status, status)
            v.setOnClickPendingIntent(R.id.n_now, Widgets.openApp(ctx, AppRulesActivity::class.java))
            return v
        }

        private val BUTTONS = listOf(
            listOf(R.id.p1b0, R.id.p1b1, R.id.p1b2, R.id.p1b3),
            listOf(R.id.p2b0, R.id.p2b1, R.id.p2b2, R.id.p2b3),
        )
        private val TOGGLES = listOf(
            Triple(R.id.q0, R.id.q0i, R.id.q0t), Triple(R.id.q1, R.id.q1i, R.id.q1t),
            Triple(R.id.q2, R.id.q2i, R.id.q2t), Triple(R.id.q3, R.id.q3i, R.id.q3t),
        )

        private fun panel(ctx: Context, id: Int, status: String): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_net_panel)
            v.backdrop(id)
            v.baseColors(R.id.n_status to R.color.w_text, R.id.p1l to R.color.w_text2, R.id.p2l to R.color.w_text2)
            v.sizes(id, R.id.n_status to 13f, R.id.p1l to 11f, R.id.p2l to 11f,
                *BUTTONS.flatten().map { it to 13f }.toTypedArray())
            v.autoBox(id, *TOGGLES.map { it.third to 16f }.toTypedArray())
            v.setTextViewText(R.id.n_status, status)
            v.setOnClickPendingIntent(R.id.n_status, Widgets.openApp(ctx))
            val sims = Widgets.sims()
            val data = if (sims.size > 1) DualSim.dataSlot() else null
            listOf(R.id.p1 to R.id.p1l, R.id.p2 to R.id.p2l).forEachIndexed { i, (block, label) ->
                val sim = sims.getOrNull(i)
                if (sim == null) {
                    v.setViewVisibility(block, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(block, View.VISIBLE)
                val now = Want.mode(sim.slot)
                // A mode outside the four buttons is named, or nothing would show it.
                val extra = if (now != null && now !in NetMode.MAIN) " · ${now.label}" else ""
                v.setTextViewText(label, Widgets.simName(sim) + (if (sim.slot == data) " · интернет" else "") + extra)
                val available = Profile.available(sim.slot)
                NetMode.MAIN.forEachIndexed { j, mode ->
                    val b = BUTTONS[i][j]
                    v.setTextViewText(b, mode.short)
                    val works = mode in available
                    val sel = mode == now
                    v.bg(b, if (sel) R.drawable.w_btn_sel else R.drawable.w_btn)
                    v.color(b, when { sel -> R.color.w_on_sel; works -> R.color.w_on_btn; else -> R.color.w_off })
                    if (works && !sel) v.setOnClickPendingIntent(b, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.SET, sim.slot, mode.name))
                    else v.setOnClickPendingIntent(b, null)
                }
            }
            val quick = Quick.parse(Prefs.widgetConfig(id)).take(TOGGLES.size)
            v.setViewVisibility(R.id.p_tg, if (quick.isEmpty()) View.GONE else View.VISIBLE)
            TOGGLES.forEachIndexed { i, (box, icon, text) ->
                val q = quick.getOrNull(i)
                if (q == null) {
                    v.setViewVisibility(box, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(box, View.VISIBLE)
                val on = Want.quickOn(q)
                v.setImageViewResource(icon, q.icon)
                v.setTextViewText(text, ctx.getString(q.label))
                v.bg(box, if (on) R.drawable.w_tg_on else R.drawable.w_tg)
                v.color(text, if (on) R.color.w_on_chip else R.color.w_on_btn)
                v.tint(icon, if (on) R.color.w_on_chip else R.color.w_on_btn)
                v.setOnClickPendingIntent(box, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.QUICK, 0, q.name))
            }
            return v
        }
    }
}

/**
 * "Кнопка": one thing at one tap — a SIM's mode, the hotspot in a route, or a rule. What it
 * is, is chosen when the widget is added ("sim:0", "ap:PROXY", "rule:<id>").
 */
class ButtonWidget : BaseWidget() {

    companion object {
        fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray, snap: Widgets.Snap) {
            for (id in ids) {
                val cfg = Prefs.widgetConfig(id) ?: "sim:0"
                manager.updateAppWidget(id, Widgets.sized(manager, id,
                    SizeF(40f, 40f) to build(ctx, id, cfg, R.layout.widget_button_small, wide = false, snap),
                    SizeF(130f, 40f) to build(ctx, id, cfg, R.layout.widget_button_wide, wide = true, snap),
                ))
            }
        }

        private fun build(ctx: Context, id: Int, cfg: String, layout: Int, wide: Boolean, snap: Widgets.Snap): RemoteViews {
            val v = RemoteViews(ctx.packageName, layout)
            val kind = cfg.substringBefore(':')
            val arg = cfg.substringAfter(':')
            // First: it decides the colours everything below is drawn in; lit is settled at the end.
            v.backdrop(id, small = !wide)
            var on = false
            val bare = Widgets.bare(id)
            when (kind) {
                "ap" -> {
                    val mode = runCatching { TetherMode.valueOf(arg) }.getOrDefault(TetherMode.DIRECT)
                    val (apOn, apMode) = Want.ap()
                    on = apOn && apMode == mode
                    // Small: icon and route, lit when on. Wide: the state in the label, the route big.
                    v.setTextViewText(R.id.b_label, if (wide) ctx.getString(R.string.w_ap_title) else apName(ctx, mode))
                    v.setTextViewText(R.id.b_value, apName(ctx, mode))
                    v.setImageViewResource(R.id.b_icon, R.drawable.w_ic_hotspot)
                    v.setViewVisibility(R.id.b_icon, View.VISIBLE)
                    v.setOnClickPendingIntent(R.id.b_root, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.AP, 0, mode.name))
                }
                "unload" -> {
                    val all = arg == "all"
                    val last = Want.unloaded?.takeIf { System.currentTimeMillis() - it.second < WidgetActionReceiver.UNLOADED_SHOWN_MS }
                    on = Want.unloading
                    val text = when {
                        Want.unloading -> "…"
                        last != null -> ctx.getString(R.string.w_unloaded, last.first)
                        else -> null
                    }
                    if (wide) {
                        v.setTextViewText(R.id.b_label, ctx.getString(if (all) R.string.w_unload_all_wide_label else R.string.w_unload_wide_label))
                        v.setTextViewText(R.id.b_value, text ?: ctx.getString(if (all) R.string.w_unload_all_go else R.string.w_unload_go))
                    } else {
                        v.setTextViewText(R.id.b_label, text ?: ctx.getString(if (all) R.string.w_unload_all_short else R.string.w_unload_short))
                    }
                    v.setImageViewResource(R.id.b_icon, R.drawable.w_ic_clean)
                    v.setViewVisibility(R.id.b_icon, View.VISIBLE)
                    v.setOnClickPendingIntent(R.id.b_root, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.UNLOAD, 0, if (all) "all" else null))
                }
                "rule" -> {
                    val rule = snap.rules.find { it.id.toString() == arg }
                    if (rule == null) {
                        v.setTextViewText(R.id.b_label, ctx.getString(R.string.w_rule_gone))
                        v.setTextViewText(R.id.b_value, "—")
                        v.setViewVisibility(R.id.b_icon, View.GONE)
                        v.setOnClickPendingIntent(R.id.b_root, Widgets.openApp(ctx, AppRulesActivity::class.java))
                    } else {
                        on = rule.enabled && snap.active(rule)
                        v.setTextViewText(R.id.b_label, rule.name ?: rule.timeLabel)
                        v.setTextViewText(R.id.b_value, when {
                            !rule.enabled -> ctx.getString(R.string.w_rule_disabled)
                            on -> ctx.getString(R.string.w_on)
                            else -> ctx.getString(R.string.w_off)
                        })
                        v.setImageViewResource(R.id.b_icon, Widgets.iconOf(rule.trigger))
                        v.setViewVisibility(R.id.b_icon, View.VISIBLE)
                        v.setOnClickPendingIntent(R.id.b_root, WidgetActionReceiver.rule(ctx, rule.id))
                    }
                }
                else -> {
                    val slot = arg.toIntOrNull() ?: 0
                    val sim = Sims.known.firstOrNull { it.slot == slot } ?: SimInfo(slot, "")
                    v.setTextViewText(R.id.b_label, if (wide) Widgets.simName(sim) else "SIM ${slot + 1}")
                    v.setTextViewText(R.id.b_value, Want.mode(slot)?.short ?: "—")
                    // A SIM is never "off", so it is never lit: 4G versus 3G is in the text.
                    if (wide) v.setImageViewResource(R.id.b_icon, R.drawable.w_ic_swap) else v.setViewVisibility(R.id.b_icon, View.GONE)
                    v.setOnClickPendingIntent(R.id.b_root, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.TOGGLE, slot))
                }
            }
            if (!wide && kind != "sim") {
                // Icon and name fill a 1×1; whether it is on is the background's job.
                v.setViewVisibility(R.id.b_value, View.GONE)
                if (Compat.SDK >= 31) {
                    v.setViewLayoutWidth(R.id.b_icon, 28f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                    v.setViewLayoutHeight(R.id.b_icon, 28f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                }
                v.sizes(id, R.id.b_label to 11f)
                v.color(R.id.b_label, when { on && bare -> R.color.w_accent; on -> R.color.w_on_chip; else -> R.color.w_text })
            }
            v.backdrop(id, lit = on, small = !wide)
            if (wide || kind == "sim") v.sizes(id, R.id.b_label to if (wide) 12f else 11f)
            v.autoBox(id, R.id.b_value to if (wide) 26f else 30f)
            if (wide || kind == "sim") v.color(R.id.b_label, R.color.w_text2)
            v.color(R.id.b_value, if (on && !bare) R.color.w_on_chip else R.color.w_accent)
            v.tint(R.id.b_icon, when { on && bare -> R.color.w_accent; on -> R.color.w_on_chip; else -> R.color.w_text2 })
            return v
        }

        fun apName(ctx: Context, mode: TetherMode) = ctx.getString(when (mode) {
            TetherMode.DIRECT -> R.string.w_ap_direct
            TetherMode.VPN -> R.string.w_ap_vpn
            TetherMode.PROXY -> R.string.w_ap_proxy
        })
    }
}

/** "Раздача": the hotspot and its route; a route button switches it on in that route. */
class HotspotWidget : BaseWidget() {

    companion object {
        fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) manager.updateAppWidget(id, Widgets.sized(manager, id,
                SizeF(110f, 40f) to build(ctx, id, R.layout.widget_hotspot_small),
                SizeF(110f, 130f) to build(ctx, id, R.layout.widget_hotspot_large),
            ))
        }

        private fun build(ctx: Context, id: Int, layout: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, layout)
            v.backdrop(id)
            v.baseColors(R.id.h_title to R.color.w_text2)
            v.sizes(id, R.id.h_title to 12f, R.id.h_state to if (layout == R.layout.widget_hotspot_small) 15f else 20f,
                R.id.h_direct to 12f, R.id.h_vpn to 12f, R.id.h_proxy to 12f)
            val (on, mode) = Want.ap()
            v.setTextViewText(R.id.h_state, when {
                !on -> ctx.getString(R.string.w_ap_off)
                mode == TetherMode.DIRECT -> ctx.getString(R.string.w_ap_on_direct)
                else -> ctx.getString(R.string.w_ap_on_via, TetherRoute.label(mode))
            })
            v.color(R.id.h_state, if (on) R.color.w_accent else R.color.w_text2)
            if (layout == R.layout.widget_hotspot_small) {
                v.setTextViewText(R.id.h_title, ctx.getString(R.string.w_ap_state, when {
                    !on -> ctx.getString(R.string.w_off)
                    mode == TetherMode.DIRECT -> ctx.getString(R.string.w_on)
                    else -> TetherRoute.label(mode)
                }))
                v.color(R.id.h_title, if (on) R.color.w_accent else R.color.w_text2)
            }
            v.tint(R.id.h_icon, if (on) R.color.w_accent else R.color.w_text2)
            listOf(R.id.h_direct to TetherMode.DIRECT, R.id.h_vpn to TetherMode.VPN, R.id.h_proxy to TetherMode.PROXY).forEach { (b, m) ->
                val sel = on && m == mode
                v.bg(b, if (sel) R.drawable.w_btn_sel else R.drawable.w_btn)
                v.color(b, if (sel) R.color.w_on_sel else R.color.w_on_btn)
                v.setOnClickPendingIntent(b, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.AP, 0, m.name))
            }
            v.setOnClickPendingIntent(R.id.h_state, Widgets.openApp(ctx, HotspotActivity::class.java))
            v.setOnClickPendingIntent(R.id.h_title, Widgets.openApp(ctx, HotspotActivity::class.java))
            return v
        }
    }
}

/** "Правила": each rule with its state; a tap applies it, or ends it the way it ends. */
class RulesWidget : BaseWidget() {

    companion object {
        fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray, snap: Widgets.Snap) {
            for (id in ids) {
                manager.updateAppWidget(id, Widgets.sized(manager, id,
                    // Narrow, the state is a short word so the names keep their room.
                    SizeF(110f, 110f) to build(ctx, id, snap, short = true),
                    SizeF(250f, 110f) to build(ctx, id, snap, short = false),
                ))
                // Before Android 12 the rows come from RulesWidgetService: tell it to read again.
                if (Compat.SDK < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.r_list)
            }
        }

        private fun build(ctx: Context, id: Int, snap: Widgets.Snap, short: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_rules)
            v.backdrop(id)
            v.baseColors(R.id.r_title to R.color.w_text, R.id.r_empty to R.color.w_text2)
            v.tint(R.id.r_add, R.color.w_text2)
            v.sizes(id, R.id.r_title to 14f, R.id.r_empty to 12f)
            if (Compat.SDK >= 31) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                rows(ctx, id, snap, short).forEach { (ruleId, row) -> items.addItem(ruleId, row) }
                v.setRemoteAdapter(R.id.r_list, items.build())
            } else {
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.r_list, RulesWidgetService.intent(ctx, id, short))
            }
            v.setEmptyView(R.id.r_list, R.id.r_empty)
            v.setPendingIntentTemplate(R.id.r_list, WidgetActionReceiver.ruleTemplate(ctx))
            v.setOnClickPendingIntent(R.id.r_title, Widgets.openApp(ctx, AppRulesActivity::class.java))
            v.setOnClickPendingIntent(R.id.r_add, Widgets.openApp(ctx, RuleEditActivity::class.java))
            return v
        }

        /** One row per rule, with its id. Colours as the widget's backing has them (see [Widgets.palette]). */
        fun rows(ctx: Context, id: Int, snap: Widgets.Snap, short: Boolean): List<Pair<Long, RemoteViews>> {
            val chosen = Prefs.widgetConfig(id)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
            // A fixed order: a row that jumped on every tap would put the next tap on another rule.
            val rules = snap.rules
                .filter { chosen.isEmpty() || it.id in chosen }
                .sortedWith(compareBy({ !it.enabled }, { it.trigger.ordinal }, { it.hour }, { it.minute }))
            val out = mutableListOf<Pair<Long, RemoteViews>>()
            for (rule in rules) {
                val row = RemoteViews(ctx.packageName, R.layout.widget_rule_item)
                row.setImageViewResource(R.id.ri_icon, Widgets.iconOf(rule.trigger))
                row.sizes(id, R.id.ri_name to 14f, R.id.ri_sub to 11f, R.id.ri_status to 11f)
                row.setTextViewText(R.id.ri_name, rule.name ?: rule.timeLabel)
                row.setTextViewText(R.id.ri_sub, rule.timeLabel)
                row.setViewVisibility(R.id.ri_sub, if (rule.name != null) View.VISIBLE else View.GONE)
                val wanted = Want.rules[rule.id]
                val active = rule.enabled && snap.active(rule)
                val waiting = wanted == null && rule.id in snap.waiting
                val held = snap.held(rule.id)
                val status = when {
                    !rule.enabled -> ctx.getString(R.string.w_rule_disabled)
                    waiting -> ctx.getString(if (short) R.string.w_rule_waiting_short else R.string.w_rule_waiting)
                    active -> ctx.getString(if (short) R.string.w_on else R.string.w_rule_active)
                    held > 0 -> ctx.getString(R.string.w_rule_held, held)
                    else -> null
                }
                row.setViewVisibility(R.id.ri_status, if (status == null) View.GONE else View.VISIBLE)
                row.setTextViewText(R.id.ri_status, status ?: "")
                row.bg(R.id.ri_status, if (active) R.drawable.w_chip else R.drawable.w_chip_off)
                // On its own chip, so the theme's colour, not the wallpaper's.
                row.color(R.id.ri_status, if (active) R.color.w_on_chip else R.color.w_on_btn)
                row.tint(R.id.ri_icon, if (active) R.color.w_accent else R.color.w_text2)
                row.color(R.id.ri_name, if (rule.enabled) R.color.w_text else R.color.w_off)
                row.color(R.id.ri_sub, R.color.w_text2)
                row.setOnClickFillInIntent(R.id.ri_root, android.content.Intent().putExtra(WidgetActionReceiver.EXTRA_ID, rule.id))
                out += rule.id to row
            }
            return out
        }
    }
}

/** "Состояние": VPN, the hotspot and the rules in force, each a way into its own screen. */
class StatusWidget : BaseWidget() {

    companion object {
        fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray, snap: Widgets.Snap) {
            // Readable with no privilege at all, and without waking a shell on every paint.
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val vpnOn = runCatching {
                cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
            }.getOrDefault(false)
            val (apOn, apMode) = Want.ap()
            val active = snap.rules.count { snap.active(it) }
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_status)
                v.backdrop(id)
                fun cell(cell: Int, icon: Int, label: Int, state: Int, on: Boolean, text: String, open: Class<*>) {
                    val tone = if (on) R.color.w_accent else R.color.w_text2
                    v.tint(icon, tone)
                    v.color(label, R.color.w_text2)
                    v.color(state, tone)
                    v.setTextViewText(state, text)
                    v.setOnClickPendingIntent(cell, Widgets.openApp(ctx, open))
                }
                cell(R.id.st_vpn_cell, R.id.st_vpn_icon, R.id.st_vpn_label, R.id.st_vpn_state, vpnOn,
                    ctx.getString(if (vpnOn) R.string.w_on else R.string.w_off), MainActivity::class.java)
                cell(R.id.st_ap_cell, R.id.st_ap_icon, R.id.st_ap_label, R.id.st_ap_state, apOn, when {
                    !apOn -> ctx.getString(R.string.w_off)
                    apMode == TetherMode.DIRECT -> ctx.getString(R.string.w_on)
                    else -> TetherRoute.label(apMode)
                }, HotspotActivity::class.java)
                cell(R.id.st_rules_cell, R.id.st_rules_icon, R.id.st_rules_label, R.id.st_rules_state, active > 0,
                    ctx.getString(R.string.w_status_rules, active), AppRulesActivity::class.java)
                manager.updateAppWidget(id, v)
            }
        }
    }
}
