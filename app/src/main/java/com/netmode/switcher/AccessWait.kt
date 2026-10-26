package com.netmode.switcher

import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Root or Shizuku is not there yet — right after a reboot the su daemon may still be starting,
 * and an ADB-started Shizuku is down until it is started again. Whatever came in meanwhile
 * (the boot catch-up, Wi-Fi connecting, a Bluetooth device, a condition) could not be applied,
 * and its event will not come again. So the app waits for access and, once it is there, goes
 * over everything as at boot: the schedule, then the current state of every trigger.
 */
object AccessWait {

    /** Looked at this often at first, then less, up to [MAX_STEP_MS]. */
    private const val FIRST_STEP_MS = 5_000L
    private const val MAX_STEP_MS = 30_000L

    /** Past this, the next event or opening the app does it instead. */
    private const val GIVE_UP_MS = 20 * 60_000L

    private var job: Job? = null

    /** Something waits for access; cleared by [catchUp]. */
    @Volatile private var pending = false

    /** The wait began with a boot: missed starts get the boot's grace (see AppFreezer.reconcile). */
    @Volatile private var boot = false

    @Volatile private var listening = false

    /** Shizuku started by hand comes as its binder: no need to wait for the next look. */
    private val binder = Shizuku.OnBinderReceivedListener {
        if (pending) App.scope.launch { if (Privileged.available()) catchUp() }
    }

    /** Access is missing for something that will not be asked for again: wait and catch up. */
    fun arm(afterBoot: Boolean = false) {
        if (afterBoot) boot = true
        pending = true
        if (!listening) {
            listening = true
            runCatching { Shizuku.addBinderReceivedListener(binder) }
        }
        synchronized(this) {
            if (job?.isActive == true) return
            job = App.scope.launch { waitForAccess() }
        }
    }

    private suspend fun waitForAccess() {
        val start = SystemClock.elapsedRealtime()
        var step = FIRST_STEP_MS
        while (pending && SystemClock.elapsedRealtime() - start < GIVE_UP_MS) {
            delay(step)
            step = (step * 3 / 2).coerceAtMost(MAX_STEP_MS)
            if (Privileged.available()) {
                catchUp()
                return
            }
        }
        if (pending) Prefs.log("root/Shizuku так и не появились — правила применятся при следующем событии или когда откроешь приложение")
    }

    /** Opening the app after starting Shizuku: the catch-up now, if one is owed. */
    fun poke() {
        if (pending) App.scope.launch { if (Privileged.available()) catchUp() }
    }

    /**
     * Everything that could not be done, now: what the schedule owes, pauses that ran out,
     * then each trigger read afresh — a rule whose start was put off for lack of access has no
     * mark (see AppFreezer.freezeLocked), so the state that holds now starts it.
     */
    private suspend fun catchUp() {
        synchronized(this) {
            if (!pending) return
            pending = false
        }
        val afterBoot = boot
        boot = false
        Prefs.log("root/Shizuku доступны — применяю правила")
        runCatching { AppFreezer.reconcile(boot = afterBoot, catchUp = Prefs.catchUpOnBoot) }
        runCatching { StepQueue.runDue() }
        // The network mode can be read only now; a change from the mode on record is an event.
        runCatching { ModeController.readAll() }
        runCatching { WifiRules.onConnected(App.ctx) }
        runCatching {
            for (address in BtDevices.connected(App.ctx)) AppFreezer.onBtConnected(address, null)
        }
        if (AppFreezer.hasDataRules()) runCatching {
            // Unchanged since before the reboot is still a state its rules have to see.
            Prefs.lastDataOn = null
            DataWatch.check()
        }
        runCatching { ConditionWatch.evaluateNow() }
        runCatching { Freezer.sync(boot = afterBoot) }
        AlarmScheduler.rearm()
    }
}
