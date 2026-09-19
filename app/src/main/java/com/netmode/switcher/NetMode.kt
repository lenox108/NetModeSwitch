package com.netmode.switcher

/**
 * Network-type bitmasks, hardcoded rather than read from TelephonyManager: several of the
 * NETWORK_TYPE_BITMASK_* constants are @SystemApi, and the values are stable AOSP-wide
 * (each is 1L shl (NETWORK_TYPE_X - 1)).
 */
object Raf {
    private const val GPRS = 1L shl 0
    private const val EDGE = 1L shl 1
    private const val UMTS = 1L shl 2
    private const val CDMA = 1L shl 3
    private const val EVDO_0 = 1L shl 4
    private const val EVDO_A = 1L shl 5
    private const val RTT_1X = 1L shl 6
    private const val HSDPA = 1L shl 7
    private const val HSUPA = 1L shl 8
    private const val HSPA = 1L shl 9
    private const val IDEN = 1L shl 10
    private const val EVDO_B = 1L shl 11
    private const val LTE = 1L shl 12
    private const val EHRPD = 1L shl 13
    private const val HSPAP = 1L shl 14
    private const val GSM = 1L shl 15
    private const val TD_SCDMA = 1L shl 16
    private const val IWLAN = 1L shl 17
    private const val LTE_CA = 1L shl 18
    private const val NR = 1L shl 19

    /**
     * Radio-access families exactly as RadioAccessFamily composes them. The presets below must
     * match a stock NT_MODE bit for bit: OxygenOS's own network-type screen re-applies its
     * default whenever the current mask matches none of its menu entries, which silently undoes
     * anything broader — measured on this device with a class-wide mask.
     */
    private const val FAMILY_GSM = GSM or GPRS or EDGE
    private const val FAMILY_WCDMA = UMTS or HSDPA or HSUPA or HSPA or HSPAP
    private const val FAMILY_LTE = LTE or LTE_CA

    /** NT_MODE 9 (LTE/WCDMA/GSM) = 0x4D387 — the value this phone shipped with. */
    const val MODE_LTE_WCDMA_GSM = FAMILY_LTE or FAMILY_WCDMA or FAMILY_GSM

    /** NT_MODE 3 (WCDMA/GSM) = 0xC387. */
    const val MODE_WCDMA_GSM = FAMILY_WCDMA or FAMILY_GSM

    const val CLASS_2G = FAMILY_GSM or CDMA or RTT_1X or IDEN
    const val CLASS_3G = FAMILY_WCDMA or EVDO_0 or EVDO_A or EVDO_B or EHRPD or TD_SCDMA
    const val CLASS_4G = FAMILY_LTE or IWLAN
    const val CLASS_5G = NR

    /** Human-readable generation list, e.g. "5G+4G+3G+2G". Used in diagnostics. */
    fun describe(mask: Long): String {
        if (mask <= 0L) return "—"
        val parts = buildList {
            if (mask and CLASS_5G != 0L) add("5G")
            if (mask and CLASS_4G != 0L) add("4G")
            if (mask and CLASS_3G != 0L) add("3G")
            if (mask and CLASS_2G != 0L) add("2G")
        }
        return if (parts.isEmpty()) "?" else parts.joinToString("+")
    }
}

/** [short] exists because a Quick Settings tile has room for about three characters. */
enum class NetMode(val label: String, val short: String, val mask: Long) {
    FULL("4G / 3G / 2G", "4G", Raf.MODE_LTE_WCDMA_GSM),
    THREE_G("3G / 2G", "3G", Raf.MODE_WCDMA_GSM);

    val other: NetMode get() = if (this == FULL) THREE_G else FULL

    companion object {
        /**
         * Classifies a bitmask read back from telephony by generation rather than by exact
         * equality: the modem strips bits it does not support, and the effective mask is the
         * AND across all reasons, so an exact match would report working writes as failures.
         */
        fun of(mask: Long): NetMode? = when {
            mask <= 0L -> null
            mask and Raf.CLASS_5G != 0L -> null            // 5G on => neither of our presets
            mask and Raf.CLASS_4G != 0L -> FULL
            mask and Raf.CLASS_3G != 0L -> THREE_G
            else -> null
        }
    }
}
