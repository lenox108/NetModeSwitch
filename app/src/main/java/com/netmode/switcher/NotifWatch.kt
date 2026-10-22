package com.netmode.switcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * "Уведомление от приложения": which apps have a notification up, and whether a lasting one.
 * The listener sees only package names and the "ongoing" flag here; nothing of what the
 * notifications say is read or kept.
 */
object NotifWatch {

    /** Whether the system has bound the listener; until then nothing can be told. */
    @Volatile var connected = false
        internal set

    /** Package → whether one of its notifications is lasting (ongoing). */
    @Volatile var active: Map<String, Boolean> = emptyMap()
        private set

    internal fun update(list: Array<StatusBarNotification>?) {
        val now = list.orEmpty().groupBy { it.packageName }.mapValues { (_, ns) -> ns.any { it.isOngoing } }
        if (now == active) return
        active = now
        ConditionWatch.evaluate(Trigger.NOTIFY_APP)
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
        NotifWatch.update(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        NotifWatch.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotifWatch.update(runCatching { activeNotifications }.getOrNull())

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotifWatch.update(runCatching { activeNotifications }.getOrNull())
}
