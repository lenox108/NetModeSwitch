package com.netmode.switcher

import android.content.Context

/** Ready rules to start from; each opens in the editor to be adjusted before saving. */
object Templates {

    class Template(val title: Int, val short: Int, val rule: (Context) -> AppRule)

    private fun base() = AppRule(
        id = 0, enabled = true, hour = 23, minute = 0, endHour = null, endMinute = null,
        days = 0, packages = emptyList(), method = FreezeMethod.STOP_RESTRICT,
    )

    val all = listOf(
        Template(R.string.tpl_sleep, R.string.tpl_sleep_hint) {
            base().copy(name = it.getString(R.string.tpl_sleep), trigger = Trigger.MANUAL, waitLocked = false,
                releaseOnLeave = true, level = 8 * 60, airplane = Toggle.ON)
        },
        Template(R.string.tpl_game, R.string.tpl_game_hint) {
            base().copy(name = it.getString(R.string.tpl_game), trigger = Trigger.MANUAL, waitLocked = false,
                releaseOnLeave = true, netMode = NetMode.LTE_ONLY, unload = true)
        },
        Template(R.string.tpl_app_vpn, R.string.tpl_app_vpn_hint) {
            base().copy(name = it.getString(R.string.tpl_app_vpn), trigger = Trigger.APP_OPEN, waitLocked = false,
                appLeaveDelay = 5, vpn = Toggle.ON)
        },
        Template(R.string.tpl_nonet, R.string.tpl_nonet_hint) {
            base().copy(name = it.getString(R.string.tpl_nonet), trigger = Trigger.NO_INTERNET, waitLocked = false,
                releaseOnLeave = false, level = 60, reconnect = true)
        },
        Template(R.string.tpl_night, R.string.tpl_night_hint) {
            base().copy(name = it.getString(R.string.tpl_night), hour = 23, minute = 0, endHour = 7, endMinute = 0,
                netMode = NetMode.THREE_G)
        },
        Template(R.string.tpl_smart, R.string.tpl_smart_hint) {
            base().copy(name = it.getString(R.string.tpl_smart), trigger = Trigger.MOBILE_DATA, waitLocked = false, netMode = NetMode.FULL,
                endCustom = true, endNet = NetMode.THREE_G)
        },
        Template(R.string.tpl_left, R.string.tpl_left_hint) {
            base().copy(name = it.getString(R.string.tpl_left), trigger = Trigger.WIFI_LEFT, waitLocked = false, ssid = "",
                mobileData = Toggle.ON, netMode = NetMode.FULL)
        },
        Template(R.string.tpl_locked, R.string.tpl_locked_hint) {
            base().copy(name = it.getString(R.string.tpl_locked), trigger = Trigger.LOCKED, waitLocked = false, level = 0,
                netMode = NetMode.THREE_G)
        },
        Template(R.string.tpl_battery, R.string.tpl_battery_hint) {
            base().copy(name = it.getString(R.string.tpl_battery), trigger = Trigger.BATTERY, waitLocked = false, level = 20,
                netMode = NetMode.THREE_G, powerSave = Toggle.ON)
        },
    )
}
