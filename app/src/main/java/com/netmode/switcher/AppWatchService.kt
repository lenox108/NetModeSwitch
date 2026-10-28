package com.netmode.switcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.launch

/**
 * "Мгновенно": the app on screen the moment it comes there, from the system's own window events
 * rather than a look at usage events every 2 s. Only a real screen of an app counts — the
 * keyboard, toasts and pop-ups raise window events too, and would read as leaving the app.
 *
 * The freezer uses it too: the system's "app paused" dialog over a soft-frozen app is read —
 * that one window only — to open the app instead ([Prefs.iceNativeInstant]).
 */
class AppWatchService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
        TileClickWatch.connected(this)
    }

    override fun onDestroy() {
        connected = false
        TileClickWatch.disconnected()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) { TileClickWatch.onEvent(event); return }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        TileClickWatch.windowChanged()
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (Prefs.iceNativeInstant && (cls.contains(SUSPENDED_DIALOG) || pkg == "android")) {
            if (openFrozen(event, sure = cls.contains(SUSPENDED_DIALOG))) return
        }
        val activity = runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        if (!activity) return
        ConditionWatch.onForeground(pkg, cls)
        Freezer.noteForeground(pkg)
    }

    /**
     * The dialog names the app — in the message the freezer gave it, or the system's own one
     * when that could not be set: the dialog goes, the app is thawed and opened. [sure]: the
     * window is the dialog by its class; a window of "android" alone counts only with the
     * freezer's message in it. False when it is not for a freezer app.
     *
     * The window's text may come a moment after the event: read again twice before giving up.
     */
    private fun openFrozen(event: AccessibilityEvent, sure: Boolean): Boolean {
        val texts = event.text.map { it.toString() }.toMutableList()
        runCatching { (event.source ?: rootInActiveWindow)?.let { collect(it, texts, 0) } }
        if (answer(texts, sure)) return true
        if (!sure) return false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (wait in longArrayOf(150L, 450L)) {
            handler.postDelayed({
                if (handled) return@postDelayed
                val again = mutableListOf<String>()
                runCatching { rootInActiveWindow?.let { collect(it, again, 0) } }
                answer(again, true)
            }, wait)
        }
        handled = false
        return true
    }

    /** One answer per dialog: set once the app is on its way, cleared by the next dialog. */
    @Volatile private var handled = false

    private fun answer(texts: List<String>, sure: Boolean): Boolean {
        val key = Freezer.fromSuspendDialog(texts, ownMessageOnly = !sure) ?: return false
        handled = true
        performGlobalAction(GLOBAL_ACTION_BACK)
        App.scope.launch { runCatching { Freezer.launch(key, "своя иконка") } }
        return true
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        node.text?.let { out += it.toString() }
        if (depth > 8) return
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    companion object {
        /** While true the 2-second look is not needed (see ConditionWatch.startAppPoll). */
        @Volatile var connected = false
            private set

        /** com.android.internal.app.SuspendedAppActivity: what a suspended app's icon opens. */
        private const val SUSPENDED_DIALOG = "SuspendedAppActivity"

        private fun component(ctx: android.content.Context) = "${ctx.packageName}/${AppWatchService::class.java.name}"

        /**
         * Switches the service on or off in the system's list of accessibility services — a
         * secure setting the privileged shell may write — leaving the others in it alone.
         */
        suspend fun enable(on: Boolean) {
            val ctx = App.ctx
            val me = component(ctx)
            val now = Privileged.exec("settings get secure enabled_accessibility_services").trim()
                .takeIf { it.isNotEmpty() && it != "null" }?.split(':').orEmpty()
            // The rules' "Мгновенно" and the freezer's own-icon launch share the service.
            val want = on || Prefs.instantApps || Prefs.iceNativeInstant || TileClickWatch.needed()
            if (want == (me in now)) return
            val next = if (want) (now - me + me) else (now - me)
            val value = next.joinToString(":")
            Privileged.exec(
                if (value.isEmpty()) "settings delete secure enabled_accessibility_services"
                else "settings put secure enabled_accessibility_services '$value'; settings put secure accessibility_enabled 1"
            )
        }
    }
}
