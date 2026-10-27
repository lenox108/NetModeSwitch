package com.netmode.switcher

/**
 * The Wi-Fi hotspot settings, read and written through [ApConfigCli] by the privileged shell.
 * Works with root and with Shizuku (measured: both may read and change the configuration).
 */
data class ApSettings(
    val ssid: String,
    val pass: String,
    /** open | wpa2 | wpa3t | wpa3 */
    val sec: String,
    /** "2", "5", "6" or a combination like "25" */
    val band: String,
    /** 0 = automatic */
    val channel: Int,
    val hidden: Boolean,
    /** 0 = no limit */
    val clients: Int,
    val autoOff: Boolean,
    /** minutes, 0 = system default */
    val timeout: Long,
    val ax: Boolean,
    val randomMac: Boolean,
    /** Full band/channel map, including concurrent APs. Empty only on Android 11. */
    val radio: String = "",
)

/** Channels the radio allows per band, as the Wi-Fi service reports them. */
data class ApCapability(
    val max: Int,
    val channels: Map<String, List<Int>>,
    val bridged: Boolean? = null,
    /** SoftApCapability band flags. Missing means unknown, not unsupported. */
    val supported: Map<String, Boolean> = emptyMap(),
)

internal object HotspotBands {
    fun fromFrequencies(frequencies: List<Int>): Set<String> = frequencies.mapNotNull {
        when (it) {
            in 2412..2484 -> "2"
            in 5160..5885 -> "5"
            in 5935..7115 -> "6"
            else -> null
        }
    }.toSet()

    fun available(caps: ApCapability, sdk: Int): Set<String> = resolve(caps, sdk, setOf("2", "5"))

    /** An incomplete OEM response must not resurrect a band previously reported unsupported. */
    fun resolve(caps: ApCapability, sdk: Int, previous: Set<String>): Set<String> {
        if (sdk < 30) return emptySet()
        fun supported(band: String) = caps.supported[band]
            ?: if (caps.channels[band].orEmpty().isNotEmpty()) true else band in previous
        return buildSet {
            listOf("2", "5", "6").filter(::supported).forEach(::add)
            if ("2" in this && "5" in this) {
                add("auto25")
                if (sdk >= 31 && (caps.bridged == true || (caps.bridged == null && "dual25" in previous))) add("dual25")
            }
        }
    }

    fun known(caps: ApCapability) = caps.supported.isNotEmpty() || caps.bridged != null || caps.channels.values.any { it.isNotEmpty() }
}

object HotspotConfig {

    private fun cli() = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.ApConfigCli"

    /** Single-quotes a value for the shell. */
    private fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"

    suspend fun read(): ApSettings? {
        val out = Privileged.exec("${cli()} get")
        if (out.contains("error=")) return null
        val m = out.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        if (!m.keys.containsAll(listOf("ssid", "sec", "band"))) return null
        return ApSettings(
            ssid = m["ssid"].orEmpty(),
            pass = m["pass"].orEmpty(),
            sec = m["sec"] ?: "wpa2",
            band = m["band"]?.ifEmpty { null } ?: "2",
            channel = m["channel"]?.toIntOrNull() ?: 0,
            hidden = m["hidden"] == "1",
            clients = m["clients"]?.toIntOrNull() ?: 0,
            autoOff = m["autooff"] == "1",
            timeout = m["timeout"]?.toLongOrNull() ?: 0,
            ax = m["ax"] != "0",
            randomMac = m["mac"] != "device",
            radio = m["radio"].orEmpty().ifBlank { ApRadio.legacy(m["band"] ?: "2", m["channel"]?.toIntOrNull() ?: 0) },
        )
    }

    /** Returns null on success, or the error text. */
    suspend fun write(s: ApSettings, previous: ApSettings): String? = configure("set", s, previous)

    /** Validate the builder without changing the saved configuration or a running AP. */
    suspend fun validate(s: ApSettings, previous: ApSettings): String? = configure("check", s, previous)

    private suspend fun configure(command: String, s: ApSettings, previous: ApSettings): String? {
        val changed = changes(s, previous)
        if (changed.isEmpty()) return null
        val args = changed.map { (key, value) -> q("$key=$value") }.joinToString(" ")
        val out = Privileged.exec("${cli()} $command $args").trim()
        return if (out.lines().any { it == "ok" }) null else out.substringAfter("error=", out)
    }

