package com.netmode.switcher

import android.os.IBinder

/**
 * Suspends apps through the package service directly, run by the privileged shell through
 * app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SuspendCli suspend <user> <message> <pkg>...
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SuspendCli suspend-each <user> <pkg> <message> [<pkg> <message>]...
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SuspendCli unsuspend <user> <pkg>...
 *
 * `pm suspend` cannot attach a button to the system's "app paused" dialog (and on OxygenOS
 * rejects --suspending-package), but the service call can: the dialog then offers
 * "unpause", and the system lifts the suspension itself when it is tapped. "suspend-each"
 * gives every app a message of its own — the freezer names the app in it, which is how the
 * instant launch from the app's own icon tells which app the dialog is for (see [Freezer]).
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
            val user = args[1].toInt()
            val caller = if (android.os.Process.myUid() == 0) "root" else "com.android.shell"

            fun dialog(message: String): Any {
                val b = Class.forName("android.content.pm.SuspendDialogInfo\$Builder").getConstructor().newInstance()
                b.javaClass.getMethod("setMessage", String::class.java).invoke(b, message)
                b.javaClass.getMethod("setNeutralButtonAction", Int::class.javaPrimitiveType)
                    .invoke(b, BUTTON_ACTION_UNSUSPEND)
                return b.javaClass.getMethod("build").invoke(b)!!
            }

            // Parameter lists differ between releases; fill them by type. Ints after the
            // caller package are user ids (suspending user, then target user); any int before
            // it is the flags word.
            val types = method.parameterTypes
            val callerIndex = types.indexOfLast { it == String::class.java }
            fun call(pkgs: Array<String>, suspend: Boolean, info: Any?, who: String): Array<*>? {
                val params = arrayOfNulls<Any>(types.size)
                for (i in types.indices) {
                    params[i] = when {
                        types[i] == Array<String>::class.java -> pkgs
                        types[i] == Boolean::class.javaPrimitiveType -> suspend
                        i == callerIndex -> who
                        types[i].simpleName == "SuspendDialogInfo" -> info
                        types[i] == Int::class.javaPrimitiveType -> if (i > callerIndex) user else 0
                        else -> null
                    }
                }
                return method.invoke(pm, *params) as? Array<*>
            }
            val failed: List<Any?> = when (args[0]) {
                "suspend" -> call(args.drop(3).toTypedArray(), true, dialog(args[2]), caller)?.toList().orEmpty()
                "suspend-each" -> args.drop(2).chunked(2).flatMap { (p, m) ->
                    runCatching { call(arrayOf(p), true, dialog(m), caller)?.toList().orEmpty() }.getOrDefault(listOf(p))
                }
                else -> {
                    val pkgs = args.drop(2).toTypedArray()
                    val left = call(pkgs, false, null, caller)?.toList().orEmpty()
                    // A suspension is keyed by who made it: one made through Shizuku ("shell") is
                    // not lifted by root's own call, so root lifts both.
                    if (caller == "root") runCatching { call(pkgs, false, null, "com.android.shell") }
                    left
                }
            }
            println("ok=${failed.joinToString(",")}")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }
}
