package com.netmode.switcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/**
 * "Уведомление от приложения": which apps have a notification up, and whether a lasting one.
 * The trigger looks at package names and the "ongoing" flag only. Titles and message bodies
 * are read by [NotifHide] for live matching and preview; bodies are not persisted.
 * App, channel and title of what was posted are kept for a day, for picking later; the text
 * of a notification is never kept.
 */
object NotifWatch {

    /** Whether the system has bound the listener; until then nothing can be told. */
    @Volatile var connected = false
        internal set

    /** The bound listener, for [NotifHide] to snooze through; null while not bound. */
    @Volatile var listener: NotifListener? = null
        internal set

    /** Package → whether one of its notifications is lasting (ongoing). */
    @Volatile var active: Map<String, Boolean> = emptyMap()
        private set

    private fun update(list: List<StatusBarNotification>) {
        val now = list.groupBy { it.packageName }.mapValues { (_, ns) -> ns.any { it.isOngoing } }
        if (now == active) return
        active = now
        ConditionWatch.evaluate(Trigger.NOTIFY_APP)
    }

    /** A notification a rule keeps out of the shade is still up as far as a condition goes. */
    internal fun refresh(listener: NotificationListenerService) {
        val up = runCatching { listener.activeNotifications.toList() }.getOrNull().orEmpty()
        val rules = Prefs.hideRules
        val hidden = if (rules.isEmpty()) emptyList() else runCatching { listener.snoozedNotifications.toList() }.getOrNull().orEmpty()
            .filter { n -> rules.any { it.matches(n) } }
        update(up + hidden)
    }

    /** Notification access is a setting the privileged shell can turn on; no settings screen needed. */
    suspend fun enable() {
        val ctx = App.ctx
        runCatching { Privileged.exec("cmd notification allow_listener ${ctx.packageName}/${NotifListener::class.java.name}") }
    }
}

class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotifWatch.connected = true
        NotifWatch.listener = this
        NotifHide.sweep(this)
        App.scope.launch { NotifRules.reconcile() }
        NotifWatch.refresh(this)
    }

    override fun onListenerDisconnected() {
        NotifWatch.connected = false
        NotifWatch.listener = null
        // Some firmwares unbind on their own; while the access is off this does nothing.
        runCatching { requestRebind(android.content.ComponentName(this, NotifListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { NotifHide.onPosted(this, it) }
        NotifWatch.refresh(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotifHide.onRemoved(this, it) }
        NotifWatch.refresh(this)
    }
}
