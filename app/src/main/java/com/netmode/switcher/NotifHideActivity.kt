package com.netmode.switcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.netmode.switcher.databinding.ActivityNotifHideBinding
import com.netmode.switcher.databinding.RowAppBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Скрытие уведомлений": what is in the shade now, to pick from; the rules that hide; the apps
 * whose notifications are off as a whole. See [NotifHide].
 */
class NotifHideActivity : AppCompatActivity() {

    private lateinit var ui: ActivityNotifHideBinding

    /** What the shade list last showed; a repaint is skipped while nothing changed. */
    private var shown: List<ShadeEntry>? = null
    private var shownRecent: List<SeenEntry>? = null
    private var recentExpanded = false
    private var shownRules: List<HideRule>? = null
    private var shownBlocked: Set<String>? = null
    private var shownJournal: List<NotifJournalEntry>? = null
    private var journalExpanded = false
    private val expandedApps = mutableSetOf<String>()
    private val collapsedSearchApps = mutableSetOf<String>()
    private val labels = mutableMapOf<String, String>()
    private var changing = false

    private val pickBlocked = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val picked = r.data?.getStringArrayExtra(AppPickerActivity.EXTRA_PACKAGES)?.toSet() ?: return@registerForActivityResult
        val before = NotifHide.blocked
        val add = picked - before
        val drop = before - picked
        if (add.isEmpty()) return@registerForActivityResult applyBlocks(add, drop)
        confirmBlock(AppPickerActivity.labels(add.toList()).joinToString(", ")) { applyBlocks(add, drop) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityNotifHideBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.grant.setOnClickListener { grant() }
        ui.recentToggle.setOnClickListener { setRecentExpanded(!recentExpanded) }
        setRecentExpanded(savedInstanceState?.getBoolean("recent_expanded") ?: false)
        expandedApps.addAll(savedInstanceState?.getStringArrayList("recent_apps").orEmpty())
        ui.historySearch.doAfterTextChanged { collapsedSearchApps.clear(); renderRecent(shownRecent.orEmpty()) }
        journalExpanded = savedInstanceState?.getBoolean("journal_expanded") ?: false
        ui.journalToggle.setOnClickListener {
            journalExpanded = !journalExpanded
            renderJournal()
        }
        ui.journalClear.setOnClickListener { NotifJournal.clear(); renderJournal() }
        ui.retryRestore.setOnClickListener { change { NotifRules.retry() } }
        ui.recentClear.setOnClickListener {
            NotifHide.forgetRecent()
            render(force = true)
        }
        ui.blockPick.setOnClickListener {
            pickBlocked.launch(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_PACKAGES, NotifHide.blocked.toTypedArray())
                    .putExtra(AppPickerActivity.EXTRA_PLAIN, true),
            )
        }
        val block = if (NotifHide.canBlock) View.VISIBLE else View.GONE
        ui.blockSection.visibility = block
        ui.blockCard.visibility = block

