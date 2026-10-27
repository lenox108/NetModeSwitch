package com.netmode.switcher

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class HotspotSnapshot(
    val enabled: Boolean?, val radios: List<String>, val clients: List<ApPeer>?, val clientCount: Int?,
    val interfaces: List<String>, val counters: ApCounters?, val radioError: String?,
)

/** Observations only. No apply(), probes, route changes, or background polling here. */
internal object HotspotMonitor {
    suspend fun readRadio(): JSONObject {
        val out = Privileged.exec("CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.HotspotReadCli", 8_000)
        return out.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.lastOrNull()
            ?: JSONObject().put("error", "Нет ответа Wi-Fi")
    }

    suspend fun snapshot(): HotspotSnapshot {
        val radio = readRadio()
        val ifaces = TetherRoute.tetherInterfaces().filter { it.matches(Regex("^(wlan|softap|ap_br|swlan)[A-Za-z0-9_.-]*$")) }
        val counters = if (ifaces.isEmpty()) null else {
            val output = Privileged.exec(ifaces.joinToString("\n") { iface ->
                "echo @IF=$iface; cat /sys/class/net/$iface/ifindex /sys/class/net/$iface/statistics/rx_bytes /sys/class/net/$iface/statistics/tx_bytes"
            }, 5_000)
            val entries = output.split("@IF=").drop(1).mapNotNull { part ->
                val lines = part.trim().lines()
                val index = lines.getOrNull(1)?.toLongOrNull()
                val rx = lines.getOrNull(2)?.toLongOrNull()
                val tx = lines.getOrNull(3)?.toLongOrNull()
                if (index == null || rx == null || tx == null || rx < 0 || tx < 0) null
                else ApCounters("${lines[0]}:$index", rx, tx)
            }
            if (entries.size != ifaces.size) null else ApCounters(entries.joinToString { it.identity }, entries.sumOf { it.received }, entries.sumOf { it.sent })
        }
        val neighbourText = if (ifaces.isEmpty()) "" else Privileged.exec("ip neigh show", 5_000)
        val clients = radio.optJSONArray("clients")
        val macs = clients?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("mac") } }
        val infos = radio.optJSONArray("infos")
        val summaries = infos?.let { a -> (0 until a.length()).mapNotNull {
            val info = a.getJSONObject(it)
            HotspotConfig.softApSummary("bandwidth=${info.optInt("bandwidth")}, frequency=${info.optInt("frequency")}")
        } }.orEmpty().distinct()
        val state = radio.optInt("state", -1)
        val enabled = when { ifaces.isNotEmpty() -> true; state == 11 -> false; state == 13 -> true; else -> null }
        return HotspotSnapshot(enabled, summaries,
            macs?.let { HotspotParsing.peers(it, neighbourText, ifaces.toSet()) }, macs?.size,
            ifaces, counters, radio.optString("error").ifBlank { null })
    }

    suspend fun report(): String {
        val started = java.time.ZonedDateTime.now()
        val mode = Prefs.tetherMode
        val snapshot = runCatching { snapshot() }
        currentCoroutineContext().ensureActive()
        val state = snapshot.getOrNull()
        val details = runCatching { TetherRoute.diagnosticDetails() }.getOrElse { "Не удалось прочитать маршрут: ${it.javaClass.simpleName}" }
        currentCoroutineContext().ensureActive()
        val saved = runCatching { HotspotConfig.read() }.getOrNull()
        val report = buildString {
            appendLine("NetModeSwitch ${BuildConfig.VERSION_NAME} — диагностика раздачи")
            appendLine("Начало: $started")
            appendLine("Конец: ${java.time.ZonedDateTime.now()}")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            val thermal = runCatching { App.ctx.getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrNull()
            appendLine("Thermal status: ${thermal ?: "недоступно"}")
            appendLine("Выбрано: ${TetherRoute.label(mode)}; в конце: ${TetherRoute.label(Prefs.tetherMode)}")
            appendLine("Точка: ${state?.enabled ?: "неизвестно"}; интерфейсы: ${state?.interfaces}")
            appendLine("Радио: ${state?.radios?.joinToString() ?: "нет данных"}")
            appendLine("Клиентов: ${state?.clientCount ?: "недоступно"}; Wi-Fi API: ${state?.radioError ?: if (state == null) "недоступно" else "OK"}")
            state?.counters?.let { appendLine("Счётчики интерфейса: RX=${it.received}, TX=${it.sent}") }
            if (snapshot.isFailure) appendLine("Снимок: ${snapshot.exceptionOrNull()?.javaClass?.simpleName}")
            appendLine("\n$details")
            appendLine("\nПоследние события маршрута:")
            Prefs.events.filter { it.contains("раздач", true) || it.contains("TTL") || it.contains("IPv6") }.take(35).forEach { appendLine(it) }
            appendLine("\nСнимок состояния без перезапуска. Это не замер скорости и не проверка выхода с подключённого устройства.")
        }
        return HotspotParsing.redact(report, listOfNotNull(saved?.pass, saved?.ssid, Prefs.tetherProxyPass, Prefs.tetherProxyUser))
    }
}
