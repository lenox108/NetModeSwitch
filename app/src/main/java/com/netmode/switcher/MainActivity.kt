package com.netmode.switcher

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import rikka.shizuku.Shizuku
import com.netmode.switcher.databinding.ActivityMainBinding
import com.netmode.switcher.databinding.ItemSimBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding

    /** Card per slot, so a refresh repaints in place instead of rebuilding the list. */
    private val cards = linkedMapOf<Int, ItemSimBinding>()

    /** Guards a toggle group while it is being set from code rather than tapped. */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        // The freezer tile's long press is for the folder alone: this screen is not shown.
        if (savedInstanceState == null && openFromTile(intent) && isTaskRoot) {
            finish()
            return
        }

        ui.toolbar.inflateMenu(R.menu.main)
        ui.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> refresh(force = true)
                R.id.action_diagnose -> diagnose()
            }
            true
        }

        ui.rulesCard.setOnClickListener { startActivity(Intent(this, AppRulesActivity::class.java)) }
        ui.settingsCard.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        ui.hideCard.setOnClickListener { startActivity(Intent(this, NotifHideActivity::class.java)) }
        ui.iceCard.setOnClickListener { startActivity(Intent(this, FreezerActivity::class.java)) }
        ui.apCard.setOnClickListener { startActivity(Intent(this, HotspotActivity::class.java)) }
        ui.tileCard.setOnClickListener { addTiles() }
        ui.logAll.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
    }

    /**
     * READ_PHONE_STATE only supplies carrier names for the SIM cards; without it the app still
     * works, the cards are just labelled "SIM 1" and "SIM 2". Shizuku's permission, by
     * contrast, is what makes the whole thing work at all when there is no root.
     */
    private fun ensurePermissions() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
        }
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                !Shizuku.shouldShowRequestPermissionRationale()
            ) {
                Shizuku.requestPermission(3)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) refresh(force = true)
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(action)) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromTile(intent)
    }

    /**
     * A long press on a tile in the shade lands here. The hotspot tiles (via Proxy / via VPN)
     * open the hotspot screen straight away, over this one, so "back" still leads home. The
     * freezer's tile opens its folder; true then, since that needs nothing of this screen.
     */
    private fun openFromTile(intent: Intent?): Boolean {
        if (intent?.action != android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES) return false
        @Suppress("DEPRECATION")
        val tile = intent.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)?.className
        if (tile == HotspotProxyTileService::class.java.name || tile == HotspotVpnTileService::class.java.name || HotspotPresetTiles.containsClass(tile)) {
            startActivity(Intent(this, HotspotActivity::class.java))
        }
        if (tile == FreezerTileService::class.java.name) {
            startActivity(Intent(this, ThawActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(IceShortcuts.EXTRA_FOLDER, true))
            return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        ensurePermissions()
        // Shizuku started by hand after a reboot: what waited for it runs now.
        AccessWait.poke()
        refresh(force = false)
        renderStatus()
        renderLog()
        offerCheck()
    }

    /** First start on a phone: offer the check once, before anything is switched. */
    private fun offerCheck() {
        if (Profile.current != null || Prefs.checkOffered) return
        lifecycleScope.launch {
            if (!Privileged.available()) return@launch
            Prefs.checkOffered = true
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.check_title)
                .setMessage(R.string.check_offer)
                .setPositiveButton(R.string.check_start) { _, _ ->
                    startActivity(Intent(this@MainActivity, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_START, true))
                }
                .setNegativeButton(R.string.check_offer_later, null)
                .show()
        }
    }

    private fun refresh(force: Boolean) {
        lifecycleScope.launch {
            val sims = Sims.active(force)
            buildCards(sims)
            sims.forEach { sim ->
                ModeController.read(sim.slot)
                    .onSuccess { paint(sim.slot, it, null) }
                    .onFailure { paint(sim.slot, null, it.message) }
            }
            renderStatus()
        }
    }

    private fun buildCards(sims: List<SimInfo>) {
        if (cards.keys.toList() == sims.map { it.slot }) {
            sims.forEach { cards[it.slot]?.simName?.text = it.label }
            return
        }
        cards.clear()
        ui.simList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        sims.forEach { sim ->
            val card = ItemSimBinding.inflate(inflater, ui.simList, false)
            card.simName.text = sim.label
            // A network no working mode of this SIM connects to is greyed out.
            val avail = Profile.available(sim.slot)
            for ((btn, g) in netButtons(card)) btn.isEnabled = avail.any { g in it.generations }
            card.toggle.setOnClickListener {
                act(sim.slot) { ModeController.toggle(sim.slot, ModeController.Source.MANUAL) }
            }
            card.toggle.setOnLongClickListener { pickPair(sim.slot); true }
            card.pairEdit.setOnClickListener { pickPair(sim.slot) }
            card.pairAdd.setOnClickListener { addTiles(listOf(sim)) }
            card.nets.addOnButtonCheckedListener { _, id, checked ->
                if (syncing) return@addOnButtonCheckedListener
                val g = netButtons(card).first { it.first.id == id }.second
                val now = ModeController.modeOf(sim.slot)
                val mode = NetMode.ticked(now, g, checked, Profile.available(sim.slot))
                if (mode != null && mode != now) pick(sim.slot, mode) else paint(sim.slot, now, null)
            }
            cards[sim.slot] = card
            ui.simList.addView(card.root)
            // The pair as last known, not two blank halves while the mode is being read.
            paintPair(sim.slot, ModeController.modeOf(sim.slot))
        }
        ui.noSims.visibility = if (sims.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun netButtons(card: ItemSimBinding) =
        listOf(card.net5 to 5, card.net4 to 4, card.net3 to 3, card.net2 to 2)

    private fun pick(slot: Int, mode: NetMode) =
        act(slot) { ModeController.apply(slot, mode, ModeController.Source.MANUAL) }

    /** The mode as the networks it connects to, "4G / 3G" — the words of the card and its pair. */
    private fun nets(mode: NetMode) = mode.generations.reversed().joinToString(" / ") { "${it}G" }

    /**
     * The two modes the quick switch goes between on this SIM. Faster one first: from any third
     * mode the switch goes there. Applies to the tile, the widget and the notification too.
     */
    private fun pickPair(slot: Int) {
        val modes = Profile.available(slot)
        val current = Prefs.togglePair(slot)?.toList().orEmpty()
        val checked = BooleanArray(modes.size) { modes[it] in current }
        // Ticked order: a third tick unticks the oldest, so there are never more than two.
        val order = modes.indices.filter { checked[it] }.toMutableList()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pair_title, slot + 1))
            .setMultiChoiceItems(modes.map { nets(it) }.toTypedArray(), checked) { d, i, on ->
                checked[i] = on
                order.remove(i)
                if (on) order.add(i)
                val shown = d as androidx.appcompat.app.AlertDialog
                if (order.size > 2) {
                    val oldest = order.removeAt(0)
                    checked[oldest] = false
                    shown.listView.setItemChecked(oldest, false)
                }
                shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = order.size == 2
            }
            .setPositiveButton(R.string.w_cfg_save) { _, _ ->
                val picked = order.map { modes[it] }.sortedBy { it.ordinal }
                Prefs.setTogglePair(slot, picked[0] to picked[1])
                paint(slot, ModeController.modeOf(slot), null)
                StatusNotification.update()
                NetModeTiles.refresh()
            }
            .setNeutralButton(R.string.pair_default) { _, _ ->
                Prefs.setTogglePair(slot, null)
                paint(slot, ModeController.modeOf(slot), null)
                StatusNotification.update()
            }
            // Two buttons stay in one row on a narrow screen; back or a tap outside cancels.
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = order.size == 2
    }

    private fun act(slot: Int, action: suspend () -> Result<NetMode>) {
        cards[slot]?.mode?.text = getString(R.string.working)
        lifecycleScope.launch {
            action()
                .onSuccess { paint(slot, it, null) }
                // The mode that is really on, not a blank: a failed switch changed nothing.
                .onFailure { paint(slot, ModeController.modeOf(slot), it.message) }
            renderLog()
        }
    }

    private fun paint(slot: Int, mode: NetMode?, error: String?) {
        val card = cards[slot] ?: return
        card.mode.text = mode?.let { nets(it) } ?: getString(R.string.unknown)

        syncing = true
        for ((btn, g) in netButtons(card)) btn.isChecked = mode != null && g in mode.generations
        syncing = false
        paintPair(slot, mode)

        val errorColor = com.google.android.material.color.MaterialColors.getColor(card.detail, android.R.attr.colorError)
        val normal = com.google.android.material.color.MaterialColors.getColor(card.detail, com.google.android.material.R.attr.colorOnSurfaceVariant)
        if (error != null) {
            card.detail.text = error
            card.detail.setTextColor(errorColor)
            card.detail.visibility = View.VISIBLE
            return
        }
        card.detail.setTextColor(normal)
        val notes = listOfNotNull(
            getString(R.string.sim_for_data).takeIf { Sims.known.size > 1 && DualSim.dataSlot() == slot },
        )
        card.detail.text = notes.joinToString(" · ")
        card.detail.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * The quick pair in one line, the mode that is on lit. With no pair chosen it is the stock
     * one — the last 4G/5G mode and 3G.
     */
    private fun paintPair(slot: Int, mode: NetMode?) {
        val card = cards[slot] ?: return
        val (first, second) = Prefs.togglePair(slot)
            ?: ((mode?.takeIf { it.high } ?: Prefs.lastHigh(slot)) to NetMode.THREE_G)
        val lit = com.google.android.material.color.MaterialColors.getColor(card.pair, androidx.appcompat.R.attr.colorPrimary)
        val text = android.text.SpannableString(getString(R.string.pair_line, nets(first), nets(second)))
        listOf(first, second).firstOrNull { it == mode }?.let { on ->
            val part = nets(on)
            val at = if (on == first) text.indexOf(part) else text.lastIndexOf(part)
            text.setSpan(android.text.style.ForegroundColorSpan(lit), at, at + part.length, 0)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + part.length, 0)
        }
        card.pair.text = text
        card.toggle.contentDescription = getString(R.string.pair_to, nets(ModeController.toggleTarget(slot, mode)))
        card.pairAdd.visibility = if (Prefs.tileShown(slot)) View.GONE else View.VISIBLE
    }

    private fun renderStatus() {
        val rules = Prefs.appRules
        val on = rules.count { it.enabled }
        val held = AppFreezer.heldApps().size
        ui.rulesSummary.text = if (rules.isEmpty()) getString(R.string.apps_summary_none)
        else listOfNotNull(
            resources.getQuantityString(R.plurals.n_app_rules, on, on),
            if (held > 0) getString(R.string.held_now, held) else null,
        ).joinToString(" · ")
        ui.settingsSummary.text = getString(R.string.settings_summary)
        val hiding = Prefs.hideRules.size
        val blocked = Prefs.notifBlocked.size
        val ice = Freezer.apps
        ui.iceSummary.text = if (ice.isEmpty()) getString(R.string.ice_summary_none)
        else getString(R.string.ice_summary, ice.count { it.frozen }, ice.count { !it.frozen })
        ui.hideSummary.text = if (hiding + blocked == 0) getString(R.string.hide_summary_none)
        else listOfNotNull(
            getString(R.string.hide_summary_rules, hiding).takeIf { hiding > 0 },
            getString(R.string.hide_summary_blocked, blocked).takeIf { blocked > 0 },
        ).joinToString(" · ")
        lifecycleScope.launch {
            runCatching { HotspotConfig.read() }.getOrNull()?.let { ui.apSummary.text = HotspotConfig.summary(it) }
        }
    }

    private fun renderLog() {
        val events = Prefs.events
        ui.log.text = if (events.isEmpty()) getString(R.string.no_events)
        else events.take(8).joinToString("\n")
    }

    /**
     * One request at a time: the system answers a second one made while the first dialog is
     * still up with "request in progress" and never shows it.
     */
    private fun addTiles(remaining: List<SimInfo> = Sims.known.ifEmpty { listOf(SimInfo(0, "")) }) {
        if (!Compat.canRequestTile) return Compat.tilesByHand(this)
        val sim = remaining.firstOrNull() ?: return
        val manager = getSystemService(StatusBarManager::class.java)
        val cls = if (sim.slot == 0) Sim1TileService::class.java else Sim2TileService::class.java
        val label = if (sim.slot == 0) R.string.tile_label_sim1 else R.string.tile_label_sim2
        manager.requestAddTileService(
            ComponentName(this, cls),
            getString(label),
            // The tile as it will look: its icon is the mode it is on.
            ModeController.modeOf(sim.slot)?.let { NetModeTiles.glyph(it) } ?: Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                android.widget.Toast.makeText(this, getString(R.string.tile_already, getString(label)), android.widget.Toast.LENGTH_SHORT).show()
            }
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Prefs.setTileShown(sim.slot, true)
                paintPair(sim.slot, ModeController.modeOf(sim.slot))
            }
            addTiles(remaining.drop(1))
        }
    }

    private fun diagnose() {
        lifecycleScope.launch {
            val text = runCatching {
                buildString {
                    appendLine("доступ: ${when (Privileged.choose()) {
                        Backend.ROOT -> "root"
                        Backend.SHIZUKU -> "Shizuku"
                        Backend.AUTO -> "НЕТ — ни root, ни Shizuku"
                    }}")
                    appendLine("настройка: ${Prefs.backend}")
                    appendLine("Shizuku: ping=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}" +
                        " разрешение=${runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)}")
                    val sims = Sims.active(force = true)
                    appendLine("активных SIM: ${sims.size}")
                    sims.forEach { sim ->
                        val mask = Telephony.readMask(sim.slot)
                        appendLine("  ${sim.label}: 0x%X  %s".format(mask, Raf.describe(mask)))
                    }
                    appendLine("эталон 4G: 0x%X   3G: 0x%X".format(NetMode.FULL.mask, NetMode.THREE_G.mask))
                    appendLine()
                    appendLine(Privileged.exec("cmd phone get-allowed-network-types-for-users").trim())
                    appendLine()
                    appendLine("SSID: ${WifiSsid.current() ?: "—"}")
                }
            }.getOrElse { "ОШИБКА: ${it.message}" }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.diagnostics)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
