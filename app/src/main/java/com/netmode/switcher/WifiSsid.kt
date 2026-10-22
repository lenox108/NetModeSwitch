package com.netmode.switcher

/**
 * Reads the current SSID through the root shell.
 *
 * The ordinary route (WifiInfo from NetworkCapabilities) returns "<unknown ssid>" unless the
 * app holds ACCESS_FINE_LOCATION, and reading it from the background additionally needs
 * "Allow all the time" plus a location indicator in the status bar. Root is already there,
 * so no location permission is requested at all.
 */
object WifiSsid {

    private val PATTERNS = listOf(
        Regex("""connected to\s+"([^"]*)""""),
        Regex("""SSID:\s*"([^"]*)""""),
        // Android 10's dumpsys: "mWifiInfo SSID: Home, BSSID: …", no quotes.
        Regex("""SSID: ([^",][^,]*), BSSID"""),
    )

    private val UNKNOWN = setOf("", "<unknown ssid>", "null", "none")

    suspend fun current(): String? = runCatching {
        // `cmd wifi status` exists from Android 11; before, the connection info in dumpsys.
        if (Compat.SDK >= 30) parse(Privileged.exec("cmd wifi status"))
        else parse(Privileged.exec("dumpsys wifi | grep -m1 'mWifiInfo'").takeIf { it.contains("state: COMPLETED") }.orEmpty())
    }.getOrNull()

    fun parse(output: String): String? {
        for (pattern in PATTERNS) {
            val hit = pattern.find(output)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
            if (hit != null && hit.lowercase() !in UNKNOWN) return hit
        }
        return null
    }
}
