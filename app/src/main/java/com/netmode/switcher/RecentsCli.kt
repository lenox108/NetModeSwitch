package com.netmode.switcher

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder

/**
 * The recents' cards, through the activity task service, run by the privileged shell through
 * app_process like [SuspendCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.RecentsCli list <user>
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.RecentsCli remove <taskId>...
 *
 * "list" prints "@T <taskId> <pkg>#<user>" for each card of that user and its profiles
 * (clones included). "remove" takes cards off, as a swipe in the recents does, and prints
 * "@N <how many went>". Ending an app's processes leaves its card: only this removes it.
 * Errors print "error=...".
 */
object RecentsCli {

    /** ActivityManager.RECENT_IGNORE_UNAVAILABLE: what the launcher's recents list shows. */
    private const val RECENT_IGNORE_UNAVAILABLE = 0x0002

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            when (args.firstOrNull()) {
                "list" -> {
                    val user = args[1].toInt()
                    val slice = atm.javaClass.getMethod("getRecentTasks", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(atm, 100, RECENT_IGNORE_UNAVAILABLE, user) ?: return done()
                    val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
                    for (t in list) {
                        if (t == null) continue
                        val id = field(t, "taskId") as? Int ?: continue
                        val u = field(t, "userId") as? Int ?: user
                        val pkg = (field(t, "baseIntent") as? Intent)?.component?.packageName
                            ?: (field(t, "realActivity") as? ComponentName)?.packageName
                            ?: (field(t, "baseActivity") as? ComponentName)?.packageName
                            ?: continue
                        println("@T $id $pkg#$u")
                    }
                }
                "remove" -> {
                    val m = atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
                    val n = args.drop(1).mapNotNull { it.toIntOrNull() }
                        .count { id -> runCatching { m.invoke(atm, id) as? Boolean == true }.getOrDefault(false) }
                    println("@N $n")
                }
                else -> println("error=usage: list <user> | remove <taskId>...")
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        done()
    }

    /** A TaskInfo field by name, public or hidden, wherever it is declared. */
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        return null
    }

    private fun done(): Nothing {
        System.exit(0)
        throw IllegalStateException()
    }
}
