package com.netmode.switcher

import android.util.Log

/**
 * Reads and writes the allowed network types through `cmd phone`, which is gated on root or
 * shell uid and applies straight to the radio.
 *
 * The slot is always passed explicitly. The two subcommands are not symmetric: `get` falls
 * back to the default voice SIM when `-s` is absent, but `set` simply answers "failed" — an
 * asymmetry that costs nothing once known and is silent otherwise.
 */
object Telephony {

    private const val TAG = "netmode"
    private const val GET = "cmd phone get-allowed-network-types-for-users"
    private const val SET = "cmd phone set-allowed-network-types-for-users"

    /** Names exactly as `cmd phone` prints them, mapped back to NETWORK_TYPE_BITMASK bits. */
    private val NAME_TO_BIT: Map<String, Long> = mapOf(
        "GPRS" to (1L shl 0),
        "EDGE" to (1L shl 1),
        "UMTS" to (1L shl 2),
        "CDMA" to (1L shl 3),
        "CDMA - EvDo rev. 0" to (1L shl 4),
        "CDMA - EvDo rev. A" to (1L shl 5),
        "CDMA - 1xRTT" to (1L shl 6),
        "HSDPA" to (1L shl 7),
        "HSUPA" to (1L shl 8),
        "HSPA" to (1L shl 9),
        "iDEN" to (1L shl 10),
        "CDMA - EvDo rev. B" to (1L shl 11),
        "LTE" to (1L shl 12),
        "CDMA - eHRPD" to (1L shl 13),
        "HSPA+" to (1L shl 14),
        "GSM" to (1L shl 15),
        "TD_SCDMA" to (1L shl 16),
        "IWLAN" to (1L shl 17),
        "LTE_CA" to (1L shl 18),
        "NR" to (1L shl 19),
    )

    @Volatile
    private var cachedSlot: Int = -1

    /** Finds the slot holding the active SIM by probing; empty slots answer with an error. */
    suspend fun slot(): Int {
        cachedSlot.takeIf { it >= 0 }?.let { return it }
        for (candidate in 0..3) {
            val out = Root.exec("$GET -s $candidate").trim()
            if (out.contains("not a valid SLOT_ID")) break
            if (out.isNotEmpty() && !out.contains("No valid subscription") && out.contains("|")) {
                cachedSlot = candidate
                return candidate
            }
        }
        error("Не найден слот с активной SIM")
    }

    /** Current allowed-types bitmask, or -1 when telephony had nothing to say. */
    suspend fun readMask(): Long {
        val out = Root.exec("$GET -s ${slot()}").trim()
        if (out.isEmpty() || out.contains("No valid subscription")) {
            Log.w(TAG, "telephony read returned: $out")
            return -1L
        }
        var mask = 0L
        out.split('|').forEach { token ->
            val name = token.trim()
            val bit = NAME_TO_BIT[name]
            if (bit == null) Log.w(TAG, "unknown network type name: '$name'")
            else mask = mask or bit
        }
        return if (mask == 0L) -1L else mask
    }

    suspend fun writeMask(mask: Long): Boolean {
        // The command takes the bitmask as 20 binary digits, NR first.
        val binary = mask.toString(2).padStart(20, '0')
        val out = Root.exec("$SET -s ${slot()} $binary")
        Log.i(TAG, "set $binary -> $out")
        if (!out.contains("completed", ignoreCase = true)) {
            cachedSlot = -1      // a swapped SIM would look exactly like this
            return false
        }
        return true
    }
}
