package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes rule edits, ownership of root-disabled channels and restoration bookkeeping. */
internal object NotifRules {
    private const val RENEW_MS = 12L * 3600 * 1000
    private const val RELEASED_TTL_MS = 7L * 24 * 3600 * 1000
    private const val MAX_KEYS = 300
    private const val POKE_MS = 60_000L
    private const val LOOK_MS = 15L * 60_000

    private val mutex = Mutex()
    @Volatile var lastError: String? = null
        private set
    @Volatile private var failedChange: Pair<List<HideRule>, List<HideRule>>? = null
    @Volatile private var stuck = 0
    @Volatile private var pokedAt = 0L
    val canRetry: Boolean get() = failedChange != null || stuck > 0

    /** What "Вернуть всё" did and did not manage. */
    data class ReturnReport(val channels: Int, val failedChannels: List<String>, val unblocked: Int,
        val failedApps: List<String>, val released: Int, val error: String?)

    suspend fun save(rule: HideRule): Result<Unit> = change { old ->
        require(AppKey.valid(rule.app)) { "Некорректное приложение" }
        old.filterNot { it.id == rule.id } + rule.copy(exclusions = NotifRulePolicy.exclusions(rule.exclusions.joinToString("\n")))
    }

    suspend fun remove(id: Long): Result<Unit> = change { old -> old.filterNot { it.id == id } }

    suspend fun retry(): Result<Unit> {
        val failed = failedChange ?: return retryRestore()
        return change { current ->
            check(current == failed.first) { "Правила уже изменились. Повтори нужное действие в редакторе" }
            failed.second
        }
    }

    fun importable(imported: List<HideRule>): Boolean =
        imported.all { AppKey.valid(it.app) } && imported.map { it.id }.distinct().size == imported.size

    suspend fun replace(imported: List<HideRule>): Result<Unit> = change {
        require(importable(imported)) { "Некорректные правила скрытия в резервной копии" }
        imported.map { it.copy(channelWas = null, pausedUntil = 0) }
    }

    suspend fun reconcile(): Result<Unit> = change { old ->
        val now = System.currentTimeMillis()
        old.map { if (it.pausedUntil in 1..now) it.copy(pausedUntil = 0) else it }
    }

