package com.netmode.switcher

import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** "Отладка по Wi-Fi": the developer option, switched through the privileged shell. */
internal object AdbWifi {
    const val SETTING = "adb_wifi_enabled"

    /** Wireless debugging exists from Android 11. */
    const val MIN_SDK = 30

    fun isOn(value: String?): Boolean = value == "1"

    fun command(on: Boolean) = "settings put global $SETTING ${if (on) 1 else 0}"
}

class AdbWifiTileService : TileService() {

    private val busy = AtomicBoolean(false)

    /** The setting itself is readable with no privilege, so listening costs no shell. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = repaint()
    }

    private fun setting() = Settings.Global.getString(contentResolver, AdbWifi.SETTING)

    override fun onStartListening() {
        super.onStartListening()
        contentResolver.registerContentObserver(Settings.Global.getUriFor(AdbWifi.SETTING), false, observer)
        repaint()
    }

    override fun onStopListening() {
        contentResolver.unregisterContentObserver(observer)
        super.onStopListening()
    }

    private fun repaint() {
        val tile = qsTile ?: return
        val on = AdbWifi.isOn(setting())
        val supported = Compat.SDK >= AdbWifi.MIN_SDK
        tile.icon = Icon.createWithResource(this, R.drawable.ic_adb_wifi)
        tile.label = getString(R.string.adb_wifi_title)
        tile.subtitle = getString(when {
            !supported -> R.string.adb_wifi_unsupported
            busy.get() -> R.string.working
            on -> R.string.w_on
            else -> R.string.w_off
        })
        tile.state = when {
            !supported || busy.get() -> Tile.STATE_UNAVAILABLE
            on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Compat.SDK < AdbWifi.MIN_SDK) return
        // Debugging access from a locked phone is exactly what the lock is there to stop.
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (!busy.compareAndSet(false, true)) return
        repaint()
        val want = !AdbWifi.isOn(setting())
        App.scope.launch {
            val failure = runCatching { Privileged.exec(AdbWifi.command(want)) }.exceptionOrNull()
            // The system takes it back by itself when there is no Wi-Fi to listen on.
            delay(CONFIRM_MS)
            val refused = failure != null || AdbWifi.isOn(setting()) != want
            busy.set(false)
            withContext(Dispatchers.Main) {
                repaint()
                if (refused) Toast.makeText(applicationContext, R.string.adb_wifi_failed, Toast.LENGTH_LONG).show()
            }
            Prefs.log(when {
                failure != null -> "отладка по Wi-Fi: ошибка ${failure.message}"
                refused -> "отладка по Wi-Fi: система не включила — нужен Wi-Fi"
                want -> "отладка по Wi-Fi включена"
                else -> "отладка по Wi-Fi выключена"
            })
        }
    }

    private companion object {
        const val CONFIRM_MS = 1_500L
    }
}
