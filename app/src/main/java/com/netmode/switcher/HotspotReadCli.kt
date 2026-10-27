package com.netmode.switcher

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Read-only, bounded SoftAp callback snapshot in the privileged shell. Never starts an AP. */
object HotspotReadCli {
    @JvmStatic fun main(args: Array<String>) {
        val result = JSONObject()
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val context = at.getMethod("getSystemContext").invoke(thread) as Context
            val wifi = context.getSystemService(Context.WIFI_SERVICE)
            fun call(o: Any, name: String, vararg values: Any): Any? = runCatching {
                o.javaClass.methods.first { it.name == name && it.parameterCount == values.size }.invoke(o, *values)
            }.getOrNull()
            result.put("state", call(wifi, "getWifiApState"))
            result.put("bridged", call(wifi, "isBridgedApConcurrencySupported") ?: JSONObject.NULL)
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val clientsReady = CountDownLatch(1)
            val infoReady = CountDownLatch(1)
            val capabilityReady = CountDownLatch(1)
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, params ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === params?.firstOrNull()
                    "toString" -> "NetModeSwitch snapshot"
                    else -> {
                        synchronized(result) {
                            when (method.name) {
                                "onConnectedClientsChanged" -> if (params?.size == 1 && params[0] is List<*>) {
                                    val clients = JSONArray()
                                    (params[0] as List<*>).filterNotNull().forEach { client ->
                                        clients.put(JSONObject().put("mac", call(client, "getMacAddress")?.toString())
                                            .put("instance", call(client, "getApInstanceIdentifier")?.toString()))
                                    }
                                    result.put("clients", clients)
                                    clientsReady.countDown()
                                }
                                "onInfoChanged" -> {
                                    val items = (params?.firstOrNull() as? List<*>) ?: listOfNotNull(params?.firstOrNull())
                                    val infos = JSONArray()
                                    items.filterNotNull().forEach { info ->
                                        infos.put(JSONObject().put("frequency", call(info, "getFrequency"))
                                            .put("bandwidth", call(info, "getBandwidth")))
                                    }
                                    result.put("infos", infos)
                                    infoReady.countDown()
                                }
                                "onCapabilityChanged" -> params?.firstOrNull()?.let { cap ->
                                    result.put("max", call(cap, "getMaxSupportedClients"))
                                    for ((key, feature) in listOf("2" to "24G", "5" to "5G", "6" to "6G")) {
                                        val supported = runCatching {
                                            val flag = cap.javaClass.getField("SOFTAP_FEATURE_BAND_${feature}_SUPPORTED").getLong(null)
                                            cap.javaClass.getMethod("areFeaturesSupported", Long::class.javaPrimitiveType).invoke(cap, flag) as Boolean
                                        }.getOrNull()
                                        result.put("supported$key", supported ?: JSONObject.NULL)
                                    }
                                    for ((key, band) in listOf("2" to 1, "5" to 2, "6" to 8)) {
                                        val channels = call(cap, "getSupportedChannelList", band) as? IntArray
                                        if (channels != null) result.put("channels$key", JSONArray(channels.toList()))
                                    }
                                    capabilityReady.countDown()
                                }
                            }
                        }
                        null
                    }
                }
            }
            val register = wifi.javaClass.getMethod("registerSoftApCallback", Executor::class.java, callbackClass)
            register.invoke(wifi, Executor { it.run() }, callback)
            try {
                // Initial callbacks include empty lists when off. Bound the whole wait to 2 s.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                for (latch in listOf(clientsReady, infoReady, capabilityReady)) {
                    latch.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                }
            } finally {
                wifi.javaClass.getMethod("unregisterSoftApCallback", callbackClass).invoke(wifi, callback)
            }
        } catch (t: Throwable) {
            result.put("error", (t.cause ?: t).javaClass.simpleName)
        }
        synchronized(result) { println(result.toString()) }
        System.exit(0)
    }
}
