package com.netmode.switcher

import android.telephony.SubscriptionManager

/**
 * State side of [SimPower]: which subscription sits in a slot and whether it is switched on.
 * The switch itself runs privileged through [SimCli]: "uicc" for a physical SIM, "esim" (the
 * profile switched on or off) for an eSIM.
 */
object SimSwitch {

    /** setUiccApplicationsEnabled and its read-back exist from Android 11. */
    val supported: Boolean get() = Compat.SDK >= 30

    /** A SIM as needed to switch it: an eSIM is addressed by its card and port. */
    data class Target(val sub: Int, val embedded: Boolean, val card: Int = -1, val port: Int = 0) {
        /** The record of the state to put back: "on:5" for a physical SIM, "on:5:2:0" for an eSIM. */
        fun prev(on: Boolean): String = (if (on) "on:" else "off:") + sub + if (embedded) ":$card:$port" else ""

        fun encode(): String = "$sub:${if (embedded) 1 else 0}:$card:$port"

        companion object {
            fun decode(s: String?): Target? {
                val p = s?.split(':')?.takeIf { it.size == 4 } ?: return null
                val sub = p[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return Target(sub, p[1] == "1", p[2].toIntOrNull() ?: -1, p[3].toIntOrNull() ?: 0)
            }

            /** [prev]'s inverse: the state and the SIM; an old "on:5" is a physical SIM. */
            fun fromPrev(prev: String?): Pair<Boolean, Target>? {
                val p = prev?.split(':') ?: return null
                val sub = p.getOrNull(1)?.toIntOrNull() ?: return null
                val on = p[0] == "on"
                return when (p.size) {
                    2 -> on to Target(sub, false)
                    4 -> on to Target(sub, true, p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: 0)
                    else -> null
                }
            }
        }
    }

    /**
     * The SIM in [slot]. One switched off may drop out of the slot lookup, an eSIM always does,
     * so the last one seen there is remembered and used instead.
     */
    fun target(slot: Int): Target? {
        val sm = App.ctx.getSystemService(SubscriptionManager::class.java)
        val info = runCatching { sm.getActiveSubscriptionInfoForSimSlotIndex(slot) }.getOrNull()
        val seen = info?.let {
            Target(it.subscriptionId, it.isEmbedded, it.cardId, if (Compat.SDK >= 33) it.portIndex else 0)
        } ?: Compat.subId(slot).takeIf { it >= 0 }?.let { sub ->
            // No READ_PHONE_STATE: the id alone, the rest as last seen.
            Target.decode(Prefs.simTarget(slot))?.takeIf { it.sub == sub } ?: Target(sub, false)
        }
        if (seen != null) { Prefs.setSimTarget(slot, seen.encode()); return seen }
        return Target.decode(Prefs.simTarget(slot))
    }

    /** Whether the SIM is switched on; null when that cannot be read (no READ_PHONE_STATE). */
    fun isOn(sub: Int): Boolean? {
        if (!supported) return null
        return runCatching {
            val info = App.ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(sub)
            // Not active at all (a disabled eSIM profile): off. The getter is hidden in the SDK.
            if (info == null) false
            else info.javaClass.getMethod("areUiccApplicationsEnabled").invoke(info) as Boolean
        }.getOrNull()
    }
}
