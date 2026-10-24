package com.netmode.switcher

import android.app.NotificationChannel
import android.os.IBinder

/**
 * Sets an app's notification channel importance through the notification service, as the
 * channel switch in the app's notification settings does. Run as root through app_process
 * like [SuspendCli]; the service lets only system, phone and root make the call, so Shizuku's
 * shell is refused:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.ChannelCli set <pkg> <uid> <channel> <importance>
 *
 * Importance 0 (NONE) is "off": the system cancels what is up and drops every new post of the
 * channel, so nothing ever reaches the shade. Prints "ok=<importance now> was=<before>" or
 * "error=...". A channel the system keeps on (locked by the OEM, a critical device function)
 * keeps its importance; "ok=" then shows the old one and the caller falls back to snoozing.
 */
object ChannelCli {

    /** NotificationChannel.USER_LOCKED_IMPORTANCE: the change counts as the user's own. */
    private const val USER_LOCKED_IMPORTANCE = 0x00000004

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 5 && args[0] == "set") { "usage: set <pkg> <uid> <channel> <importance>" }
            val pkg = args[1]
            val uid = args[2].toInt()
            val id = args[3]
            val importance = args[4].toInt()

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            val nm = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

            val channel = read(nm, pkg, uid, id) ?: error("нет канала $id")
            val was = channel.importance
            channel.importance = importance
            runCatching {
                NotificationChannel::class.java.getMethod("lockFields", Int::class.javaPrimitiveType)
                    .invoke(channel, USER_LOCKED_IMPORTANCE)
            }
            nm.javaClass.methods.first { it.name == "updateNotificationChannelForPackage" }
                .invoke(nm, pkg, uid, channel)
            val now = read(nm, pkg, uid, id)?.importance ?: -1
            println("ok=$now was=$was")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }

    /**
     * getNotificationChannelForPackage(pkg, uid, channelId, [conversationId,] includeDeleted):
     * the conversation id came in Android 11. Filled by type, strings in that order.
     */
    private fun read(nm: Any, pkg: String, uid: Int, id: String): NotificationChannel? {
        val m = nm.javaClass.methods.first { it.name == "getNotificationChannelForPackage" }
        val strings = ArrayDeque(listOf<String?>(pkg, id, null))
        val params = m.parameterTypes.map { t ->
            when (t) {
                String::class.java -> strings.removeFirstOrNull()
                Int::class.javaPrimitiveType -> uid
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return m.invoke(nm, *params.toTypedArray()) as? NotificationChannel
    }
}
