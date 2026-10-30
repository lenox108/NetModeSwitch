package com.netmode.switcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A rule's own notification ([AppRule.notice]): "Выполнить?" before a start that waits for it,
 * or what a start did, with a button to undo. One per rule, gone when its window closes.
 */
object RunNotices {

    private const val CHANNEL_ASK = "rule_ask"
    private const val CHANNEL_DONE = "rule_done"
    private const val CHANNEL_FAILED = "rule_failed"
    private const val ID = 1

    /** An unanswered question goes after an hour; the window has usually moved on by then. */
    private const val ASK_TIMEOUT_MS = 60 * 60_000L

    private fun tag(ruleId: Long) = "rule:$ruleId"

    /** Beside the rule's own notice, not over it: "Отменить" still applies to what did work. */
    private fun failedTag(ruleId: Long) = "failed:$ruleId"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /**
     * Whether a notification of [channel] would be seen. A question no one can see would hold
     * the rule back for good, so without it the rule runs as if it had not asked.
     */
    private fun visible(ctx: Context, channel: String): Boolean {
        val nm = nm(ctx)
        if (!Compat.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) || !nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(channel) == null) {
            val (name, importance) = when (channel) {
                CHANNEL_ASK -> R.string.channel_rule_ask to NotificationManager.IMPORTANCE_HIGH
                CHANNEL_FAILED -> R.string.channel_rule_failed to NotificationManager.IMPORTANCE_DEFAULT
                else -> R.string.channel_rule_done to NotificationManager.IMPORTANCE_LOW
            }
            nm.createNotificationChannel(NotificationChannel(channel, ctx.getString(name), importance))
        }
        return nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun title(rule: AppRule) = rule.name ?: rule.timeLabel.replaceFirstChar { it.uppercase() }

    private fun button(ctx: Context, action: String, ruleId: Long, label: Int): Notification.Action {
        val i = Intent(ctx, RunNoticeReceiver::class.java).setAction(action)
            .setData(Uri.parse("netmode://rule/$action/$ruleId")).putExtra(RunNoticeReceiver.EXTRA_ID, ruleId)
        val pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, ctx.getString(label), pi).build()
    }

    /** Asks before [rule] starts. False when the question cannot be seen. */
    fun ask(rule: AppRule): Boolean {
        val ctx = App.ctx
        if (!visible(ctx, CHANNEL_ASK)) return false
        val n = Notification.Builder(ctx, CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.notice_ask_title, title(rule)))
            .setContentText(rule.actionLabel.ifEmpty { rule.endLabel.orEmpty() })
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(ASK_TIMEOUT_MS)
            .addAction(button(ctx, RunNoticeReceiver.RUN, rule.id, R.string.notice_run))
            .addAction(button(ctx, RunNoticeReceiver.SKIP, rule.id, R.string.notice_skip))
            .setDeleteIntent(button(ctx, RunNoticeReceiver.SKIP, rule.id, R.string.notice_skip).actionIntent)
            .build()
        nm(ctx).notify(tag(rule.id), ID, n)
        return true
    }

    /** Tells what [rule] did as it started; it can be undone while its window is open. */
    fun ran(rule: AppRule) {
        val ctx = App.ctx
        if (!visible(ctx, CHANNEL_DONE)) return
        val b = Notification.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.notice_done_title, title(rule)))
            .setContentText(rule.actionLabel.ifEmpty { rule.endLabel.orEmpty() })
            .setAutoCancel(true)
        // A rule without an end changes things for good: there is nothing on record to undo.
        if (rule.restores) {
            b.addAction(button(ctx, RunNoticeReceiver.UNDO, rule.id, if (rule.endCustom) R.string.notice_end else R.string.notice_undo))
        } else {
            b.setTimeoutAfter(15 * 60_000L)
        }
        nm(ctx).notify(tag(rule.id), ID, b.build())
    }

    /** What of [rule]'s start failed or was skipped, whatever its [AppRule.notice]; it can be run again. */
    fun failed(rule: AppRule, lines: List<String>) {
        val ctx = App.ctx
        if (!visible(ctx, CHANNEL_FAILED)) return
        val text = lines.joinToString("\n")
        val log = PendingIntent.getActivity(ctx, 0, Intent(ctx, LogActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.notice_failed_title, title(rule)))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(log)
            .setAutoCancel(true)
            .addAction(button(ctx, RunNoticeReceiver.RETRY, rule.id, R.string.notice_retry))
            .build()
        nm(ctx).notify(failedTag(rule.id), ID, n)
    }

    fun cancelFailed(ruleId: Long) {
        runCatching { nm(App.ctx).cancel(failedTag(ruleId), ID) }
    }

    /** "Показать уведомление": a rule's own text, each one a notification of its own. */
    fun message(text: String) {
        val ctx = App.ctx
        if (!visible(ctx, CHANNEL_DONE)) return
        val n = Notification.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(text)
            .setAutoCancel(true)
            .build()
        nm(ctx).notify("note:${System.currentTimeMillis()}", ID, n)
    }

    fun cancel(ruleId: Long) {
        runCatching { nm(App.ctx).cancel(tag(ruleId), ID) }
        cancelFailed(ruleId)
    }
}

/** The buttons of [RunNotices]: run what waited, skip it, end a rule that ran, or run it again. */
class RunNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        // "Повторить" leaves the rule's own notice: its window stays open.
        if (intent.action == RETRY) RunNotices.cancelFailed(id) else RunNotices.cancel(id)
        val pending = goAsync()
        val work = App.scope.launch {
            runCatching {
                when (intent.action) {
                    RUN -> AppFreezer.runAsked(id)
                    SKIP -> AppFreezer.skipAsked(id)
                    UNDO -> AppFreezer.releaseNow(id)
                    RETRY -> AppFreezer.retryRun(id)
                }
            }
            Widgets.update()
        }
        App.scope.launch {
            withTimeoutOrNull(9_000L) { work.join() }
            pending.finish()
        }
    }

    companion object {
        const val RUN = "run"
        const val SKIP = "skip"
        const val UNDO = "undo"
        const val RETRY = "retry"
        const val EXTRA_ID = "id"
    }
}
