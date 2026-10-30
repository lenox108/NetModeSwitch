package com.netmode.switcher

/** State adapters for tiles whose foreground service is not their on/off state. */
internal object TileStateProbe {
    // Both Amnezia clients can keep a foreground service after their tunnel is disconnected.
    // Do not apply this to every VpnService provider: Happ also has a separate proxy mode.
    fun usesVpn(component: String, explicit: Set<String>): Boolean =
        component in explicit || component.substringBefore('/') in setOf("org.amnezia.vpn", "org.amnezia.awg")

    private val failure = Regex("Permission Denial|Permission denied|SecurityException|Can't find service|Error dumping", RegexOption.IGNORE_CASE)
    private val legacyOwner = Regex("\\bextra: VPN:([A-Za-z0-9_.]+)")
    private val connectionState = Regex("\\bVPN (CONNECTED|CONNECTING|DISCONNECTING|DISCONNECTED)\\b|\\bstate:\\s*(CONNECTED|CONNECTING|DISCONNECTING|DISCONNECTED)\\b")
    private val vpnTransport = Regex("Transports:\\s*(?:[A-Z_]+\\|)*VPN(?![A-Z_])")
    private val ownerUid = Regex("\\bOwnerUid:\\s*(\\d+)")

    /** null means unreadable/unsupported, never "off". Restrict parsing to current networks:
     * the rest of dumpsys also contains historical VPN connections. */
    fun vpn(dump: String, pkg: String, uid: Int?): Boolean? = vpn(dump, pkg, uid) { }

    /** As [vpn], and [why] hears what made the answer unknown, for the log. */
    fun vpn(dump: String, pkg: String, uid: Int?, why: (String) -> Unit): Boolean? {
        fun unknown(reason: String): Boolean? { why(reason); return null }
        // A failed dump says so up front; the full dump's log history (bigger with a hotspot
        // on) may quote such errors from other services and is no failure of this read.
        if (failure.containsMatchIn(head(dump))) return unknown("ошибка dumpsys: ${head(dump).lineSequence().first().take(80)}")
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim().equals("Current Networks:", ignoreCase = true) }
        if (start < 0) return unknown("в dumpsys нет «Current Networks», ${dump.length} симв.")
        val indent = lines[start].takeWhile { it.isWhitespace() }.length
        val remaining = lines.drop(start + 1)
        val entries = remaining.takeWhile { it.isBlank() || it.trimStart().startsWith("NetworkAgentInfo") ||
            it.takeWhile { char -> char.isWhitespace() }.length > indent }
        val section = entries.joinToString("\n").trim()
        if (section.isEmpty()) {
            // A following section distinguishes an empty list from a truncated/unknown reply.
            val next = remaining.drop(entries.size).firstOrNull { it.isNotBlank() }?.trim()
            return if (next?.endsWith(':') == true) false else unknown("пустой список сетей без следующего раздела")
        }
        if (!section.startsWith("NetworkAgentInfo")) return unknown("формат сетей не распознан: ${section.lineSequence().first().take(80)}")
        val networks = section.split(Regex("(?=NetworkAgentInfo[\\s{(])")).filter { it.isNotBlank() }
        var unidentified: String? = null
        fun state(state: String?): Boolean? = when (state) {
            "CONNECTED" -> true
            "DISCONNECTED" -> false
            else -> unknown("VPN $pkg в состоянии ${state ?: "?"}")
        }
        for (block in networks) {
            // Only the network's own line: the lines under it list the requests it serves, and
            // those can name a VPN (or an owner) with no bearing on this network.
            val network = block.lineSequence().first()
            val legacy = legacyOwner.find(network)
            val state = connectionState.find(network)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            if (legacy != null) {
                if (legacy.groupValues[1] == pkg) return state(state)
            } else if (vpnTransport.containsMatchIn(network)) {
                val owner = ownerUid.find(network)?.groupValues?.get(1)?.toIntOrNull()
                if (owner == null || owner < 0 || uid == null)
                    unidentified = "VPN-сеть без владельца (OwnerUid ${owner ?: "нет"}, uid ${uid ?: "нет"}): ${network.trim().take(160)}"
                else if (owner == uid) return state(state)
            } else if (!network.contains("Transports:") && !network.contains("ni{")) {
                unidentified = "сеть не распознана: ${network.trim().take(160)}"
            }
        }
        return if (unidentified != null) unknown(unidentified) else false
    }

    private fun head(dump: String) = dump.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")

    /** Existing adapter for proxy/non-VPN tiles, with dump failures kept unknown. */
    fun foreground(dump: String): Boolean? {
        if (failure.containsMatchIn(dump)) return null
        if (!dump.contains("ACTIVITY MANAGER SERVICES") && !dump.trimStart().startsWith("No services match:")) return null
        return Regex("\\bisForeground=true\\b").containsMatchIn(dump)
    }
}
