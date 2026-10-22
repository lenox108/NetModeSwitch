package com.netmode.switcher

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts or stops the Wi-Fi hotspot from a shell, where no app context exists.
 *
 * OxygenOS ships no shell command for tethering (`cmd wifi start-softap` is absent on user
 * builds), so this is run by the privileged shell through app_process:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.HotspotCli start|stop
 *
 * It talks to the tethering service directly, as shell (Shizuku) or root. The hotspot comes
 * up with the name and password configured in the system settings. Prints "result=<code>",
 * where 0 is success. Android 10 has no tethering service yet (it became a module in 11):
 * there the connectivity service takes the same request.
 */
object HotspotCli {

    private const val TETHERING_WIFI = 0
    private const val LISTENER = "android.net.IIntResultListener"

    @JvmStatic
    fun main(args: Array<String>) {
        val start = args.firstOrNull() != "stop"
        val caller = if (android.os.Process.myUid() == 0) "root" else "com.android.shell"
        try {
            val getService = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "tethering") as IBinder?
                ?: return legacy(start, caller, getService.invoke(null, "connectivity") as IBinder)
            val connector = Class.forName("android.net.ITetheringConnector\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!

            val latch = CountDownLatch(1)
            var result = -1
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        data.enforceInterface(LISTENER)
                        result = data.readInt()
                        latch.countDown()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            val listener = Class.forName("$LISTENER\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, callback)

            val methods = connector.javaClass.methods
            if (start) {
                val request = Class.forName("android.net.TetheringRequestParcel").getConstructor().newInstance()
                fun set(name: String, value: Any?) = runCatching {
                    request.javaClass.getField(name).set(request, value)
                }
                set("tetheringType", TETHERING_WIFI)
                set("exemptFromEntitlementCheck", true)
                set("showProvisioningUi", false)
                set("connectivityScope", 1)  // CONNECTIVITY_SCOPE_GLOBAL: share the upstream
                val m = methods.first { it.name == "startTethering" }
                val params = arrayOfNulls<Any>(m.parameterCount)
                params[0] = request
                params[1] = caller
                params[params.size - 1] = listener
                m.invoke(connector, *params)
            } else {
                val m = methods.first { it.name == "stopTethering" }
                val params = arrayOfNulls<Any>(m.parameterCount)
                params[0] = TETHERING_WIFI
                params[1] = caller
                params[params.size - 1] = listener
                m.invoke(connector, *params)
            }
            latch.await(8, TimeUnit.SECONDS)
            println("result=$result")
        } catch (t: Throwable) {
            println("error=${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}")
        }
        System.exit(0)
    }

    /**
     * Android 10: IConnectivityManager.startTethering(type, ResultReceiver, showUi, caller) and
     * stopTethering(type, caller). The result comes to the receiver on a binder thread.
     */
    private fun legacy(start: Boolean, caller: String, binder: IBinder) {
        try {
            val cm = Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val methods = cm.javaClass.methods
            if (start) {
                val latch = CountDownLatch(1)
                var result = -1
                val receiver = object : android.os.ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                        result = resultCode
                        latch.countDown()
                    }
                }
                val m = methods.first { it.name == "startTethering" }
                val params = arrayOfNulls<Any>(m.parameterCount)
                params[0] = TETHERING_WIFI
                params[1] = receiver
                if (params.size > 2) params[2] = false
                if (params.size > 3) params[3] = caller
                m.invoke(cm, *params)
                latch.await(8, TimeUnit.SECONDS)
                println("result=$result")
            } else {
                val m = methods.first { it.name == "stopTethering" }
                val params = arrayOfNulls<Any>(m.parameterCount)
                params[0] = TETHERING_WIFI
                if (params.size > 1) params[1] = caller
                m.invoke(cm, *params)
                println("result=0")
            }
        } catch (t: Throwable) {
            println("error=${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}")
        }
        System.exit(0)
    }
}
