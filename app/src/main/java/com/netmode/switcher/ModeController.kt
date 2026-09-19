package com.netmode.switcher

import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place any mode change goes through. The mutex serialises concurrent triggers,
 * which is what gives the agreed "last event wins" behaviour.
 */
object ModeController {

    enum class Source { MANUAL, TILE, SCHEDULE, WIFI, BOOT }

    /** Last known mode, or null when unknown / not one of our two presets. */
    val current = MutableStateFlow<NetMode?>(null)

    private val lock = Mutex()

    fun subId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    suspend fun read(): Result<NetMode?> = runCatching {
        val mask = Telephony.readMask()
        if (mask < 0L) error("Не удалось прочитать режим у телефонии")
        NetMode.of(mask).also { current.value = it }
    }

    suspend fun apply(target: NetMode, source: Source): Result<NetMode> = lock.withLock {
        runCatching {
            if (!Telephony.writeMask(target.mask)) error("Команда телефонии не приняла запись")

            // Never trust the write. A silent OEM no-op is the failure mode to catch here:
            // without this check it would look like success and lie to the user.
            val back = Telephony.readMask()
            val actual = NetMode.of(back)
            if (actual != target) {
                error("Режим не применился: просили ${target.label}, радио сообщает ${Raf.describe(back)}")
            }
            current.value = actual
            Log.i(TAG, "applied ${target.label} (source=$source)")
            NetModeTileService.requestUpdate()
            actual
        }
    }

    /** Reads the real current mode rather than flipping a stored flag, so it cannot desync. */
    suspend fun toggle(source: Source): Result<NetMode> {
        val now = read().getOrNull()
        return apply(now?.other ?: NetMode.FULL, source)
    }

    private const val TAG = "netmode"
}
