package com.netmode.switcher

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place any mode change goes through. The mutex serialises concurrent triggers,
 * which is what gives the agreed "last event wins" behaviour.
 */
object ModeController {

    /** Where a change came from, for the log. Rule chains are guarded in AppFreezer. */
    enum class Source { MANUAL, TILE, SCHEDULE, WIFI, BOOT }

    /** Last known mode per slot; a missing or null value means unknown or not one of our presets. */
    val current = MutableStateFlow<Map<Int, NetMode?>>(emptyMap())

    private val lock = Mutex()

    fun modeOf(slot: Int): NetMode? = current.value[slot]

    /** Under the lock, so a read that started before a write cannot record a stale mode after it. */
    suspend fun read(slot: Int): Result<NetMode?> = lock.withLock {
        runCatching {
            val mask = Telephony.readMask(slot)
            if (mask < 0L) error("Не удалось прочитать режим SIM ${slot + 1}")
            NetMode.of(mask).also { remember(slot, it) }
        }
    }

    suspend fun readAll(): Map<Int, NetMode?> {
        Sims.active().forEach { read(it.slot) }
        return current.value
    }

    suspend fun apply(slot: Int, target: NetMode, source: Source): Result<NetMode> = lock.withLock {
        runCatching {
            // Never trust the write. A silent OEM no-op is the failure mode to catch here:
            // without the read-back it would look like success and lie to the user.
            val back = writeVerified(slot, Profile.maskFor(target, slot)) { NetMode.of(it) == target }
            val actual = NetMode.of(back)
            if (actual != target) {
                error("SIM ${slot + 1}: просили ${target.label}, радио сообщает ${Raf.describe(back)}")
            }
            remember(slot, actual)
            Log.i(TAG, "slot $slot applied ${target.label} (source=$source)")
            NetModeTiles.refresh()
            actual
        }
    }

    /** Writes a raw mask — a user's own setting put back as it was, not one of the presets. */
    suspend fun applyMask(slot: Int, mask: Long): Result<NetMode?> = lock.withLock {
        runCatching {
            val back = writeVerified(slot, mask) { it == mask }
            if (back != mask) error("SIM ${slot + 1}: просили ${Raf.describe(mask)}, радио сообщает ${Raf.describe(back)}")
            NetMode.of(back).also { remember(slot, it); NetModeTiles.refresh() }
        }
    }

    /**
     * Writes [mask] and reads it back, retrying a refused or not yet applied write.
     *
     * `cmd phone` can refuse a write while the radio is busy — seen once as a Wi-Fi rule fired
     * on arrival home. A single refusal left the rule undone with nothing to try again, since
     * the rule fires only on the event, so a few spaced attempts are made before giving up. A write reported as refused is still read back: what matters is the radio.
     * Returns the last mask read; throws when no write was accepted and the mode is not there.
     */
    private suspend fun writeVerified(slot: Int, mask: Long, done: (Long) -> Boolean): Long {
        var back = -1L
        var accepted = false
        for (attempt in 0 until WRITE_ATTEMPTS) {
            if (attempt > 0) {
                Log.w(TAG, "slot $slot write attempt $attempt failed: ${Telephony.lastWriteReply}")
                delay(WRITE_RETRY_MS * attempt)
            }
            accepted = Telephony.writeMask(slot, mask) || accepted
            back = Telephony.readMask(slot)
            if (done(back)) return back
        }
        if (!accepted) {
            val reply = Telephony.lastWriteReply.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            error("Команда телефонии не приняла запись для SIM ${slot + 1}" +
                " (попыток: $WRITE_ATTEMPTS${reply?.let { ", ответ: ${it.take(120)}" } ?: ""})")
        }
        return back
    }

    /**
     * Applies to a rule target, which may be a single slot or [SLOT_ALL]. Every slot is
     * attempted even if an earlier one failed, so one dead SIM cannot silently swallow the
     * other; the first failure is what surfaces.
     */
    suspend fun applyTarget(target: Int, mode: NetMode, source: Source): Result<Unit> {
        val slots = Sims.resolve(target)
        if (slots.isEmpty()) return Result.failure(IllegalStateException("Нет активных SIM"))
        if (target != SLOT_ALL && Sims.active().none { it.slot == target }) {
            return Result.failure(IllegalStateException("SIM ${target + 1} не активна — режим сети не менялся"))
        }
        var failure: Throwable? = null
        slots.forEach { slot ->
            apply(slot, mode, source).onFailure { if (failure == null) failure = it }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** Reads the real current mode rather than flipping a stored flag, so it cannot desync. */
    suspend fun toggle(slot: Int, source: Source): Result<NetMode> {
        val now = read(slot).getOrNull()
        // Down to 3G from whichever of 4G/5G was on, and back up to that same one.
        return apply(slot, toggleTarget(slot, now), source)
    }

    /**
     * What the toggle switches [slot] to from [now]. With a chosen pair: between those two, and
     * to the first from anything else. Without: 4G/5G down to 3G, anything else back up.
     */
    fun toggleTarget(slot: Int, now: NetMode?): NetMode {
        Prefs.togglePair(slot)?.let { (first, second) -> return if (now == first) second else first }
        return if (now?.high == true) NetMode.THREE_G else Prefs.lastHigh(slot)
    }

    /**
     * Stores the mode and, when it differs from the last one seen, reports the change to the
     * network-mode triggers — outside this call, since those may change modes themselves.
     * A read that finds a new mode reports it too: that is a change made elsewhere, e.g. in
     * the system settings.
     */
    private fun remember(slot: Int, mode: NetMode?) {
        val before = current.value[slot]
        val known = current.value.containsKey(slot)
        current.value = current.value.toMutableMap().apply { put(slot, mode) }
        if (mode?.high == true) Prefs.setLastHigh(slot, mode)
        if (before != mode) StatusNotification.update()
        if (mode != null && (!known || before != mode)) NetWatch.changed(slot, mode)
    }

    private const val TAG = "netmode"
    private const val WRITE_ATTEMPTS = 3
    private const val WRITE_RETRY_MS = 1_500L
}
