package com.netmode.switcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent

/**
 * "Мгновенно": the app on screen the moment it comes there, from the system's own window events
 * rather than a look at usage events every 2 s. Only a real screen of an app counts — the
 * keyboard, toasts and pop-ups raise window events too, and would read as leaving the app.
 */
class AppWatchService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        val activity = runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        if (!activity) return
        ConditionWatch.onForeground(pkg, cls)
    }

    companion object {
        /** While true the 2-second look is not needed (see ConditionWatch.startAppPoll). */
        @Volatile var connected = false
            private set

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
            val next = if (on) (now - me + me) else (now - me)
            val value = next.joinToString(":")
            Privileged.exec(
                if (value.isEmpty()) "settings delete secure enabled_accessibility_services"
                else "settings put secure enabled_accessibility_services '$value'; settings put secure accessibility_enabled 1"
            )
        }
    }
}
