package com.netmode.switcher

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.netmode.switcher.databinding.ActivityHotspotBinding

/** Debug-only visual fixture. No shell, network or routing commands. */
class HotspotLab : AppCompatActivity() {
    private lateinit var screen: HotspotScreen
    private lateinit var ui: ActivityHotspotBinding
    private var active: TetherMode? = TetherMode.VPN
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityHotspotBinding.inflate(layoutInflater)
        setContentView(ui.root)
        screen = HotspotScreen(ui)
        screen.restore(savedInstanceState)
        ui.toolbar.setNavigationOnClickListener { finish() }
        val panel = HotspotPanel(this, ui)
        panel.render(HotspotSnapshot(true, listOf("2,4 ГГц · канал 6 · 20 МГц", "5 ГГц · канал 149 · 80 МГц"),
            listOf(ApPeer("aa:bb:cc:dd:ee:01", listOf("192.168.43.10")), ApPeer("aa:bb:cc:dd:ee:02", emptyList())),
            2, listOf("ap_br0"), ApCounters("ap_br0:9", 1000, 2000), null))
        ui.liveTraffic.text = "↓\u00a0≈\u00a04,1\u00a0МиБ/\u2060с     ↑\u00a0≈\u00a0128\u00a0КиБ/\u2060с"
        ui.liveDetails.text = "Выбрано: VPN\nПолучено ≈ 254 МиБ · отправлено ≈ 12 МиБ\nНаблюдение: 8 мин 24 с\nБатарея: 36,5 °C"
        ui.startVpn.isEnabled = true
        ui.startProxy.isEnabled = true
        if (savedInstanceState != null) active = savedInstanceState.getString("lab_active")?.let { TetherMode.valueOf(it) }
        val presets = HotspotPresetsUi(this, ui) { preset ->
            active = preset.mode
            HotspotScreen.quick(ui, active)
            ui.presetStatus.visibility = View.VISIBLE
            ui.presetStatus.text = "Пример запуска: ${preset.title}"
        }
        presets.setEnabled(true)
        val available = if (intent.getBooleanExtra("limited_bands", false)) setOf("2", "5", "auto25")
            else setOf("2", "5", "6", "auto25", "dual25")
        presets.bindAvailable(available)
        HotspotScreen.quick(ui, active)
        ui.stopHotspot.setOnClickListener { active = null; HotspotScreen.quick(ui, active) }
        ui.startVpn.setOnClickListener {
            active = if (active == TetherMode.VPN) null else TetherMode.VPN
            HotspotScreen.quick(ui, active)
        }
        ui.startProxy.setOnClickListener {
            active = if (active == TetherMode.PROXY) null else TetherMode.PROXY
            HotspotScreen.quick(ui, active)
        }
        ui.ssid.setText("NetModeSwitch QA")
        ui.pass.setText("test-password")
        ui.security.setItems(listOf("WPA2", "WPA3"), 0)
        val bands = HotspotBandButtons(ui)
        bands.bind(if ("dual25" in available) "dual25" else "2", available)
        bands.onChange = {
            ui.bandHint.text = ApRadio.label(bands.selectedKey)
            ui.channel.isEnabled = bands.selectedKey in listOf("2", "5", "6")
        }
        ui.bandHint.text = if ("dual25" in available) "Телефон поддерживает два диапазона одновременно. Android может отключать незанятый диапазон для экономии энергии." else ""
        ui.bandHint.visibility = if (ui.bandHint.text.isBlank()) View.GONE else View.VISIBLE
        ui.savedSummary.text = "NetModeSwitch QA · WPA2"
        ui.channel.setItems(listOf("Авто"), 0)
        ui.channel.isEnabled = false
        ui.clients.setItems(listOf("Без ограничения", "2", "5"), 0)
        ui.timeout.setItems(listOf("10 мин"), 0)
        ui.timeoutRow.visibility = View.GONE
        ui.routeGroup.check(R.id.routeVpn)
        ui.routeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) ui.proxyRow.visibility = if (id == R.id.routeProxy) View.VISIBLE else View.GONE
        }
        ui.proxyAddr.setItems(listOf("127.0.0.1:10808"), 0)
        ui.proxyRow.visibility = View.GONE
        ui.ttlRow.visibility = View.GONE
        ui.tweaksOffloadHint.visibility = View.GONE
        ui.save.isEnabled = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("lab_active", active?.name)
        screen.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        HotspotScreen.quick(ui, active)
    }
}
