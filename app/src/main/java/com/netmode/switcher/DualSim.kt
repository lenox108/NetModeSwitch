package com.netmode.switcher

import android.telephony.SubscriptionManager

/**
 * "Основная и запасная SIM": the SIM chosen for mobile data gets [Prefs.dualPrimary], the other
 * one [Prefs.dualSecondary] — e.g. 4G for the data SIM and 2G for the one that only takes
 * calls. Follows the data SIM when it is switched in the system.
 */
object DualSim {

    /** Slot of the SIM Android uses for mobile data, or null. */
    fun dataSlot(): Int? = runCatching {
        SubscriptionManager.getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId())
    }.getOrNull()?.takeIf { it >= 0 }

    /**
     * Both writes happen with no rule event in between, so a "Сеть" rule fired by the first
     * sees the second too instead of racing it.
     */
    suspend fun sync(reason: String): String? = AppFreezer.exclusive { syncLocked(reason) }

    private suspend fun syncLocked(reason: String): String? {
        if (!Prefs.dualSimEnabled) return null
        val slots = Sims.active().map { it.slot }
        if (slots.size < 2) return "основная/запасная SIM: нужна вторая активная SIM".also { Prefs.log(it) }
        val primary = dataSlot()?.takeIf { it in slots } ?: slots.first()
        val secondary = slots.first { it != primary }
        val done = mutableListOf<String>()
        for ((slot, mode) in listOf(primary to Prefs.dualPrimary, secondary to Prefs.dualSecondary)) {
            // Exact mask: a non-stock mask of the same generation is rewritten, or OxygenOS
            // would put its own default back over it.
            if (Telephony.readMask(slot) == Profile.maskFor(mode, slot)) continue
            ModeController.apply(slot, mode, ModeController.Source.SCHEDULE)
                .onSuccess { done += "SIM ${slot + 1} → ${mode.short}" }
                .onFailure { done += "SIM ${slot + 1}: ошибка ${it.message}" }
        }
        val line = "основная SIM ${primary + 1}, запасная SIM ${secondary + 1} ($reason)" +
            if (done.isEmpty()) ": режимы уже как нужно" else ": " + done.joinToString(", ")
        Prefs.log(line)
        return line
    }
}
