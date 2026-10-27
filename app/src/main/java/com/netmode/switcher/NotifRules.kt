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
    private val mutex = Mutex()
    @Volatile var lastError: String? = null
        private set
    @Volatile private var failedChange: Pair<List<HideRule>, List<HideRule>>? = null
    val canRetry: Boolean get() = failedChange != null

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

    suspend fun replace(imported: List<HideRule>): Result<Unit> = change {
        require(imported.all { AppKey.valid(it.app) } && imported.map { it.id }.distinct().size == imported.size) {
            "Некорректные правила скрытия в резервной копии"
        }
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
                        schedule()
                        restorePending()
                        if (failedChange == null) lastError = null
                    } catch (e: Exception) {
                        if (old != next && Prefs.hideRules == old) failedChange = old.toList() to next.toList()
                        throw e
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
            restore = { key, was -> NotifHide.setChannel(key.first, key.second, was)?.first },
            disable = { key -> NotifHide.channelOff(key.first, key.second) })
        val saved = next.map { r -> r.copy(channelWas =
            if (r.active(now) && r.wholeChannel) importance[r.app to r.channel!!] else null) }
        Prefs.hideRules = saved

        val snoozed = runCatching { NotifWatch.listener?.snoozedNotifications?.associateBy { it.key } }.getOrNull()
        val keep = mutableMapOf<String, Long>()
        val free = mutableMapOf<String, Long>()
        for ((key, ruleId) in Prefs.hiddenKeys) {
            val entry = snoozed?.get(key)
            val match = entry?.let { n -> saved.firstOrNull { it.matches(n) } }
            when {
                match != null -> keep[key] = match.id
                entry != null -> free[key] = now
                snoozed != null -> Unit // Withdrawn or already restored; no longer hidden.
                saved.any { it.id == ruleId && it.active(now) && it == old.firstOrNull { o -> o.id == ruleId } } -> keep[key] = ruleId
                else -> free[key] = now // Listener unavailable: persist until it reconnects.
            }
        }
        Prefs.hiddenKeys = keep
        Prefs.releasedKeys = (Prefs.releasedKeys + free) - keep.keys
        NotifWatch.listener?.let { NotifHide.sweep(it) }
        Prefs.log("правила скрытия уведомлений обновлены")
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
        if (pending.isEmpty()) return
        val listener = NotifWatch.listener ?: error("Для восстановления нужен доступ к уведомлениям")
        val snoozed = runCatching { listener.snoozedNotifications.associateBy { it.key } }.getOrElse {
            error("Android не дал проверить скрытые уведомления. Повтори после подключения службы")
        }
        val keepHidden = mutableMapOf<String, Long>()
        val release = pending.keys.filter { key ->
            val n = snoozed[key]
            val rule = n?.let { sbn -> Prefs.hideRules.firstOrNull { it.matches(sbn) } }
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
        Prefs.releasedKeys = pending.filterKeys { it in release && it in still }
        check(Prefs.releasedKeys.isEmpty()) {
            "Правило сохранено. Ожидают восстановления: ${Prefs.releasedKeys.size}. Нажми «Повторить восстановление»"
        }
    }

    private fun schedule() {
        val ctx = App.ctx
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val action = PendingIntent.getBroadcast(ctx, 7631, Intent(ctx, NotifResumeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        val next = Prefs.hideRules.filter { it.enabled && it.pausedUntil > now }.minOfOrNull { it.pausedUntil }
        if (next == null) alarm.cancel(action)
        else {
            val exact = android.os.Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            val scheduled = exact && runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, action)
            }.isSuccess
            if (!scheduled) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, action)
        }
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
