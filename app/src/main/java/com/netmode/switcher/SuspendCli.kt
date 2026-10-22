package com.netmode.switcher

import android.os.IBinder

/**
 * Suspends apps through the package service directly, run by the privileged shell through
 * app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SuspendCli suspend <user> <message> <pkg>...
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SuspendCli unsuspend <user> <pkg>...
 *
 * `pm suspend` cannot attach a button to the system's "app paused" dialog (and on OxygenOS
 * rejects --suspending-package), but the service call can: the dialog then offers
 * "unpause", and the system lifts the suspension itself when it is tapped.
 * Prints "ok=<packages the call could not suspend>" or "error=...".
 */
object SuspendCli {

    /** SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND */
    private const val BUTTON_ACTION_UNSUSPEND = 1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "package") as IBinder
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val method = pm.javaClass.methods.first { it.name == "setPackagesSuspendedAsUser" }

            if (args.firstOrNull() == "sig") {
                println(method.parameterTypes.joinToString { it.simpleName })
                return
            }
            val suspend = args[0] == "suspend"
            val user = args[1].toInt()
            val message = if (suspend) args[2] else null
            val pkgs = args.drop(if (suspend) 3 else 2).toTypedArray()
            val caller = if (android.os.Process.myUid() == 0) "root" else "com.android.shell"

            val dialog = if (suspend) {
                val b = Class.forName("android.content.pm.SuspendDialogInfo\$Builder").getConstructor().newInstance()
                b.javaClass.getMethod("setMessage", String::class.java).invoke(b, message)
                b.javaClass.getMethod("setNeutralButtonAction", Int::class.javaPrimitiveType)
                    .invoke(b, BUTTON_ACTION_UNSUSPEND)
                b.javaClass.getMethod("build").invoke(b)
            } else null

            // Parameter lists differ between releases; fill them by type. Ints after the
            // caller package are user ids (suspending user, then target user); any int before
            // it is the flags word.
            val types = method.parameterTypes
            val callerIndex = types.indexOfLast { it == String::class.java }
            fun call(who: String): Array<*>? {
                val params = arrayOfNulls<Any>(types.size)
                for (i in types.indices) {
                    params[i] = when {
                        types[i] == Array<String>::class.java -> pkgs
                        types[i] == Boolean::class.javaPrimitiveType -> suspend
                        i == callerIndex -> who
                        types[i].simpleName == "SuspendDialogInfo" -> dialog
                        types[i] == Int::class.javaPrimitiveType -> if (i > callerIndex) user else 0
                        else -> null
                    }
                }
                return method.invoke(pm, *params) as? Array<*>
            }
            val failed = call(caller)
            // A suspension is keyed by who made it: one made through Shizuku ("shell") is not
            // lifted by root's own call, so root lifts both.
            if (!suspend && caller == "root") runCatching { call("com.android.shell") }
            println("ok=${failed?.joinToString(",").orEmpty()}")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }
}
