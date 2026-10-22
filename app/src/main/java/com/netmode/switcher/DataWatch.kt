package com.netmode.switcher

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Notices the user switching mobile data on or off, for [Trigger.MOBILE_DATA] rules.
 *
 * There is no broadcast for it, so this is a settings observer: event-driven, no polling, but
 * it lives in the app process — which [WifiWatchService] keeps alive whenever such a rule
 * exists. The toggle is written as "mobile_data" or "mobile_data<subId>" depending on the
 * firmware, so every global setting with that prefix is watched and the state itself is read
 * from TelephonyManager.
 */
object DataWatch {

    private const val TAG = "netmode"

    @Volatile
    private var registered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri?.lastPathSegment?.startsWith("mobile_data") == false) return
            App.scope.launch { check() }
        }
    }

    fun register() {
        if (registered) return
        runCatching {
            App.ctx.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
            registered = true
        }.onFailure { Log.w(TAG, "data observer failed", it) }
    }

    fun unregister() {
        if (!registered) return
        runCatching { App.ctx.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    fun enabled(): Boolean = runCatching {
        App.ctx.getSystemService(TelephonyManager::class.java).isDataEnabled
    }.getOrElse {
        Settings.Global.getInt(App.ctx.contentResolver, "mobile_data", 1) == 1
    }

    /**
     * Acts only on a real change against the last state seen, so repeated notifications and
     * process restarts neither re-apply nor miss a toggle made while the app was not running.
     */
    suspend fun check() {
        val on = enabled()
        if (Prefs.lastDataOn == on) return
        Prefs.lastDataOn = on
        Log.i(TAG, "mobile data ${if (on) "on" else "off"}")
        AppFreezer.onDataChanged(on)
    }
}
