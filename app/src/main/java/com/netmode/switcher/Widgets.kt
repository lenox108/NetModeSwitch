package com.netmode.switcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The four home-screen widgets share this: painting all of them, keeping them current and
 * what their buttons do.
 *
 * Nothing polls. While a widget exists the app listens, as long as its process lives, to the
 * system's own broadcasts for the radios it shows, to its settings for rule changes, and to
 * unlocking — which also catches a mode changed in the system settings meanwhile.
 */
object Widgets {

    private const val TAG = "netmode"

    val providers = listOf(ModeWidget::class.java, ButtonWidget::class.java, HotspotWidget::class.java, RulesWidget::class.java)

    fun ids(ctx: Context, cls: Class<out AppWidgetProvider>): IntArray =
        runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls)) }.getOrNull() ?: IntArray(0)

    fun any(ctx: Context = App.ctx): Boolean = providers.any { ids(ctx, it).isNotEmpty() }

    @Volatile private var pending: Job? = null

    /** Repaints every widget from what is already known; calls close together paint once. */
    fun update() {
        if (!any()) return
        pending?.cancel()
        pending = App.scope.launch {
            delay(200)
            paintAll()
        }
    }

    /** Reads what only the shell can tell — the hotspot, the SIMs, the modes — and repaints. */
    fun refresh(modes: Boolean = false) {
        if (!any()) return
        App.scope.launch {
            runCatching {
                if (Sims.known.isEmpty()) Sims.active()
                if (modes || Sims.known.any { ModeController.current.value[it.slot] == null }) ModeController.readAll()
                HotspotControl.isOn()
            }.onFailure { Log.w(TAG, "widget refresh failed", it) }
            paintAll()
        }
    }

    /** One paint at a time; each reads the rules once for every widget it draws. */
    @Synchronized
    fun paintAll() {
        val ctx = App.ctx
        val manager = AppWidgetManager.getInstance(ctx)
        val snap = Snap()
        runCatching { ModeWidget.paint(ctx, manager, ids(ctx, ModeWidget::class.java), snap) }.onFailure { Log.w(TAG, "paint", it) }
        runCatching { ButtonWidget.paint(ctx, manager, ids(ctx, ButtonWidget::class.java), snap) }.onFailure { Log.w(TAG, "paint", it) }
        runCatching { HotspotWidget.paint(ctx, manager, ids(ctx, HotspotWidget::class.java)) }.onFailure { Log.w(TAG, "paint", it) }
        runCatching { RulesWidget.paint(ctx, manager, ids(ctx, RulesWidget::class.java), snap) }.onFailure { Log.w(TAG, "paint", it) }
    }

    /**
     * The rules and their state as one paint sees them. The settings are JSON decoded on
     * every read, so they are read here once rather than per rule and per size.
     */
    class Snap {
        val rules: List<AppRule> by lazy { Prefs.appRules }
        val open: Map<Long, Long> by lazy { Prefs.openWindows }
        val waiting: Map<Long, String> by lazy { Prefs.pendingStarts }
        private val frozen by lazy { Prefs.frozen }

        /** In force, or waiting for the lock — or, right after a tap, about to be. */
        fun active(r: AppRule): Boolean = Want.rules[r.id] ?: (AppFreezer.isActive(r, open) || r.id in waiting)

        fun held(id: Long): Int = frozen.count { (it.ruleId == id || it.ruleId == AppFreezer.endOwner(id)) && !it.isPseudo }
    }

    // ---- keeping current ----

    @Volatile private var watching = false
    @Volatile private var lastUnlockRead = 0L

    private val system = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    val now = System.currentTimeMillis()
                    val reread = now - lastUnlockRead > 60_000L
                    if (reread) lastUnlockRead = now
                    refresh(modes = reread)
                }
                TETHER_CHANGED -> refresh()
                else -> update()
            }
        }
    }

    private val settings = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri?.lastPathSegment?.startsWith("mobile_data") == true) update()
        }
    }

    private val ruleKeys = setOf("app_rules", "open_windows", "pending_starts", "frozen_apps", "app_rules_handled", "tether_mode", "device_profile")

    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in ruleKeys) update() }

    /** Starts listening when a widget exists, stops when the last one is removed. */
    fun watch() {
        val ctx = App.ctx
        val need = any(ctx)
        if (need == watching) return
        if (need) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(TETHER_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
                // A bare widget takes its text colour from the wallpaper.
                @Suppress("DEPRECATION") addAction(Intent.ACTION_WALLPAPER_CHANGED)
            }
            runCatching { ctx.registerReceiver(system, filter, Context.RECEIVER_NOT_EXPORTED) }
            runCatching { ctx.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settings) }
            Prefs.listen(prefs)
        } else {
            runCatching { ctx.unregisterReceiver(system) }
            runCatching { ctx.contentResolver.unregisterContentObserver(settings) }
            Prefs.unlisten(prefs)
        }
        watching = need
    }

    private const val TETHER_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"

    // ---- shared pieces ----

    fun openApp(ctx: Context, cls: Class<*> = MainActivity::class.java): PendingIntent =
        PendingIntent.getActivity(ctx, cls.name.hashCode(), Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "● Дом, Ночь · раздача через Proxy", or that nothing is in force. */
    fun statusLine(snap: Snap): String {
        val pending = snap.waiting
        val active = snap.rules.filter { snap.active(it) }
        val parts = mutableListOf<String>()
        val now = active.filter { it.id !in pending }.map { it.name ?: it.timeLabel }
        val waiting = active.filter { it.id in pending }.map { it.name ?: it.timeLabel }
        if (now.isNotEmpty()) parts += "● " + now.joinToString(", ")
        if (waiting.isNotEmpty()) parts += "⏳ " + waiting.joinToString(", ")
        val (apOn, apMode) = Want.ap()
        if (apOn) parts += "раздача " + if (apMode == TetherMode.DIRECT) "вкл" else "через ${TetherRoute.label(apMode)}"
        return parts.joinToString(" · ").ifEmpty { App.ctx.getString(R.string.w_nothing_active) }
    }

    fun iconOf(t: Trigger) = when (t) {
        Trigger.MANUAL -> R.drawable.w_ic_t_manual
        Trigger.TIME -> R.drawable.w_ic_t_time
        Trigger.WIFI, Trigger.WIFI_LEFT -> R.drawable.w_ic_t_wifi
        Trigger.BLUETOOTH -> R.drawable.w_ic_t_bt
        Trigger.MOBILE_DATA -> R.drawable.w_ic_t_data
        Trigger.NET_MODE -> R.drawable.w_ic_t_net
        Trigger.BATTERY -> R.drawable.w_ic_t_battery
        Trigger.CHARGING -> R.drawable.w_ic_t_charging
        Trigger.SCREEN_OFF -> R.drawable.w_ic_t_screen
        Trigger.APP_OPEN -> R.drawable.w_ic_t_app
        Trigger.PLACE -> R.drawable.w_ic_t_place
        Trigger.ROAMING -> R.drawable.w_ic_t_roaming
        Trigger.WEAK_SIGNAL -> R.drawable.w_ic_t_signal
        Trigger.CALL -> R.drawable.w_ic_t_call
        Trigger.HEADPHONES -> R.drawable.w_ic_t_headphones
        Trigger.DND -> R.drawable.w_ic_t_dnd
        Trigger.LOCKED -> R.drawable.w_ic_t_lock
    }

    /**
     * Text colour from a colour resource. From Android 12 the launcher resolves it, so it follows
     * dark mode by itself; before, it is resolved here, in the app's current configuration.
     */
    fun RemoteViews.color(id: Int, res: Int) {
        val r = onWallpaper(res)
        if (Compat.SDK >= 31) setColorStateList(id, "setTextColor", r) else setTextColor(id, App.ctx.getColor(r))
    }

    fun RemoteViews.tint(id: Int, res: Int) {
        val r = onWallpaper(res)
        if (Compat.SDK >= 31) setColorStateList(id, "setImageTintList", r) else setInt(id, "setColorFilter", App.ctx.getColor(r))
    }

    fun RemoteViews.bg(id: Int, res: Int) = setInt(id, "setBackgroundResource", onWallpaper(res))

    /**
     * The widget being drawn has (next to) no backing: null when it has one, else whether its
     * wallpaper is light. Set by [backdrop] at the start of each layout; painting is one at a time.
     */
    @Volatile private var bareOnLight: Boolean? = null

    private val ON_LIGHT = mapOf(R.color.w_text to R.color.wl_text, R.color.w_text2 to R.color.wl_text2,
        R.color.w_accent to R.color.wl_accent, R.color.w_off to R.color.wl_off, R.color.w_line to R.color.wl_line)
    private val ON_DARK = mapOf(R.color.w_text to R.color.wd_text, R.color.w_text2 to R.color.wd_text2,
        R.color.w_accent to R.color.wd_accent, R.color.w_off to R.color.wd_off, R.color.w_line to R.color.wd_line)

    /** The theme's colours on a backing; on the bare wallpaper, those that stand out on it. */
    private fun onWallpaper(res: Int): Int = when (bareOnLight) {
        null -> res
        true -> ON_LIGHT[res] ?: res
        false -> ON_DARK[res] ?: res
    }

    /**
     * What the wallpaper says of itself — the same hint the launcher picks its labels by. Before
     * Android 12 the hint is not public: the lightness of its main colour stands in.
     */
    private fun wallpaperIsLight(): Boolean = runCatching {
        val colors = App.ctx.getSystemService(android.app.WallpaperManager::class.java)
            .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) ?: return@runCatching false
        if (Compat.SDK >= 31) colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        else colors.primaryColor.luminance() > 0.5f
    }.getOrDefault(false)

    /**
     * Text sizes in sp as the layout has them, scaled by widget [id]'s choice. Text that fits
     * itself to its box (autosize) is left out: it ignores a set size.
     */
    fun RemoteViews.sizes(id: Int, vararg pairs: Pair<Int, Float>) {
        val k = Prefs.widgetScale(id) / 100f
        pairs.forEach { (view, sp) -> setTextViewTextSize(view, android.util.TypedValue.COMPLEX_UNIT_SP, sp * k) }
    }

    /**
     * Text that fits itself to its box follows the box: at a chosen size the box gets that
     * size's height, [sp] being its height at 100%. Untouched at 100%, where it wraps.
     */
    fun RemoteViews.autoBox(id: Int, vararg pairs: Pair<Int, Float>) {
        val k = Prefs.widgetScale(id) / 100f
        // Sizes of views in a widget can be set from Android 12; before, such text stays as it fits.
        if (k == 1f || Compat.SDK < 31) return
        pairs.forEach { (view, sp) -> setViewLayoutHeight(view, sp * k, android.util.TypedValue.COMPLEX_UNIT_SP) }
    }

    /**
     * The layout for a widget's size. From Android 12 the launcher picks among them as the widget
     * is resized; before, the one fitting its current size is picked here — the same rule: the
     * largest that fits, else the smallest. Sizes are in dp.
     */
    fun sized(manager: AppWidgetManager, id: Int, vararg layouts: Pair<android.util.SizeF, RemoteViews>): RemoteViews {
        if (Compat.SDK >= 31) return RemoteViews(layouts.toMap())
        val o = manager.getAppWidgetOptions(id)
        // Portrait: the full width, the shorter height.
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
        val fits = layouts.filter { (s, _) -> s.width <= w && s.height <= h }
        return (fits.maxByOrNull { it.first.width * it.first.height } ?: layouts.minBy { it.first.width * it.first.height }).second
    }

    /** Colours that the layouts set themselves, so a bare widget gets them from the wallpaper too. */
    fun RemoteViews.baseColors(vararg pairs: Pair<Int, Int>) = pairs.forEach { (id, res) -> color(id, res) }

    /**
     * The backing layer at widget [id]'s chosen opacity; [lit] swaps in the "on" colour. Gone
     * at 0, the content then sits on the wallpaper.
     */
    fun RemoteViews.backdrop(id: Int, lit: Boolean = false, small: Boolean = false) {
        val alpha = Prefs.widgetAlpha(id)
        setImageViewResource(R.id.w_back, when {
            lit && small -> R.drawable.w_bg_small_on
            lit -> R.drawable.w_bg_on
            small -> R.drawable.w_bg_small
            else -> R.drawable.w_bg
        })
        setInt(R.id.w_back, "setImageAlpha", alpha * 255 / 100)
        setViewVisibility(R.id.w_back, if (alpha == 0) android.view.View.GONE else android.view.View.VISIBLE)
        // Chosen by hand, it holds whatever is behind: the wallpaper's hint speaks for the whole
        // screen, not for the clock widget the text may sit next to. Automatic: under half the
        // backing is more wallpaper than backing, and the wallpaper decides.
        palette(id)
    }

    /** Sets the colours everything drawn next is mapped by, for widget [id]. See [backdrop]. */
    fun palette(id: Int) {
        val alpha = Prefs.widgetAlpha(id)
        bareOnLight = when (Prefs.widgetText(id)) {
            "light" -> false
            "dark" -> true
            "theme" -> null
            else -> if (alpha < 50) wallpaperIsLight() else null
        }
    }

    /** Without a backing a lit state has nothing to light, so the content carries it. */
    fun bare(id: Int): Boolean = Prefs.widgetAlpha(id) == 0

    /** "SIM 1 · Билайн", or just "SIM 1" while the names are unknown. */
    fun simName(sim: SimInfo): String =
        if (sim.name.isEmpty() || sim.name == "SIM ${sim.slot + 1}") "SIM ${sim.slot + 1}" else sim.label

    fun sims(): List<SimInfo> = Sims.known.ifEmpty { listOf(SimInfo(0, "")) }
}

