package com.netmode.switcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var shownRules: List<HideRule>? = null
    private var shownBlocked: Set<String>? = null

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

    override fun onStart() {
        super.onStart()
        if (!NotifWatch.connected) lifecycleScope.launch { NotifWatch.enable() }
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
    }

    private fun renderShade(list: List<ShadeEntry>) {
        ui.shadeList.removeAllViews()
        if (list.isEmpty()) return empty(ui.shadeList, R.string.hide_shade_empty)
        list.sortedWith(compareByDescending<ShadeEntry> { it.ongoing }.thenBy { it.app }).forEach { e ->
            val sub = listOfNotNull(
                getString(R.string.hide_ongoing).takeIf { e.ongoing },
                e.channel?.let { getString(R.string.hide_channel, it) },
            ).joinToString(" · ")
            row(ui.shadeList, e.app, e.title.ifEmpty { label(e.app) }, label(e.app) + if (sub.isEmpty()) "" else " · $sub") { pick(e) }
        }
    }

    private fun renderRecent(list: List<SeenEntry>) {
        ui.recentList.removeAllViews()
        ui.recentClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) return empty(ui.recentList, R.string.hide_recent_empty)
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        list.forEach { r ->
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

    private fun renderRules() {
        ui.ruleList.removeAllViews()
        val rules = Prefs.hideRules
        if (rules.isEmpty()) return empty(ui.ruleList, R.string.hide_rules_empty)
        rules.forEach { r ->
            val what = listOfNotNull(
                r.channel?.let { getString(R.string.hide_channel, it) } ?: getString(R.string.hide_all_channels),
                r.title?.let { getString(R.string.hide_with_title, it) },
            ).joinToString(" · ")
            val how = if (r.channelWas != null) " · " + getString(R.string.hide_channel_off) else ""
            row(ui.ruleList, r.app, label(r.app), what + how) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(label(r.app))
                    .setMessage(getString(R.string.hide_unhide_q, what))
                    .setPositiveButton(R.string.hide_unhide) { _, _ ->
                        lifecycleScope.launch {
                            NotifHide.remove(r)
                            render(force = true)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
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
            if (e.title.isNotBlank()) {
                add(getString(R.string.hide_opt_title, e.title) to { askTitle(e) })
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
        val field = com.google.android.material.textfield.TextInputEditText(this).apply { setText(e.title) }
        val box = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.hide_title_words)
            setPadding(48, 16, 48, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(label(e.app))
            .setView(box)
            .setPositiveButton(R.string.hide_do) { _, _ ->
                val words = field.text?.toString()?.trim().orEmpty()
                if (words.isNotEmpty()) hide(HideRule(Prefs.newId(), e.app, e.channel, words))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hide(rule: HideRule) {
        lifecycleScope.launch {
            val saved = NotifHide.add(rule)
            // Root was there, yet the system kept the channel on: say it is snoozing instead.
            if (saved.wholeChannel && saved.channelWas == null && NotifHide.canSwitchChannels()) {
                Toast.makeText(this@NotifHideActivity, R.string.hide_channel_kept, Toast.LENGTH_LONG).show()
            }
            render(force = true)
            delay(300)
            render(force = true)
        }
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

    private fun label(app: String) = AppPickerActivity.labels(listOf(app)).first()

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
