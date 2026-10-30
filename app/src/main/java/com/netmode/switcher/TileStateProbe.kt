package com.netmode.switcher

/** State adapters for tiles whose foreground service is not their on/off state. */
internal object TileStateProbe {
    // Both Amnezia clients can keep a foreground service after their tunnel is disconnected.
    // Do not apply this to every VpnService provider: Happ also has a separate proxy mode.
    fun usesVpn(component: String, explicit: Set<String>): Boolean =
        component in explicit || component.substringBefore('/') in setOf("org.amnezia.vpn", "org.amnezia.awg")

    /**
     * What [vpn] reads, cut down on the device: the await loop asks every 2 s, and the full dump
     * is large. Keeps the first lines, where a failed dump says so, then "Current Networks:"
     * up to and including the next header, and stops reading there.
     */
    const val VPN_DUMP = "dumpsys connectivity 2>&1 | awk '" +
        "!s && /^[ \\t]*Current Networks:[ \\t]*\$/ { s = 1; match(\$0, /^[ \\t]*/); i = RLENGTH; print; next } " +
        "s { print; if (NF) { match(\$0, /^[ \\t]*/); if (RLENGTH <= i && \$0 !~ /^[ \\t]*NetworkAgentInfo/) exit } next } " +
        "NF && h < 3 { h++; print }'"

    private val failure = Regex("Permission Denial|Permission denied|SecurityException|Can't find service|Error dumping", RegexOption.IGNORE_CASE)
    private val legacyOwner = Regex("\\bextra: VPN:([A-Za-z0-9_.]+)")
    private val connectionState = Regex("\\bVPN (CONNECTED|CONNECTING|DISCONNECTING|DISCONNECTED)\\b|\\bstate:\\s*(CONNECTED|CONNECTING|DISCONNECTING|DISCONNECTED)\\b")
    private val vpnTransport = Regex("Transports:\\s*[^]\\n]*\\bVPN\\b")
    private val ownerUid = Regex("\\bOwnerUid:\\s*(\\d+)")

    /** null means unreadable/unsupported, never "off". Restrict parsing to current networks:
     * the rest of dumpsys also contains historical VPN connections. */
    fun vpn(dump: String, pkg: String, uid: Int?): Boolean? {
        if (failure.containsMatchIn(dump)) return null
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim().equals("Current Networks:", ignoreCase = true) }
        if (start < 0) return null
        val indent = lines[start].takeWhile { it.isWhitespace() }.length
        val remaining = lines.drop(start + 1)
        val entries = remaining.takeWhile { it.isBlank() || it.trimStart().startsWith("NetworkAgentInfo") ||
            it.takeWhile { char -> char.isWhitespace() }.length > indent }
        val section = entries.joinToString("\n").trim()
        if (section.isEmpty()) {
            // A following section distinguishes an empty list from a truncated/unknown reply.
            val next = remaining.drop(entries.size).firstOrNull { it.isNotBlank() }?.trim()
            return if (next?.endsWith(':') == true) false else null
        }
        if (!section.startsWith("NetworkAgentInfo")) return null
        val networks = section.split(Regex("(?=NetworkAgentInfo[\\s{(])")).filter { it.isNotBlank() }
        var unknown = false
        for (network in networks) {
            val legacy = legacyOwner.find(network)
            val state = connectionState.find(network)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            if (legacy != null) {
                if (legacy.groupValues[1] == pkg) {
                    return when (state) {
                        "CONNECTED" -> true
                        "DISCONNECTED" -> false
                        else -> null
                    }
                }
            } else if (vpnTransport.containsMatchIn(network)) {
                val owner = ownerUid.find(network)?.groupValues?.get(1)?.toIntOrNull()
                if (owner == null || owner < 0 || uid == null) unknown = true
                else if (owner == uid) return when (state) {
                    "CONNECTED" -> true
                    "DISCONNECTED" -> false
                    else -> null
                }
            } else if (!network.contains("Transports:") && !network.contains("ni{")) {
                unknown = true
            }
        }
        return if (unknown) null else false
    }

    /** Existing adapter for proxy/non-VPN tiles, with dump failures kept unknown. */
    fun foreground(dump: String): Boolean? {
        if (failure.containsMatchIn(dump)) return null
        if (!dump.contains("ACTIVITY MANAGER SERVICES") && !dump.trimStart().startsWith("No services match:")) return null
        return Regex("\\bisForeground=true\\b").containsMatchIn(dump)
    }
}
