package com.netmode.switcher

import android.os.IBinder

/**
 * Starts or stops any VPN app through the system VPN service, run by the privileged shell
 * through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.VpnCli on <pkg> <user>
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.VpnCli off <pkg> <user>
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.VpnCli restore <pkg|-> <user>
 *
 * "on" makes the app the always-on VPN, which has the system start its VpnService at once, and
 * prints the previous always-on package as "previous=<pkg|->". The setting stays: every change
 * of the always-on package revokes the VPN running now (Vpn.setAlwaysOnPackageInternal prepares
 * the new package), so putting the old value back right away dropped the VPN seconds after it
 * came up. "restore" puts that value back when the VPN is to go off, which disconnects it too.
 * "off" revokes the app's VPN the way the system settings do. None of them needs the app's
 * tile, the screen, or an unlocked phone.
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
            val pkg = args[1]
            val user = args.getOrNull(2)?.toIntOrNull() ?: 0

            fun setAlwaysOn(p: String?) {
                val set = methods.first { it.name == "setAlwaysOnVpnPackage" }
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

            when (args[0]) {
                "on" -> {
                    val previous = methods.first { it.name == "getAlwaysOnVpnPackage" }.invoke(svc, user) as String?
                    // Even when it is already the always-on app: setting it is what starts the VPN.
                    setAlwaysOn(pkg)
                    println("previous=" + (previous ?: "-"))
                }
                "restore" -> setAlwaysOn(pkg.takeIf { it != "-" })
                else -> {
                    val prepare = methods.first { it.name == "prepareVpn" }
                    // Replacing the app with the legacy VPN placeholder is how Settings disconnects it.
                    prepare.invoke(svc, pkg, "[Legacy VPN]", user)
                }
            }
            println("ok")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }
}
