package com.netmode.switcher

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * The phone check: finds out what works on this phone and how, the way it was worked out by
 * hand on the OnePlus the app was built on, and saves it as the [Profile] the app then follows.
 *
 *  1. the device and its firmware;
 *  2. root or Shizuku;
 *  3. which way of switching the network mode takes effect — the AOSP command, or the phone
 *     service itself where the command answers "completed" and changes nothing;
 *  4. every preset on every active SIM: written, read back at once and again after
 *     [HOLD_MS], since some firmwares put their own default back over a mask their
 *     network-type screen does not list. Other masks of the same preset are tried until one
 *     holds; a preset the modem or the carrier strips (no 5G) is marked as unavailable;
 *  5. the SIM's own mode is put back at the end;
 *  6. every other feature, without changing anything.
 *
 * [report] gets each step as it starts (state null) and as it ends.
 */
object Calibration {

    private const val SETTLE_MS = 1_500L
    private const val HOLD_MS = 8_000L

    /** True while modes are being switched on purpose; rules must not react. */
    @Volatile var running = false
        private set

    suspend fun run(report: (key: String, title: String, state: CheckState?, note: String) -> Unit): DeviceProfile {
        running = true
        try {
            return runLocked(report)
        } finally {
            running = false
            runCatching { ModeController.readAll() }
        }
    }

    private suspend fun runLocked(report: (String, String, CheckState?, String) -> Unit): DeviceProfile {
        // --- 1. the device ---
        report("device", "Телефон", null, "")
        val backend = Privileged.choose()
        val props = if (backend == Backend.AUTO) "" else runCatching {
            Privileged.exec(
                "echo \"oplus=$(getprop ro.build.version.oplusrom)\"; echo \"miui=$(getprop ro.miui.ui.version.name)\"; " +
                    "echo \"hyper=$(getprop ro.mi.os.version.name)\"; echo \"oneui=$(getprop ro.build.version.oneui)\"; " +
                    "echo \"magic=$(getprop ro.build.version.magic)\"; echo \"band=$(getprop gsm.version.baseband)\""
            )
        }.getOrDefault("")
        fun prop(k: String) = props.lineSequence().firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.trim().orEmpty()
        val rom = when {
            prop("oplus").isNotEmpty() -> (if (Build.MANUFACTURER.equals("OnePlus", true)) "OxygenOS " else "ColorOS ") + prop("oplus")
            prop("hyper").isNotEmpty() -> "HyperOS ${prop("hyper")}"
            prop("miui").isNotEmpty() -> "MIUI ${prop("miui")}"
            prop("oneui").isNotEmpty() -> "One UI " + prop("oneui").toIntOrNull()?.let { "${it / 10000}.${it / 100 % 100}" }.orEmpty()
            prop("magic").isNotEmpty() -> "MagicOS ${prop("magic")}"
            else -> "стандартная прошивка (${Build.ID})"
        }
        val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        report("device", "Телефон", CheckState.OK, "$device · $rom · Android ${Build.VERSION.RELEASE}" +
            prop("band").takeIf { it.isNotEmpty() }?.let { " · модем $it" }.orEmpty())

        // --- 2. access ---
        report("access", "Доступ к системе", null, "")
        val backendLabel = when (backend) { Backend.ROOT -> "root"; Backend.SHIZUKU -> "Shizuku"; Backend.AUTO -> "нет" }
        if (backend == Backend.AUTO) {
            report("access", "Доступ к системе", CheckState.FAIL, "нет ни root, ни Shizuku — без них переключать нечем")
            return save(device, rom, backendLabel, null, emptyMap(), emptyList())
        }
        report("access", "Доступ к системе", CheckState.OK, backendLabel)

        // --- 3. SIMs and the way to switch ---
        report("sims", "SIM-карты", null, "")
        val sims = Sims.active(force = true)
        if (sims.isEmpty()) {
            report("sims", "SIM-карты", CheckState.FAIL, "активных SIM нет — режимы сети проверить нельзя")
        } else {
            report("sims", "SIM-карты", CheckState.OK, sims.joinToString(", ") { it.label })
        }
        var method: WriteMethod? = null
        val modes = linkedMapOf<Int, Map<NetMode, ModeResult>>()
        if (sims.isNotEmpty()) {
            report("method", "Способ переключения режима", null, "")
            val slot = sims.first().slot
            method = findMethod(slot)
            if (method == null) {
                report("method", "Способ переключения режима", CheckState.FAIL,
                    "ни команда, ни системная служба не меняют режим — прошивка или оператор не дают его менять")
            } else {
                report("method", "Способ переключения режима", CheckState.OK, method.label)
                // --- 4, 5. every preset on every SIM, then its own mode back ---
                for (sim in sims) {
                    val original = Telephony.readWith(method, sim.slot)
                    val results = linkedMapOf<NetMode, ModeResult>()
                    for (mode in NetMode.entries) {
                        val key = "mode${sim.slot}_${mode.name}"
                        val title = "SIM ${sim.slot + 1}: ${mode.label}"
                        report(key, title, null, "")
                        val r = checkMode(method, sim.slot, mode)
                        results[mode] = r
                        report(key, title, r.state, r.note)
                    }
                    if (original > 0) Telephony.writeWith(method, sim.slot, original)
                    modes[sim.slot] = results
                }
            }
        }

        // --- 6. everything else ---
        val checks = mutableListOf<CheckItem>()
        for ((key, title, check) in featureChecks(backend, sims.size)) {
            report(key, title, null, "")
            val item = runCatching { check() }.getOrElse { CheckItem(title, CheckState.FAIL, "ошибка: ${it.message}") }
                .copy(title = title)
            checks += item
            report(key, title, item.state, item.note)
        }
        return save(device, rom, backendLabel, method, modes, checks)
    }