    /** Only edited fields cross the shell boundary: preserve OEM/dual-band settings verbatim. */
    internal fun changes(s: ApSettings, old: ApSettings): Map<String, String> = buildMap {
        if (s.ssid != old.ssid) put("ssid", s.ssid)
        if (s.pass != old.pass || s.sec != old.sec) { put("pass", s.pass); put("sec", s.sec) }
        if (s.radio != old.radio) put("radio", s.radio)
        if (s.hidden != old.hidden) put("hidden", if (s.hidden) "1" else "0")
        if (s.clients != old.clients) put("clients", s.clients.toString())
        if (s.autoOff != old.autoOff) put("autooff", if (s.autoOff) "1" else "0")
        if (s.timeout != old.timeout) put("timeout", s.timeout.toString())
        if (s.ax != old.ax) put("ax", if (s.ax) "1" else "0")
        if (s.randomMac != old.randomMac) put("mac", if (s.randomMac) "random" else "device")
    }

    /**
     * Supported channels from the Wi-Fi service's own log line (CMD_UPDATE_AP_CAPABILITY),
     * which shell can read without any extra permission.
     */
    suspend fun capability(): ApCapability {
        val snapshot = HotspotMonitor.readRadio()
        val supported = listOf("2", "5", "6").mapNotNull { band ->
            if (snapshot.isNull("supported$band")) null else band to snapshot.getBoolean("supported$band")
        }.toMap()
        if (snapshot.has("max")) return ApCapability(snapshot.optInt("max", 32),
            listOf("2", "5", "6").associateWith { band ->
                snapshot.optJSONArray("channels$band")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            }, if (snapshot.isNull("bridged")) null else snapshot.optBoolean("bridged"), supported)
        val line = Privileged.exec("dumpsys wifi 2>/dev/null | grep -m1 'SupportedChannelListIn24g'")
        fun list(tag: String) = Regex("$tag\\[([0-9, ]*)]").find(line)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val max = Regex("MaximumSupportedClientNumber=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 32
        return ApCapability(max, mapOf("2" to list("SupportedChannelListIn24g"), "5" to list("SupportedChannelListIn5g"),
            "6" to list("SupportedChannelListIn6g")), if (snapshot.isNull("bridged")) null else snapshot.optBoolean("bridged"), supported)
    }

    /** Band and frequency of the running hotspot, e.g. "5 ГГц · канал 149", or null when off. */
    suspend fun running(): String? {
        val info = Privileged.exec("dumpsys wifi 2>/dev/null | grep -m1 -oE 'SoftApInfo\\{bandwidth= ?[0-9]+, frequency= ?[0-9]+'")
        if (Privileged.exec("${Compat.TETHER_DUMP} 2>/dev/null | grep -cE '(wlan|softap|ap_br)[^ ]* - TetheredState'").trim() == "0") return null
        return softApSummary(info)
    }

    /** SoftApInfo reports an enum, not MHz. Unknown values must not masquerade as a width. */
    internal fun softApSummary(info: String): String? {
        val f = Regex("frequency= ?(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val (band, ch) = when {
            f in 2412..2484 -> "2,4 ГГц" to (if (f == 2484) 14 else (f - 2407) / 5)
            f in 5160..5885 -> "5 ГГц" to (f - 5000) / 5
            f in 5955..7115 -> "6 ГГц" to (f - 5950) / 5
            f == 5935 -> "6 ГГц" to 2
            else -> return null
        }
        val width = when (Regex("bandwidth= ?(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()) {
            1, 2 -> "20"
            3 -> "40"
            4 -> "80"
            5 -> "80+80"
            6 -> "160"
            11 -> "320"
            else -> null
        }
        return "$band · канал $ch" + (width?.let { " · $it МГц" } ?: "")
    }

    fun summary(s: ApSettings): String {
        val band = ApRadio.label(ApRadio.key(s.radio.ifBlank { ApRadio.legacy(s.band, s.channel) }))
        val ch = if (s.channel == 0) "канал авто" else "канал ${s.channel}"
        return "${s.ssid} · $band · $ch"
    }
}
