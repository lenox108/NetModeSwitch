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
    private const val FAMILY_CDMA = CDMA or RTT_1X
    private const val FAMILY_EVDO = EVDO_0 or EVDO_A or EVDO_B

    /** NT_MODE 9 (LTE/WCDMA/GSM) = 0x4D387 — the value this phone shipped with. */
    const val MODE_LTE_WCDMA_GSM = FAMILY_LTE or FAMILY_WCDMA or FAMILY_GSM

    /** NT_MODE 33 (NR/LTE/GSM/WCDMA) = 0xCD387 — the stock "5G" entry. */
    const val MODE_NR_LTE_WCDMA_GSM = NR or MODE_LTE_WCDMA_GSM

    /** NT_MODE 24 (NR/LTE) = 0xC1000: no fallback below 4G. */
    const val MODE_NR_LTE = NR or FAMILY_LTE

    /** NT_MODE 11 (LTE only) = 0x41000. */
    const val MODE_LTE = FAMILY_LTE

    /** NT_MODE 2 (WCDMA only) = 0x4384. */
    const val MODE_WCDMA = FAMILY_WCDMA

    /** NT_MODE 3 (WCDMA/GSM) = 0xC387. */
    const val MODE_WCDMA_GSM = FAMILY_WCDMA or FAMILY_GSM

    /** NT_MODE 1 (GSM only) = 0x8003, as the stock menu writes it. */
    const val MODE_GSM = FAMILY_GSM

    const val CLASS_2G = FAMILY_GSM or CDMA or RTT_1X or IDEN
    const val CLASS_3G = FAMILY_WCDMA or EVDO_0 or EVDO_A or EVDO_B or EHRPD or TD_SCDMA
    const val CLASS_4G = FAMILY_LTE or IWLAN
    const val CLASS_5G = NR

    /**
     * Masks to try for [mode] in the phone check, stock one first: a firmware whose own
     * network-type screen puts its default back over an unknown mask needs the exact value of
     * one of its entries, and which one differs between firmwares.
     */
    fun variants(mode: NetMode): List<Long> = when (mode) {
        // RIL modes 26, 33, 32 and class-wide.
        NetMode.FIVE_G -> listOf(mode.mask, NR or MODE_LTE_WCDMA_GSM or FAMILY_CDMA or FAMILY_EVDO or TD_SCDMA,
            NR or MODE_LTE_WCDMA_GSM or TD_SCDMA, NR or CLASS_4G or CLASS_3G or CLASS_2G)
        NetMode.NR_LTE -> listOf(mode.mask, NR or CLASS_4G)
        // RIL modes 9, 10 (CDMA carriers), 20 and 22 (TD-SCDMA), and class-wide.
        NetMode.FULL -> listOf(mode.mask, MODE_LTE_WCDMA_GSM or FAMILY_CDMA or FAMILY_EVDO,
            MODE_LTE_WCDMA_GSM or TD_SCDMA, MODE_LTE_WCDMA_GSM or TD_SCDMA or FAMILY_CDMA or FAMILY_EVDO,
            CLASS_4G or CLASS_3G or CLASS_2G)
        NetMode.LTE_ONLY -> listOf(mode.mask, CLASS_4G)
        // RIL modes 3, 7, 18, 21 and class-wide.
        NetMode.THREE_G -> listOf(mode.mask, MODE_WCDMA_GSM or FAMILY_CDMA or FAMILY_EVDO, MODE_WCDMA_GSM or TD_SCDMA,
            MODE_WCDMA_GSM or TD_SCDMA or FAMILY_CDMA or FAMILY_EVDO, CLASS_3G or CLASS_2G)
        NetMode.WCDMA_ONLY -> listOf(mode.mask, CLASS_3G)
        NetMode.TWO_G -> listOf(mode.mask, CLASS_2G)
    }.distinct()

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
    FIVE_G("5G / 4G / 3G / 2G", "5G", Raf.MODE_NR_LTE_WCDMA_GSM),
    NR_LTE("5G / 4G", "5G/4G", Raf.MODE_NR_LTE),
    FULL("4G / 3G / 2G", "4G", Raf.MODE_LTE_WCDMA_GSM),
    LTE_ONLY("Только 4G", "только 4G", Raf.MODE_LTE),
    THREE_G("3G / 2G", "3G", Raf.MODE_WCDMA_GSM),
    WCDMA_ONLY("Только 3G", "только 3G", Raf.MODE_WCDMA),
    TWO_G("2G", "2G", Raf.MODE_GSM);


    /** 4G or better: what the toggle and the tile treat as "high". */
    val high: Boolean get() = mask and (Raf.CLASS_4G or Raf.CLASS_5G) != 0L

    companion object {
        /** The preset whose stock mask this is, bit for bit; null for any other mask. */
        fun exact(mask: Long): NetMode? =
            entries.firstOrNull { it.mask == mask } ?: entries.firstOrNull { mask in Profile.masksOf(it) }

        /**
         * Classifies a bitmask read back from telephony by generation rather than by exact
         * equality: the modem strips bits it does not support, and the effective mask is the
         * AND across all reasons, so an exact match would report working writes as failures.
         */
        fun of(mask: Long): NetMode? {
            if (mask <= 0L) return null
            exact(mask)?.let { return it }
            val below3 = mask and (Raf.CLASS_3G or Raf.CLASS_2G) != 0L
            return when {
                mask and Raf.CLASS_5G != 0L -> if (below3) FIVE_G else NR_LTE
                mask and Raf.CLASS_4G != 0L -> if (below3) FULL else LTE_ONLY
                mask and Raf.CLASS_3G != 0L -> if (mask and Raf.CLASS_2G != 0L) THREE_G else WCDMA_ONLY
                mask and Raf.CLASS_2G != 0L -> TWO_G
                else -> null
            }
        }

        /** The four everyday modes that get a button of their own; the rest are in a menu. */
        val MAIN = listOf(FIVE_G, FULL, THREE_G, TWO_G)
    }
}
