package com.netmode.switcher

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.netmode.switcher.databinding.ActivityHotspotBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Wi-Fi hotspot's own settings: name, password, protection, band, channel and the rest. */
class HotspotActivity : AppCompatActivity() {

    private lateinit var ui: ActivityHotspotBinding
    private var loaded: ApSettings? = null
    private var caps = ApCapability(32, emptyMap())

    // The option lists get the saved value added when it is not among them: otherwise saving
    // only a new name would silently reset, say, a limit of 7 clients or an unusual channel.
    private var secValues = listOf("wpa2", "wpa3t", "wpa3", "open")
    private var clientValues = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 32)
    private var timeoutValues = listOf(0L, 1, 3, 5, 10, 15, 30, 60)
    private var savedChannel = 0
    private lateinit var bands: HotspotBandButtons
    private var bandEdited = false
    private var saveBusy = false
    private lateinit var panel: HotspotPanel
    private lateinit var screen: HotspotScreen
    private lateinit var presets: HotspotPresetsUi
    private val countryScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lifecycleScope.launch {
            val selectedChannel = channels().getOrNull(ui.channel.selectedItemPosition) ?: 0
            caps = runCatching { HotspotConfig.capability() }.getOrDefault(caps)
            if (HotspotBands.known(caps)) Prefs.hotspotAvailableBands = HotspotBands.resolve(caps, Compat.SDK, Prefs.hotspotAvailableBands)
            presets.bindAvailable(Prefs.hotspotAvailableBands)
            bands.bind(band(), Prefs.hotspotAvailableBands)
            fillChannels(selectedChannel)
            showBandHint()
            paintTweakControls(Privileged.choose() == Backend.ROOT)
            panel.refresh()
        }
    }
    private val routeIds = mapOf(TetherMode.DIRECT to R.id.routeDirect, TetherMode.VPN to R.id.routeVpn, TetherMode.PROXY to R.id.routeProxy)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityHotspotBinding.inflate(layoutInflater)
        setContentView(ui.root)
        screen = HotspotScreen(ui)
        screen.restore(savedInstanceState)
        presets = HotspotPresetsUi(this, ui) { preset ->
            performHotspotAction(reloadRadio = true) { HotspotControl.startPreset(preset) }
        }
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.wifiCountry.setOnClickListener {
            countryScreen.launch(android.content.Intent(this, WifiCountryActivity::class.java))
        }
        ui.save.setOnClickListener { save() }
        bands = HotspotBandButtons(ui)
        bands.bind("keep", Prefs.hotspotAvailableBands)
        bands.setEnabled(false)
        bands.onChange = { bandEdited = true; fillChannels(0); showBandHint() }
        panel = HotspotPanel(this, ui)
        panel.start()
        ui.autoOff.setOnCheckedChangeListener { _, on -> ui.timeoutRow.visibility = if (on) View.VISIBLE else View.GONE }
        ui.security.onChange = { ui.passLayout.visibility = if (secValues[it] == "open") View.GONE else View.VISIBLE }
        ui.save.isEnabled = false
        setupTether()
        setupTweaks()
        ui.startProxy.setOnClickListener { quick(TetherMode.PROXY) }
        ui.startVpn.setOnClickListener { quick(TetherMode.VPN) }
        ui.stopHotspot.setOnClickListener { performHotspotAction { HotspotControl.stop() } }
        ui.toolbar.menu.add(R.string.ap_add_tiles).setOnMenuItemClickListener { addTiles(); true }
        paintQuick()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        screen.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        lifecycleScope.launch {
            val s = runCatching { HotspotConfig.read() }.getOrNull()
            caps = runCatching { HotspotConfig.capability() }.getOrDefault(caps)
            if (HotspotBands.known(caps)) Prefs.hotspotAvailableBands = HotspotBands.resolve(caps, Compat.SDK, Prefs.hotspotAvailableBands)
            presets.bindAvailable(Prefs.hotspotAvailableBands)
            if (s == null) {
                // Before Android 11 the hotspot settings are another API (WifiConfiguration),
                // not supported here: they stay in the system settings, the routes still work.
                ui.savedSummary.setText(if (Compat.SDK < 30) R.string.ap_legacy else R.string.ap_read_failed)
                bands.setEnabled(false)
                ui.channel.isEnabled = false
                return@launch
            }
            loaded = s
            ui.ssid.setText(s.ssid)
            ui.pass.setText(s.pass)
            val secLabels = mutableListOf(getString(R.string.ap_sec_wpa2), getString(R.string.ap_sec_wpa3t),
                getString(R.string.ap_sec_wpa3), getString(R.string.ap_sec_open))
            if (s.sec !in secValues) { secValues = secValues + s.sec; secLabels += s.sec.uppercase() }
            ui.security.setItems(secLabels, secValues.indexOf(s.sec))
            ui.passLayout.visibility = if (s.sec == "open") View.GONE else View.VISIBLE
            bands.bind(ApRadio.key(s.radio), Prefs.hotspotAvailableBands)
            showBandHint()
            savedChannel = s.channel
            fillChannels(s.channel)
            clientValues = (clientValues.filter { it <= caps.max } + s.clients).distinct().sorted()
            ui.clients.setItems(clientValues.map { if (it == 0) getString(R.string.ap_no_limit) else it.toString() },
                clientValues.indexOf(s.clients).coerceAtLeast(0))
            ui.autoOff.isChecked = s.autoOff
            ui.timeoutRow.visibility = if (s.autoOff) View.VISIBLE else View.GONE
            timeoutValues = (timeoutValues + s.timeout).distinct().sorted()
            ui.timeout.setItems(timeoutValues.map { if (it == 0L) getString(R.string.ap_default) else getString(R.string.ap_minutes, it) },
                timeoutValues.indexOf(s.timeout).coerceAtLeast(0))
            ui.hidden.isChecked = s.hidden
            ui.ax.isChecked = s.ax
            ui.randomMac.isChecked = s.randomMac
            ui.save.isEnabled = true
            paintTweakControls(Privileged.choose() == Backend.ROOT)
            showStatus()
        }
    }

    private fun showStatus() {
        loaded?.let { ui.savedSummary.text = "${it.ssid} · ${it.sec.uppercase()}" }
        panel.refresh()
    }

    private fun band(): String = bands.selectedKey

    private fun showBandHint() {
        ui.bandHint.text = buildList {
            when (band()) {
                "auto25" -> add("Авто: Android выберет один диапазон — 2,4 или 5 ГГц.")
                "dual25" -> add("2,4 и 5 ГГц одновременно. Android может отключать незанятый диапазон для экономии энергии.")
                "keep" -> add("Сохранится текущая системная конфигурация диапазонов и каналов.")
                else -> if (caps.channels[band()].orEmpty().isEmpty())
                    add("Список каналов ${ApRadio.label(band())} недоступен. Оставь канал «Авто»; возможность применения проверит Android при сохранении.")
            }
        }.joinToString("\n")
        ui.bandHint.visibility = if (ui.bandHint.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setRouteEnabled(enabled: Boolean) {
        listOf(ui.routeDirect, ui.routeVpn, ui.routeProxy).forEach { it.isEnabled = enabled }
    }

    private fun selectedRoute(): TetherMode = routeIds.entries.firstOrNull { it.value == ui.routeGroup.checkedButtonId }?.key ?: Prefs.tetherMode

    private fun channels(): List<Int> {
        val list = listOf(0) + caps.channels[band()].orEmpty()
        // The saved channel stays pickable on its own band even if the list lacks it.
        val own = savedChannel.takeIf { it != 0 && loaded?.let { s -> ApRadio.key(s.radio) } == band() && it !in list }
        return if (own != null) list + own else list
    }

    private fun fillChannels(selected: Int) {
        val list = channels()
        ui.channel.isEnabled = band() in listOf("2", "5", "6")
        ui.channel.setItems(list.map { if (it == 0) getString(R.string.ap_auto) else it.toString() },
            list.indexOf(selected).coerceAtLeast(0))
    }

    private fun save() {
        if (saveBusy || tetherBusy) return
        val base = loaded ?: return
        val sec = secValues[ui.security.selectedItemPosition]
        val ssid = ui.ssid.text.toString().trim()
        val pass = ui.pass.text.toString()
        when {
            ssid.isEmpty() -> return toast(R.string.ap_ssid_required)
            sec != "open" && pass.length < 8 -> return toast(R.string.ap_pass_short)
        }
        val s = base.copy(
            ssid = ssid,
            pass = if (sec == "open") "" else pass,
            sec = sec,
            band = band(),
            channel = channels()[ui.channel.selectedItemPosition],
            radio = if (!bandEdited && channels()[ui.channel.selectedItemPosition] == base.channel) base.radio
                else ApRadio.value(band(), channels()[ui.channel.selectedItemPosition], base.radio),
            hidden = ui.hidden.isChecked,
            clients = clientValues[ui.clients.selectedItemPosition],
            autoOff = ui.autoOff.isChecked,
            timeout = timeoutValues[ui.timeout.selectedItemPosition],
            ax = ui.ax.isChecked,
            randomMac = ui.randomMac.isChecked,
        )
        saveBusy = true
        paintTweakControls(false)
        lifecycleScope.launch {
            val error = runCatching { HotspotControl.saveSettings(s, base) }.getOrElse { it.message ?: it.javaClass.simpleName }
            saveBusy = false
            if (error == null) {
                loaded = runCatching { HotspotConfig.read() }.getOrNull() ?: s
                bandEdited = false
                savedChannel = loaded!!.channel
                Prefs.log("точка доступа: сохранено — ${HotspotConfig.summary(loaded!!)}")
                toast(R.string.ap_saved)
                showStatus()
            } else {
                Toast.makeText(this@HotspotActivity, getString(R.string.ap_save_failed, error), Toast.LENGTH_LONG).show()
            }
            paintTweakControls(Privileged.choose() == Backend.ROOT)
        }
    }

    private fun paintQuick() {
        lifecycleScope.launch {
            // The settings screen must remain usable while root / Shizuku is unavailable.
            runCatching { HotspotControl.isOn() }
            val proxyOn = HotspotControl.activeIn(TetherMode.PROXY)
            val vpnOn = HotspotControl.activeIn(TetherMode.VPN)
            HotspotScreen.quick(ui, when { vpnOn -> TetherMode.VPN; proxyOn -> TetherMode.PROXY
                HotspotControl.lastOn -> TetherMode.DIRECT; else -> null })
            // Both need root: without it they would only report that it is missing.
            val root = Privileged.choose() == Backend.ROOT
            ui.startProxy.isEnabled = root
            ui.startVpn.isEnabled = root
            // Keep the switches below in step with what the buttons just did.
            programmatic = true
            setRouteEnabled(true)
            ui.routeGroup.check(routeIds.getValue(Prefs.tetherMode))
            ui.proxyRow.visibility = if (Prefs.tetherMode == TetherMode.PROXY) View.VISIBLE else View.GONE
            programmatic = false
            paintTweakControls(root)
        }
    }

    private fun quick(mode: TetherMode) = performHotspotAction { HotspotControl.toggle(mode) }

    private fun performHotspotAction(reloadRadio: Boolean = false, action: suspend () -> String) {
        if (tetherBusy || tweaksBusy || saveBusy) return
        tetherBusy = true
        paintTweakControls(false)
        ui.presetStatus.visibility = View.VISIBLE
        ui.presetStatus.setText(R.string.working)
        // A frequency switch must finish even if the user leaves this page mid-restart.
        App.scope.launch {
            val line = runCatching { action() }.getOrElse { "Ошибка: ${it.message}" }
            val current = if (reloadRadio) runCatching { HotspotConfig.read() }.getOrNull() else null
            withContext(Dispatchers.Main) {
                tetherBusy = false
                if (!isDestroyed) {
                    ui.tetherStatus.text = line
                    ui.presetStatus.text = line
                    if (current != null) {
                        // Keep unsaved identity/advanced edits; only the launched band is replaced.
                        loaded = current
                        bandEdited = false
                        savedChannel = current.channel
                        bands.bind(ApRadio.key(current.radio), Prefs.hotspotAvailableBands)
                        fillChannels(current.channel)
                        showBandHint()
                    }
                    paintQuick()
                    showStatus()
                }
            }
        }
    }

    /** One request at a time; a second one while the first dialog is up is refused unseen. */
    private fun addTiles(remaining: List<Pair<Class<*>, Int>> = listOf(
        HotspotProxyTileService::class.java to R.string.ap_tile_proxy,
        HotspotVpnTileService::class.java to R.string.ap_tile_vpn,
    )) {
        if (android.os.Build.VERSION.SDK_INT < 33 || !Compat.canRequestTile) return Compat.tilesByHand(this)
        val (cls, label) = remaining.firstOrNull() ?: return
        getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            android.content.ComponentName(this, cls), getString(label),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_hotspot_tile), mainExecutor,
        ) { result ->
            if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(this, getString(R.string.tile_already, getString(label)), Toast.LENGTH_SHORT).show()
            }
            addTiles(remaining.drop(1))
        }
    }

    /** Proxy choices: the saved one plus whatever listens on 127.0.0.1 right now. */
    private var proxies: List<String> = emptyList()

    /** One explicit route choice, applied immediately. */
    private fun setupTether() {
        ui.checkTether.setOnClickListener {
            ui.checkTether.isEnabled = false
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { TetherRoute.diagnostics() }.getOrElse { "Проверка не завершилась: ${it.message}" }
                }
                if (!isDestroyed) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@HotspotActivity)
                        .setTitle(R.string.tether_check).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
                    paintTweakControls(Privileged.choose() == Backend.ROOT)
                }
            }
        }
        val mode = Prefs.tetherMode
        ui.routeGroup.check(routeIds.getValue(mode))
        ui.proxyRow.visibility = if (mode == TetherMode.PROXY) View.VISIBLE else View.GONE
        proxies = listOf(Prefs.tetherProxy)
        fillProxies()
        ui.routeGroup.addOnButtonCheckedListener { _, _, checked ->
            if (checked && !programmatic) {
                ui.proxyRow.visibility = if (selectedRoute() == TetherMode.PROXY) View.VISIBLE else View.GONE
                setTether()
            }
        }
        ui.proxyAddr.onChange = { i ->
            if (i == proxies.size) manualProxy()
            else {
                Prefs.tetherProxy = proxies[i].substringBefore(' ')
                // A detected local proxy needs no login.
                Prefs.tetherProxyUser = ""
                Prefs.tetherProxyPass = ""
                setTether()
            }
        }
        lifecycleScope.launch {
            val found = runCatching { TetherRoute.localProxies() }.getOrDefault(emptyList())
                .map { (port, proc) -> "127.0.0.1:$port" + if (proc.isNotEmpty()) " ($proc)" else "" }
            proxies = (listOf(Prefs.tetherProxy) + found).distinctBy { it.substringBefore(' ') }
            fillProxies()
            showTether(null)
        }
    }

    /** The detected and saved proxies, then "enter manually" as the last item. */
    private fun fillProxies() {
        val current = Prefs.tetherProxy + if (Prefs.tetherProxyUser.isNotEmpty()) " (с логином)" else ""
        val labels = listOf(current) + proxies.drop(1) + getString(R.string.proxy_manual)
        ui.proxyAddr.setItems(labels, 0)
    }

    private fun manualProxy() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fun field(hint: Int, value: String, type: Int): com.google.android.material.textfield.TextInputEditText {
            val layout = com.google.android.material.textfield.TextInputLayout(this).apply { setHint(hint) }
            val edit = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
                setText(value); inputType = type; maxLines = 1
            }
            layout.addView(edit)
            box.addView(layout)
            return edit
        }
        val cur = Prefs.tetherProxy
        val host = field(R.string.proxy_host, cur.substringBeforeLast(':'),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(R.string.proxy_port, cur.substringAfterLast(':', ""), android.text.InputType.TYPE_CLASS_NUMBER)
        val user = field(R.string.proxy_user, Prefs.tetherProxyUser, android.text.InputType.TYPE_CLASS_TEXT)
        val pass = field(R.string.proxy_pass, Prefs.tetherProxyPass,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_manual_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                if (h.isEmpty() || p == null || p !in 1..65535) {
                    toast(R.string.proxy_invalid)
                    fillProxies()
                    return@setPositiveButton
                }
                Prefs.tetherProxy = "$h:$p"
                Prefs.tetherProxyUser = user.text.toString().trim()
                Prefs.tetherProxyPass = pass.text.toString()
                proxies = listOf(Prefs.tetherProxy) + proxies.drop(1).filter { it.substringBefore(' ') != Prefs.tetherProxy }
                fillProxies()
                setTether()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> fillProxies() }
            .show()
    }

    /** Set while the switches are changed from code, so that is not taken for a tap. */
    private var programmatic = false
    private var tweaksBusy = false
    private var tetherBusy = false

    private var ttlValues = emptyList<Int>()

    private fun setupTweaks() {
        ui.fixTtl.isChecked = Prefs.tetherTtlEnabled
        fillTtlValues()
        ui.fixTtl.setOnCheckedChangeListener { _, on ->
            if (!programmatic) {
                Prefs.tetherTtlEnabled = on
                applyTweaks()
            }
        }
        ui.ttlValue.onChange = { index ->
            if (index == ttlValues.size) manualTtl()
            else {
                Prefs.tetherTtlValue = ttlValues[index]
                applyTweaks()
            }
        }
        ui.checkTweaks.setOnClickListener { applyTweaks(inspect = true) }
        // Also refreshes a screen opened after a network callback or a background restoration.
        applyTweaks()
    }

    private fun fillTtlValues() {
        ttlValues = (listOf(0, 64, 128, 255) + Prefs.tetherTtlValue).distinct().sorted()
        ui.ttlValue.setItems(ttlValues.map {
            if (it == 0) getString(R.string.tether_ttl_auto) else it.toString()
        } + getString(R.string.tether_ttl_manual), ttlValues.indexOf(Prefs.tetherTtlValue))
    }

    private fun manualTtl() {
        fillTtlValues()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            setHint(R.string.tether_ttl_value)
        }
        val edit = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            if (Prefs.tetherTtlValue != 0) setText(Prefs.tetherTtlValue.toString())
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
        }
        layout.addView(edit)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tether_ttl_value)
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = edit.text.toString().trim().toIntOrNull()
                if (value == null || value !in 1..255) {
                    layout.error = getString(R.string.tether_ttl_invalid)
                } else {
                    Prefs.tetherTtlValue = value
                    fillTtlValues()
                    dialog.dismiss()
                    applyTweaks()
                }
            }
        }
        dialog.show()
    }

    private fun paintTweakControls(root: Boolean) {
        val ready = root && !tweaksBusy && !tetherBusy && !saveBusy
        ui.fixTtl.isEnabled = ready
        ui.ttlValue.isEnabled = ready && Prefs.tetherTtlEnabled
        ui.checkTweaks.isEnabled = ready
        ui.checkTether.isEnabled = ready
        ui.ttlRow.visibility = if (Prefs.tetherTtlEnabled) View.VISIBLE else View.GONE
        ui.tweaksOffloadHint.visibility = if (Prefs.tetherTtlEnabled) View.VISIBLE else View.GONE
        setRouteEnabled(ready)
        ui.startVpn.isEnabled = ready
        ui.startProxy.isEnabled = ready
        ui.stopHotspot.isEnabled = ready
        presets.setEnabled(ready && loaded != null && Compat.SDK >= 30)
        ui.save.isEnabled = loaded != null && !tetherBusy && !saveBusy
        bands.setEnabled(loaded != null && !tetherBusy && !saveBusy)
        ui.channel.isEnabled = loaded != null && !tetherBusy && !saveBusy && band() in listOf("2", "5", "6")
    }

    /** The operation survives activity destruction, so turning a switch off always restores. */
    private fun applyTweaks(inspect: Boolean = false) {
        if (tweaksBusy) return
        tweaksBusy = true
        paintTweakControls(false)
        ui.tweaksStatus.setText(R.string.working)
        App.scope.launch {
            val line = runCatching {
                WifiMonitor.syncAll()
                val applied = NetworkTweaks.apply()
                TetherWatch.sync()
                if (inspect) NetworkTweaks.inspect() else applied
            }.getOrElse { "TTL / IPv6: ошибка ${it.message}" }
            if (line.isNotBlank() && line != lastTweaksLine) Prefs.log(line)
            lastTweaksLine = line
            // Re-evaluate foreground-service need after a successful restoration.
            WifiMonitor.register()
            val root = Privileged.choose() == Backend.ROOT
            withContext(Dispatchers.Main) {
                tweaksBusy = false
                if (!isDestroyed) {
                    ui.tweaksStatus.text = line.ifBlank { getString(R.string.tether_tweaks_off) }
                    paintTweakControls(root)
                }
            }
        }
    }

    private var lastTweaksLine = ""

    private fun setTether() {
        if (programmatic) return
        Prefs.tetherMode = selectedRoute()
        setRouteEnabled(false)
        tetherBusy = true
        paintTweakControls(false)
        ui.tetherStatus.setText(R.string.working)
        lifecycleScope.launch {
            TetherWatch.sync()
            val line = runCatching { TetherRoute.apply() }.getOrElse { "раздача: ошибка ${it.message}" }
            tetherBusy = false
            Prefs.log(line)
            showTether(line)
            paintQuick()
            panel.refresh()
        }
    }

    private fun showTether(line: String?) {
        lifecycleScope.launch {
            val root = Privileged.choose() == Backend.ROOT
            setRouteEnabled(root)
            paintTweakControls(root)
            if (!tweaksBusy) ui.tweaksStatus.text = NetworkTweaks.status.ifBlank { getString(R.string.tether_tweaks_off) }
            ui.tetherStatus.text = when {
                !root -> getString(R.string.tether_need_root)
                line != null -> line
                else -> "Выбран маршрут: " + TetherRoute.label(Prefs.tetherMode) + ". Проверка ниже показывает его состояние."
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
