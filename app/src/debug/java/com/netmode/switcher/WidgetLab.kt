package com.netmode.switcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug harness: hosts each widget at the given sizes. Needs
 * `adb shell appwidget grantbind --package com.netmode.switcher`.
 * Extras: "only" = provider simple name, "dark" ignored (follows the system).
 */
class WidgetLab : Activity() {

    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 4711)
        host.deleteHost()
        val manager = AppWidgetManager.getInstance(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(40, 60, 90))
        }
        if (intent.getBooleanExtra("showcase", false)) return showcase(manager)
        val only = intent.getStringExtra("only")
        val plan = listOf(
            ButtonWidget::class.java to listOf(SizeF(80f, 85f), SizeF(170f, 85f)),
            ModeWidget::class.java to listOf(SizeF(260f, 85f), SizeF(350f, 85f), SizeF(350f, 200f)),
            HotspotWidget::class.java to listOf(SizeF(170f, 85f), SizeF(170f, 170f)),
            RulesWidget::class.java to listOf(SizeF(350f, 220f)),
        ).filter { only == null || it.first.simpleName == only }
        val buttonCfg = mutableListOf("unload:all", "sim:0", "ap:PROXY", "rule:" + (Prefs.appRules.firstOrNull()?.id ?: 0))
        for ((cls, sizes) in plan) {
            for (size in sizes) {
                val cfgs = when (cls) {
                    ButtonWidget::class.java -> buttonCfg
                    ModeWidget::class.java -> listOf("DATA,WIFI,HOTSPOT,BT")
                    else -> listOf<String?>(null)
                }
                for (cfg in cfgs) {
                    val id = host.allocateAppWidgetId()
                    val options = Bundle().apply {
                        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
                    }
                    if (!manager.bindAppWidgetIdIfAllowed(id, ComponentName(this, cls), options)) {
                        column.addView(label("bind refused: run appwidget grantbind"))
                        continue
                    }
                    if (cfg != null) Prefs.setWidgetConfig(id, cfg)
                    column.addView(label("${cls.simpleName} ${size.width.toInt()}×${size.height.toInt()} ${cfg ?: ""}"))
                    val view = host.createView(this, id, manager.getAppWidgetInfo(id))
                    labSize(view, manager, id, size)
                    column.addView(view, LinearLayout.LayoutParams(dp(size.width), dp(size.height)).apply { bottomMargin = dp(10) })
                }
            }
        }
        setContentView(ScrollView(this).apply { addView(column) })
        Widgets.watch()
        Widgets.refresh(modes = true)
    }

    /** For the store screenshots: the widgets over the real wallpaper, laid out like a home screen. */
    private fun showcase(manager: AppWidgetManager) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val firstRule = Prefs.appRules.firstOrNull { it.trigger == Trigger.WIFI } ?: Prefs.appRules.firstOrNull()
        fun widget(cls: Class<out android.appwidget.AppWidgetProvider>, w: Float, h: Float, cfg: String? = null): android.view.View {
            val id = host.allocateAppWidgetId()
            val size = SizeF(w, h)
            val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size)) }
            manager.bindAppWidgetIdIfAllowed(id, ComponentName(this, cls), options)
            if (cfg != null) Prefs.setWidgetConfig(id, cfg)
            Prefs.setWidgetAlpha(id, intent.getIntExtra("alpha", 100))
            Prefs.setWidgetText(id, intent.getStringExtra("text") ?: "auto")
            Prefs.setWidgetScale(id, intent.getIntExtra("scale", 100))
            return host.createView(this, id, manager.getAppWidgetInfo(id)).apply {
                labSize(this, manager, id, size)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(h))
            }
        }
        fun row(vararg views: android.view.View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(64), dp(8), dp(8))
            addView(widget(ModeWidget::class.java, 395f, 205f, "DATA,WIFI,HOTSPOT,BT"))
            addView(row(widget(HotspotWidget::class.java, 197f, 190f), widget(RulesWidget::class.java, 198f, 190f)))
            addView(row(
                widget(ButtonWidget::class.java, 99f, 99f, "sim:0"),
                widget(ButtonWidget::class.java, 99f, 99f, "rule:${firstRule?.id ?: 0}"),
                widget(ButtonWidget::class.java, 197f, 99f, "ap:PROXY"),
            ))
            addView(widget(ModeWidget::class.java, 395f, 99f, "DATA,WIFI,HOTSPOT,BT"))
        }
        setContentView(column)
        Widgets.watch()
        Widgets.refresh(modes = true)
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); host.stopListening() }

    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 11f }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun dp(v: Int) = dp(v.toFloat())

    /** Android 12+ takes a size list; before, the min/max options the widget reads (Widgets.sized). */
    @Suppress("DEPRECATION")
    private fun labSize(view: android.appwidget.AppWidgetHostView, manager: AppWidgetManager, id: Int, size: SizeF) {
        if (android.os.Build.VERSION.SDK_INT >= 31) { view.updateAppWidgetSize(Bundle(), listOf(size)); return }
        val w = size.width.toInt(); val h = size.height.toInt()
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        })
        view.updateAppWidgetSize(null, w, h, w, h)
    }
}
