package com.netmode.switcher

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * "Системные настройки": sound, volume, Do Not Disturb, brightness, screen timeout, refresh rate.
 * A rule keeps them as [SysKey] name → one of its option codes. What was there before is read in
 * a raw form of its own ("i:7", "m:120", …) so the end can put back exactly that.
 */
enum class SysKey(val title: String, val options: List<String>) {
    RINGER("Звонок", listOf("normal", "vibrate", "silent")),
    MEDIA("Громкость медиа", listOf("0", "25", "50", "75", "100")),
    DND("«Не беспокоить»", listOf("on", "off")),
    BRIGHT("Яркость", listOf("auto", "10", "30", "50", "100")),
    TIMEOUT("Тайм-аут экрана", listOf("15", "30", "60", "120", "300", "600")),
    REFRESH("Частота экрана", listOf("60", "max"));

    fun optionLabel(code: String): String = when (this) {
        RINGER -> when (code) { "normal" -> "со звуком"; "vibrate" -> "вибрация"; "silent" -> "без звука"; else -> "как было" }
        MEDIA -> code.toIntOrNull()?.let { "$it%" } ?: "как было"
        DND -> when (code) { "on" -> "вкл"; "off" -> "выкл"; else -> "как было" }
        BRIGHT -> if (code == "auto") "авто" else code.toIntOrNull()?.let { "$it%" } ?: "как было"
        TIMEOUT -> code.toIntOrNull()?.let { Steps.words(it) } ?: "как было"
        REFRESH -> when (code) { "60" -> "60 Гц"; "max" -> "максимальная"; else -> "как было" }
    }

    companion object {
        fun of(name: String): SysKey? = entries.firstOrNull { it.name == name }

        /** "звонок: вибрация, яркость: 30%" */
        fun summary(map: Map<String, String>): String = map.entries.mapNotNull { (k, v) ->
            of(k)?.let { it.title.lowercase() + ": " + it.optionLabel(v) }
        }.joinToString(", ")
    }
}

object SysSettings {

    private val ctx: Context get() = App.ctx
    private val audio get() = ctx.getSystemService(AudioManager::class.java)
    private val notes get() = ctx.getSystemService(NotificationManager::class.java)

    /** What is set now, in the raw form [apply] takes back; null when it cannot be read. */
    fun read(key: SysKey): String? = runCatching {
        val cr = ctx.contentResolver
        when (key) {
            SysKey.RINGER -> when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "silent"
            }
            SysKey.MEDIA -> "i:" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            SysKey.DND -> "f:" + notes.currentInterruptionFilter
            SysKey.BRIGHT -> if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) "auto"
            else "m:" + Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            SysKey.TIMEOUT -> "ms:" + Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT)
            SysKey.REFRESH -> "p:" + Settings.System.getString(cr, PEAK).orEmpty() + "|" + Settings.System.getString(cr, MIN).orEmpty()
        }
    }.getOrNull()

    private const val PEAK = "peak_refresh_rate"
    private const val MIN = "min_refresh_rate"

    /**
     * Do Not Disturb and silent mode need the app's own access to the notification policy,
     * which the privileged shell can grant without a settings screen.
     */
    private suspend fun policyAccess() {
        if (notes.isNotificationPolicyAccessGranted) return
        runCatching { Privileged.exec("cmd notification allow_dnd ${ctx.packageName}") }
    }

    /** Sets [key] to [value] — an option code or a raw form from [read]. */
    suspend fun apply(key: SysKey, value: String) {
        when (key) {
            SysKey.RINGER -> {
                policyAccess()
                audio.ringerMode = when (value) {
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_SILENT
                }
            }
            SysKey.MEDIA -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val index = value.removePrefix("i:").toIntOrNull()?.takeIf { value.startsWith("i:") }
                    ?: ((value.toIntOrNull() ?: return) * max + 50) / 100
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0)
            }
            SysKey.DND -> {
                policyAccess()
                val filter = when {
                    value == "on" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    value == "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    else -> value.removePrefix("f:").toIntOrNull() ?: return
                }
                notes.setInterruptionFilter(filter)
            }
            SysKey.BRIGHT -> Privileged.exec(SysCommands.brightness(value) ?: reject(key, value))
            SysKey.TIMEOUT -> Privileged.exec(SysCommands.timeout(value) ?: reject(key, value))
            SysKey.REFRESH -> Privileged.exec(SysCommands.refresh(value, PEAK, MIN, ::maxRate) ?: reject(key, value))
        }
    }

    /** No command for a value that is not one: it would otherwise reach the root shell as it is. */
    private fun reject(key: SysKey, value: String): Nothing {
        Prefs.log("${key.title}: недопустимое значение «${value.take(40)}» — команда не выполнена")
        throw IllegalArgumentException("недопустимое значение")
    }

    /** The highest refresh rate the screen offers, "120.0" on the phone the app was built on. */
    private fun maxRate(): String = runCatching {
        val d = ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        d.supportedModes.maxOf { it.refreshRate }.toString()
    }.getOrDefault("120.0")
}