    private suspend fun change(edit: (List<HideRule>) -> List<HideRule>): Result<Unit> =
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val old = Prefs.hideRules
                    val next = edit(old)
                    try {
                        if (old != next) {
                            apply(old, next)
                            failedChange = null
                        }
                        renew()
                        restorePending()
                        if (failedChange == null) lastError = null
                    } catch (e: Exception) {
                        if (old != next && Prefs.hideRules == old) failedChange = old.toList() to next.toList()
                        throw e
                    } finally {
                        schedule()
                        NotifWatch.listener?.let { NotifWatch.refresh(it) }
                    }
                }.onFailure { lastError = it.message ?: "Не удалось изменить скрытие уведомлений" }
            }
        }

    private suspend fun apply(old: List<HideRule>, next: List<HideRule>) {
        val now = System.currentTimeMillis()
        val owned = old.filter { it.channel != null && it.channelWas != null }
            .groupBy { it.app to it.channel!! }
        val wanted = next.filter { it.active(now) && it.wholeChannel }.groupBy { it.app to it.channel!! }
        val importance = NotifChannelTransitions.update(owned.mapValues { it.value.first().channelWas!! }, wanted.keys,
            restore = { key, was -> NotifHide.restoreChannel(key.first, key.second, was) },
            disable = { key -> NotifHide.channelOff(key.first, key.second) })
        val saved = next.map { r -> r.copy(channelWas =
            if (r.active(now) && r.wholeChannel) importance[r.app to r.channel!!] else null) }
        Prefs.hideRules = saved

        val snoozed = runCatching { NotifWatch.listener?.snoozedNotifications?.associateBy { it.key } }.getOrNull()
        val keep = mutableMapOf<String, Long>()
        val free = mutableMapOf<String, Long>()
        for ((key, ruleId) in Prefs.hiddenKeys) {
            val entry = snoozed?.get(key)
            val match = entry?.let { n -> ruleFor(n, snoozed.values, saved) }
            when {
                match != null -> keep[key] = match.id
                entry != null -> free[key] = now
                saved.any { it.id == ruleId && it.active(now) && it == old.firstOrNull { o -> o.id == ruleId } } -> keep[key] = ruleId
                else -> free[key] = now // Not up: a snooze may wait for its next post, or for the listener.
            }
        }
        // A summary let go takes its group: siblings Android snoozed along with it, not tracked.
        groupOf(free.keys, snoozed.orEmpty()).filter { it.key !in keep && ruleFor(it, snoozed.orEmpty().values, saved) == null }
            .forEach { free[it.key] = now }
        Prefs.hiddenKeys = keep
        Prefs.releasedKeys = (Prefs.releasedKeys + free) - keep.keys
        NotifWatch.listener?.let { NotifHide.sweep(it) }
        Prefs.log("правила скрытия уведомлений обновлены")
    }

    private fun groupOf(keys: Set<String>, snoozed: Map<String, StatusBarNotification>): List<StatusBarNotification> {
        val groups = keys.mapNotNull { snoozed[it] }.filter(NotifHide::isSummary).map { it.groupKey }.toSet()
        return snoozed.values.filter { it.groupKey in groups }
    }

    /** The rule holding [n]: its own, or for a summary that of a sibling still kept hidden. */
    private fun ruleFor(n: StatusBarNotification, snoozed: Collection<StatusBarNotification>, rules: List<HideRule>): HideRule? =
        rules.firstOrNull { it.matches(n) } ?: if (!NotifHide.isSummary(n)) null else
            snoozed.firstOrNull { it.key != n.key && it.groupKey == n.groupKey && !NotifHide.isSummary(it) }
                ?.let { s -> rules.firstOrNull { it.matches(s) } }

    /** Tracks what Android snoozed along with [rule]'s match, so it can be let back. */
    suspend fun rememberGroup(rule: HideRule, group: List<StatusBarNotification>) = mutex.withLock {
        val snoozed = runCatching { NotifWatch.listener?.snoozedNotifications?.map { it.key }?.toSet() }.getOrNull()
            ?: return@withLock
        val rules = Prefs.hideRules
        val taken = group.filter { it.key in snoozed }
        val (held, wrong) = taken.partition { s -> NotifHide.isSummary(s) || rules.any { it.matches(s) } }
        if (held.isNotEmpty()) Prefs.hiddenKeys = Prefs.hiddenKeys + held.associate { s ->
            s.key to (rules.firstOrNull { it.matches(s) }?.id ?: rule.id)
        }
        if (wrong.isNotEmpty()) {
            val now = System.currentTimeMillis()
            Prefs.releasedKeys = Prefs.releasedKeys + wrong.associate { it.key to now }
            runCatching { restorePending() }
        }
    }

    /** Pending keys get no callback when Android re-snoozes them: any post is a chance to look. */
    suspend fun poke() {
        val now = System.currentTimeMillis()
        if (now - pokedAt < POKE_MS) return
        pokedAt = now
        mutex.withLock { runCatching { renew(); restorePending() } }
        NotifWatch.listener?.let { NotifWatch.refresh(it) }
    }

    /**
     * Extends the snoozes. Without this they end on their own within [NotifHide.SNOOZE_MS],
     * which is the whole point: no code runs when the app is uninstalled. Snoozing a snoozed
     * key restarts its time and shows nothing.
     */
    private fun renew() {
        val listener = NotifWatch.listener ?: return
        val now = System.currentTimeMillis()
        val hidden = Prefs.hiddenKeys
        if (hidden.isEmpty() || now - Prefs.hideRenewedAt in 0 until RENEW_MS) return
        val snoozed = runCatching { listener.snoozedNotifications }.getOrNull() ?: return
        val up = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        val rules = Prefs.hideRules
        snoozed.filter { it.key in hidden && ruleFor(it, snoozed.asList(), rules) != null }.forEach { n ->
            val group = up.filter { it.groupKey == n.groupKey && !NotifHide.isSummary(it) }
            // A snooze of a group member can take visible siblings along, a child's too: with
            // one up, let this snooze run out. [NotifHide.onPosted] hides the repost.
            if (NotifRulePolicy.mayHide(true, group.map { s -> rules.any { it.matches(s) } }))
                runCatching { listener.snoozeNotification(n.key, NotifHide.SNOOZE_MS) }
        }
        Prefs.hideRenewedAt = now
        if (hidden.size > MAX_KEYS) {
            val keys = snoozed.map { it.key }.toSet()
            Prefs.hiddenKeys = hidden.filterKeys { it in keys }
        }
    }

    /**
     * Everything back as Android had it, before uninstalling: rules off, channels and app
     * permissions restored, snoozes let go. Goes on past a failure and says what is left.
     */
    suspend fun returnAll(): ReturnReport = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val rules = Prefs.hideRules
            val owned = rules.filter { it.channel != null && it.channelWas != null }
                .groupBy { it.app to it.channel!! }.mapValues { it.value.first().channelWas!! }
            val failed = owned.filter { (key, was) -> NotifHide.restoreChannel(key.first, key.second, was) != was }.keys
            Prefs.hideRules = rules.map { r ->
                r.copy(enabled = false, pausedUntil = 0, channelWas = r.channelWas.takeIf { r.app to r.channel in failed })
            }
            failedChange = null

            val apps = NotifHide.blocked
            val appFailures = apps.filter { NotifHide.unblock(it).isFailure }

            val snoozed = runCatching { NotifWatch.listener?.snoozedNotifications?.associateBy { it.key } }.getOrNull().orEmpty()
            val hidden = Prefs.hiddenKeys.keys
            val release = hidden + groupOf(hidden, snoozed).map { it.key }
            Prefs.hiddenKeys = emptyMap()
            Prefs.releasedKeys = Prefs.releasedKeys + release.associateWith { now }
            val error = runCatching { restorePending() }.exceptionOrNull()?.message
            lastError = error
            schedule()
            Prefs.log("скрытие уведомлений отменено перед удалением")
            ReturnReport(owned.size - failed.size, failed.map { it.second }, apps.size - appFailures.size,
                appFailures, release.count { it in snoozed }, error)
        }
    }

    /** A post can race an edit: always attribute the key to the rule that is active now. */
    suspend fun remember(sbn: StatusBarNotification) = mutex.withLock {
        val rule = Prefs.hideRules.firstOrNull { it.matches(sbn) }
        if (rule != null) {
            Prefs.hiddenKeys = Prefs.hiddenKeys + (sbn.key to rule.id)
            Prefs.releasedKeys = Prefs.releasedKeys - sbn.key
        } else {
            Prefs.releasedKeys = Prefs.releasedKeys + (sbn.key to System.currentTimeMillis())
            runCatching { restorePending() }
        }
    }

    suspend fun retryRestore(): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            runCatching { restorePending(); lastError = null }
                .onFailure { lastError = it.message ?: "Не удалось восстановить уведомления" }
        }
    }

    private suspend fun restorePending() {
        val pending = Prefs.releasedKeys
        if (pending.isEmpty()) {
            stuck = 0
            return
        }
        val listener = NotifWatch.listener ?: error("Для восстановления нужен доступ к уведомлениям")
        val snoozed = runCatching { listener.snoozedNotifications.associateBy { it.key } }.getOrElse {
            error("Android не дал проверить скрытые уведомления. Повтори после подключения службы")
        }
        val rules = Prefs.hideRules
        val keepHidden = mutableMapOf<String, Long>()
        val release = pending.keys.filter { key ->
            val n = snoozed[key]
            val rule = n?.let { sbn -> ruleFor(sbn, snoozed.values, rules) }
            if (rule != null) keepHidden[key] = rule.id
            n != null && rule == null
        }
        if (keepHidden.isNotEmpty()) Prefs.hiddenKeys = Prefs.hiddenKeys + keepHidden
        // One short wait for the batch, never one wait per notification.
        release.forEach { key -> runCatching { listener.snoozeNotification(key, 1L) } }
        if (release.isNotEmpty()) delay(200)
        val still = runCatching { listener.snoozedNotifications.map { it.key }.toSet() }.getOrElse {
            error("Запрос отправлен, но Android не подтвердил восстановление. Можно повторить")
        }
        val up = runCatching { listener.activeNotifications.map { it.key }.toSet() }.getOrDefault(emptySet())
        Prefs.releasedKeys = NotifRulePolicy.nextPending(pending, keepHidden.keys, release.toSet(), still, up,
            System.currentTimeMillis(), RELEASED_TTL_MS, MAX_KEYS)
        stuck = release.count { it in still }
        check(stuck == 0) {
            "Правило сохранено. Ожидают восстановления: $stuck. Нажми «Повторить восстановление»"
        }
    }

    private fun schedule() {
        val ctx = App.ctx
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val action = PendingIntent.getBroadcast(ctx, 7631, Intent(ctx, NotifResumeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        // Without the listener nothing can be renewed; onListenerConnected schedules this again.
        val renewAt = if (Prefs.hiddenKeys.isEmpty() || NotifWatch.listener == null) null
            else maxOf(Prefs.hideRenewedAt + RENEW_MS, now + 60_000)
        val next = (Prefs.hideRules.filter { it.enabled && it.pausedUntil > now }.map { it.pausedUntil } + listOfNotNull(renewAt)).minOrNull()
        if (next == null) alarm.cancel(action)
        else {
            val exact = android.os.Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            val scheduled = exact && runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, action)
            }.isSuccess
            if (!scheduled) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, action)
        }
        // Waiting for a key needs no wake-up: nobody looks at the shade while the phone sleeps.
        // Android also says nothing when an app withdraws a hidden notification, which the
        // "Уведомление приложения" condition has to learn from this look.
        val look = PendingIntent.getBroadcast(ctx, 7632, Intent(ctx, NotifResumeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Prefs.releasedKeys.isEmpty() && Prefs.hiddenKeys.isEmpty()) alarm.cancel(look) else alarm.set(AlarmManager.RTC, now + LOOK_MS, look)
    }
}

class NotifResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        App.scope.launch {
            try { NotifRules.reconcile() } finally { pending.finish() }
        }
    }
}
