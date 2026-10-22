package com.netmode.switcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Paired Bluetooth devices, from three sources merged by address:
 *  1. the platform list — complete, but empty whenever Bluetooth is switched off;
 *  2. the stack's own pairing store, /data/misc/bluedroid/bt_config.conf — readable with root
 *     only, and there regardless of the adapter state;
 *  3. devices this app has seen connect, remembered in [Prefs.knownBt].
 */
object BtDevices {

    private val MAC = Regex("^\\[([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})\\]$")

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun saved(ctx: Context): List<BtDevice> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, BtDevice>()
        fun add(address: String, name: String?) {
            val key = address.uppercase()
            if (name.isNullOrBlank()) found.putIfAbsent(key, BtDevice(key, key))
            else found[key] = BtDevice(key, name)
        }

        runCatching {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                ?.bondedDevices.orEmpty()
                .forEach { add(it.address, Compat.btName(it)) }
        }
        runCatching { fromConfig(Privileged.exec("cat /data/misc/bluedroid/bt_config.conf 2>/dev/null")) }
            .getOrDefault(emptyList())
            .forEach { add(it.address, it.name) }
        Prefs.knownBt.forEach { add(it.address, it.name) }

        found.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Addresses of devices connected right now. BluetoothDevice.isConnected() is hidden API,
     * reached through the bypass the app already sets up; empty without the permission.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun connected(ctx: Context): Set<String> = Prefs.btConnected + runCatching {
        val adapter = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return@runCatching emptySet<String>()
        val method = android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected")
        adapter.bondedDevices.filter { runCatching { method.invoke(it) as Boolean }.getOrDefault(false) }
            .map { it.address.uppercase() }.toSet()
    }.getOrDefault(emptySet())

    /** Remembers a device seen connecting, so it stays pickable with Bluetooth off. */
    fun remember(address: String, name: String?) {
        if (name.isNullOrBlank()) return
        val key = address.uppercase()
        Prefs.knownBt = Prefs.knownBt.filterNot { it.address == key } + BtDevice(key, name)
    }

    /** Sections of bt_config.conf that carry a bond key, i.e. paired devices. */
    private fun fromConfig(text: String): List<BtDevice> {
        val out = mutableListOf<BtDevice>()
        var address: String? = null
        var name: String? = null
        var bonded = false
        fun flush() {
            val a = address
            if (a != null && bonded) out += BtDevice(a, name ?: a)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                flush()
                address = MAC.find(line)?.groupValues?.get(1)
                name = null
                bonded = false
                continue
            }
            if (address == null) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when {
                key == "Name" -> name = value
                key == "LinkKey" || key.startsWith("LE_KEY_") -> bonded = true
            }
        }
        flush()
        return out
    }
}