/**
 * The shell commands of [SysSettings], for an option code or a raw form from its read; null when
 * [value] is neither. Rules and backups are text, so nothing else may reach the shell.
 */
internal object SysCommands {
    private val RATE = Regex("^(\\d{1,4}(\\.\\d{1,9})?|Infinity)$")

    fun brightness(value: String): String? {
        val raw = when {
            value == "auto" -> return "settings put system screen_brightness_mode 1"
            value.startsWith("m:") -> value.removePrefix("m:").toIntOrNull()?.takeIf { it >= 0 }
            else -> value.toIntOrNull()?.let { (it * 255 / 100).coerceIn(1, 255) }
        } ?: return null
        return "settings put system screen_brightness_mode 0; settings put system screen_brightness $raw"
    }

    fun timeout(value: String): String? {
        val ms = if (value.startsWith("ms:")) value.removePrefix("ms:").toIntOrNull()
        else value.toIntOrNull()?.let { it * 1000 }
        return ms?.let { "settings put system screen_off_timeout $it" }
    }

    /** An empty rate was unset when read, so it is unset again; see [SysSettings.read]. */
    fun refresh(value: String, peakKey: String, minKey: String, maxRate: () -> String): String? {
        val (peak, min) = when {
            value == "60" -> "60.0" to "60.0"
            value == "max" -> maxRate() to "0.0"
            value.startsWith("p:") -> value.removePrefix("p:").substringBefore('|') to value.substringAfter('|', "")
            else -> return null
        }
        if (listOf(peak, min).any { it.isNotEmpty() && !RATE.matches(it) }) return null
        fun put(name: String, v: String) = if (v.isEmpty()) "settings delete system $name" else "settings put system $name $v"
        return put(peakKey, peak) + "; " + put(minKey, min)
    }
}

/** One-off actions: a notification, an HTTP request, a shell command. Each returns a log line. */
object OneShots {

    /** "POST https://…" or a bare URL, a GET. */
    suspend fun http(spec: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val post = spec.trim().startsWith("POST ", ignoreCase = true)
        val url = spec.trim().removePrefix("POST ").removePrefix("post ").removePrefix("GET ").removePrefix("get ").trim()
        runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            if (post) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("{}".toByteArray()) }
            }
            try { "HTTP ${c.responseCode}" } finally { c.disconnect() }
        }.getOrElse { "HTTP: ошибка ${it.message}" }
    }

    suspend fun shell(command: String): String = runCatching {
        val out = Privileged.exec(command, timeoutMs = 20_000L).trim().lineSequence().firstOrNull().orEmpty()
        "команда выполнена" + if (out.isNotEmpty()) ": " + out.take(80) else ""
    }.getOrElse { "команда: ошибка ${it.message}" }

    fun note(text: String): String {
        RunNotices.message(text)
        return "уведомление"
    }

    /** All of a start's or an end's one-offs, in this order, into the log; returns those lines. */
    suspend fun run(note: String?, http: String?, shell: String?, why: String): List<String> {
        val done = listOfNotNull(note?.let { note(it) }, http?.let { http(it) }, shell?.let { shell(it) })
        if (done.isNotEmpty()) Prefs.log("($why): ${done.joinToString(", ")}")
        return done
    }
}
