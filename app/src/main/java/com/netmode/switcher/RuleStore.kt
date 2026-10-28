package com.netmode.switcher

import android.os.PowerManager
import kotlinx.coroutines.launch

/** Saving and deleting rules, and bringing the watchers in line afterwards. */
object RuleStore {

    /**
     * Saving never fires a time rule on the spot (see [AppFreezer.markSaved]); it only releases
     * what the edit no longer covers — a disabled rule, a removed app, a moved end time.
     * Returns true when the rule needs the battery exemption to work in the background.
     */
    fun save(rule: AppRule): Boolean {
        val old = Prefs.appRules.find { it.id == rule.id }
        Prefs.appRules = Prefs.appRules.filterNot { it.id == rule.id } + rule
        // An event rule's "already applied" mark belongs to the condition it was made for:
        // switched off, or pointed at another trigger, it has to be able to fire afresh.
        if (!rule.isTime && (!rule.enabled || old == null || old.timeLabel != rule.timeLabel)) {
            Prefs.appHandled = Prefs.appHandled - rule.id
        }
        AppFreezer.markSaved(rule)
        if (rule.trigger == Trigger.APP_OPEN) ConditionWatch.grantUsageAccess()
        changed()
        // A scene runs on a press, and its time on an exact alarm: nothing waits in the background.
        return rule.enabled && !rule.isTime && rule.trigger != Trigger.MANUAL && rule.trigger != Trigger.TILE_CLICK && !batteryExempt()
    }

    fun delete(id: Long) = delete(listOf(id))

    /** Remove the whole batch before rearming alarms and reconciling held apps once. */
    fun delete(ids: Collection<Long>) {
        val removed = ids.toSet()
        if (removed.isEmpty()) return
        Prefs.appRules = Prefs.appRules.filterNot { it.id in removed }
        Prefs.appHandled = Prefs.appHandled - removed
        Prefs.ruleOrder = Prefs.ruleOrder.filterNot { it in removed }
        RuleTiles.forget(removed)
        changed()
    }

    fun batteryExempt(): Boolean =
        App.ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(App.ctx.packageName)

    /**
     * A rule of a connection or a state is a condition rather than an event: saved while the
     * condition already holds, it takes effect straight away, as if it had just begun.
     */
    fun changed() {
        AlarmScheduler.rearm()
        runCatching { Scenes.sync(App.ctx) }
        WifiMonitor.syncAll()
        App.scope.launch {
            runCatching { AppFreezer.reconcile() }
            if (AppFreezer.hasWifiRules()) runCatching { WifiRules.onConnected(App.ctx) }
            ConditionWatch.evaluate()
            for (r in Prefs.appRules.filter { it.enabled && it.trigger == Trigger.NET_MODE }) {
                ModeController.read(r.triggerSlot).getOrNull()?.let { runCatching { AppFreezer.onNetModeChanged(r.triggerSlot, it) } }
            }
            val connected = BtDevices.connected(App.ctx)
            for (r in Prefs.appRules.filter { it.enabled && it.trigger == Trigger.BLUETOOTH }) {
                val a = r.btAddress ?: continue
                if (a.uppercase() in connected) runCatching { AppFreezer.onBtConnected(a, r.btName) }
            }
            if (AppFreezer.hasDataRules()) runCatching {
                val on = DataWatch.enabled()
                Prefs.lastDataOn = on
                if (on) AppFreezer.onDataChanged(true)
            }
        }
    }
}
