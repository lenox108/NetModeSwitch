package com.netmode.switcher

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Chooses the SIM used for mobile data through the subscription service, run by the
 * privileged shell through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.SimCli data <subId>
 *
 * The same call the system's own SIM settings make; it needs MODIFY_PHONE_STATE, which root
 * and shell both hold. Prints "ok" or "error=...".
 *
 *   ... SimCli uicc <subId> <1|0>
 *
 * Switches a SIM on or off the way "Использовать SIM-карту" in the system settings does
 * (setUiccApplicationsEnabled, Android 11+): the card stays in its slot, its network goes away.
 *
 *   ... SimCli esim <cardId> <portIndex> <subId|-1>
 *
 * An eSIM is switched by its profile instead, as the system's eSIM settings do: subId enables
 * that profile on the port, -1 disables whatever profile the port holds. Needs
 * WRITE_EMBEDDED_SUBSCRIPTION, which only root (or the system) has: under Shizuku the eUICC
 * service asks for the user's consent instead, reported as "error=consent".
 */
object SimCli {

    /** EuiccManager's result codes, delivered as the callback's code. */
    private const val RESULT_OK = 0
    private const val RESULT_RESOLVABLE = 1
    private const val EXTRA_DETAILED = "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DETAILED_CODE"

    /** Profile switches take seconds; the eUICC answers through the callback. */
    private const val ESIM_WAIT_S = 40L

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "esim") {
            println(runCatching { esim(args[1].toInt(), args[2].toInt(), args[3].toInt()) }
                .getOrElse { t -> (t.cause ?: t).let { "error=${it.javaClass.simpleName}: ${it.message}" } })
            System.exit(0)
        }
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
                "uicc" -> {
                    val id = args[1].toInt()
                    val on = args[2] == "1"
                    sub.javaClass.methods.first {
                        it.name == "setUiccApplicationsEnabled" && it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    }.invoke(sub, on, id)
                    println("ok")
                }
                else -> println("error=usage: data <subId> | uicc <subId> <1|0> | esim <cardId> <port> <subId|-1>")
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }

    /**
     * The eUICC service reports through a PendingIntent. Out here there is no Context to make
     * one, so the PendingIntent wraps a local binder: the activity manager hands a send() to an
     * IntentSender that is not its own straight to it, the way "pm install" gets its result.
     */
    private fun esim(card: Int, port: Int, sub: Int): String {
        val done = CountDownLatch(1)
        var code = -1
        var detail = 0
        val receiver = object : Binder() {
            override fun onTransact(what: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (what !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) return super.onTransact(what, data, reply, flags)
                runCatching {
                    data.enforceInterface("android.content.IIntentSender")
                    code = data.readInt()
                    if (data.readInt() != 0) detail = Intent.CREATOR.createFromParcel(data).getIntExtra(EXTRA_DETAILED, 0)
                }
                done.countDown()
                return true
            }
        }
        val senderClass = Class.forName("android.content.IIntentSender")
        val sender = Class.forName("android.content.IIntentSender\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, receiver)
        val callback = PendingIntent::class.java.getDeclaredConstructor(senderClass)
            .apply { isAccessible = true }.newInstance(sender)

        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "econtroller") as IBinder?
            ?: return "error=no eUICC service"
        val ctl = Class.forName("com.android.internal.telephony.euicc.IEuiccController\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        // Root resolves to "root" whatever the name; shell must name its own package.
        val pkg = "com.android.shell"
        val withPort = ctl.javaClass.methods.firstOrNull { it.name == "switchToSubscriptionWithPort" && it.parameterTypes.size == 5 }
        if (withPort != null) withPort.invoke(ctl, card, sub, port, pkg, callback)
        else ctl.javaClass.methods.first { it.name == "switchToSubscription" && it.parameterTypes.size == 4 }
            .invoke(ctl, card, sub, pkg, callback)

        if (!done.await(ESIM_WAIT_S, TimeUnit.SECONDS)) return "error=no answer from eUICC in ${ESIM_WAIT_S}s"
        return when (code) {
            RESULT_OK -> "ok"
            RESULT_RESOLVABLE -> "error=consent"
            else -> "error=eUICC code $code, detail $detail"
        }
    }
}
