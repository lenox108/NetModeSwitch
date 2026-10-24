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
) {
    /** A rule on one whole channel: the kind root can switch off rather than snooze. */
    val wholeChannel: Boolean get() = channel != null && title == null

    fun matches(sbn: StatusBarNotification): Boolean =
        app == NotifHide.appOf(sbn) &&
            (channel == null || channel == sbn.notification.channelId) &&
            (title == null || NotifHide.titleOf(sbn).contains(title, ignoreCase = true))

    /** The same test for a notification remembered from earlier, not up now. */
    fun covers(e: SeenEntry): Boolean =
        app == e.app && (channel == null || channel == e.channel) &&
            (title == null || e.title.contains(title, ignoreCase = true))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("app", app)
        .put("channel", channel ?: JSONObject.NULL)
        .put("title", title ?: JSONObject.NULL)
        .put("channelWas", channelWas ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = HideRule(
            id = o.getLong("id"),
            app = o.getString("app"),
            channel = o.optString("channel").takeIf { !o.isNull("channel") && it.isNotEmpty() },
            title = o.optString("title").takeIf { !o.isNull("title") && it.isNotEmpty() },
            channelWas = if (o.has("channelWas") && !o.isNull("channelWas")) o.getInt("channelWas") else null,
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
)

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
 * A year, not "forever": the system adds the time to now, and a huge one overflows into a
 * snooze that ends at once, over and over. A key a removed rule let go is kept in
 * [Prefs.releasedKeys] and let go again each time the listener connects: the system remembers
 * snoozes across reboots, and one whose notification was not up at the time is otherwise
 * held for the rest of the year.
 *
 * The other way, [block], takes POST_NOTIFICATIONS from the whole app through the privileged
 * shell (root first). Nothing of it reaches the shade then, but Android kills the app when a
 * runtime permission is revoked, and a foreground service still shows under "Активные
 * приложения".
 */
object NotifHide {

    private const val SNOOZE_MS = 365L * 24 * 3600 * 1000
    private const val PERM = "android.permission.POST_NOTIFICATIONS"

    /** POST_NOTIFICATIONS is a runtime permission from Android 13 on; before it there is none. */
    val canBlock: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** UserHandle's own user id is hidden API; the uid carries it (100000 uids per user). */
    fun appOf(sbn: StatusBarNotification): String = AppKey.of(sbn.packageName, sbn.uid / 100_000)

    fun titleOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty()
    }

    fun entryOf(sbn: StatusBarNotification) = ShadeEntry(
        key = sbn.key,
        app = NotifHide.appOf(sbn),
        channel = sbn.notification.channelId,
        title = titleOf(sbn).ifEmpty { sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() },
        ongoing = sbn.isOngoing,
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

    /**
     * The snooze goes first and alone: the shade takes some tens of milliseconds to draw a
     * new notification, and one snoozed before that never shows. The bookkeeping — the
     * "recent" list, the keys to let go later — is done off the main thread afterwards.
     */
    internal fun onPosted(listener: NotificationListenerService, sbn: StatusBarNotification) {
        val rule = Prefs.hideRules.firstOrNull { it.matches(sbn) }
        val snoozed = rule != null && runCatching { listener.snoozeNotification(sbn.key, SNOOZE_MS) }.isSuccess
        App.scope.launch {
            if (snoozed) remember(sbn.key, rule!!.id)
            seen(sbn)
            // Up in the shade again: a released key needs no more letting go.
            if (sbn.key in Prefs.releasedKeys) Prefs.releasedKeys = Prefs.releasedKeys - sbn.key
        }
    }

    /** Applies the rules to everything up now: on connect, and when a rule is added. */
    internal fun sweep(listener: NotificationListenerService) {
        if (Prefs.hideRules.isEmpty()) return
        runCatching { listener.activeNotifications }.getOrNull().orEmpty().forEach { onPosted(listener, it) }
    }

    /**
     * Saves the rule and hides what it covers. A whole-channel rule first tries switching the
     * channel off through root: nothing of it is posted then, not even for the instant a
     * snooze needs. Where that does not work it is a snoozing rule like any other; the
     * snooze also stays as a second line, should someone switch the channel back on.
     */
    suspend fun add(rule: HideRule): HideRule {
        val same = Prefs.hideRules.filter { it.app == rule.app && it.channel == rule.channel && it.title == rule.title }
        // Re-adding the same rule keeps the importance to give back, not the "off" it set.
        val was = same.firstNotNullOfOrNull { it.channelWas }
            ?: if (rule.wholeChannel) channelOff(rule.app, rule.channel!!) else null
        val saved = rule.copy(channelWas = was)
        Prefs.hideRules = Prefs.hideRules - same.toSet() + saved
        Prefs.log("скрытие уведомлений: ${describe(saved)}" + if (was != null) " — канал отключён через root" else "")
        NotifWatch.listener?.let { sweep(it) }
        return saved
    }

    private const val IMPORTANCE_NONE = 0

    /** Whether channel rules can be switched off rather than snoozed: root, not Shizuku. */
    suspend fun canSwitchChannels(): Boolean = Root.available()

    /** Switches the channel off; returns the importance it had, or null when it did not take. */
    private suspend fun channelOff(app: String, channel: String): Int? {
        val r = setChannel(app, channel, IMPORTANCE_NONE) ?: return null
        return r.second.takeIf { r.first == IMPORTANCE_NONE && it != IMPORTANCE_NONE }
    }

    /** Runs [ChannelCli] as root; (importance now, importance before), or null on failure. */
    private suspend fun setChannel(app: String, channel: String, importance: Int): Pair<Int, Int>? {
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
            Prefs.log("канал $channel не переключён: ${line?.removePrefix("error=") ?: out.trim().take(200)}")
            return null
        }
        val now = line.substringAfter("ok=").substringBefore(' ').toIntOrNull() ?: return null
        val was = line.substringAfter("was=").trim().toIntOrNull() ?: return null
        return now to was
    }

    /** Drops the rule and lets back what it hid, unless another rule still covers it. */
    suspend fun remove(rule: HideRule) {
        Prefs.hideRules = Prefs.hideRules.filterNot { it.id == rule.id }
        rule.channelWas?.let { was -> setChannel(rule.app, rule.channel!!, was) }
        val left = Prefs.hideRules.map { it.id }.toSet()
        val (free, keep) = Prefs.hiddenKeys.entries.partition { it.value == rule.id || it.value !in left }
        Prefs.hiddenKeys = keep.associate { it.key to it.value }
        val now = System.currentTimeMillis()
        Prefs.releasedKeys = Prefs.releasedKeys + free.associate { it.key to now }
        free.forEach { unsnooze(it.key) }
        Prefs.log("уведомления снова видны: ${describe(rule)}")
    }

    /**
     * Snoozing again for a moment brings a snoozed notification back through the listener
     * API alone. The shell's own unsnooze goes first where it works; it also reaches a key the
     * system re-snoozed after a reboot without telling the listener.
     */
    private suspend fun unsnooze(key: String) {
        val quoted = "'" + key.replace("'", "'\\''") + "'"
        val shell = runCatching { Privileged.exec("cmd notification unsnooze $quoted") }
            .getOrNull()?.let { !it.contains("Unknown", true) && !it.contains("error", true) } == true
        if (!shell) runCatching { NotifWatch.listener?.snoozeNotification(key, 1L) }
    }

    /**
     * On connect: lets go again of what removed rules hid and has not shown up since. Keys
     * older than a snooze are dropped — whatever the system held has run out by then.
     */
    internal suspend fun release(listener: NotificationListenerService) {
        val released = Prefs.releasedKeys
        if (released.isEmpty()) return
        val up = runCatching { listener.activeNotifications }.getOrNull().orEmpty().map { it.key }.toSet()
        val now = System.currentTimeMillis()
        val pending = released.filter { (k, at) -> k !in up && now - at < SNOOZE_MS && k !in Prefs.hiddenKeys }
        Prefs.releasedKeys = pending
        pending.keys.forEach { unsnooze(it) }
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
        // The title only: what entryOf falls back to for an untitled one is the text.
        val e = entryOf(sbn).copy(title = titleOf(sbn))
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

    @Synchronized
    private fun remember(key: String, ruleId: Long) {
        val now = Prefs.hiddenKeys
        if (now[key] != ruleId) Prefs.hiddenKeys = now + (key to ruleId)
    }

    fun describe(rule: HideRule): String = listOfNotNull(
        AppPickerActivity.labels(listOf(rule.app)).first(),
        rule.channel?.let { "канал «$it»" } ?: "все каналы",
        rule.title?.let { "заголовок с «$it»" },
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
