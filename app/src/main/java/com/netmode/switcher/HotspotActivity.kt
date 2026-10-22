package com.netmode.switcher

import android.os.Bundle
import android.view.View
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityHotspotBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.save.setOnClickListener { save() }
        ui.band.addOnButtonCheckedListener { _, _, checked -> if (checked) fillChannels(0) }
        ui.autoOff.setOnCheckedChangeListener { _, on -> ui.timeoutRow.visibility = if (on) View.VISIBLE else View.GONE }
        ui.security.onChange = { ui.passLayout.visibility = if (secValues[it] == "open") View.GONE else View.VISIBLE }
        ui.save.isEnabled = false
        setupTether()
        setupTweaks()
        ui.startProxy.setOnClickListener { quick(TetherMode.PROXY) }
        ui.startVpn.setOnClickListener { quick(TetherMode.VPN) }
        ui.addApTiles.setOnClickListener { addTiles() }
        paintQuick()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val s = runCatching { HotspotConfig.read() }.getOrNull()
            caps = runCatching { HotspotConfig.capability() }.getOrDefault(caps)
            if (s == null) {
                // Before Android 11 the hotspot settings are another API (WifiConfiguration),
                // not supported here: they stay in the system settings, the routes still work.
                ui.status.setText(if (Compat.SDK < 30) R.string.ap_legacy else R.string.ap_read_failed)
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
            ui.band.check(when (s.band.firstOrNull()) { '5' -> R.id.band5; '6' -> R.id.band6; else -> R.id.band2 })
            ui.band6.isEnabled = caps.channels["6"].orEmpty().isNotEmpty()
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
            showStatus()
        }
    }

    private fun showStatus() {
        lifecycleScope.launch {
            val running = runCatching { HotspotConfig.running() }.getOrNull()
            ui.status.text = running?.let { getString(R.string.ap_running, it) } ?: getString(R.string.ap_off)
        }
    }

    private fun band(): String = when (ui.band.checkedButtonId) { R.id.band5 -> "5"; R.id.band6 -> "6"; else -> "2" }

    private fun channels(): List<Int> {
        val list = listOf(0) + caps.channels[band()].orEmpty()
        // The saved channel stays pickable on its own band even if the list lacks it.
        val own = savedChannel.takeIf { it != 0 && loaded?.band?.firstOrNull()?.toString() == band() && it !in list }
        return if (own != null) list + own else list
    }

    private fun fillChannels(selected: Int) {
        val list = channels()
        ui.channel.setItems(list.map { if (it == 0) getString(R.string.ap_auto) else it.toString() },
            list.indexOf(selected).coerceAtLeast(0))
    }

    private fun save() {
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
            hidden = ui.hidden.isChecked,
            clients = clientValues[ui.clients.selectedItemPosition],
            autoOff = ui.autoOff.isChecked,
            timeout = timeoutValues[ui.timeout.selectedItemPosition],
            ax = ui.ax.isChecked,
            randomMac = ui.randomMac.isChecked,
        )
        ui.save.isEnabled = false
        lifecycleScope.launch {
            val error = runCatching { HotspotConfig.write(s) }.getOrElse { it.message }
            ui.save.isEnabled = true
            if (error == null) {
                loaded = s
                Prefs.log("точка доступа: сохранено — ${HotspotConfig.summary(s)}")
                toast(R.string.ap_saved)
                showStatus()
            } else {
                Toast.makeText(this@HotspotActivity, getString(R.string.ap_save_failed, error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun paintQuick() {
        lifecycleScope.launch {
            // The settings screen must remain usable while root / Shizuku is unavailable.
            runCatching { HotspotControl.isOn() }
            val proxyOn = HotspotControl.activeIn(TetherMode.PROXY)
            val vpnOn = HotspotControl.activeIn(TetherMode.VPN)
            ui.startProxy.setText(if (proxyOn) R.string.ap_btn_proxy_off else R.string.ap_btn_proxy_on)
            ui.startVpn.setText(if (vpnOn) R.string.ap_btn_vpn_off else R.string.ap_btn_vpn_on)
            // Both need root: without it they would only report that it is missing.
            val root = Privileged.choose() == Backend.ROOT
            ui.startProxy.isEnabled = root
            ui.startVpn.isEnabled = root
            // Keep the switches below in step with what the buttons just did.
            programmatic = true
            ui.viaVpn.isChecked = Prefs.tetherMode == TetherMode.VPN
            ui.viaProxy.isChecked = Prefs.tetherMode == TetherMode.PROXY
            ui.proxyRow.visibility = if (ui.viaProxy.isChecked) View.VISIBLE else View.GONE
            paintVpnFast()
            programmatic = false
            paintTweakControls(root)
        }
    }

    private fun quick(mode: TetherMode) {
        ui.startProxy.isEnabled = false
        ui.startVpn.isEnabled = false
        lifecycleScope.launch {
            val line = runCatching { HotspotControl.toggle(mode) }.getOrElse { "ошибка: ${it.message}" }
            ui.tetherStatus.text = line
            ui.startProxy.isEnabled = true
            ui.startVpn.isEnabled = true
            paintQuick()
            showStatus()
        }
    }

    /** One request at a time; a second one while the first dialog is up is refused unseen. */
    private fun addTiles(remaining: List<Pair<Class<*>, Int>> = listOf(
        HotspotProxyTileService::class.java to R.string.ap_tile_proxy,
        HotspotVpnTileService::class.java to R.string.ap_tile_vpn,
    )) {
        if (!Compat.canRequestTile) return Compat.tilesByHand(this)
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

    /** The two switches are exclusive: VPN, proxy, or neither (direct). */
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
        ui.viaVpn.isChecked = mode == TetherMode.VPN
        ui.viaProxy.isChecked = mode == TetherMode.PROXY
        ui.proxyRow.visibility = if (mode == TetherMode.PROXY) View.VISIBLE else View.GONE
        paintVpnFast()
        ui.vpnFast.isChecked = Prefs.vpnFast
        ui.vpnFast.setOnCheckedChangeListener { _, on ->
            if (programmatic) return@setOnCheckedChangeListener
            Prefs.vpnFast = on
            if (ui.viaVpn.isChecked) setTether()
        }
        proxies = listOf(Prefs.tetherProxy)
        fillProxies()
        ui.viaVpn.setOnCheckedChangeListener { _, on ->
            if (programmatic) return@setOnCheckedChangeListener
            if (on) { programmatic = true; ui.viaProxy.isChecked = false; programmatic = false; ui.proxyRow.visibility = View.GONE }
            paintVpnFast()
            setTether()
        }
        ui.viaProxy.setOnCheckedChangeListener { _, on ->
            if (programmatic) return@setOnCheckedChangeListener
            if (on) { programmatic = true; ui.viaVpn.isChecked = false; programmatic = false }
            ui.proxyRow.visibility = if (on) View.VISIBLE else View.GONE
            paintVpnFast()
            setTether()
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

    /** "Ускорение" belongs to the VPN route: shown under it while it is on. */
    private fun paintVpnFast() {
        val v = if (ui.viaVpn.isChecked) View.VISIBLE else View.GONE
        ui.vpnFast.visibility = v
        ui.vpnFastHint.visibility = v
    }

    /** Set while the switches are changed from code, so that is not taken for a tap. */
    private var programmatic = false
    private var tweaksBusy = false
    private var tetherBusy = false

    private var ttlValues = emptyList<Int>()

    private fun setupTweaks() {
        ui.fixTtl.isChecked = Prefs.tetherTtlEnabled
        ui.disableIpv6.isChecked = Prefs.disableTetherIpv6
        fillTtlValues()
        ui.fixTtl.setOnCheckedChangeListener { _, on ->
            if (!programmatic) {
                Prefs.tetherTtlEnabled = on
                applyTweaks()
            }
        }
        ui.disableIpv6.setOnCheckedChangeListener { _, on ->
            if (!programmatic) {
                Prefs.disableTetherIpv6 = on
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
        val ready = root && !tweaksBusy && !tetherBusy
        ui.fixTtl.isEnabled = ready
        ui.disableIpv6.isEnabled = ready
        ui.ttlValue.isEnabled = ready && Prefs.tetherTtlEnabled
        ui.checkTweaks.isEnabled = ready
        ui.checkTether.isEnabled = ready
        ui.ttlRow.visibility = if (Prefs.tetherTtlEnabled) View.VISIBLE else View.GONE
        ui.tweaksOffloadHint.visibility = if (Prefs.tetherTtlEnabled || Prefs.disableTetherIpv6) View.VISIBLE else View.GONE
        ui.viaVpn.isEnabled = ready
        ui.viaProxy.isEnabled = ready
        ui.vpnFast.isEnabled = ready
        ui.startVpn.isEnabled = ready
        ui.startProxy.isEnabled = ready
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
        Prefs.tetherMode = when {
            ui.viaVpn.isChecked -> TetherMode.VPN
            ui.viaProxy.isChecked -> TetherMode.PROXY
            else -> TetherMode.DIRECT
        }
        // No second change while the route is being rebuilt: it would be shown but not applied.
        ui.viaVpn.isEnabled = false
        ui.viaProxy.isEnabled = false
        ui.vpnFast.isEnabled = false
        tetherBusy = true
        paintTweakControls(false)
        ui.tetherStatus.setText(R.string.working)
        lifecycleScope.launch {
            TetherWatch.sync()
            val line = runCatching { TetherRoute.apply() }.getOrElse { "раздача: ошибка ${it.message}" }
            tetherBusy = false
            Prefs.log(line)
            showTether(line)
        }
    }

    private fun showTether(line: String?) {
        lifecycleScope.launch {
            val root = Privileged.choose() == Backend.ROOT
            ui.viaVpn.isEnabled = root
            ui.viaProxy.isEnabled = root
            ui.vpnFast.isEnabled = root
            paintTweakControls(root)
            if (!tweaksBusy) ui.tweaksStatus.text = NetworkTweaks.status.ifBlank { getString(R.string.tether_tweaks_off) }
            ui.tetherStatus.text = when {
                !root -> getString(R.string.tether_need_root)
                line != null -> line
                else -> "сейчас: " + TetherRoute.label(Prefs.tetherMode)
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