        // The shade changes on its own; while the screen is up it is looked at every two seconds.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    render()
                    delay(2_000)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recent_expanded", recentExpanded)
        outState.putStringArrayList("recent_apps", ArrayList(expandedApps))
        outState.putBoolean("journal_expanded", journalExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!NotifWatch.connected) lifecycleScope.launch { NotifWatch.enable() }
        lifecycleScope.launch { NotifRules.reconcile(); render(force = true) }
    }

    /** The shell turns the access on; without root or Shizuku the system screen does. */
    private fun grant() {
        lifecycleScope.launch {
            if (Privileged.available()) NotifWatch.enable()
            else runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            delay(800)
            render(force = true)
        }
    }

    private fun render(force: Boolean = false) {
        ui.noAccess.visibility = if (NotifWatch.connected) View.GONE else View.VISIBLE
        ui.retryRestore.visibility = if (Prefs.releasedKeys.isEmpty() && !NotifRules.canRetry) View.GONE else View.VISIBLE
        ui.restoreStatus.visibility = if (NotifRules.lastError == null) View.GONE else View.VISIBLE
        ui.restoreStatus.text = NotifRules.lastError.orEmpty()
        val now = NotifHide.shade()
        if (force || now != shown) {
            shown = now
            renderShade(now)
        }
        val rules = Prefs.hideRules
        // Not up now and not hidden already: what is in the shade is picked from above.
        val recent = NotifHide.recent().filter { r ->
            now.none { r.same(it) } && rules.none { it.covers(r) }
        }
        if (force || recent != shownRecent) {
            shownRecent = recent
            renderRecent(recent)
        }
        if (force || rules != shownRules) {
            shownRules = rules
            renderRules()
        }
        val blocked = NotifHide.blocked
        if (force || blocked != shownBlocked) {
            shownBlocked = blocked
            renderBlocked()
        }
        val journal = NotifJournal.entries()
        if (force || shownJournal != journal) {
            shownJournal = journal
            renderJournal()
        }
    }

    private fun renderShade(list: List<ShadeEntry>) {
        ui.shadeList.removeAllViews()
        if (list.isEmpty()) return empty(ui.shadeList, R.string.hide_shade_empty)
        list.sortedWith(compareByDescending<ShadeEntry> { it.ongoing }.thenBy { it.app }).forEach { e ->
            val sub = listOfNotNull(
                getString(R.string.hide_ongoing).takeIf { e.ongoing },
                e.channel?.let { getString(R.string.hide_channel, it) },
            ).joinToString(" · ")
            row(ui.shadeList, e.app, e.displayedTitle.ifEmpty { label(e.app) }, label(e.app) + if (sub.isEmpty()) "" else " · $sub") { pick(e) }
        }
    }

    private fun setRecentExpanded(open: Boolean) {
        recentExpanded = open
        ui.recentCard.visibility = if (open) View.VISIBLE else View.GONE
        ui.recentToggle.setIconResource(if (open) R.drawable.ic_hotspot_collapse else R.drawable.ic_expand)
        ViewCompat.setStateDescription(ui.recentToggle, getString(
            if (open) R.string.hide_recent_expanded else R.string.hide_recent_collapsed,
        ))
        renderRecent(shownRecent.orEmpty())
    }

    private fun renderRecent(list: List<SeenEntry>) {
        ui.recentList.removeAllViews()
        ui.recentToggle.text = getString(R.string.hide_recent_count, list.size)
        ui.recentClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        // Keep collecting history while collapsed, but only build rows when it is opened.
        if (!recentExpanded) return
        if (list.isEmpty()) return empty(ui.recentList, R.string.hide_recent_empty)
        val query = ui.historySearch.text?.toString()?.trim().orEmpty()
        val filtered = list.filter { r -> listOf(label(r.app), r.app, r.title, r.channel.orEmpty())
            .any { it.contains(query, ignoreCase = true) } }
        if (filtered.isEmpty()) return empty(ui.recentList, R.string.hide_search_empty)
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        filtered.groupBy { it.app }.entries.sortedBy { label(it.key).lowercase() }.forEach { (app, entries) ->
            val open = if (query.isNotEmpty()) app !in collapsedSearchApps else app in expandedApps
            val group = textButton(getString(R.string.hide_history_group, label(app), entries.size)) {
                val selected = if (query.isNotEmpty()) collapsedSearchApps else expandedApps
                if (app in selected) selected.remove(app) else selected.add(app)
                renderRecent(shownRecent.orEmpty())
            }
            group.setIconResource(if (open) R.drawable.ic_hotspot_collapse else R.drawable.ic_expand)
            ViewCompat.setStateDescription(group, getString(if (open) R.string.hide_recent_expanded else R.string.hide_recent_collapsed))
            ui.recentList.addView(group)
            if (open) entries.forEach { r ->
                val at = java.time.Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault()).format(time)
                val sub = listOfNotNull(
                    label(r.app),
                    getString(R.string.hide_ongoing).takeIf { r.ongoing },
                    r.channel?.let { getString(R.string.hide_channel, it) },
                    getString(R.string.hide_seen_at, at),
                ).joinToString(" · ")
                row(ui.recentList, r.app, r.title.ifEmpty { label(r.app) }, sub) { pick(r.asShade()) }
            }
        }
    }

    private fun renderRules() {
        ui.ruleList.removeAllViews()
        val rules = Prefs.hideRules
        if (rules.isEmpty()) return empty(ui.ruleList, R.string.hide_rules_empty)
        rules.forEach { r ->
            val what = listOfNotNull(
                r.channel?.let { getString(R.string.hide_channel, it) } ?: getString(R.string.hide_all_channels),
                r.title?.let { getString(R.string.hide_with_title, it) },
            ).joinToString(" · ")
            val how = when {
                r.channelWas != null -> " · " + getString(R.string.hide_channel_off)
                r.active() -> " · " + getString(R.string.hide_listener_mode)
                else -> ""
            }
            val conditions = listOfNotNull(what, getString(R.string.hide_only_ongoing).takeIf { r.ongoingOnly },
                r.exclusions.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = getString(R.string.hide_except_prefix)))
                .joinToString(" · ")
            val state = when {
                !r.enabled -> getString(R.string.hide_rule_off)
                !r.active() -> getString(R.string.hide_paused_until, java.time.Instant.ofEpochMilli(r.pausedUntil)
                    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")))
                else -> getString(R.string.hide_rule_on)
            }
            row(ui.ruleList, r.app, label(r.app), "$state · $conditions$how") { editRule(r) }
            ui.ruleList.addView(MaterialSwitch(this).apply {
                text = getString(R.string.hide_rule_enabled)
                isChecked = r.enabled
                setOnCheckedChangeListener { _, checked -> change { NotifRules.save(r.copy(enabled = checked, pausedUntil = 0)) } }
            })
            ui.ruleList.addView(textButton(getString(if (r.enabled && !r.active()) R.string.hide_resume_rule else R.string.hide_pause_rule)) {
                if (r.enabled && !r.active()) change { NotifRules.save(r.copy(pausedUntil = 0)) }
                else pauseRule(r)
            }.apply { isEnabled = r.enabled })
        }
    }

    private fun pauseRule(rule: HideRule) {
        val durations = longArrayOf(15 * 60_000, 60 * 60_000, 4 * 60 * 60_000)
        MaterialAlertDialogBuilder(this).setTitle(R.string.hide_pause_rule)
            .setItems(arrayOf(getString(R.string.hide_pause_15m), getString(R.string.hide_pause_1h), getString(R.string.hide_pause_4h))) { _, i ->
                change { NotifRules.save(rule.copy(pausedUntil = System.currentTimeMillis() + durations[i])) }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun editRule(rule: HideRule) {
        val remove: (() -> Unit)? = if (Prefs.hideRules.any { it.id == rule.id }) ({
            MaterialAlertDialogBuilder(this).setTitle(R.string.hide_remove_rule)
                .setMessage(getString(R.string.hide_unhide_q, label(rule.app)))
                .setPositiveButton(R.string.hide_remove_rule) { _, _ -> change { NotifRules.remove(rule.id) } }
                .setNegativeButton(R.string.cancel, null).show()
            Unit
        }) else null
        NotifRuleEditor.show(this, rule, remove) { saved -> change { NotifRules.save(saved) } }
    }

    private fun change(action: suspend () -> Result<Unit>) {
        if (changing) { render(force = true); return }
        changing = true
        lifecycleScope.launch {
            try {
                action().exceptionOrNull()?.let {
                    Toast.makeText(this@NotifHideActivity, it.message.orEmpty(), Toast.LENGTH_LONG).show()
                }
            } finally {
                changing = false
                render(force = true)
            }
        }
    }

    private fun renderJournal() {
        val entries = NotifJournal.entries()
        ui.journalToggle.text = getString(R.string.hide_journal_count, entries.size)
        ui.journalToggle.setIconResource(if (journalExpanded) R.drawable.ic_hotspot_collapse else R.drawable.ic_expand)
        ViewCompat.setStateDescription(ui.journalToggle,
            getString(if (journalExpanded) R.string.hide_recent_expanded else R.string.hide_recent_collapsed))
        ui.journalCard.visibility = if (journalExpanded) View.VISIBLE else View.GONE
        ui.journalClear.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        ui.journalList.removeAllViews()
        if (!journalExpanded) return
        if (entries.isEmpty()) return empty(ui.journalList, R.string.hide_journal_empty)
        entries.forEach { entry ->
            val at = java.time.Instant.ofEpochMilli(entry.at).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val outcome = getString(when (entry.outcome) {
                "hidden" -> R.string.hide_journal_hidden
                "error" -> R.string.hide_journal_error
                else -> R.string.hide_journal_unconfirmed
            })
            row(ui.journalList, entry.app, entry.title.ifEmpty { label(entry.app) },
                "$at · $outcome · ${label(entry.app)} · ${entry.reason}") {
                Prefs.hideRules.firstOrNull { it.id == entry.ruleId }?.let { editRule(it) }
                    ?: Toast.makeText(this, R.string.hide_journal_rule_removed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun textButton(title: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = title
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val color = com.google.android.material.color.MaterialColors.getColor(ui.root, androidx.appcompat.R.attr.colorPrimary)
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(color)
        iconTint = android.content.res.ColorStateList.valueOf(color)
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    private fun renderBlocked() {
        if (!NotifHide.canBlock) return
        ui.blockList.removeAllViews()
        NotifHide.blocked.sorted().forEach { app ->
            row(ui.blockList, app, label(app), AppKey.pkg(app)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(label(app))
                    .setMessage(R.string.hide_unblock_q)
                    .setPositiveButton(R.string.hide_unblock) { _, _ -> applyBlocks(emptySet(), setOf(app)) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** What to hide of the notification tapped: its channel, its title, the app, or all of it. */
    private fun pick(e: ShadeEntry) {
        val name = label(e.app)
        val choices = buildList<Pair<String, () -> Unit>> {
            e.channel?.let { ch ->
                add(getString(R.string.hide_opt_channel, ch) to { hide(HideRule(Prefs.newId(), e.app, ch, null)) })
            }
            if (e.displayedTitle.isNotBlank()) {
                add(getString(R.string.hide_opt_title, e.displayedTitle) to { askTitle(e) })
            }
            add(getString(R.string.hide_opt_app) to { hide(HideRule(Prefs.newId(), e.app, null, null)) })
            if (NotifHide.canBlock && AppKey.valid(e.app) && AppKey.pkg(e.app) != packageName) {
                add(getString(R.string.hide_opt_block) to { confirmBlock(name) { applyBlocks(setOf(e.app), emptySet()) } })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The words to look for, prefilled with the whole title; a counter or a time can be cut off. */
    private fun askTitle(e: ShadeEntry) {
        editRule(HideRule(Prefs.newId(), e.app, e.channel, e.displayedTitle))
    }

    private fun hide(rule: HideRule) {
        editRule(rule)
    }

    private fun confirmBlock(names: String, then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hide_block_title)
            .setMessage(getString(R.string.hide_block_warn, names))
            .setPositiveButton(R.string.hide_block_do) { _, _ -> then() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyBlocks(add: Set<String>, drop: Set<String>) {
        lifecycleScope.launch {
            if (!Privileged.available()) {
                Toast.makeText(this@NotifHideActivity, R.string.hide_need_shell, Toast.LENGTH_LONG).show()
                return@launch
            }
            val failed = add.mapNotNull { a -> NotifHide.block(a).exceptionOrNull()?.let { a to it } } +
                drop.mapNotNull { a -> NotifHide.unblock(a).exceptionOrNull()?.let { a to it } }
            failed.firstOrNull()?.let { (a, err) ->
                Toast.makeText(this@NotifHideActivity, getString(R.string.hide_block_failed, label(a), err.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
            render(force = true)
        }
    }

    private fun label(app: String) = labels.getOrPut(app) { AppPickerActivity.labels(listOf(app)).first() }

    private fun empty(parent: LinearLayout, text: Int) {
        parent.addView(TextView(this).apply {
            setText(text)
            setTextAppearance(R.style.NMS_Hint)
            setPadding(0, 24, 0, 24)
        })
    }

    private fun row(parent: LinearLayout, app: String, title: String, sub: String, onClick: () -> Unit) {
        val row = RowAppBinding.inflate(layoutInflater, parent, false)
        row.check.visibility = View.GONE
        row.label.text = title
        row.pkg.text = sub
        row.icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(AppKey.pkg(app)) }.getOrNull())
        row.root.setOnClickListener { onClick() }
        parent.addView(row.root)
    }
}