/** The switches the control panel can carry: each reads the system state, each flips it. */
enum class Quick(val label: Int, val icon: Int) {
    DATA(R.string.w_q_data, R.drawable.w_ic_t_data),
    WIFI(R.string.w_q_wifi, R.drawable.w_ic_t_wifi),
    HOTSPOT(R.string.w_q_hotspot, R.drawable.w_ic_hotspot),
    AIRPLANE(R.string.w_q_airplane, R.drawable.w_ic_a_airplane),
    POWERSAVE(R.string.w_q_powersave, R.drawable.w_ic_t_battery),
    BT(R.string.w_q_bt, R.drawable.w_ic_t_bt);

    fun isOn(): Boolean = runCatching {
        val ctx = App.ctx
        when (this) {
            DATA -> DataWatch.enabled()
            WIFI -> ctx.getSystemService(WifiManager::class.java).isWifiEnabled
            HOTSPOT -> HotspotControl.lastOn
            AIRPLANE -> Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            POWERSAVE -> ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
            BT -> ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        }
    }.getOrDefault(false)

    /** Switches it to [on]; nothing when it already is. */
    suspend fun set(on: Boolean) {
        if (this == HOTSPOT) {
            val now = HotspotControl.isOn()
            // Off in whatever route it runs; on the plain way — the hotspot widget picks routes.
            if (now && !on) HotspotControl.toggle(Prefs.tetherMode)
            if (!now && on) HotspotControl.toggle(TetherMode.DIRECT)
            return
        }
        if (isOn() == on) return
        val cmd = when (this) {
            DATA -> if (on) AppFreezer.DATA_ON else AppFreezer.DATA_OFF
            WIFI -> if (on) AppFreezer.WIFI_ON else AppFreezer.WIFI_OFF
            AIRPLANE -> if (on) AppFreezer.AIR_ON else AppFreezer.AIR_OFF
            POWERSAVE -> if (on) AppFreezer.PS_ON else AppFreezer.PS_OFF
            BT -> if (on) AppFreezer.BT_ON else AppFreezer.BT_OFF
            HOTSPOT -> return
        }
        Privileged.exec(cmd)
        Prefs.log("виджет: ${App.ctx.getString(label)} " + if (on) "вкл" else "выкл")
    }