    private fun save(
        device: String, rom: String, backend: String, method: WriteMethod?,
        modes: Map<Int, Map<NetMode, ModeResult>>, checks: List<CheckItem>,
    ): DeviceProfile {
        val p = DeviceProfile(System.currentTimeMillis(), device, rom, Build.VERSION.RELEASE, backend, method, modes, checks)
        Profile.save(p)
        Prefs.log("проверка телефона: " + when {
            modes.isEmpty() && method == null && backend != "нет" -> "активных SIM нет, режимы сети не проверены"
            method == null -> "способ переключения не найден"
            else -> "${method.label}, режимов работает: ${modes.values.firstOrNull()?.count { it.value.state == CheckState.OK } ?: 0}"
        })
        return p
    }

    /**
     * Writes a preset other than the current one and reads it back, first through the AOSP
     * command, then through the phone service. The SIM's mode is put back either way.
     */
    private suspend fun findMethod(slot: Int): WriteMethod? {
        val original = Telephony.readWith(WriteMethod.CMD, slot).takeIf { it > 0 }
            ?: Telephony.readWith(WriteMethod.BINDER, slot)
        val probe = if (NetMode.of(original) == NetMode.THREE_G) NetMode.FULL.mask else NetMode.THREE_G.mask
        for (m in WriteMethod.entries) {
            if (!Telephony.writeWith(m, slot, probe)) continue
            delay(SETTLE_MS)
            val back = Telephony.readWith(m, slot).takeIf { it > 0 } ?: Telephony.readWith(WriteMethod.CMD, slot)
            if (original > 0) Telephony.writeWith(m, slot, original)
            if (back > 0 && NetMode.of(back) == NetMode.of(probe)) return m
        }
        return null
    }

    private suspend fun checkMode(method: WriteMethod, slot: Int, mode: NetMode): ModeResult {
        var last = "запись не принята"
        for (mask in Raf.variants(mode)) {
            if (!Telephony.writeWith(method, slot, mask)) { last = "запись не принята"; continue }
            delay(SETTLE_MS)
            val back = Telephony.readWith(method, slot)
            if (back <= 0) { last = "режим не читается"; continue }
            if (NetMode.of(back) != mode) {
                // The modem or the carrier dropped part of it: no other mask of this preset
                // will bring back a generation the radio does not have.
                return ModeResult(CheckState.FAIL, 0, "не поддерживается: радио оставило ${Raf.describe(back)}")
            }
            delay(HOLD_MS)
            val held = Telephony.readWith(method, slot)
            if (NetMode.of(held) == mode) {
                val note = if (mask == mode.mask) "работает" else "работает (своя маска прошивки)"
                return ModeResult(CheckState.OK, mask, note)
            }
            last = "прошивка вернула ${Raf.describe(held)}"
        }
        return ModeResult(CheckState.FAIL, 0, last)
    }

    private data class Feature(val key: String, val title: String, val check: suspend () -> CheckItem)

    private fun ok(note: String) = CheckItem("", CheckState.OK, note)
    private fun warn(note: String) = CheckItem("", CheckState.WARN, note)
    private fun fail(note: String) = CheckItem("", CheckState.FAIL, note)
    private fun skip(note: String) = CheckItem("", CheckState.SKIP, note)

