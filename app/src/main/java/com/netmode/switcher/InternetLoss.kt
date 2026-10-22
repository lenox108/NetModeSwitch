package com.netmode.switcher

/**
 * "Интернет пропал" ([Trigger.NO_INTERNET]): the phone wants the internet and has none. The
 * system's own check decides — the default network carries VALIDATED once the system has
 * reached the internet through it, and loses it when it no longer can — so a network that is up
 * but passes nothing counts the same as no network at all.
 *
 * Nothing to fix is not a loss: airplane mode, or Wi-Fi and mobile data both switched off.
 */
object InternetLoss {

    /** Whether the phone is without internet in a way a rule should act on. */
    fun lost(hasNetwork: Boolean, validated: Boolean, dataWanted: Boolean, airplane: Boolean): Boolean =
        !airplane && if (hasNetwork) !validated else dataWanted

    /** The loss has lasted [afterMs] by [now]; [lostSince] is 0 while online. */
    fun holds(lostSince: Long, now: Long, afterMs: Long): Boolean = lostSince != 0L && now - lostSince >= afterMs
}
