package com.netmode.switcher

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connects to a saved Wi-Fi network through the Wi-Fi service, run by the privileged shell
 * through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.WifiJoinCli list
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.WifiJoinCli connect <ssid> [<bssid>]
 *
 * "list" prints one "<networkId>\t<ssid>" per saved network. "connect" prints "ok" once the
 * phone is connected to it (and to that access point, given one), or "error=...".
 *
 * `cmd wifi` can only connect with a passphrase; the saved network's own is used by the hidden
 * IWifiManager.connect, open to uid 2000 through NETWORK_SETTINGS. Its signature changed in
 * nearly every release, so the arguments are filled by type. Android 10 has no such call (its
 * WifiManager.connect went through an AsyncChannel); there enableNetwork(netId, disableOthers)
 * does the same for a NETWORK_SETTINGS caller: the system treats it as the user's choice and
 * connects.
 *
 * No BSSID on Android 10: its WifiConfigManager copies a BSSID into the saved network but has
 * no way to take it off again (a null means "unchanged", "any" fails validation), and a
 * network left tied to one access point no longer joins by itself anywhere else. Recreating
 * the network would need its passphrase, which uid 2000 may not read there
 * (READ_WIFI_CREDENTIAL). Measured on the Android 10 emulator.
 *
 * A BSSID is set on the saved network for the connection (the framework then associates with
 * that access point only) and taken off again once connected or given up, so the network is
 * not left tied to one access point — a BSSID the user set some other way is cleared too,
 * which Android's own settings never set. Roaming afterwards is the firmware's business.
 */
object WifiJoinCli {

