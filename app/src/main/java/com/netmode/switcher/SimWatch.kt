package com.netmode.switcher

import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * A SIM switched on while a rule is in force — the second SIM off for the night and back on in
 * the morning, at home on Wi-Fi — comes up in the modem's own default mode, and the rule only
 * set the SIMs that were on when it ran. Here the SIM list is watched, and a SIM that appears
 * gets the mode of the rule in force (see [AppFreezer.onSimAdded]), or its primary/secondary
 * mode. An event from the system, not polling; it lives as long as the app's process does.
 */
object SimWatch {

    private const val TAG = "netmode"

    /** The modem settles a SIM for a few seconds after it comes up; changes come in bursts. */
    private const val SETTLE_MS = 3_000L

    /** And may put its own default back over the first write: checked once more after this. */
    private const val RECHECK_MS = 10_000L

    private var listener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var pending: Job? = null

    /** Active slots as last seen; null until the first look. */
    @Volatile private var lastSlots: Set<Int>? = null

    fun needed(): Boolean = Prefs.dualSimEnabled || Prefs.appRules.any { it.enabled && it.netMode != null }

    fun sync() {
        val want = needed()
        // The listener needs a Looper on the registering thread before Android 11: the main one.
        Handler(Looper.getMainLooper()).post {
            val sm = App.ctx.getSystemService(SubscriptionManager::class.java) ?: return@post
            if (want && listener == null) {
                val l = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() = changed()
                }
                runCatching {
                    if (Compat.SDK >= 30) sm.addOnSubscriptionsChangedListener(App.ctx.mainExecutor, l)
                    else @Suppress("DEPRECATION") sm.addOnSubscriptionsChangedListener(l)
                    listener = l
                }.onFailure { Log.w(TAG, "SIM watch registration failed", it) }
            } else if (!want && listener != null) {
                runCatching { sm.removeOnSubscriptionsChangedListener(listener) }
                listener = null
                lastSlots = null
            }
        }
    }

    /** Debug harness only: [slot] counts as having been off, then the list is looked at again. */
    internal fun forgetForTest(slot: Int) {
        lastSlots = Sims.known.map { it.slot }.toSet() - slot
        changed()
    }

    /** One look at a time; a later burst waits for the one under way. */
    private val checking = kotlinx.coroutines.sync.Mutex()

    private fun changed() {
        // Only the wait is restarted by each change of a burst. A look under way runs on: setting
        // a mode writes the SIM database too, and that change must not cut the write short.
        pending?.cancel()
        pending = App.scope.launch {
            delay(SETTLE_MS)
            App.scope.launch {
                checking.withLock { runCatching { check() }.onFailure { Log.w(TAG, "SIM watch check failed", it) } }
            }
        }
    }

    private suspend fun check() {
        val now = Sims.refresh().map { it.slot }.toSet()
        val before = lastSlots
        lastSlots = now
        // The first answer only says what is there; and nothing can be set without access.
        if (before == null) return
        val added = now - before
        if (added.isEmpty()) return
        Log.i(TAG, "SIM switched on: ${added.joinToString { "SIM ${it + 1}" }}")
        for (slot in added) {
            val mode = AppFreezer.onSimAdded(slot)
            if (mode == null && Prefs.dualSimEnabled) DualSim.sync("включена SIM ${slot + 1}")
        }
        // A modem that put its default back over the first write is given the mode again.
        delay(RECHECK_MS)
        for (slot in added) {
            if (slot !in lastSlots.orEmpty()) continue
            AppFreezer.onSimAdded(slot, recheck = true)
        }
    }
}
