package com.netmode.switcher

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What to keep out of the shade: an app's notifications on one channel (or all of them),
 * optionally only those whose title has [title] in it. [app] is an [AppKey].
 *
 * [channelWas]: set when the channel itself was switched off through root — its importance
 * before, to give back when the rule goes. Null for a rule that only snoozes.
 */
data class HideRule(
    val id: Long,
    val app: String,
    val channel: String?,
    val title: String?,
    val channelWas: Int? = null,
    val enabled: Boolean = true,
    val pausedUntil: Long = 0,
    val ongoingOnly: Boolean = false,
    val exclusions: List<String> = emptyList(),
    val dismiss: Boolean = false,
) {
    /** A rule on one whole channel: the kind root can switch off rather than snooze. */
    val wholeChannel: Boolean get() = channel != null && title == null && !ongoingOnly && exclusions.isEmpty()

    fun active(now: Long = System.currentTimeMillis()) = NotifRulePolicy.active(enabled, pausedUntil, now)

    fun matches(sbn: StatusBarNotification): Boolean =
        active() && app == NotifHide.appOf(sbn) &&
            (channel == null || channel == sbn.notification.channelId) &&
            NotifRulePolicy.matches(title, ongoingOnly, exclusions, NotifHide.titleOf(sbn), NotifHide.textOf(sbn), NotifHide.ongoingOf(sbn))

    fun matches(e: ShadeEntry): Boolean = active() && app == e.app &&
        (channel == null || channel == e.channel) &&
        NotifRulePolicy.matches(title, ongoingOnly, exclusions, e.title, e.text, e.ongoing)

    /** The same test for a notification remembered from earlier, not up now. */
    fun covers(e: SeenEntry): Boolean =
        // History has no body: do not assume that a body exception did not match.
        exclusions.isEmpty() && active() && app == e.app && (channel == null || channel == e.channel) &&
            NotifRulePolicy.matches(title, ongoingOnly, exclusions, e.title, "", e.ongoing)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("app", app)
        .put("channel", channel ?: JSONObject.NULL)
        .put("title", title ?: JSONObject.NULL)
        .put("channelWas", channelWas ?: JSONObject.NULL)
        .put("enabled", enabled).put("pausedUntil", pausedUntil).put("ongoingOnly", ongoingOnly)
        .put("exclusions", org.json.JSONArray(exclusions))
        .put("dismiss", dismiss)

    companion object {
        fun fromJson(o: JSONObject) = HideRule(
            id = o.getLong("id"),
            app = o.getString("app"),
            channel = o.optString("channel").takeIf { !o.isNull("channel") && it.isNotEmpty() },
            title = o.optString("title").takeIf { !o.isNull("title") && it.isNotEmpty() },
            channelWas = if (o.has("channelWas") && !o.isNull("channelWas")) o.getInt("channelWas") else null,
            enabled = o.optBoolean("enabled", true),
            pausedUntil = o.optLong("pausedUntil", 0).coerceAtLeast(0),
            ongoingOnly = o.optBoolean("ongoingOnly", false),
            exclusions = o.optJSONArray("exclusions")?.let { a ->
                NotifRulePolicy.exclusions((0 until a.length()).joinToString("\n") { a.optString(it) })
            }.orEmpty(),
            dismiss = o.optBoolean("dismiss", false),
        )
    }
}

/** A notification as the hide screen lists it. Nothing of it is stored but [key]. */
data class ShadeEntry(
    val key: String,
    val app: String,
    val channel: String?,
    val title: String,
    val ongoing: Boolean,
    val text: String = "",
) {
    val displayedTitle: String get() = title.ifBlank { text }
}

/** A notification seen lately, for picking one that is not up right now. [at] is epoch ms. */
data class SeenEntry(
    val app: String,
    val channel: String?,
    val title: String,
    val ongoing: Boolean,
    val at: Long,
) {
    fun same(e: ShadeEntry) = app == e.app && channel == e.channel && title == e.title

    fun asShade() = ShadeEntry("", app, channel, title, ongoing)

    fun toJson(): JSONObject = JSONObject()
        .put("app", app)
        .put("channel", channel ?: JSONObject.NULL)
        .put("title", title)
        .put("ongoing", ongoing)
        .put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = SeenEntry(
            app = o.getString("app"),
            channel = o.optString("channel").takeIf { !o.isNull("channel") && it.isNotEmpty() },
            title = o.optString("title"),
            ongoing = o.optBoolean("ongoing"),
            at = o.getLong("at"),
        )
    }
}

