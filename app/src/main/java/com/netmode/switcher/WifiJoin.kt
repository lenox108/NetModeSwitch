package com.netmode.switcher

import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A saved Wi-Fi network to connect to, optionally through one access point ([bssid]). */
data class WifiTarget(val ssid: String, val bssid: String? = null) {
    val label: String get() = "Wi-Fi → «$ssid»" + (bssid?.let { " ($it)" } ?: "")

    fun toJson(): JSONObject = JSONObject().put("ssid", ssid).put("bssid", bssid ?: JSONObject.NULL)

    companion object {
        private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** "AA-BB-CC-DD-EE-FF", "aabbccddeeff" → "aa:bb:cc:dd:ee:ff"; null when it is not a MAC. */
        fun normalizeBssid(raw: String): String? {
            val hex = raw.trim().lowercase().filter { it.isLetterOrDigit() }
            if (hex.length != 12) return null
            return hex.chunked(2).joinToString(":").takeIf { MAC.matches(it) }
        }

        fun fromJson(o: JSONObject): WifiTarget? {
            val ssid = o.optString("ssid").takeIf { it.isNotEmpty() } ?: return null
            val bssid = o.optString("bssid").takeIf { it.isNotEmpty() && it != "null" }?.let { normalizeBssid(it) }
            return WifiTarget(ssid, bssid)
        }
    }
}

/** Connects to saved networks through [WifiJoinCli]. */
object WifiJoin {

    private const val ENABLE_WAIT_MS = 6_000L
    /** The CLI's own waits (listener, association) plus starting a Java process in deep sleep. */
    private const val CLI_TIMEOUT_MS = 45_000L

    private fun cli() = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.WifiJoinCli"

    /** The saved networks' names, for the editor; empty when they cannot be read. */
    suspend fun saved(): List<String> = runCatching {
        Privileged.exec("${cli()} list", 15_000).lines()
            .mapNotNull { l -> l.substringAfter('\t', "").takeIf { '\t' in l && it.isNotEmpty() } }
            .distinct()
    }.getOrDefault(emptyList())

    private val BSSID_RE = Regex("""BSSID:\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")

    /** The access point connected through now, when the phone is in network [ssid]. */
    suspend fun currentBssid(ssid: String): String? = runCatching {
        if (!WifiSsid.current().equals(ssid, ignoreCase = true)) return null
        val out = if (Compat.SDK >= 30) Privileged.exec("cmd wifi status") else Privileged.exec("dumpsys wifi | grep -m1 'mWifiInfo'")
        BSSID_RE.find(out)?.groupValues?.get(1)?.lowercase()?.takeIf { it != "02:00:00:00:00:00" }
    }.getOrNull()

    /** Android 10 cannot take a BSSID off a saved network again (see [WifiJoinCli]). */
    val bssidSupported: Boolean get() = Compat.SDK >= 30

    /** Pauses before the background retries: Wi-Fi, root or Shizuku still starting after boot. */
    private val RETRY_DELAYS_MS = listOf(10_000L, 20_000L, 30_000L)

    /** Answers that another try will not change. */
    private val FINAL = setOf("ok", "error=not saved", "error=bssid unsupported")
    private const val BOOT_MS = 180_000L

    /** Right after boot the saved networks may not be loaded yet: "not saved" is tried again. */
    private fun final(out: String): Boolean = out in FINAL &&
        !(out == "error=not saved" && android.os.SystemClock.elapsedRealtime() < BOOT_MS)

    @Volatile private var retry: Job? = null

    /**
     * The rule action: Wi-Fi on if needed, then the network; the outcome goes to the log.
     *
     * A failed first try is repeated in the background, so the rule's other actions are not
     * held up: right after the head unit boots the Wi-Fi service, root or Shizuku may not be
     * up yet, and the same rule run by hand a minute later works.
     */
    suspend fun run(rule: WifiTarget, why: String) {
        // A rule made on a newer Android (shared, restored) still connects, to any access point.
        val target = if (bssidSupported) rule else rule.copy(bssid = null)
        if (target !== rule) Prefs.log("($why): на Android 10 MAC точки не поддерживается — подключаюсь к сети «${rule.ssid}» через любую точку")
        retry?.cancel()
        val first = attempt(target)
        if (first == null) { Prefs.log("($why): уже в сети «${target.ssid}»"); return }
        if (final(first)) { Prefs.log("($why): " + describe(target, first)); return }
        Prefs.log("($why): ${target.label}: не вышло (${first.removePrefix("error=")}) — попробую ещё раз")
        retry = App.scope.launch {
            var last: String = first
            for ((i, pause) in RETRY_DELAYS_MS.withIndex()) {
                delay(pause)
                last = attempt(target) ?: "ok"
                if (final(last) || i == RETRY_DELAYS_MS.lastIndex) break
            }
            Prefs.log("($why): " + describe(target, last) + " (попыток: ${RETRY_DELAYS_MS.size + 1})".takeIf { last != "ok" }.orEmpty())
        }
    }

    /** One try: null when already there, else the helper's answer or "error=…". */
    private suspend fun attempt(target: WifiTarget): String? = try {
        if (target.bssid == null && WifiSsid.current().equals(target.ssid, ignoreCase = true)) {
            null
        } else {
            val wm = App.ctx.getSystemService(WifiManager::class.java)
            if (!wm.isWifiEnabled) {
                Privileged.exec(AppFreezer.WIFI_ON)
                val until = System.currentTimeMillis() + ENABLE_WAIT_MS
                while (!wm.isWifiEnabled && System.currentTimeMillis() < until) delay(300)
            }
            val args = listOfNotNull(target.ssid, target.bssid).joinToString(" ") { NetworkScripts.quote(it) }
            answer(Privileged.exec("${cli()} connect $args", CLI_TIMEOUT_MS))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "error=${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * The helper's own line among whatever else the shell printed: some head units' root shell
     * writes its own lines ("Decrypt success") around the command's output. With no such line
     * the tail of the output is kept for the log.
     */
    internal fun answer(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.lastOrNull { it == "ok" || it.startsWith("error=") }
            ?: "error=нет ответа: " + lines.takeLast(3).joinToString(" | ").take(200).ifEmpty { "пусто" }
    }

    private fun describe(target: WifiTarget, out: String): String = when (out) {
        "ok" -> "подключено: ${target.label}"
        "error=not saved" -> "сеть «${target.ssid}» не сохранена на устройстве — подключись к ней один раз вручную"
        "error=not connected" -> "не удалось подключиться: ${target.label} — сеть не в зоне действия?"
        else -> "не удалось подключиться: ${target.label} — ${out.removePrefix("error=")}"
    }
}
