package com.netmode.switcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityLogBinding
import com.netmode.switcher.databinding.RowLogBinding

class LogActivity : AppCompatActivity() {

    private lateinit var ui: ActivityLogBinding
    private var entries: List<JournalEntry> = emptyList()
    private var shown: List<JournalEntry> = emptyList()
    private val expanded = hashSetOf<String>()

    /** null: everything; [PROBLEMS]: errors and skips; otherwise one rule's name. */
    private var filter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityLogBinding.inflate(layoutInflater)
        setContentView(ui.root)
        filter = savedInstanceState?.getString(KEY_FILTER)
            ?: PROBLEMS.takeIf { savedInstanceState == null && intent.getBooleanExtra(EXTRA_PROBLEMS, false) }
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.toolbar.menu.add(0, 1, 0, R.string.log_copy)
        ui.toolbar.menu.add(0, 2, 1, R.string.log_clear)
        ui.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> copy(shown.joinToString("\n") { it.raw })
                2 -> MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.log_clear_confirm)
                    .setPositiveButton(R.string.log_clear) { _, _ -> Prefs.clearLog(); filter = null; render() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        ui.list.layoutManager = LinearLayoutManager(this)
        ui.list.adapter = adapter
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILTER, filter)
    }

    private fun render() {
        entries = Prefs.events.map(Journal::parse)
        Prefs.journalSeen = entries.firstOrNull()?.raw
        val groups = Journal.groups(entries)
        if (filter != null && filter != PROBLEMS && filter !in groups) filter = null
        ui.filters.removeAllViews()
        chip(getString(R.string.log_filter_all), null)
        chip(getString(R.string.log_filter_problems), PROBLEMS)
        groups.forEach { chip(it, it) }
        applyFilter()
    }

    private fun chip(label: String, value: String?) {
        val view = layoutInflater.inflate(R.layout.chip_choice, ui.filters, false) as Chip
        view.text = label
        view.id = View.generateViewId()
        ui.filters.addView(view)
        view.isChecked = filter == value
        view.setOnClickListener { filter = value; applyFilter() }
    }

    private fun applyFilter() {
        shown = when (filter) {
            null -> entries
            PROBLEMS -> entries.filter { it.problem }
            else -> entries.filter { it.group == filter }
        }
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        ui.empty.text = getString(if (entries.isEmpty()) R.string.no_events else R.string.log_no_problems)
        ui.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("NetModeSwitch", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private val adapter = object : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(RowLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val entry = shown[position]
            bindLogRow(holder.row, entry, if (entry.raw in expanded) Int.MAX_VALUE else COLLAPSED_LINES)
            holder.row.root.setOnClickListener {
                if (!expanded.add(entry.raw)) expanded.remove(entry.raw)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.row.root.setOnLongClickListener { copy(entry.raw); true }
        }
    }

    private class RowHolder(val row: RowLogBinding) : RecyclerView.ViewHolder(row.root)

    companion object {
        const val EXTRA_PROBLEMS = "problems"
        private const val KEY_FILTER = "filter"
        private const val PROBLEMS = "\u0000problems"
        private const val COLLAPSED_LINES = 4
    }
}

/** One event, the same in the journal and on the home screen. */
internal fun bindLogRow(row: RowLogBinding, entry: JournalEntry, maxLines: Int) {
    val ctx: Context = row.root.context
    val (icon, color, state) = when (entry.severity) {
        JournalEntry.Severity.ERROR -> Triple(R.drawable.ic_log_error,
            MaterialColors.getColor(row.icon, android.R.attr.colorError), R.string.log_state_error)
        JournalEntry.Severity.WARNING -> Triple(R.drawable.ic_log_warning, ctx.getColor(R.color.log_warning), R.string.log_state_warning)
        JournalEntry.Severity.DONE -> Triple(R.drawable.ic_log_done, ctx.getColor(R.color.log_done), R.string.log_state_done)
        JournalEntry.Severity.INFO -> Triple(R.drawable.ic_info,
            MaterialColors.getColor(row.icon, com.google.android.material.R.attr.colorOnSurfaceVariant), R.string.log_state_info)
    }
    row.icon.setImageResource(icon)
    row.icon.imageTintList = ColorStateList.valueOf(color)
    row.title.text = entry.group ?: ctx.getString(R.string.log_system)
    row.time.text = entry.time
    row.body.text = entry.text
    row.body.maxLines = maxLines
    row.root.contentDescription = listOfNotNull(ctx.getString(state), entry.group, entry.time, entry.text).joinToString(", ")
}