    companion object {
        val DEFAULT = listOf(DATA, WIFI, HOTSPOT)

        fun parse(s: String?): List<Quick> =
            if (s == WidgetConfigActivity.NONE) emptyList() else s?.split(',')?.mapNotNull { n -> entries.find { it.name == n } }?.takeIf { it.isNotEmpty() } ?: DEFAULT
    }
}

/**
 * What a tap asked for. The widget shows it at once; the work catches up behind it. A tap
 * while the work for the same button is still running only changes the target, so the
 * last tap wins and nothing is ignored — four taps on Wi-Fi end where the screen says.
 */
object Want {
    val modes = java.util.concurrent.ConcurrentHashMap<Int, NetMode>()
    val quick = java.util.concurrent.ConcurrentHashMap<Quick, Boolean>()
    val rules = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /** The hotspot: a route name, or [AP_OFF]. */
    val ap = java.util.concurrent.atomic.AtomicReference<String?>(null)
    const val AP_OFF = "OFF"

    /** "Выгрузить фон": under way, and how the last one went (count, when). */
    @Volatile var unloading = false
    @Volatile var unloaded: Pair<Int, Long>? = null

    fun mode(slot: Int): NetMode? = modes[slot] ?: ModeController.modeOf(slot)

    fun quickOn(q: Quick): Boolean = quick[q] ?: q.isOn()

