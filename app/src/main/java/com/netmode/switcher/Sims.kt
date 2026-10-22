package com.netmode.switcher

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/** A rule target meaning "every active SIM", and the default for rules made before dual-SIM. */
const val SLOT_ALL = -1

data class SimInfo(val slot: Int, val name: String) {
    val label: String get() = "SIM ${slot + 1} · $name"
}

/**
 * Discovers which slots hold a usable SIM.
 *
 * Names come from SubscriptionManager rather than the telephony database: the database is
 * readable only by root and the system — shell, and therefore Shizuku, gets a SecurityException
 * — while SubscriptionManager needs only READ_PHONE_STATE and works the same on both channels.
 * Without that permission the slots are still found by probing telephony, just unnamed.
 */
object Sims {

    private const val TAG = "netmode"
    private const val MAX_SLOTS = 4
    private const val CACHE_MS = 60_000L

    @Volatile
    private var cache: List<SimInfo> = emptyList()

    @Volatile
    private var cachedAt = 0L

    /** Last known list without touching the privileged channel; empty until the first refresh. */
    val known: List<SimInfo> get() = cache

    suspend fun active(force: Boolean = false): List<SimInfo> {
        val fresh = System.currentTimeMillis() - cachedAt < CACHE_MS
        if (!force && fresh && cache.isNotEmpty()) return cache
        return refresh()
    }

    suspend fun refresh(): List<SimInfo> = runCatching {
        val names = namesFromSubscriptions()
        val masks = readWithRetry()
        // The telephony read now and then comes back empty for a moment, for one slot or all.
        // Such an answer must not make a known SIM vanish: a SIM 2 rule would fail with "not
        // active" and "all SIM" rules would skip it. A slot that the subscription list still
        // names is kept; one that neither source knows any more is really gone.
        val usable = (masks.filterValues { it > 0L }.keys + cache.map { it.slot }.filter { it in names })
            .toSortedSet()
            .map { slot -> SimInfo(slot, names[slot] ?: cache.firstOrNull { it.slot == slot }?.name ?: "SIM ${slot + 1}") }
        if (usable.isEmpty() && cache.isNotEmpty() && names.isEmpty() && masks.values.none { it > 0L }) {
            Log.w(TAG, "SIM discovery found nothing, keeping ${cache.joinToString { it.label }}")
            cachedAt = System.currentTimeMillis()
            return@runCatching cache
        }
        cache = usable
        cachedAt = System.currentTimeMillis()
        Log.i(TAG, "active SIMs: ${usable.joinToString { it.label }}")
        usable
    }.getOrElse {
        Log.w(TAG, "SIM discovery failed", it)
        cache
    }

    /** All slots in one call; repeated only when no slot answered at all. */
    private suspend fun readWithRetry(): Map<Int, Long> {
        var masks = emptyMap<Int, Long>()
        repeat(3) { attempt ->
            masks = Telephony.readAll(MAX_SLOTS)
            if (masks.values.any { it > 0L }) return masks
            if (attempt < 2) kotlinx.coroutines.delay(400)
        }
        return masks
    }

    /** Every SIM slot the phone has, active or not. */
    fun slots(): List<Int> {
        val n = runCatching { Compat.modemCount(App.ctx.getSystemService(android.telephony.TelephonyManager::class.java)) }
            .getOrDefault(1).coerceIn(1, MAX_SLOTS)
        return (0 until n).toList()
    }

    /** "SIM 2 · MegaFon", or "SIM 2 (не активна)" for an empty slot. */
    fun slotLabel(slot: Int): String =
        cache.firstOrNull { it.slot == slot }?.label ?: "SIM ${slot + 1} (не активна)"

    fun name(slot: Int): String =
        cache.firstOrNull { it.slot == slot }?.name ?: "SIM ${slot + 1}"

    /** Expands a rule target into the slots it should actually touch. */
    suspend fun resolve(target: Int): List<Int> =
        if (target == SLOT_ALL) active().map { it.slot }
        // A rule for a SIM that is switched off skips it instead of failing on every run.
        else listOf(target).filter { t -> active().any { it.slot == t } }

    /** Carrier name per slot, empty when READ_PHONE_STATE has not been granted. */
    private fun namesFromSubscriptions(): Map<Int, String> {
        val granted = ContextCompat.checkSelfPermission(App.ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyMap()
        return runCatching {
            App.ctx.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList
                .orEmpty()
                .associate { it.simSlotIndex to (it.carrierName?.toString()?.takeIf { n -> n.isNotBlank() } ?: it.displayName?.toString().orEmpty()) }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }
}
