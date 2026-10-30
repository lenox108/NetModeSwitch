package com.netmode.switcher

/** Shared by matching and preview; of Android, only compile-time flag constants. */
internal object NotifRulePolicy {
    fun active(enabled: Boolean, pausedUntil: Long, now: Long) = enabled && pausedUntil <= now

    fun matches(words: String?, ongoingOnly: Boolean, exclusions: List<String>,
                title: String, text: String, ongoing: Boolean): Boolean {
        if (ongoingOnly && !ongoing) return false
        // An untitled notification is displayed using its text: match exactly that fallback.
        val displayed = title.ifBlank { text }
        if (words != null && !displayed.contains(words, ignoreCase = true)) return false
        val all = "$title\n$text"
        return exclusions.none { it.isNotBlank() && all.contains(it, ignoreCase = true) }
    }

    /** A foreground service loses FLAG_ONGOING_EVENT on Android 14+, yet still stays up. */
    fun ongoing(flags: Int) = flags and (android.app.Notification.FLAG_ONGOING_EVENT or
        android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0

    /** Android snoozes a summary's whole group: only when nothing of it has to stay visible. */
    fun mayHide(summary: Boolean, siblingsMatch: List<Boolean>) = !summary || siblingsMatch.all { it }

    /** What the channel service says of a channel that is gone; any other error may be root's. */
    fun channelGone(line: String) = line.contains("нет канала")

    /**
     * Keys still to let go. One Android reported back as snoozed is retried; one not snoozed
     * and not up may be a remembered snooze waiting for the app's next post, so it stays until
     * [ttl], newest [max] only.
     */
    fun nextPending(pending: Map<String, Long>, keptByRule: Set<String>, requested: Set<String>,
                    still: Set<String>, up: Set<String>, now: Long, ttl: Long, max: Int): Map<String, Long> =
        pending.filter { (key, at) ->
            when {
                key in keptByRule || key in up -> false
                key in requested -> key in still
                else -> now - at < ttl
            }
        }.entries.sortedByDescending { it.value }.take(max).associate { it.key to it.value }

    fun exclusions(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(30).toList()
}