    /** Whether the hotspot is (to be) on, and in which route. */
    fun ap(): Pair<Boolean, TetherMode> = when (val t = ap.get()) {
        null -> HotspotControl.lastOn to Prefs.tetherMode
        AP_OFF -> false to Prefs.tetherMode
        else -> true to TetherMode.valueOf(t)
    }
}

/** Every widget button lands here; the work outlives the broadcast on the app scope. */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val slot = intent.getIntExtra(EXTRA_SLOT, 0)
        val arg = intent.getStringExtra(EXTRA_ARG)
        // The target first and the repaint right away: the press shows before any work.
        val key = when (action) {
            TOGGLE -> {
                Want.modes[slot] = ModeController.toggleTarget(slot, Want.mode(slot))
                "sim:$slot"
            }
            SET -> {
                Want.modes[slot] = runCatching { NetMode.valueOf(arg!!) }.getOrNull() ?: return
                "sim:$slot"
            }
            QUICK -> {
                val q = runCatching { Quick.valueOf(arg!!) }.getOrNull() ?: return
                Want.quick[q] = !Want.quickOn(q)
                "q:${q.name}"
            }
            AP -> {
                val mode = runCatching { TetherMode.valueOf(arg!!) }.getOrNull() ?: return
                val (on, now) = Want.ap()
                Want.ap.set(if (on && now == mode) Want.AP_OFF else mode.name)
                "ap"
            }
            RULE -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L)
                val rule = Prefs.appRules.find { it.id == id } ?: return
                val active = Want.rules[id] ?: (AppFreezer.isActive(rule) || id in Prefs.pendingStarts)
                if (!active && !rule.enabled) return
                Want.rules[id] = !active
                "rule:$id"
            }
            UNLOAD -> {
                Want.unloading = true
                if (arg == "all") "unload:all" else "unload"
            }
            else -> return
        }
        val pending = goAsync()
        val work = App.scope.launch {
            Widgets.paintAll()
            // Already under way: that work picks up the new target when its step is done.
            if (running.add(key)) drive(key)
        }
        App.scope.launch {
            withTimeoutOrNull(9_000L) { work.join() }
            pending.finish()
        }
    }

    private suspend fun drive(key: String) {
        do {
            try {
                when {
                    key.startsWith("sim:") -> driveMode(key.removePrefix("sim:").toInt())
                    key.startsWith("q:") -> driveQuick(Quick.valueOf(key.removePrefix("q:")))
                    key == "ap" -> driveAp()
                    key.startsWith("rule:") -> driveRule(key.removePrefix("rule:").toLong())
                    key.startsWith("unload") -> {
                        val n = AppFreezer.unloadBackground(App.ctx.getString(R.string.w_unload_log), all = key == "unload:all")
                        Want.unloaded = n to System.currentTimeMillis()
                        Want.unloading = false
                        // The result shows for a moment, then the button is a button again.
                        App.scope.launch { delay(UNLOADED_SHOWN_MS); Widgets.update() }
                    }
                }
            } catch (t: Throwable) {
                Log.w("netmode", "widget $key failed", t)
                clear(key)  // a target that cannot be reached must not stay on screen
            } finally {
                running.remove(key)
            }
            Widgets.paintAll()
            // A tap that landed between the last step and the line above has nobody else.
        } while (wanted(key) && running.add(key))
    }

    private fun wanted(key: String): Boolean = when {
        key.startsWith("sim:") -> Want.modes.containsKey(key.removePrefix("sim:").toInt())
        key.startsWith("q:") -> Want.quick.containsKey(Quick.valueOf(key.removePrefix("q:")))
        key == "ap" -> Want.ap.get() != null
        key.startsWith("rule:") -> Want.rules.containsKey(key.removePrefix("rule:").toLong())
        else -> false
    }

    private fun clear(key: String) {
        when {
            key.startsWith("sim:") -> Want.modes.remove(key.removePrefix("sim:").toInt())
            key.startsWith("q:") -> Want.quick.remove(Quick.valueOf(key.removePrefix("q:")))
            key == "ap" -> Want.ap.set(null)
            key.startsWith("rule:") -> Want.rules.remove(key.removePrefix("rule:").toLong())
            key.startsWith("unload") -> Want.unloading = false
        }
    }

    private suspend fun driveMode(slot: Int) {
        while (true) {
            val t = Want.modes[slot] ?: return
            ModeController.apply(slot, t, ModeController.Source.MANUAL)
                .onFailure { Prefs.log("SIM ${slot + 1}: ${it.message}") }
            if (Want.modes.remove(slot, t)) return
        }
    }

    private suspend fun driveQuick(q: Quick) {
        while (true) {
            val t = Want.quick[q] ?: return
            q.set(t)
            // The radio takes a moment to report its new state; until then the target shows.
            withTimeoutOrNull(3_000L) { while (q.isOn() != t) delay(100) }
            if (Want.quick.remove(q, t)) return
        }
    }

    private suspend fun driveAp() {
        while (true) {
            val t = Want.ap.get() ?: return
            val on = HotspotControl.isOn()
            if (t == Want.AP_OFF) {
                if (on) HotspotControl.toggle(Prefs.tetherMode)
            } else {
                val mode = TetherMode.valueOf(t)
                if (!on || Prefs.tetherMode != mode) HotspotControl.toggle(mode)
            }
            if (Want.ap.compareAndSet(t, null)) return
        }
    }

    private suspend fun driveRule(id: Long) {
        while (true) {
            val t = Want.rules[id] ?: return
            val rule = Prefs.appRules.find { it.id == id }
            if (rule != null) {
                val active = AppFreezer.isActive(rule) || id in Prefs.pendingStarts
                if (t && !active && rule.enabled) AppFreezer.freezeNow(rule)
                if (!t && active) AppFreezer.releaseNow(id)
            }
            if (Want.rules.remove(id, t)) return
        }
    }

    companion object {
        const val TOGGLE = "toggle"
        const val SET = "set"
        const val QUICK = "quick"
        const val AP = "ap"
        const val RULE = "rule"
        const val UNLOAD = "unload"
        const val UNLOADED_SHOWN_MS = 5_000L

        private const val EXTRA_ACTION = "a"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_ARG = "arg"
        const val EXTRA_ID = "id"

        /** Buttons whose work is under way. */
        private val running: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Each distinct button gets its own PendingIntent through a distinct data URI. */
        fun intent(ctx: Context, action: String, slot: Int = 0, arg: String? = null): PendingIntent {
            val i = Intent(ctx, WidgetActionReceiver::class.java)
                .setData(Uri.parse("netmode://widget/$action/$slot/${arg ?: ""}"))
                .putExtra(EXTRA_ACTION, action).putExtra(EXTRA_SLOT, slot).putExtra(EXTRA_ARG, arg)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun rule(ctx: Context, ruleId: Long): PendingIntent {
            val i = Intent(ctx, WidgetActionReceiver::class.java)
                .setData(Uri.parse("netmode://widget/rule/$ruleId"))
                .putExtra(EXTRA_ACTION, RULE).putExtra(EXTRA_ID, ruleId)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** The rule list's template; each row fills in its rule id. */
        fun ruleTemplate(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetActionReceiver::class.java)
                .setData(Uri.parse("netmode://widget/rule"))
                .putExtra(EXTRA_ACTION, RULE)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
