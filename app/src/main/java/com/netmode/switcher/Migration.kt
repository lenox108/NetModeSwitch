package com.netmode.switcher

/**
 * One-time move of the old "Расписание" and "Wi-Fi" network-mode rules into the single rule
 * list, where network mode is one action among others.
 *
 * - "at 21:00 → 3G" becomes a time rule without an end.
 * - "on connecting → X, on leaving → Y" becomes "while connected → X, at the end → Y".
 * - "on leaving → Y" alone becomes "after leaving → Y", closed by coming back.
 */
object Migration {

    suspend fun run() {
        dropManualMarks()
        if (Prefs.migratedRules) return
        val converted = mutableListOf<AppRule>()
        val handled = Prefs.appHandled.toMutableMap()

        for (r in Prefs.schedules) {
            val rule = AppRule(
                id = r.id, enabled = r.enabled, hour = r.hour, minute = r.minute,
                endHour = null, endMinute = null, days = r.days, packages = emptyList(),
                method = FreezeMethod.STOP_RESTRICT, trigger = Trigger.TIME,
                netMode = r.mode, netSlot = r.slot,
            )
            converted += rule
            rule.lastStart(java.time.ZonedDateTime.now())?.let {
                handled[rule.id] = it.toInstant().toEpochMilli()
            }
        }

        for (w in Prefs.wifiRules) {
            val base = AppRule(
                id = w.id, enabled = w.enabled, hour = 0, minute = 0, endHour = null, endMinute = null,
                days = 0, packages = emptyList(), method = FreezeMethod.STOP_RESTRICT,
                ssid = w.ssid, leaveOnWifiOff = w.leaveOnWifiOff, netSlot = w.slot,
            )
            when {
                w.onConnect != null -> converted += base.copy(
                    trigger = Trigger.WIFI, netMode = w.onConnect,
                    releaseOnLeave = w.onDisconnect != null,
                    endCustom = w.onDisconnect != null, endNet = w.onDisconnect,
                )
                w.onDisconnect != null -> converted += base.copy(trigger = Trigger.WIFI_LEFT, netMode = w.onDisconnect)
            }
        }

        if (converted.isNotEmpty()) {
            Prefs.appRules = converted + Prefs.appRules
            Prefs.appHandled = handled
            Prefs.log("правила режима сети перенесены в общий список (${converted.size})")
        }
        Prefs.schedules = emptyList()
        Prefs.wifiRules = emptyList()
        Prefs.migratedRules = true
        AlarmScheduler.rearm()
    }

    /**
     * Up to 3.3 "Применить" on a Bluetooth rule marked its device as connected, and the mark
     * outlived "Вернуть как было": the real connection that followed was then skipped. A mark
     * whose device is not connected now is that leftover.
     */
    private fun dropManualMarks() {
        if (!Prefs.once("drop_manual_marks_v1")) return
        val connected = BtDevices.connected(App.ctx)
        val stale = Prefs.appRules.filter { r ->
            r.trigger == Trigger.BLUETOOTH && (Prefs.appHandled[r.id] ?: 0L) != 0L && r.btAddress?.uppercase() !in connected
        }
        if (stale.isNotEmpty()) Prefs.appHandled = Prefs.appHandled - stale.map { it.id }.toSet()
    }
}
