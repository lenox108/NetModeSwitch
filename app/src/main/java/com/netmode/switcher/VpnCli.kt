package com.netmode.switcher

import android.os.IBinder

/**
 * Starts or stops any VPN app through the system VPN service, run by the privileged shell
 * through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.VpnCli on <pkg> <user>
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.VpnCli off <pkg> <user>
 *
 * "on" makes the app the always-on VPN, which has the system start its VpnService at once,
 * then restores the previous always-on setting; "off" revokes the app's VPN the way the system
 * settings do. Neither needs the app's tile, the screen, or an unlocked phone.
 * Prints "ok" or "error=...".
 */
object VpnCli {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val sm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            val binder = (sm.invoke(null, "vpn_management") ?: sm.invoke(null, "connectivity")) as IBinder
            val iface = runCatching { Class.forName("android.net.IVpnManager\$Stub") }
                .getOrElse { Class.forName("android.net.IConnectivityManager\$Stub") }
            val svc = iface.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val methods = svc.javaClass.methods

            if (args.firstOrNull() == "sig") {
                methods.filter { it.name.contains("Vpn", true) && (it.name.startsWith("prepare") || it.name.contains("AlwaysOn")) }
                    .forEach { m -> println(m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")") }
                return
            }
            val on = args[0] == "on"
            val pkg = args[1]
            val user = args.getOrNull(2)?.toIntOrNull() ?: 0

            if (on) {
                val get = methods.first { it.name == "getAlwaysOnVpnPackage" }
                val set = methods.first { it.name == "setAlwaysOnVpnPackage" }
                val previous = get.invoke(svc, user) as String?
                fun setAlwaysOn(p: String?) {
                    val params = set.parameterTypes.map { t ->
                        when (t) {
                            Int::class.javaPrimitiveType -> user
                            String::class.java -> p
                            Boolean::class.javaPrimitiveType -> false
                            else -> null
                        }
                    }.toTypedArray()
                    set.invoke(svc, *params)
                }
                setAlwaysOn(pkg)
                if (previous != pkg) {
                    Thread.sleep(3000)
                    setAlwaysOn(previous)
                }
            } else {
                val prepare = methods.first { it.name == "prepareVpn" }
                // Replacing the app with the legacy VPN placeholder is how Settings disconnects it.
                prepare.invoke(svc, pkg, "[Legacy VPN]", user)
            }
            println("ok")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }
}