    private fun wifiConnected(): Boolean = runCatching {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    private fun cli(cls: String) = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin com.netmode.switcher.$cls"

    /** Checks that change nothing on the phone. */
    private fun featureChecks(backend: Backend, simCount: Int): List<Feature> {
        val ctx = App.ctx
        val root = backend == Backend.ROOT
        fun granted(p: String) = Compat.granted(ctx, p)
        return listOf(
            Feature("ssid", "Имя сети Wi-Fi (правила по Wi-Fi)") {
                if (!wifiConnected()) skip("Wi-Fi не подключён — проверится при подключении")
                else WifiSsid.current()?.let { ok("видно «$it»") } ?: fail("имя сети не читается")
            },
            Feature("cells", "Вышки связи (место, Wi-Fi рядом с домом)") {
                val c = ConditionWatch.currentCells()
                if (c.isNotEmpty()) ok("видно вышек: ${c.size}") else warn("вышки не видны — нет сети или прошивка их не показывает")
            },
            Feature("data", "Мобильный интернет вкл/выкл") {
                val out = Privileged.exec("svc data help 2>&1 | head -3; echo; cmd phone help 2>&1 | grep -c 'data enable'")
                if (out.isNotBlank()) ok("работает") else fail("команды нет")
            },
            Feature("airplane", "Режим полёта") {
                val out = Privileged.exec("cmd connectivity airplane-mode 2>&1").trim()
                when {
                    out == "enabled" || out == "disabled" -> ok("работает")
                    // Android 10: through the setting and the system broadcast, root only.
                    Compat.SDK < 30 && Privileged.choose() == Backend.ROOT -> ok("через системную настройку")
                    else -> fail(out.take(60))
                }
            },
            Feature("powersave", "Энергосбережение") {
                if (Privileged.exec("cmd power help 2>&1 | grep -c set-mode").trim().toIntOrNull() ?: 0 > 0) ok("работает")
                else warn("команды нет — будет через системную настройку")
            },
            Feature("location", "Геолокация вкл/выкл") {
                val out = Privileged.exec("cmd location is-location-enabled 2>&1").trim()
                if (out == "true" || out == "false") ok("работает") else warn("через системную настройку")
            },
            Feature("datasim", "SIM для мобильного интернета") {
                if (simCount < 2) skip("одна SIM — нечего переключать")
                else if (Privileged.exec("service check isub").contains("found")) ok("работает") else fail("служба подписок недоступна")
            },
            Feature("stop", "Остановка приложений") { ok("работает") },
            Feature("restrict", "Запрет работы в фоне") {
                if (root) ok("работает") else warn("через Shizuku слабее: только «ограниченная» группа ожидания")
            },
            Feature("suspend", "Заморозка приложений") {
                val sig = Privileged.exec("${cli("SuspendCli")} sig 2>&1")
                if (sig.contains("String")) ok("работает, с кнопкой «вернуть сейчас»") else warn("без кнопки — через pm suspend")
            },
            Feature("tiles", "Плитки других приложений") {
                Privileged.exec("settings get secure sysui_qs_tiles").takeIf { it.isNotBlank() && !it.contains("null") }
                    ?.let { ok("работает") } ?: warn("список плиток шторки не читается")
            },
            Feature("vpn", "VPN вкл/выкл") {
                if (!root) warn("нужен root")
                else if (Privileged.exec("${cli("VpnCli")} sig 2>&1").contains("AlwaysOn")) ok("работает") else fail("служба VPN недоступна")
            },
            Feature("ap", "Точка доступа: настройки") {
                if (HotspotConfig.read() != null) ok("работает") else fail("настройки не читаются")
            },
            Feature("tether", "Раздача через VPN / Proxy") {
                if (!root) warn("нужен root")
                else if (Privileged.exec("iptables -S FORWARD >/dev/null 2>&1 && ip rule >/dev/null 2>&1 && echo ok").contains("ok")) ok("работает")
                else fail("нет iptables или ip rule")
            },
            Feature("alarms", "Точные будильники (правила по времени)") {
                if (Compat.canExact(ctx.getSystemService(AlarmManager::class.java))) ok("разрешены")
                else fail("не разрешены — правила по времени будут опаздывать")
            },
            Feature("battery", "Работа в фоне") {
                if (ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) ok("без ограничений")
                else warn("ограничена оптимизацией батареи — события могут теряться")
            },
            Feature("bt", "Bluetooth-устройства") {
                if (granted(Manifest.permission.BLUETOOTH_CONNECT)) ok("разрешено") else warn("нужно разрешение «Устройства поблизости»")
            },
            Feature("usage", "Открытое приложение (условие «Приложение»)") {
                Privileged.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
                if (PermissionsActivity.usageGranted(ctx)) ok("работает") else warn("доступ к статистике не выдаётся")
            },
            Feature("notify", "Уведомление с режимами SIM") {
                if (granted(Manifest.permission.POST_NOTIFICATIONS)) ok("разрешено") else skip("уведомления выключены — нужно только для него")
            },
        )
    }
}
