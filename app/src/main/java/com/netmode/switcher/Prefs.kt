package com.netmode.switcher

import android.content.Context
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SharedPreferences rather than DataStore on purpose: the alarm receiver, the Wi-Fi receiver
 * and the QS tile all need this synchronously, before they are allowed to suspend.
 */
object Prefs {

    private const val FILE = "netmode"
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_WIFI = "wifi_rules"
    private const val KEY_CATCH_UP = "catch_up_on_boot"
    private const val KEY_RELIABLE_WIFI = "reliable_wifi"
    private const val KEY_EVENTS = "events"
    private const val KEY_LAST_SSID = "last_ssid"
    private const val KEY_IGNORE_WIFI_OFF = "ignore_wifi_off"
    private const val MAX_EVENTS = 20

    private val sp by lazy { App.ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    var schedules: List<ScheduleRule>
        get() = decodeList(sp.getString(KEY_SCHEDULES, "[]")!!, ScheduleRule::fromJson)
        set(value) = sp.edit().putString(KEY_SCHEDULES, encodeList(value) { it.toJson() }).apply()

    var wifiRules: List<WifiRule>
        get() = decodeList(sp.getString(KEY_WIFI, "[]")!!, WifiRule::fromJson)
        set(value) = sp.edit().putString(KEY_WIFI, encodeList(value) { it.toJson() }).apply()

    var catchUpOnBoot: Boolean
        get() = sp.getBoolean(KEY_CATCH_UP, true)
        set(value) = sp.edit().putBoolean(KEY_CATCH_UP, value).apply()

    /**
     * On: a persistent watcher service, which is the only arrangement that survives OxygenOS
     * dropping the PendingIntent registration. Off: notification-free but occasionally misses
     * events, and the disconnect half cannot start its service from the background at all.
     */
    var reliableWifi: Boolean
        get() = sp.getBoolean(KEY_RELIABLE_WIFI, true)
        set(value) = sp.edit().putBoolean(KEY_RELIABLE_WIFI, value).apply()

    /** SSID we last acted on; persisted so a disconnect is still attributable after a restart. */
    var lastSsid: String?
        get() = sp.getString(KEY_LAST_SSID, null)
        set(value) = sp.edit().putString(KEY_LAST_SSID, value).apply()

    /**
     * Losing the network because the adapter was switched off is not the same as walking out
     * of range, and night-time power saving does exactly the former. Treating them alike would
     * flip the phone to 4G every night.
     */
    var ignoreWifiOff: Boolean
        get() = sp.getBoolean(KEY_IGNORE_WIFI_OFF, true)
        set(value) = sp.edit().putBoolean(KEY_IGNORE_WIFI_OFF, value).apply()

    val events: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString(KEY_EVENTS, "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())

    fun log(line: String) {
        val stamp = LocalDateTime.now().format(TIME)
        val next = (listOf("$stamp  $line") + events).take(MAX_EVENTS)
        sp.edit().putString(KEY_EVENTS, JSONArray(next).toString()).apply()
    }

    fun newId(): Long = System.currentTimeMillis()

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
}
