package com.netmode.switcher

/** A launch button stores only a band and route; Wi-Fi credentials remain in Android. */
internal data class HotspotPreset(val band: String, val mode: TetherMode) {
    val bandLabel: String get() = if (band == "dual25") "2,4 + 5 ГГц" else ApRadio.label(band)
    val routeLabel: String get() = if (mode == TetherMode.DIRECT) "Напрямую" else TetherRoute.label(mode)
    val title: String get() = "$bandLabel · $routeLabel"

    companion object {
        val bands = listOf("2", "5", "6", "dual25")
        val defaults = listOf(TetherMode.VPN, TetherMode.PROXY, TetherMode.DIRECT)
            .flatMap { mode -> listOf("2", "5").map { HotspotPreset(it, mode) } }

        fun decode(value: String?): List<HotspotPreset> = if (value == null) defaults else
            value.split(';').mapNotNull { item ->
                val parts = item.split(':')
                if (parts.size != 2 || parts[1] !in bands) null else
                    runCatching { HotspotPreset(parts[1], TetherMode.valueOf(parts[0])) }.getOrNull()
            }.distinct().take(12)

        fun encode(items: List<HotspotPreset>): String = items.distinct()
            .filter { it.band in bands }.take(12).joinToString(";") { "${it.mode.name}:${it.band}" }
    }
}

/** Stop before changing frequency, and never start a different band after a failed write. */
internal class HotspotPresetStart(private val device: Device) {
    interface Device {
        suspend fun read(): ApSettings?
        suspend fun validate(next: ApSettings, old: ApSettings): String?
        suspend fun write(next: ApSettings, old: ApSettings): String?
        suspend fun isOn(): Boolean
        suspend fun activeBands(): Set<String>
        suspend fun stop(): Boolean
        suspend fun ensureOn(mode: TetherMode): String
    }

    suspend fun restore(radio: String, enabled: Boolean, mode: TetherMode): String? {
        if (ApRadio.parse(radio).isEmpty()) return "Неверный сохранённый диапазон"
        val old = device.read() ?: return "Не удалось прочитать настройки Wi-Fi"
        val changed = old.radio != radio
        val next = old.copy(radio = radio)
        if (changed) device.validate(next, old)?.let { return it }
        if (device.isOn() && (changed || !enabled) && !device.stop()) return "Точка не остановилась"
        if (changed) {
            device.write(next, old)?.let { return it }
            if (device.read()?.radio != radio) return "Android не подтвердил возврат диапазона"
        }
        if (enabled) {
            device.ensureOn(mode)
            if (!device.isOn()) return "Точка доступа не включилась при возврате"
        }
        return null
    }

    suspend fun run(preset: HotspotPreset, before: (ApSettings, Boolean) -> Unit = { _, _ -> }): String {
        require(preset.band in HotspotPreset.bands)
        val old = device.read() ?: return "Не удалось прочитать настройки Wi-Fi. Быстрый запуск отменён."
        val changed = ApRadio.key(old.radio) != preset.band
        val next = if (changed) old.copy(band = preset.band, channel = 0, radio = ApRadio.value(preset.band)) else old
        if (changed) device.validate(next, old)?.let { return "Диапазон не применён: $it" }
        val on = device.isOn()
        val active = if (on) device.activeBands() else emptySet()
        // One idle AP may be shut down in bridged mode; that is not a reason to restart it.
        val expected = if (preset.band == "dual25") setOf("2", "5") else setOf(preset.band)
        before(old, on)
        if (on && (changed || active.any { it !in expected }) && !device.stop())
            return "Точка не остановилась. Диапазон и маршрут оставлены без изменений."
        if (changed) {
            device.write(next, old)?.let { return "Не удалось сохранить диапазон: $it. Раздача не запущена." }
            if (device.read()?.let { ApRadio.key(it.radio) } != preset.band)
                return "Android не подтвердил выбранный диапазон. Раздача не запущена."
        }
        return "${preset.bandLabel} · ${device.ensureOn(preset.mode)}"
    }
}
