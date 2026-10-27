package com.netmode.switcher

import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

internal data class NotifJournalEntry(val app: String, val title: String, val ruleId: Long,
    val reason: String, val outcome: String, val at: Long) {
    fun toJson() = JSONObject().put("app", app).put("title", title).put("rule", ruleId)
        .put("reason", reason).put("outcome", outcome).put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = NotifJournalEntry(o.getString("app"), o.optString("title"),
            o.getLong("rule"), o.optString("reason"), o.getString("outcome"), o.getLong("at"))
    }
}

/** Local, bounded diagnostic history. Never exported by Backup; notification bodies are not stored. */
internal object NotifJournal {
    private const val DAY = 24L * 3600 * 1000
    private val prefs by lazy { App.ctx.getSharedPreferences("notif_journal", Context.MODE_PRIVATE) }
    private var cache: List<NotifJournalEntry>? = null

    @Synchronized fun entries(): List<NotifJournalEntry> {
        val now = System.currentTimeMillis()
        val all = cache ?: runCatching {
            val a = JSONArray(prefs.getString("entries", "[]"))
            (0 until a.length()).map { NotifJournalEntry.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).also { cache = it }
        return all.filter { now - it.at in 0 until DAY }.take(100)
    }

    @Synchronized fun record(sbn: StatusBarNotification, rule: HideRule, outcome: String) {
        val now = System.currentTimeMillis()
        val entry = NotifJournalEntry(NotifHide.appOf(sbn), NotifHide.titleOf(sbn), rule.id,
            listOfNotNull(rule.channel?.let { "канал $it" } ?: "все каналы", "фильтр по словам".takeIf { rule.title != null },
                "только постоянные".takeIf { rule.ongoingOnly }).joinToString(" · "), outcome, now)
        val old = entries()
        // VPN counters can update every second: do not write the same event continuously.
        if (old.any { it.copy(at = now) == entry && now - it.at < 60_000 }) return
        write((listOf(entry) + old).take(100))
    }

    @Synchronized fun clear() = write(emptyList())

    private fun write(value: List<NotifJournalEntry>) {
        cache = value
        prefs.edit().putString("entries", JSONArray().apply { value.forEach { put(it.toJson()) } }.toString()).apply()
    }
}
