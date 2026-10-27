package com.netmode.switcher

/** Shared by matching and preview; has no Android dependencies. */
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

    fun exclusions(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(30).toList()
}
