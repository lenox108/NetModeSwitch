package com.netmode.switcher

/** Wi-Fi sharing only: USB, Bluetooth and local-only APs do not satisfy this condition. */
internal object HotspotCondition {
    const val ACTION = "android.net.conn.TETHER_STATE_CHANGED"
    const val ACTIVE = "tetherArray"
    const val RADIO_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"

    fun band(value: String?): String? = value?.takeIf { it in listOf("2", "5") }

    fun bandsFromDump(dump: String): Set<String> {
        val frequencies = Regex("(?:mReportedFrequency|mFrequency)\\s*[=:]\\s*(\\d+)")
            .findAll(dump.substringAfter("--Dump of SoftApManager--", "").substringBefore("--Dump of "))
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return HotspotBands.fromFrequencies(frequencies)
    }

    private val wifiInterface = Regex("^(wlan|softap|ap_br|swlan)[A-Za-z0-9_.-]*$")

    /** A missing snapshot is unknown; an explicit empty list means sharing is off. */
    fun enabled(interfaces: List<String>?): Boolean? = interfaces?.any { wifiInterface.matches(it) }

    /** [wanted] null accepts every route. The route is the mode selected in NetModeSwitch. */
    fun holds(enabled: Boolean?, current: TetherMode, wanted: TetherMode?,
        activeBands: Set<String>? = null, wantedBand: String? = null): Boolean? {
        if (enabled != true) return enabled
        if (wanted != null && current != wanted) return false
        if (wantedBand == null) return true
        return activeBands?.takeIf { it.isNotEmpty() }?.contains(wantedBand)
    }

    fun label(via: TetherMode?, band: String? = null): String = "точка доступа включена" + when (via) {
        TetherMode.VPN -> " · через VPN"
        TetherMode.PROXY -> " · через прокси"
        TetherMode.DIRECT -> " · напрямую"
        null -> ""
    } + (band?.let { " · ${ApRadio.label(it)}" } ?: "")
}
