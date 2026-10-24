package com.netmode.switcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/**
 * "Уведомление от приложения": which apps have a notification up, and whether a lasting one.
 * The trigger looks at package names and the "ongoing" flag only. The titles are read just
 * for [NotifHide]: to list what is in the shade, and for a rule that matches on a title.
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
        NotifWatch.listener = this
        NotifHide.sweep(this)
        App.scope.launch { NotifHide.release(this@NotifListener) }
        NotifWatch.update(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        NotifWatch.connected = false
        NotifWatch.listener = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { NotifHide.onPosted(this, it) }
        NotifWatch.update(runCatching { activeNotifications }.getOrNull())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotifWatch.update(runCatching { activeNotifications }.getOrNull())
}
