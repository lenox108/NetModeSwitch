package com.netmode.switcher

import android.os.IBinder

/**
 * The allowed network types through the phone service itself, run by the privileged shell
 * through app_process like [HotspotCli] — the fallback for firmwares where
 * `cmd phone set-allowed-network-types-for-users` answers "completed" and changes nothing:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.PhoneCli get <subId>
 *   CLASSPATH=<apk> app_process /system/bin com.netmode.switcher.PhoneCli set <subId> <mask>
 *
 * Reason 0 is "user", the one the system's own network-type screen writes. The method has
 * gained a calling-package argument on some builds; it is matched by name and filled by type.
 * Android 10 has neither: it keeps one "preferred network type", a RIL NT_MODE number, which
 * is translated to and from the mask here. Prints "mask=<decimal>", "ok" or "error=...".
 */
object PhoneCli {

    private const val REASON_USER = 0

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "phone") as IBinder
            val phone = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val sub = args[1].toInt()
            val reason = runCatching {
                Class.forName("android.telephony.TelephonyManager").getField("ALLOWED_NETWORK_TYPES_REASON_USER").getInt(null)
            }.getOrDefault(REASON_USER)
            val methods = phone.javaClass.methods
            // Android 10 has setAllowedNetworkTypes too, but there it is a carrier restriction,
            // not the user's choice: the user's mode is the preferred network type.
            val legacy = android.os.Build.VERSION.SDK_INT < 30
            when (args[0]) {
                "get" -> {
                    if (legacy) getPreferred(phone, methods, sub)?.let { println("mask=$it"); return exit() }
                    // Every overload by name: the parameter list differs between builds.
                    for (m in methods.filter { it.name == "getAllowedNetworkTypesForReason" }) {
                        val r = runCatching { m.invoke(phone, *fill(m.parameterTypes, sub, reason, null)) }.getOrNull()
                        if (r is Long) { println("mask=$r"); return exit() }
                    }
                    getPreferred(phone, methods, sub)?.let { println("mask=$it"); return exit() }
                    println("error=no getter")
                }
                "set" -> {
                    val mask = args[2].toLong()
                    if (legacy) setPreferred(phone, methods, sub, mask)?.let { println(it); return exit() }
                    // The current method first, then the Android 11 one without a reason.
                    for (name in listOf("setAllowedNetworkTypesForReason", "setAllowedNetworkTypes")) {
                        for (m in methods.filter { it.name == name }) {
                            val r = runCatching { m.invoke(phone, *fill(m.parameterTypes, sub, reason, mask)) }
                            if (r.isSuccess) { println(if (r.getOrNull() == false) "error=rejected" else "ok"); return exit() }
                        }
                    }
                    setPreferred(phone, methods, sub, mask)?.let { println(it); return exit() }
                    println("error=no setter")
                }
                else -> println("error=usage")
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            println("error=${c.javaClass.simpleName}: ${c.message}")
        }
        System.exit(0)
    }

    private fun exit() = System.exit(0)

    /** getPreferredNetworkType(subId) → NT_MODE → mask; null when there is no such method. */
    private fun getPreferred(phone: Any, methods: Array<java.lang.reflect.Method>, sub: Int): Long? {
        for (m in methods.filter { it.name == "getPreferredNetworkType" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }) {
            val nt = runCatching { m.invoke(phone, sub) }.getOrNull()
            (nt as? Int)?.let { maskOfNt(it) }?.let { return it }
        }
        return null
    }

    /** setPreferredNetworkType(subId, NT_MODE): "ok", "error=rejected", or null without it. */
    private fun setPreferred(phone: Any, methods: Array<java.lang.reflect.Method>, sub: Int, mask: Long): String? {
        val nt = ntOfMask(mask)
        for (m in methods.filter { it.name == "setPreferredNetworkType" && it.parameterTypes.size == 2 }) {
            val r = runCatching { m.invoke(phone, sub, nt) }
            if (r.isSuccess) return if (r.getOrNull() == false) "error=rejected" else "ok"
        }
        return null
    }

    // --- Android 10: NT_MODE numbers (RILConstants) by the radio families they allow ----------

    private const val GSM = 1
    private const val WCDMA = 2
    private const val CDMA = 4
    private const val EVDO = 8
    private const val LTE = 16
    private const val NR = 32
    private const val TDS = 64

    /** NT_MODE → families, as RadioAccessFamily.getRafFromNetworkType composes them. */
    private val NT = mapOf(
        0 to (GSM or WCDMA), 1 to GSM, 2 to WCDMA, 3 to (GSM or WCDMA),
        4 to (CDMA or EVDO), 5 to CDMA, 6 to EVDO, 7 to (GSM or WCDMA or CDMA or EVDO),
        8 to (LTE or CDMA or EVDO), 9 to (LTE or GSM or WCDMA), 10 to (LTE or CDMA or EVDO or GSM or WCDMA),
        11 to LTE, 12 to (LTE or WCDMA), 13 to TDS, 14 to (TDS or WCDMA), 15 to (LTE or TDS),
        16 to (TDS or GSM), 17 to (LTE or TDS or GSM), 18 to (TDS or GSM or WCDMA), 19 to (LTE or TDS or WCDMA),
        20 to (LTE or TDS or GSM or WCDMA), 21 to (TDS or CDMA or EVDO or GSM or WCDMA),
        22 to (LTE or TDS or CDMA or EVDO or GSM or WCDMA),
        23 to NR, 24 to (NR or LTE), 25 to (NR or LTE or CDMA or EVDO), 26 to (NR or LTE or GSM or WCDMA),
        27 to (NR or LTE or CDMA or EVDO or GSM or WCDMA), 28 to (NR or LTE or WCDMA), 29 to (NR or LTE or TDS),
        30 to (NR or LTE or TDS or GSM), 31 to (NR or LTE or TDS or WCDMA), 32 to (NR or LTE or TDS or GSM or WCDMA),
        33 to (NR or LTE or TDS or CDMA or EVDO or GSM or WCDMA),
    )

    /** Network-type bits of each family (NETWORK_TYPE_BITMASK_*, see Raf). */
    private val BITS = mapOf(
        GSM to ((1L shl 15) or (1L shl 0) or (1L shl 1)),
        WCDMA to ((1L shl 2) or (1L shl 7) or (1L shl 8) or (1L shl 9) or (1L shl 14)),
        CDMA to ((1L shl 3) or (1L shl 6)),
        EVDO to ((1L shl 4) or (1L shl 5) or (1L shl 11) or (1L shl 13)),
        LTE to ((1L shl 12) or (1L shl 18)),
        NR to (1L shl 19),
        TDS to (1L shl 16),
    )

    private fun maskOfNt(nt: Int): Long? =
        NT[nt]?.let { fam -> BITS.filterKeys { fam and it != 0 }.values.fold(0L) { a, b -> a or b } }

    /** The NT_MODE allowing exactly the families present in [mask]; else the closest superset. */
    private fun ntOfMask(mask: Long): Int {
        val fam = BITS.filterValues { mask and it != 0L }.keys.fold(0) { a, b -> a or b }
        NT.entries.firstOrNull { it.value == fam }?.let { return it.key }
        return NT.entries.filter { it.value and fam == fam }.minByOrNull { Integer.bitCount(it.value) }?.key ?: 9
    }

    /** subId and reason are the ints in that order; the mask is the long; a String is the caller. */
    private fun fill(types: Array<Class<*>>, sub: Int, reason: Int, mask: Long?): Array<Any?> {
        var ints = 0
        return Array(types.size) { i ->
            when (types[i]) {
                Int::class.javaPrimitiveType -> if (ints++ == 0) sub else reason
                Long::class.javaPrimitiveType -> mask ?: 0L
                String::class.java -> "com.android.phone"
                else -> null
            }
        }
    }
}
