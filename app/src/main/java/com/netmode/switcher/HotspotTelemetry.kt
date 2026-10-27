package com.netmode.switcher

internal data class ApCounters(val identity: String, val received: Long, val sent: Long)
internal data class ApTraffic(val since: Long, val upload: Long, val download: Long, val upRate: Long?, val downRate: Long?)

/** Interface counters include local traffic. A restart/reset always starts a new observation. */
internal class HotspotTelemetry {
    private var first: Pair<Long, ApCounters>? = null
    private var last: Pair<Long, ApCounters>? = null
    fun sample(now: Long, counters: ApCounters?): ApTraffic? {
        if (counters == null) { first = null; last = null; return null }
        val previous = last
        val reset = previous == null || previous.second.identity != counters.identity ||
            counters.received < previous.second.received || counters.sent < previous.second.sent || now <= previous.first
        if (reset) first = now to counters
        val origin = first!!
        val elapsed = previous?.let { now - it.first } ?: 0L
        val up = if (!reset && elapsed > 0) ((counters.received - previous!!.second.received).toDouble() * 1000 / elapsed).toLong() else null
        val down = if (!reset && elapsed > 0) ((counters.sent - previous!!.second.sent).toDouble() * 1000 / elapsed).toLong() else null
        last = now to counters
        return ApTraffic(origin.first, counters.received - origin.second.received, counters.sent - origin.second.sent, up, down)
    }
}

internal data class ApPeer(val mac: String, val addresses: List<String>)

internal object HotspotParsing {
    private val macPattern = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    fun validMac(value: String) = macPattern.matches(value) && value != "00:00:00:00:00:00" && value != "02:00:00:00:00:00"

    /** Neighbours enrich confirmed clients only, never create clients from stale ARP entries. */
    fun peers(macs: List<String>, neighbours: String, interfaces: Set<String>): List<ApPeer> = macs
        .map(String::lowercase).filter(::validMac).distinct().map { mac ->
            val addresses = neighbours.lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { words ->
                val dev = words.indexOf("dev")
                val ll = words.indexOf("lladdr")
                dev >= 0 && ll >= 0 && words.getOrNull(dev + 1) in interfaces &&
                    words.getOrNull(ll + 1)?.lowercase() == mac && words.none { it == "FAILED" || it == "INCOMPLETE" }
            }.mapNotNull { it.firstOrNull()?.takeIf { ip -> ip.matches(Regex("[0-9a-fA-F:.]+")) } }.distinct().toList()
            ApPeer(mac, addresses)
        }

    fun redact(text: String, secrets: List<String>): String {
        var clean = text
        secrets.filter(String::isNotEmpty).distinct().sortedByDescending(String::length).forEach { clean = clean.replace(it, "[скрыто]") }
        clean = clean.replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "[MAC]")
        return clean
    }
}
