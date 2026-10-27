package com.netmode.switcher

import android.os.IBinder

/**
 * Reads and changes the Wi-Fi hotspot settings through the Wi-Fi service, run by the privileged
 * shell through app_process like [HotspotCli]:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.ApConfigCli get
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.ApConfigCli set key=value ...
 *
 * "get" prints one "key=value" per line. "set" starts from the current configuration, changes
 * only the keys given, and prints "ok" or "error=...". Keys:
 *   ssid, pass, sec (open|wpa2|wpa3|wpa3t), band (2|5|6|25), channel (0 = auto),
 *   hidden (0|1), clients (0 = no limit), autooff (0|1), timeout (minutes),
 *   ax (0|1), mac (random|device)
 *
 * SoftApConfiguration and its Builder are system APIs, so everything goes through reflection.
 */
object ApConfigCli {

    // SoftApConfiguration constants (stable since Android 11).
    private const val BAND_2GHZ = 1
    private const val BAND_5GHZ = 2
    private const val BAND_6GHZ = 8
    private const val SEC_OPEN = 0
    private const val SEC_WPA2 = 1
    private const val SEC_WPA3_TRANSITION = 2
    private const val SEC_WPA3 = 3
    private const val MAC_NONE = 0
    private const val MAC_PERSISTENT = 1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val current = wifi.javaClass.methods.first { it.name == "getSoftApConfiguration" }.invoke(wifi)!!

            when (args.firstOrNull()) {
                "get" -> print(current)
                "check", "set" -> {
                    val cfg = build(current, args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") })
                    if (args[0] == "check") {
                        println("ok")
                    } else {
                        val set = wifi.javaClass.methods.first { it.name == "setSoftApConfiguration" }
                        val params = set.parameterTypes.map { t ->
                            if (t == String::class.java) (if (android.os.Process.myUid() == 0) "root" else "com.android.shell") else cfg
                        }.toTypedArray()
                        val ok = set.invoke(wifi, *params) as? Boolean ?: true
                        println(if (ok) "ok" else "error=rejected")
                    }
                }
                else -> println("error=usage")
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }

    private fun call(o: Any, name: String): Any? =
        runCatching { o.javaClass.getMethod(name).invoke(o) }.getOrNull()

    private fun print(c: Any) {
        val ssid = call(c, "getWifiSsid")?.toString()?.trim('"') ?: call(c, "getSsid")
        println("ssid=$ssid")
        println("pass=${call(c, "getPassphrase") ?: ""}")
        println("sec=" + when (call(c, "getSecurityType") as? Int) {
            SEC_OPEN -> "open"; SEC_WPA2 -> "wpa2"; SEC_WPA3_TRANSITION -> "wpa3t"; SEC_WPA3 -> "wpa3"; else -> "other"
        })
        val band = call(c, "getBand") as? Int ?: 0
        println("band=" + listOfNotNull(
            "2".takeIf { band and BAND_2GHZ != 0 }, "5".takeIf { band and BAND_5GHZ != 0 },
            "6".takeIf { band and BAND_6GHZ != 0 },
        ).joinToString(""))
        println("channel=${call(c, "getChannel") ?: 0}")
        val channels = call(c, "getChannels") as? android.util.SparseIntArray
        if (channels != null) println("radio=" + (0 until channels.size()).joinToString(",") {
            "${channels.keyAt(it)}:${channels.valueAt(it)}"
        })
        println("hidden=${if (call(c, "isHiddenSsid") == true) 1 else 0}")
        println("clients=${call(c, "getMaxNumberOfClients") ?: 0}")
        println("autooff=${if (call(c, "isAutoShutdownEnabled") == true) 1 else 0}")
        println("timeout=${((call(c, "getShutdownTimeoutMillis") as? Long) ?: 0L).coerceAtLeast(0) / 60_000}")
        println("ax=${if (call(c, "isIeee80211axEnabled") == false) 0 else 1}")
        println("mac=" + if (call(c, "getMacRandomizationSetting") == MAC_NONE) "device" else "random")
    }

    private fun build(current: Any, set: Map<String, String>): Any {
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val b = builderClass.getConstructor(current.javaClass).newInstance(current)
        fun invoke(name: String, vararg a: Any?) {
            val m = builderClass.methods.first { it.name == name && it.parameterCount == a.size }
            m.invoke(b, *a)
        }
        val pass = set["pass"] ?: (call(current, "getPassphrase") as String?)
        set["ssid"]?.let { invoke("setSsid", it) }
        if ("sec" in set || "pass" in set) {
            val sec = when (set["sec"]) {
                "open" -> SEC_OPEN; "wpa2" -> SEC_WPA2; "wpa3t" -> SEC_WPA3_TRANSITION; "wpa3" -> SEC_WPA3
                else -> call(current, "getSecurityType") as Int
            }
            invoke("setPassphrase", if (sec == SEC_OPEN) null else pass, sec)
        }
        val bandArg = set["band"]
        val channel = set["channel"]?.toIntOrNull()
        if ("radio" in set) {
            val entries = ApRadio.parse(set.getValue("radio"))
            require(entries.isNotEmpty()) { "Invalid radio configuration" }
            if (entries.size > 1) {
                require(android.os.Build.VERSION.SDK_INT >= 31) { "Concurrent APs require Android 12" }
                val channels = android.util.SparseIntArray()
                entries.forEach { (band, ch) -> channels.put(band, ch) }
                invoke("setChannels", channels)
            } else {
                val (band, ch) = entries.entries.single()
                if (ch > 0) invoke("setChannel", ch, band) else invoke("setBand", band)
            }
        } else if (bandArg != null || channel != null) {
            var band = 0
            val spec = bandArg ?: ""
            if ('2' in spec) band = band or BAND_2GHZ
            if ('5' in spec) band = band or BAND_5GHZ
            if ('6' in spec) band = band or BAND_6GHZ
            if (band == 0) band = call(current, "getBand") as Int
            if (channel != null && channel > 0) invoke("setChannel", channel, band) else invoke("setBand", band)
        }
        set["hidden"]?.let { invoke("setHiddenSsid", it == "1") }
        set["clients"]?.toIntOrNull()?.let { invoke("setMaxNumberOfClients", it) }
        set["autooff"]?.let { invoke("setAutoShutdownEnabled", it == "1") }
        // 0 used to mean "system default"; Android 16 rejects it ("Invalid timeout value: 0")
        // and takes -1 (SoftApConfiguration.DEFAULT_TIMEOUT) instead.
        set["timeout"]?.toLongOrNull()?.let { min ->
            invoke("setShutdownTimeoutMillis", if (min > 0) min * 60_000 else -1L)
        }
        set["ax"]?.let { invoke("setIeee80211axEnabled", it == "1") }
        set["mac"]?.let { invoke("setMacRandomizationSetting", if (it == "device") MAC_NONE else MAC_PERSISTENT) }
        return builderClass.getMethod("build").invoke(b)!!
    }
}
