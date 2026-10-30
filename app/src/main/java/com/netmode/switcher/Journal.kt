package com.netmode.switcher

/** A log line taken apart for the journal: when, which rule, what happened, and how it went. */
internal data class JournalEntry(
    val raw: String,
    val time: String,
    val group: String?,
    val text: String,
    val severity: Severity,
) {
    enum class Severity { ERROR, WARNING, DONE, INFO }

    val problem: Boolean get() = severity == Severity.ERROR || severity == Severity.WARNING
}

/**
 * The log stays plain text, the form old entries and "copy" already have, so everything here is
 * read back from the words. A word not listed only costs a line its colour, never the line.
 */
internal object Journal {

    private val STAMP = Regex("^(\\d{2}\\.\\d{2} \\d{2}:\\d{2})\\s+(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val QUOTED = Regex("«[^»]*»")
    private val ENDED = Regex("\\s+—\\s+больше нет$")

    private val ERRORS = listOf(
        "ошибк", "не удалось", "неизвестно", "не подтвердилось", "заблокирована", "нужен root",
        "не выключил", "не включил", "не запустился", "неверный адрес",
    )
    private val WARNINGS = listOf("пропущен", "не активна", "без root", "подожди", "занята")

    /** The hotspot holds its clients back until the VPN is up, usually for a moment: on purpose. */
    private const val WAITING_FOR_VPN = "vpn не подключён — раздача заблокирована"

    /**
     * A VPN that was to go off is off already: nothing failed, there was nothing to do.
     * One action only: a rule's line joins its actions with commas, the others still count.
     */
    private val ALREADY_OFF = Regex("vpn [^,]*не подключён — пропущено")

    /** What older versions wrote when a newer event replaced a running job: never a failure. */
    private val CANCELLED = Regex("[^,]*was cancelled")

    fun parse(raw: String): JournalEntry {
        val stamp = STAMP.find(raw)
        val time = stamp?.groupValues?.get(1).orEmpty()
        val line = stamp?.groupValues?.get(2) ?: raw
        val (label, text) = splitLabel(line)
        return JournalEntry(raw, time, label?.let(::ruleOf), text, severity(text, label != null))
    }

    /** "(«Ночь» расписание): SIM 1 → 3G" and "приложения («Ночь» …): …" name their rule first. */
    private fun splitLabel(line: String): Pair<String?, String> {
        val open = when {
            line.startsWith("(") -> 0
            line.startsWith("приложения (") -> "приложения ".length
            else -> return null to line
        }
        var depth = 0
        for (i in open until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) {
                    if (line.getOrNull(i + 1) != ':') return null to line
                    return line.substring(open + 1, i) to line.substring(i + 2).trim()
                }
            }
        }
        return null to line
    }

    /** A rule's name when it has one, else its condition; its start and end are one rule. */
    private fun ruleOf(label: String): String {
        if (label.startsWith("«")) QUOTED.find(label)?.let { return it.value.trim('«', '»') }
        return label.replace(ENDED, "")
    }

    private fun severity(text: String, ofRule: Boolean): JournalEntry.Severity {
        // A name in quotes is the user's word, not the app's: a rule may be called "Ошибка".
        val words = text.replace(QUOTED, "").lowercase()
        // These first: their wording overlaps the lists below.
        val settled = words.replace(ALREADY_OFF, "").replace(CANCELLED, "")
        return when {
            WAITING_FOR_VPN in settled -> JournalEntry.Severity.WARNING
            ERRORS.any { it in settled } -> JournalEntry.Severity.ERROR
            WARNINGS.any { it in settled } -> JournalEntry.Severity.WARNING
            CANCELLED.containsMatchIn(words) -> JournalEntry.Severity.INFO
            ofRule -> JournalEntry.Severity.DONE
            else -> JournalEntry.Severity.INFO
        }
    }

    /** Rules present in [entries], the most recent first, for the filter. */
    fun groups(entries: List<JournalEntry>, limit: Int = 12): List<String> =
        entries.mapNotNull { it.group }.distinct().take(limit)

    /** Today's entries: how many went well and how many did not. */
    fun today(entries: List<JournalEntry>, date: String): Pair<Int, Int> {
        val ofToday = entries.filter { it.time.startsWith(date) }
        return ofToday.count { it.severity == JournalEntry.Severity.DONE } to ofToday.count { it.problem }
    }

    /** Errors newer than the [seen] line; entries come newest first. */
    fun unseenErrors(entries: List<JournalEntry>, seen: String?): List<JournalEntry> =
        entries.takeWhile { it.raw != seen }.filter { it.severity == JournalEntry.Severity.ERROR }
}