/**
 * Hiding notifications the way "Hide Persistent Notifications" does: the listener snoozes a
 * matching one for [SNOOZE_MS]. That works on ongoing ones too (VPN, call recording, a
 * foreground service), which a listener cannot cancel. An update of a snoozed notification
 * stays snoozed; a fresh post comes through [onPosted] and is snoozed again at once.
 *
 * Days, not a year: nothing runs when this app is uninstalled, so a snooze has to end by
 * itself. [NotifRules.renew] extends it while the app is here. A key a removed rule let go is
 * kept in [Prefs.releasedKeys] for a while and let go again when it shows up: the system
 * remembers snoozes across reboots and applies one to the app's next post without telling
 * any listener.
 *
 * The other way, [block], takes POST_NOTIFICATIONS from the whole app through the privileged
 * shell (root first). Nothing of it reaches the shade then, but Android kills the app when a
 * runtime permission is revoked, and a foreground service still shows under "Активные
 * приложения".
 */
object NotifHide {

    internal const val SNOOZE_MS = 3L * 24 * 3600 * 1000
    private const val PERM = "android.permission.POST_NOTIFICATIONS"

    /** POST_NOTIFICATIONS is a runtime permission from Android 13 on; before it there is none. */
    val canBlock: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** UserHandle's own user id is hidden API; the uid carries it (100000 uids per user). */
    fun appOf(sbn: StatusBarNotification): String = AppKey.of(sbn.packageName, sbn.uid / 100_000)

    fun ongoingOf(sbn: StatusBarNotification) = NotifRulePolicy.ongoing(sbn.notification.flags)

