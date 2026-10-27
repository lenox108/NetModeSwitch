package com.netmode.switcher

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.netmode.switcher.databinding.ActivityHotspotBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The live page observes only while visible, with one bounded refresh in flight. */
internal class HotspotPanel(private val activity: AppCompatActivity, private val ui: ActivityHotspotBinding) {
    private val busy = Mutex()
    private val traffic = HotspotTelemetry()
    private val names = activity.getSharedPreferences("hotspot_client_names", 0)
    private val reportFile get() = File(activity.filesDir, "hotspot-diagnostic.txt")
    private var exporting: String? = null
    private var peerSignature = ""
    private var capturing = false
    private val export = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = exporting ?: reportFile.takeIf(File::exists)?.readText()
        exporting = null
        if (uri != null && report != null) activity.lifecycleScope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                    ?: error("Файл недоступен")
            } }
            message(if (ok.isSuccess) "Отчёт сохранён" else "Не удалось сохранить отчёт")
        }
    }

    fun start() {
        activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
            .add("Последний отчёт").setOnMenuItemClickListener {
                if (reportFile.exists()) showReport(reportFile.readText()) else message("Сохранённых отчётов пока нет")
                true
            }
        ui.refreshLive.setOnClickListener { refresh() }
        ui.captureReport.setOnClickListener { capture() }
        ui.captureReport.setOnLongClickListener {
            if (reportFile.exists()) showReport(reportFile.readText()) else message("Сохранённых отчётов пока нет")
            true
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) { update(); delay(8_000) }
                } finally { traffic.sample(SystemClock.elapsedRealtime(), null) }
            }
        }
    }

    fun refresh() {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activity.lifecycleScope.launch { update() }
    }

    private suspend fun update() {
        if (!busy.tryLock()) return
        ui.refreshLive.isEnabled = false
        try {
            val snapshot = HotspotMonitor.snapshot()
            render(snapshot)
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            traffic.sample(SystemClock.elapsedRealtime(), null)
            ui.status.text = "Состояние недоступно — проверь root / Shizuku"
            ui.liveTraffic.text = "Нет данных о трафике"
            ui.liveDetails.text = "Обновление раз в 8 секунд, пока экран открыт"
            ui.peersStatus.text = "Список устройств недоступен"
            ui.peerList.removeAllViews()
            peerSignature = ""
        } finally {
            ui.refreshLive.isEnabled = true
            busy.unlock()
        }
    }

    internal fun render(snapshot: HotspotSnapshot) {
        snapshot.enabled?.let { on -> HotspotScreen.quick(ui, if (on) Prefs.tetherMode else null) }
        ui.status.text = when (snapshot.enabled) {
            true -> "Точка доступа включена\n" + snapshot.radios.joinToString("\n").ifBlank { "Диапазон пока недоступен" }
            false -> "Точка доступа выключена"
            null -> "Точка запускается или состояние недоступно"
        }
        val now = SystemClock.elapsedRealtime()
        val sample = traffic.sample(now, snapshot.counters)
        ui.liveTraffic.text = if (sample == null) "Нет данных о трафике" else
            "↓\u00a0${rate(sample.downRate)}     ↑\u00a0${rate(sample.upRate)}"
        ui.liveDetails.text = buildList {
            add("Выбрано: ${TetherRoute.label(Prefs.tetherMode)}")
            if (sample != null) {
                add("Получено ≈ ${bytes(sample.download)} · отправлено ≈ ${bytes(sample.upload)}")
                val seconds = ((now - sample.since) / 1000).coerceAtLeast(0)
                add("Наблюдение: ${seconds / 60} мин ${seconds % 60} с")
            }
            val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (temp != null && temp != Int.MIN_VALUE) add("Батарея: ${String.format(Locale.getDefault(), "%.1f", temp / 10.0)} °C")
            val thermal = runCatching { activity.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull()
            if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_MODERATE) add("Система сообщает о нагреве и ограничении производительности")
        }.joinToString("\n")
        renderPeers(snapshot)
    }

    private fun renderPeers(snapshot: HotspotSnapshot) {
        ui.peersStatus.text = when {
            snapshot.enabled == false -> "Устройства появятся после включения точки"
            snapshot.clients == null -> "Прошивка не предоставила список клиентов"
            snapshot.clientCount == 0 -> "Подключённых устройств нет"
            snapshot.clients.isEmpty() -> "Подключено: ${snapshot.clientCount}. Адреса скрыты системой"
            else -> "Подключено: ${snapshot.clientCount}. Нажми на устройство, чтобы задать имя"
        }
        val peers = if (snapshot.enabled == false) emptyList() else snapshot.clients.orEmpty()
        val signature = peers.joinToString { "${it.mac}:${it.addresses}:${names.getString(it.mac, "")}" }
        if (signature != peerSignature) {
            peerSignature = signature
            ui.peerList.removeAllViews()
            peers.forEach { peer ->
                val name = names.getString(peer.mac, "").orEmpty().ifBlank { "Устройство ${peer.mac.takeLast(5)}" }
                val row = MaterialButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "$name\n${peer.addresses.joinToString().ifBlank { "IP пока неизвестен" }}"
                    contentDescription = "$text, MAC ${peer.mac}. Изменить имя"
                    textSize = 14f
                    isAllCaps = false
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    setOnClickListener { rename(peer, snapshot) }
                }
                ui.peerList.addView(row)
            }
        }
    }

    private fun rename(peer: ApPeer, snapshot: HotspotSnapshot) {
        val edit = TextInputEditText(activity).apply {
            setText(names.getString(peer.mac, "")); hint = "Например, Мой Mac"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(48))
        }
        MaterialAlertDialogBuilder(activity).setTitle("Имя устройства").setView(edit)
            .setMessage("MAC: ${peer.mac}\n${peer.addresses.joinToString().ifBlank { "IP пока неизвестен" }}\n\nИмя хранится на телефоне. При смене случайного MAC адреса устройство появится как новое.")
            .setPositiveButton(R.string.save) { _, _ ->
                names.edit().putString(peer.mac, edit.text.toString().trim()).apply()
                peerSignature = ""
                // Do not turn the stale snapshot into a new traffic measurement.
                renderPeers(snapshot)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun capture() {
        if (capturing) return
        capturing = true
        ui.captureReport.isEnabled = false
        ui.captureReport.text = "Собираю…"
        activity.lifecycleScope.launch {
            try {
                val report = HotspotMonitor.report()
                withContext(Dispatchers.IO) { reportFile.writeText(report) }
                showReport(report)
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { message("Не удалось собрать отчёт. Попробуй ещё раз")
            } finally {
                capturing = false
                ui.captureReport.isEnabled = true
                ui.captureReport.setText(R.string.ap_diagnostics_short)
            }
        }
    }

    private fun showReport(report: String) {
        MaterialAlertDialogBuilder(activity).setTitle("Диагностика раздачи")
            .setMessage(report)
            .setPositiveButton("Сохранить файл") { _, _ ->
                exporting = report
                export.launch("NetModeSwitch-hotspot-${System.currentTimeMillis()}.txt")
            }
            .setNeutralButton("Поделиться") { _, _ ->
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, report), "Диагностика раздачи"))
            }
            .setNegativeButton(android.R.string.ok, null).show()
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun rate(value: Long?) = value?.let { "≈\u00a0${bytes(it).replace(' ', '\u00a0')}/\u2060с" } ?: "…"
    private fun bytes(value: Long): String = when {
        value >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f ГиБ", value / (1024.0 * 1024 * 1024))
        value >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f МиБ", value / (1024.0 * 1024))
        value >= 1024 -> String.format(Locale.getDefault(), "%.1f КиБ", value / 1024.0)
        else -> "$value Б"
    }
}
