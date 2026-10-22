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


    /** Allowed-types bitmask for [slot], or -1 when telephony had nothing to say. */
    suspend fun readMask(slot: Int): Long = readWith(Profile.method, slot)

    suspend fun readWith(method: WriteMethod, slot: Int): Long = when (method) {
        WriteMethod.CMD -> parseMask(Privileged.exec("$GET -s $slot").trim())
        WriteMethod.BINDER -> {
            val sub = subOf(slot)
            if (sub < 0) -1L
            else Privileged.exec("${cli()} get $sub").lineSequence().firstOrNull { it.startsWith("mask=") }
                ?.removePrefix("mask=")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
        }
    }

    private fun subOf(slot: Int): Int =
        Compat.subId(slot)

    private fun cli() = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.PhoneCli"

    /**
     * Masks of every slot the device has, in one shell call: slot → mask, -1 for a slot whose
     * read came back empty. One call instead of an existence probe plus a read per slot —
     * each `cmd phone` costs a few hundred milliseconds, and the main screen waits for this.
     */
    suspend fun readAll(maxSlots: Int): Map<Int, Long> {
        if (Profile.method == WriteMethod.BINDER) {
            return (0 until maxSlots).associateWith { readWith(WriteMethod.BINDER, it) }.filterValues { it > 0 }
        }
        val script = "for s in ${(0 until maxSlots).joinToString(" ")}; do " +
            "o=\$($GET -s \$s 2>&1 | head -1); " +
            "case \"\$o\" in *\"not a valid SLOT_ID\"*) break;; esac; " +
            "echo \"@S \$s \$o\"; done"
        return Privileged.exec(script).lineSequence()
            .filter { it.startsWith("@S ") }
            .mapNotNull { line ->
                val rest = line.removePrefix("@S ")
                val slot = rest.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
                slot to parseMask(rest.substringAfter(' ', "").trim())
            }
            .toMap()
    }

    private fun parseMask(out: String): Long {
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

    suspend fun writeMask(slot: Int, mask: Long): Boolean = writeWith(Profile.method, slot, mask)

    suspend fun writeWith(method: WriteMethod, slot: Int, mask: Long): Boolean = when (method) {
        WriteMethod.CMD -> {
            // The command takes the bitmask as 20 binary digits, NR first.
            val binary = mask.toString(2).padStart(20, '0')
            val out = Privileged.exec("$SET -s $slot $binary")
            Log.i(TAG, "slot $slot set $binary -> $out")
            out.contains("completed", ignoreCase = true)
        }
        WriteMethod.BINDER -> {
            val sub = subOf(slot)
            sub >= 0 && Privileged.exec("${cli()} set $sub $mask").lineSequence().any { it.trim() == "ok" }
        }
    }
}