    fun isSummary(sbn: StatusBarNotification) = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    fun titleOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG)
            .firstNotNullOfOrNull { extras.getCharSequence(it)?.toString()?.takeIf { s -> s.isNotBlank() } }.orEmpty()
    }

    fun textOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT)
            .firstNotNullOfOrNull { extras.getCharSequence(it)?.toString()?.takeIf { s -> s.isNotBlank() } }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
    }

    fun entryOf(sbn: StatusBarNotification) = ShadeEntry(
        key = sbn.key,
        app = NotifHide.appOf(sbn),
        channel = sbn.notification.channelId,
        title = titleOf(sbn),
        ongoing = ongoingOf(sbn),
        text = textOf(sbn),
    )

    /**
     * What is in the shade now, hidden ones aside; empty while the listener is not bound.
     * A group summary is left out while its group has notifications of its own to pick.
     */
    fun shade(): List<ShadeEntry> {
        val all = NotifWatch.listener?.let { l -> runCatching { l.activeNotifications }.getOrNull() }.orEmpty()
        fun summary(s: StatusBarNotification) = s.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val groups = all.filterNot(::summary).map { it.groupKey }.toSet()
        return all.filter { !summary(it) || it.groupKey !in groups }.map(::entryOf)
    }

    /** What the rules hold back now, for the screen to list; a group's summary is left out. */
    fun hiddenNow(): List<ShadeEntry> {
        val keys = Prefs.hiddenKeys
        if (keys.isEmpty()) return emptyList()
        return NotifWatch.listener?.let { l -> runCatching { l.snoozedNotifications }.getOrNull() }.orEmpty()
            .filter { it.key in keys && !isSummary(it) }.map(::entryOf)
    }

    /**
     * The snooze goes first and alone: the shade takes some tens of milliseconds to draw a
     * new notification, and one snoozed before that never shows. The bookkeeping — the
     * "recent" list, the keys to let go later — is done off the main thread afterwards.
     */
    internal fun onPosted(listener: NotificationListenerService, sbn: StatusBarNotification, up: List<StatusBarNotification>? = null) {
        val rules = Prefs.hideRules
        val shown = Prefs.hideShownKeys
        val rule = if (sbn.key in shown) null else rules.firstOrNull { it.matches(sbn) }
        // Android takes the rest of a group along with a snooze; only a group needs this look.
        val group = if (rule == null || !sbn.isGroup) emptyList() else groupOf(listener, sbn, up)
        val allowed = rule != null && NotifRulePolicy.mayHide(isSummary(sbn),
            group.filterNot(::isSummary).map { s -> s.key !in shown && rules.any { it.matches(s) } })
        // A rule that closes still has to snooze what cannot be closed: a foreground service.
        val closing = rule?.dismiss == true && NotifRulePolicy.dismissable(sbn.notification.flags)
        val attempt = if (!allowed) null else runCatching {
            if (closing) listener.cancelNotification(sbn.key) else listener.snoozeNotification(sbn.key, SNOOZE_MS)
        }
        val snoozed = attempt?.isSuccess == true && !closing
        App.scope.launch {
            if (snoozed) NotifRules.remember(sbn)
            seen(sbn)
            NotifRules.poke()
            if (rule != null && allowed) {
                kotlinx.coroutines.delay(120)
                if (snoozed && group.isNotEmpty()) NotifRules.rememberGroup(rule, group)
                val confirmed = runCatching {
                    if (closing) listener.activeNotifications.none { it.key == sbn.key }
                    else listener.snoozedNotifications.any { it.key == sbn.key }
                }.getOrDefault(false)
                NotifJournal.record(sbn, rule, when {
                    attempt?.isFailure == true -> "error"
                    !confirmed -> "unconfirmed"
                    closing -> "dismissed"
                    else -> "hidden"
                })
            }
        }
    }

    private fun groupOf(listener: NotificationListenerService, sbn: StatusBarNotification, up: List<StatusBarNotification>?) =
        (up ?: runCatching { listener.activeNotifications.toList() }.getOrNull()).orEmpty()
            .filter { it.key != sbn.key && it.groupKey == sbn.groupKey }

    /** A summary kept for the sake of a visible sibling can go once that sibling has gone. */
    internal fun onRemoved(listener: NotificationListenerService, sbn: StatusBarNotification) {
        val shown = Prefs.hideShownKeys
        if (sbn.key in shown) Prefs.hideShownKeys = shown - sbn.key
        if (!sbn.isGroup || Prefs.hideRules.isEmpty()) return
        val up = runCatching { listener.activeNotifications.toList() }.getOrNull().orEmpty()
        up.filter { isSummary(it) && it.groupKey == sbn.groupKey }.forEach { onPosted(listener, it, up) }
    }

    /** Applies the rules to everything up now: on connect, and when a rule is added. */
    internal fun sweep(listener: NotificationListenerService) {
        if (Prefs.hideRules.isEmpty()) return
        val up = runCatching { listener.activeNotifications.toList() }.getOrNull().orEmpty()
        up.forEach { onPosted(listener, it, up) }
    }

    /**
     * Saves the rule and hides what it covers. A whole-channel rule first tries switching the
     * channel off through root: nothing of it is posted then, not even for the instant a
     * snooze needs. Where that does not work it is a snoozing rule like any other; the
     * snooze also stays as a second line, should someone switch the channel back on.
     */
    suspend fun add(rule: HideRule): HideRule {
        NotifRules.save(rule).getOrThrow()
        return Prefs.hideRules.first { it.id == rule.id }
    }

    private const val IMPORTANCE_NONE = 0

    /** Whether channel rules can be switched off rather than snoozed: root, not Shizuku. */
    suspend fun canSwitchChannels(): Boolean = Root.available()

    /** Switches the channel off; returns the importance it had, or null when it did not take. */
    internal suspend fun channelOff(app: String, channel: String): Int? {
        val r = setChannel(app, channel, IMPORTANCE_NONE) ?: return null
        return r.second.takeIf { r.first == IMPORTANCE_NONE && it != IMPORTANCE_NONE }
    }

    /**
     * Gives the channel its importance back; returns what it has now, or null on failure. An
     * app or channel that no longer exists has nothing to give back and counts as done.
     */
    internal suspend fun restoreChannel(app: String, channel: String, was: Int): Int? {
        val installed = runCatching { Compat.appInfo(App.ctx.packageManager, AppKey.pkg(app)) }
            .exceptionOrNull() !is android.content.pm.PackageManager.NameNotFoundException
        if (!installed) return was
        var gone = false
        val set = setChannel(app, channel, was) { gone = true }
        return if (gone) was else set?.first
    }

    /** Runs [ChannelCli] as root; (importance now, importance before), or null on failure. */
    internal suspend fun setChannel(app: String, channel: String, importance: Int, onGone: () -> Unit = {}): Pair<Int, Int>? {
        if (!AppKey.valid(app) || !Root.available()) return null
        val uid = runCatching {
            AppKey.user(app) * 100_000 + Compat.appInfo(App.ctx.packageManager, AppKey.pkg(app)).uid % 100_000
        }.getOrNull() ?: return null
        val quoted = "'" + channel.replace("'", "'\\''") + "'"
        val out = runCatching {
            Root.exec("CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin " +
                "com.netmode.switcher.ChannelCli set ${AppKey.pkg(app)} $uid $quoted $importance 2>&1")
        }.getOrNull().orEmpty()
        val line = out.lineSequence().firstOrNull { it.startsWith("ok=") || it.startsWith("error=") }
        if (line == null || line.startsWith("error=")) {
            if (line != null && NotifRulePolicy.channelGone(line)) onGone()
            Prefs.log("канал $channel не переключён: ${line?.removePrefix("error=") ?: out.trim().take(200)}")
            return null
        }
        val now = line.substringAfter("ok=").substringBefore(' ').toIntOrNull() ?: return null
        val was = line.substringAfter("was=").trim().toIntOrNull() ?: return null
        return now to was
    }

    /** Drops the rule and lets back what it hid, unless another rule still covers it. */
    suspend fun remove(rule: HideRule) {
        NotifRules.remove(rule.id).getOrThrow()
    }

    private const val SEEN_MS = 24L * 3600 * 1000
    private const val SEEN_MAX = 100

    /** A notification already listed is written again only this much later, not per update. */
    private const val SEEN_REFRESH_MS = 10L * 60 * 1000

    /** [Prefs.seenNotifs], read once; a VPN's counter posts every second. */
    private var seenCache: List<SeenEntry>? = null

    /**
     * Keeps app, channel and title of what was posted over the last day, so a notification
     * that is up only now and then (call recording) can be picked later.
     */
    @Synchronized
    private fun seen(sbn: StatusBarNotification) {
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Only titles go to persistent history; message bodies stay in memory.
        val e = entryOf(sbn)
        val now = System.currentTimeMillis()
        val list = seenCache ?: Prefs.seenNotifs.also { seenCache = it }
        val old = list.firstOrNull { it.same(e) }
        if (old != null && now - old.at < SEEN_REFRESH_MS) return
        val next = (listOf(SeenEntry(e.app, e.channel, e.title, e.ongoing, now)) + list.filterNot { it.same(e) })
            .filter { now - it.at < SEEN_MS }
            .take(SEEN_MAX)
        seenCache = next
        Prefs.seenNotifs = next
    }

    /** What was posted over the last day, newest first. */
    @Synchronized
    fun recent(): List<SeenEntry> {
        val now = System.currentTimeMillis()
        return (seenCache ?: Prefs.seenNotifs.also { seenCache = it }).filter { now - it.at < SEEN_MS }
    }

    @Synchronized
    fun forgetRecent() {
        seenCache = emptyList()
        Prefs.seenNotifs = emptyList()
    }

    fun describe(rule: HideRule): String = listOfNotNull(
        AppPickerActivity.labels(listOf(rule.app)).first(),
        rule.channel?.let { "канал «$it»" } ?: "все каналы",
        rule.title?.let { "заголовок с «$it»" },
        "только постоянные".takeIf { rule.ongoingOnly },
        "закрывать".takeIf { rule.dismiss },
        rule.exclusions.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "кроме: "),
    ).joinToString(", ")

    /** Apps whose notifications are off as a whole, as [AppKey]s. */
    val blocked: Set<String> get() = Prefs.notifBlocked

    /**
     * user-set + user-fixed keep the app from asking again; the revoke itself is what kills
     * it, so the caller warns first.
     */
    suspend fun block(app: String): Result<Unit> = runCatching {
        require(AppKey.valid(app) && canBlock && AppKey.pkg(app) != App.ctx.packageName)
        val u = "--user ${AppKey.user(app)}"
        val p = AppKey.pkg(app)
        Privileged.exec("pm revoke $u $p $PERM && pm set-permission-flags $u $p $PERM user-set user-fixed")
            .takeIf { it.contains("Exception") || it.contains("Error:") }?.let { error(it.trim()) }
        Prefs.notifBlocked = Prefs.notifBlocked + app
        Prefs.log("уведомления отключены целиком: ${AppPickerActivity.labels(listOf(app)).first()}")
    }

    suspend fun unblock(app: String): Result<Unit> = runCatching {
        require(AppKey.valid(app) && canBlock)
        val u = "--user ${AppKey.user(app)}"
        val p = AppKey.pkg(app)
        Privileged.exec("pm clear-permission-flags $u $p $PERM user-set user-fixed; pm grant $u $p $PERM")
            .takeIf { it.contains("Exception") || it.contains("Error:") }?.let { error(it.trim()) }
        Prefs.notifBlocked = Prefs.notifBlocked - app
        Prefs.log("уведомления включены: ${AppPickerActivity.labels(listOf(app)).first()}")
    }
}