    private const val CONNECT_WAIT_S = 10L
    private const val ASSOCIATE_WAIT_MS = 20_000L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val wifi = service()
            when (args.firstOrNull()) {
                // Android 13+ lists a network once per security variant (open/OWE…), same id.
                "list" -> configs(wifi).distinctBy { field(it, "networkId") }.forEach { c ->
                    val ssid = ssidOf(c) ?: return@forEach
                    println("${field(c, "networkId")}\t$ssid")
                }
                "connect" -> println(connect(wifi, args.getOrNull(1) ?: error("no ssid"), args.getOrNull(2)?.lowercase()))
                else -> println("error=usage")
            }
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }

    private fun service(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
        return Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private val pkg: String get() = if (android.os.Process.myUid() == 0) "root" else "com.android.shell"

    /**
     * Fills a hidden method's arguments by type: the caller's package for the first String,
     * null for the rest (attribution tags), [ints] in order for the ints, empty Bundles.
     */
    private fun call(target: Any, name: String, ints: List<Int> = emptyList(), vararg objects: Any?): Any? {
        val m = target.javaClass.methods.filter { it.name == name }.maxByOrNull { it.parameterCount }
            ?: error("no $name")
        var strings = 0
        var intAt = 0
        val args = m.parameterTypes.map { t ->
            when {
                t == String::class.java -> if (strings++ == 0) pkg else null
                t == Int::class.javaPrimitiveType -> ints.getOrElse(intAt++) { 0 }
                t == Boolean::class.javaPrimitiveType -> false
                t == Bundle::class.java -> Bundle()
                t == IBinder::class.java -> Binder()
                else -> objects.firstOrNull { it != null && t.isInstance(it) }
            }
        }.toTypedArray()
        return m.invoke(target, *args)
    }

    /** The saved networks, with their passphrases where the caller may see them. */
    private fun configs(wifi: Any): List<Any> {
        val slice = runCatching { call(wifi, "getPrivilegedConfiguredNetworks") }.getOrNull()
            ?: call(wifi, "getConfiguredNetworks")
        @Suppress("UNCHECKED_CAST")
        return (slice?.javaClass?.getMethod("getList")?.invoke(slice) as? List<Any>).orEmpty()
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getField(name).get(o)

    private fun ssidOf(c: Any): String? = (field(c, "SSID") as? String)?.removeSurrounding("\"")

    private fun connect(wifi: Any, ssid: String, bssid: String?): String {
        val config = configs(wifi).firstOrNull { ssidOf(it) == ssid }
            ?: configs(wifi).firstOrNull { ssidOf(it).equals(ssid, ignoreCase = true) }
            ?: return "error=not saved"
        val netId = field(config, "networkId") as Int

        if (bssid != null && legacy) return "error=bssid unsupported"
        if (bssid == null) {
            val result = awaitConnect(wifi, null, netId)
            return if (result != "ok" || waitConnected(wifi, netId, null)) result else "error=not connected"
        }

        config.javaClass.getField("BSSID").set(config, bssid)
        var result = awaitConnect(wifi, config, netId)
        if (result == "ok" && !waitConnected(wifi, netId, bssid)) result = "error=not connected"
        // Untie the saved network again, whatever came of the connection — not back to what it
        // held before: that may be a BSSID left by a run that was killed.
        config.javaClass.getField("BSSID").set(config, null)
        runCatching { call(wifi, "save", emptyList(), config, listener(null)) }
            .onFailure { return "error=bssid kept: ${(it.cause ?: it).message}" }
        return result
    }

    /** Android 10: no IWifiManager.connect. */
    private val legacy get() = android.os.Build.VERSION.SDK_INT < 30

    /** connect(config or null, netId, …) and its listener's answer. */
    private fun awaitConnect(wifi: Any, config: Any?, netId: Int): String {
        if (legacy) return if (enableOthersOff(wifi, netId)) "ok" else "error=rejected"
        val done = CountDownLatch(1)
        var failure: Int? = null
        val l = listener { reason -> failure = reason; done.countDown() }
        call(wifi, "connect", listOf(netId), config, l)
        if (!done.await(CONNECT_WAIT_S, TimeUnit.SECONDS)) return "error=timeout"
        return failure?.let { "error=failed $it" } ?: "ok"
    }

    /** enableNetwork(netId, disableOthers = true, pkg): the boolean must be true here. */
    private fun enableOthersOff(wifi: Any, netId: Int): Boolean {
        val m = wifi.javaClass.methods.first { it.name == "enableNetwork" }
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> netId
                Boolean::class.javaPrimitiveType -> true
                String::class.java -> pkg
                else -> null
            }
        }.toTypedArray()
        return m.invoke(wifi, *args) as? Boolean ?: false
    }

    /**
     * Whether the phone comes to be connected to [netId] (through [bssid], given one) within
     * [ASSOCIATE_WAIT_MS]; the listener only says the request was taken. With a BSSID this is
     * also what the BSSID is held on the saved network for.
     */
    private fun waitConnected(wifi: Any, netId: Int, bssid: String?): Boolean {
        val until = System.currentTimeMillis() + ASSOCIATE_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val info = runCatching { call(wifi, "getConnectionInfo") }.getOrNull()
            fun get(name: String) = info?.let { runCatching { it.javaClass.getMethod(name).invoke(it) }.getOrNull() }
            val state = get("getSupplicantState")?.toString()
            if (get("getNetworkId") == netId && state == "COMPLETED" &&
                (bssid == null || (get("getBSSID") as String?).equals(bssid, ignoreCase = true))) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * An IActionListener without its stub class: a Binder answering the interface's two calls,
     * onSuccess (1) and onFailure(int reason) (2), wrapped by the stub's own proxy. [done]
     * gets null for success.
     */
    private fun listener(done: ((Int?) -> Unit)?): Any {
        val descriptor = "android.net.wifi.IActionListener"
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
                data.enforceInterface(descriptor)
                when (code) {
                    FIRST_CALL_TRANSACTION -> done?.invoke(null)
                    FIRST_CALL_TRANSACTION + 1 -> done?.invoke(data.readInt())
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }
        return Class.forName("$descriptor\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
}
