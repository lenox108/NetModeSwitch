package com.netmode.switcher

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the rule list and the editor say about a rule, kept free of Android for the tests. */
internal object RuleCard {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("EE, dd.MM 'в' HH:mm", Locale.forLanguageTag("ru"))

    /** "сегодня в 01:00", "завтра в 01:00", further off with its date. */
    fun nextLabel(next: ZonedDateTime, now: ZonedDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())
        return when (days) {
            0L -> "сегодня в ${next.format(TIME)}"
            1L -> "завтра в ${next.format(TIME)}"
            else -> next.format(DATE)
        }
    }

    /** The newest journal entry of a rule; entries come newest first. */
    fun lastOf(entries: List<JournalEntry>, rule: String): JournalEntry? = entries.firstOrNull { it.group == rule }

    /** Group names in use, sorted, each once whatever its case or spacing. */
    fun groups(names: List<String?>): List<String> =
        names.mapNotNull { it?.trim()?.ifEmpty { null } }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    fun inGroup(group: String?, filter: String?): Boolean =
        filter == null || group?.trim().equals(filter, ignoreCase = true)

    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000

    /** One icon per kind of action, in a fixed order, so rules can be told apart at a glance. */
    fun actionIcons(r: AppRule): List<Int> = listOfNotNull(
        R.drawable.ic_apps.takeIf { r.packages.isNotEmpty() },
        R.drawable.ic_t_signal.takeIf { r.netMode != null },
        R.drawable.ic_t_data.takeIf { r.mobileData != null },
        R.drawable.ic_t_wifi.takeIf { r.wifi != null },
        R.drawable.ic_hotspot.takeIf { r.hotspot != null },
        R.drawable.ic_a_vpn.takeIf { r.vpn != null },
        R.drawable.ic_a_tiles.takeIf { r.tiles.isNotEmpty() },
        R.drawable.ic_t_bt.takeIf { r.bluetoothOff },
        R.drawable.ic_a_locoff.takeIf { r.locationOff },
        R.drawable.ic_a_airplane.takeIf { r.airplane != null },
        R.drawable.ic_t_battery.takeIf { r.powerSave != null },
        R.drawable.ic_a_sim.takeIf { r.dataSim != null },
        R.drawable.ic_t_net.takeIf { r.reconnect || r.http != null },
        R.drawable.ic_ice.takeIf { r.ice != null },
        R.drawable.ic_settings.takeIf { r.sys.isNotEmpty() },
        R.drawable.ic_log.takeIf { r.note != null },
        R.drawable.ic_edit.takeIf { r.shell != null },
        R.drawable.ic_a_clean.takeIf { r.unload },
        R.drawable.ic_t_app.takeIf { r.launchApps.isNotEmpty() },
    ).distinct()

    /** The picker's sections; a trigger not listed still shows, in the last one. */
    val TRIGGER_SECTIONS: List<Pair<Int, List<Trigger>>> = listOf(
        R.string.trigger_section_time to listOf(Trigger.MANUAL, Trigger.TILE_CLICK, Trigger.TIME, Trigger.ALARM, Trigger.SUN, Trigger.PLACE),
        R.string.trigger_section_net to listOf(Trigger.WIFI, Trigger.WIFI_OTHER, Trigger.HOTSPOT, Trigger.MOBILE_DATA, Trigger.NET_MODE,
            Trigger.ROAMING, Trigger.WEAK_SIGNAL, Trigger.NO_INTERNET, Trigger.VPN, Trigger.TRAFFIC),
        R.string.trigger_section_device to listOf(Trigger.BLUETOOTH, Trigger.CAR, Trigger.HEADPHONES, Trigger.USB, Trigger.BATTERY,
            Trigger.CHARGING, Trigger.SCREEN_OFF, Trigger.LOCKED, Trigger.DND, Trigger.CALL),
        R.string.trigger_section_apps to listOf(Trigger.APP_OPEN, Trigger.NOTIFY_APP),
    )

    /** [offered] by section, in the order above, with [query] matched against [text]. */
    fun sections(offered: List<Trigger>, query: String, text: (Trigger) -> String): List<Pair<Int, List<Trigger>>> {
        val wanted = query.trim().lowercase()
        fun shown(t: Trigger) = wanted.isEmpty() || wanted in text(t).lowercase()
        val listed = TRIGGER_SECTIONS.flatMap { it.second }.toSet()
        val rest = offered.filter { it !in listed }
        val all = TRIGGER_SECTIONS.mapIndexed { i, (title, triggers) ->
            title to (triggers.filter { it in offered } + if (i == TRIGGER_SECTIONS.lastIndex) rest else emptyList())
        }
        return all.map { (title, triggers) -> title to triggers.filter(::shown) }.filter { it.second.isNotEmpty() }
    }
}
