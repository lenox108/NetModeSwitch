package com.netmode.switcher

/** Wi-Fi sharing only: USB, Bluetooth and local-only APs do not satisfy this condition. */
internal object HotspotCondition {
    const val ACTION = "android.net.conn.TETHER_STATE_CHANGED"
    const val ACTIVE = "tetherArray"

    private val wifiInterface = Regex("^(wlan|softap|ap_br|swlan)[A-Za-z0-9_.-]*$")

    /** A missing snapshot is unknown; an explicit empty list means sharing is off. */
    fun enabled(interfaces: List<String>?): Boolean? = interfaces?.any { wifiInterface.matches(it) }

    /** [wanted] null accepts every route. The route is the mode selected in NetModeSwitch. */
    fun holds(enabled: Boolean?, current: TetherMode, wanted: TetherMode?): Boolean? =
        enabled?.let { it && (wanted == null || current == wanted) }

    fun label(via: TetherMode?): String = "точка доступа включена" + when (via) {
        TetherMode.VPN -> " · через VPN"
        TetherMode.PROXY -> " · через прокси"
        TetherMode.DIRECT -> " · напрямую"
        null -> ""
    }
}
