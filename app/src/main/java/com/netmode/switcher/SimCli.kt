package com.netmode.switcher

import android.os.IBinder

/**
 * Chooses the SIM used for mobile data through the subscription service, run by the
 * privileged shell through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SimCli data <subId>
 *
 * The same call the system's own SIM settings make; it needs MODIFY_PHONE_STATE, which root
 * and shell both hold. Prints "ok" or "error=...".
 */
object SimCli {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "isub") as IBinder
            val sub = Class.forName("com.android.internal.telephony.ISub\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            when (args.firstOrNull()) {
                "data" -> {
                    val id = args[1].toInt()
                    sub.javaClass.methods.first { it.name == "setDefaultDataSubId" && it.parameterTypes.size == 1 }
                        .invoke(sub, id)
                    println("ok")
                }
                else -> println("error=usage: data <subId>")
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }
}
