package com.netmode.switcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/**
 * "Коды из СМС": a one-time code in an incoming SMS goes straight to the clipboard, with a
 * quiet notification saying so. The SMS itself is read by [SmsCodeReceiver]; codes that do
 * not come as SMS (RCS chats, push codes) can be taken from other apps' notifications.
 *
 * Neither the text nor the code is kept or logged; only who sent it, for the event log.
 */
object SmsCodes {

    const val CHANNEL = "sms_code"
    private const val TAG = "SmsCodes"
    private const val NOTIF_ID = 7301

    /** The same code from the SMS and then from the SMS app's notification is copied once. */
    private const val SAME_CODE_MS = 3 * 60_000L

    /** A notification older than this is an old one being updated, not a new message. */
    private const val FRESH_MS = 2 * 60_000L

    private var lastCode: String? = null
    private var lastAt = 0L

    /** Notification key → the code last seen in it, so its updates do not copy it again. */
    private val seenInNotif = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 50
    }

    fun smsGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * The SMS receiver is switched on only while the option is: otherwise every SMS would
     * start the app's process for nothing.
     */
    fun applyReceiver(ctx: Context = App.ctx) {
        val on = Prefs.smsCodeEnabled
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, SmsCodeReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Grants SMS reception through root or Shizuku, which works where the system dialog is barred. */
    suspend fun grantPrivileged(ctx: Context): Boolean {
        if (smsGranted(ctx)) return true
        runCatching { Privileged.exec("pm grant ${ctx.packageName} ${Manifest.permission.RECEIVE_SMS}") }
        return smsGranted(ctx)
    }

    /** The app-op behind Android 15's hiding of codes from notification listeners. */
    private const val OP_SENSITIVE = "android:receive_sensitive_notifications"

    /**
     * From Android 15 a listener gets "Конфиденциальная информация" in place of any text that
     * holds a code, unless this op is allowed. Before 15 nothing is hidden.
     */
    fun sensitiveAllowed(ctx: Context): Boolean {
        if (Compat.SDK < 35) return true
        return runCatching {
            ctx.getSystemService(android.app.AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP_SENSITIVE, android.os.Process.myUid(), ctx.packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /**
     * Allows the op through root or Shizuku. The system reads it when the listener binds, so the
     * listener is bound again once, after the op is in place (never in a loop: next time it is allowed).
     */
    suspend fun allowSensitive(ctx: Context): Boolean {
        if (sensitiveAllowed(ctx)) return true
        val pkg = ctx.packageName
        runCatching { Privileged.exec("appops set $pkg RECEIVE_SENSITIVE_NOTIFICATIONS allow") }
        if (!sensitiveAllowed(ctx)) return false
        val c = "$pkg/${NotifListener::class.java.name}"
        runCatching { Privileged.exec("cmd notification disallow_listener $c; cmd notification allow_listener $c") }
        return true
    }

    /** Whether [source] (a sender, an app name or a package) is on the user's "never" list. */
    fun excluded(vararg source: String?): Boolean {
        val list = Prefs.smsCodeExclude.map(::norm).filter { it.isNotEmpty() }
        if (list.isEmpty()) return false
        val from = source.filterNotNull().map(::norm).filter { it.isNotEmpty() }
        return list.any { e -> from.any { it.contains(e) } }
    }

    /** Case, spaces, "+" and dashes do not matter: "+7 900 123-45-67" is "79001234567". */
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun onSms(ctx: Context, sender: String?, body: String) {
        if (!Prefs.smsCodeEnabled || excluded(sender)) return
        val code = SmsCodeParser.find(body)
        android.util.Log.d(TAG, "sms from $sender: code ${if (code != null) "found" else "none"}")
        offer(ctx, code ?: return, sender)
    }

    /** The fallback: a code in some other app's notification. */
    fun onNotification(ctx: Context, sbn: StatusBarNotification) {
        if (!Prefs.smsCodeEnabled || !Prefs.smsCodeFromNotif) return
        if (sbn.packageName == ctx.packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (System.currentTimeMillis() - sbn.postTime > FRESH_MS) return
        val x = n.extras ?: return
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(Compat.appInfo(ctx.packageManager, sbn.packageName)).toString()
        }.getOrNull()
        if (excluded(title, label, sbn.packageName)) return
        val texts = buildList {
            lastMessage(x)?.let(::add)
            x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { add(it.toString()) }
            x.getCharSequence(Notification.EXTRA_TEXT)?.let { add(it.toString()) }
        }
        val code = texts.firstNotNullOfOrNull { SmsCodeParser.find(it) } ?: return
        synchronized(seenInNotif) {
            if (seenInNotif[sbn.key] == code) return
            seenInNotif[sbn.key] = code
        }
        offer(ctx, code, title ?: label)
    }

    /** A chat's newest message: in a conversation the plain text may be a summary. */
    @Suppress("DEPRECATION")
    private fun lastMessage(x: android.os.Bundle): String? = runCatching {
        val msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        (msgs.lastOrNull() as? android.os.Bundle)?.getCharSequence("text")?.toString()
    }.getOrNull()

    @Synchronized
    private fun offer(ctx: Context, code: String, from: String?) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAt < SAME_CODE_MS) return
        lastCode = code
        lastAt = now
        android.util.Log.d(TAG, "copying a code from ${from ?: "?"}")
        if (!copy(ctx, code)) return
        scheduleClear(ctx)
        awaitPaste(code)
        notifyCopied(ctx, code, from)
        Prefs.log("Код из сообщения скопирован" + (from?.let { " ($it)" } ?: ""))
    }

    /** Puts [code] in the clipboard, marked sensitive so Android 13+ does not show it in the preview. */
    fun copy(ctx: Context, code: String): Boolean = runCatching {
        val clip = ClipData.newPlainText(ctx.getString(R.string.sms_code_clip_label), code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }.onFailure {
        android.util.Log.w(TAG, "clipboard write failed", it)
        Prefs.log("Код не скопирован: ${it.message}")
    }.isSuccess

    /** Choices for "Очищать буфер", in seconds; 0 keeps the code. */
    val CLEAR_CHOICES = listOf(0, 30, 60, 120, 300, 600)

    @Volatile private var clearJob: kotlinx.coroutines.Job? = null

    /**
     * Wipes the code from the clipboard after [Prefs.smsCodeClearSec]. A timer in the process
     * keeps the time (OxygenOS shifts a background app's alarms by tens of seconds); the alarm
     * stays as the fallback for when the process is gone by then.
     */
    fun scheduleClear(ctx: Context) {
        val sec = Prefs.smsCodeClearSec
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        val pi = SmsCodeActionReceiver.intent(ctx, SmsCodeActionReceiver.ACTION_CLEAR, "clear")
        am.cancel(pi)
        clearJob?.cancel()
        if (sec <= 0) return
        val app = ctx.applicationContext
        clearJob = App.scope.launch {
            kotlinx.coroutines.delay(sec * 1000L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { clearNow(app) }
        }
        val at = System.currentTimeMillis() + sec * 1000L
        runCatching {
            if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Past this a code no longer counts as waiting to be pasted. */
    private const val PASTE_WAIT_MS = 10 * 60_000L

    @Volatile private var pasteCode: String? = null
    @Volatile private var pasteUntil = 0L

    fun awaitingPaste(): Boolean = pasteCode != null && System.currentTimeMillis() < pasteUntil

    fun awaitPaste(code: String) {
        if (!Prefs.smsCodeClearOnPaste) return
        pasteCode = code
        pasteUntil = System.currentTimeMillis() + PASTE_WAIT_MS
        AppWatchService.watchText(true)
    }

    private fun stopPasteWatch() {
        pasteCode = null
        AppWatchService.watchText(false)
    }

    /**
     * A field changed somewhere. The code showing up in it means it was pasted (or typed in by
     * hand, which also means it is used): the clipboard is cleared. A password field shows no
     * text, only how much came in at once; the code's length at once is a paste.
     */
    fun onTextChanged(ctx: Context, e: android.view.accessibility.AccessibilityEvent) {
        val code = pasteCode ?: return stopPasteWatch()
        if (System.currentTimeMillis() > pasteUntil) return stopPasteWatch()
        if (e.packageName?.toString() == ctx.packageName) return
        val text = e.text.joinToString("") { it?.toString().orEmpty() }.filter { it.isLetterOrDigit() }
        val pasted = if (e.isPassword) e.addedCount >= code.length else text.contains(code, ignoreCase = true)
        if (!pasted) return
        android.util.Log.d(TAG, "code pasted in ${e.packageName}")
        stopPasteWatch()
        clearNow(ctx, force = true)
    }

    /**
     * Clears the clipboard if it still holds the code. Where Android lets the app look, a clip
     * that is not ours (copied later) is left alone; where it hides the clipboard from a
     * background app, the code's own label and time are all there is to go by.
     */
    fun clearNow(ctx: Context, force: Boolean = false) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        if (force) {
            clearJob?.cancel()
            runCatching { cm.clearPrimaryClip() }
            cancelNotice(ctx)
            ctx.getSystemService(android.app.AlarmManager::class.java)
                .cancel(SmsCodeActionReceiver.intent(ctx, SmsCodeActionReceiver.ACTION_CLEAR, "clear"))
            return
        }
        stopPasteWatch()
        clearJob?.cancel()
        ctx.getSystemService(android.app.AlarmManager::class.java)
            .cancel(SmsCodeActionReceiver.intent(ctx, SmsCodeActionReceiver.ACTION_CLEAR, "clear"))
        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        val ours = desc == null || desc.label == ctx.getString(R.string.sms_code_clip_label)
        android.util.Log.d(TAG, "clear: description ${if (desc == null) "hidden or empty" else "seen, ours=$ours"}")
        if (!ours) return
        runCatching { cm.clearPrimaryClip() }.onFailure { android.util.Log.w(TAG, "clear failed", it) }
        cancelNotice(ctx)
    }

    private fun channel(nm: NotificationManager, ctx: Context) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.sms_code_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = ctx.getString(R.string.notif_kind_sms) },
            )
        }
    }

    private fun notifyCopied(ctx: Context, code: String, from: String?) {
        if (!Compat.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channel(nm, ctx)
        val title = ctx.getString(R.string.sms_code_copied, code)
        val text = from?.let { ctx.getString(R.string.sms_code_from, it) }
        val public = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.sms_code_copied_public))
            .build()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.sms_code_copy_again),
                SmsCodeActionReceiver.intent(ctx, SmsCodeActionReceiver.ACTION_COPY, code)).build())
        if (from != null) {
            b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.sms_code_never_from, from),
                SmsCodeActionReceiver.intent(ctx, SmsCodeActionReceiver.ACTION_EXCLUDE, from)).build())
        }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
    }

    fun cancelNotice(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}

/** Incoming SMS; enabled only while the option is on (see [SmsCodes.applyReceiver]). */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        // A long SMS comes in parts; put each sender's parts back together before looking.
        parts.filterNotNull().groupBy { it.displayOriginatingAddress }.forEach { (sender, ps) ->
            val body = ps.joinToString("") { it.displayMessageBody.orEmpty() }
            SmsCodes.onSms(context, sender, body)
        }
    }
}

/** The notification's buttons: copy the code again, or never copy from this sender. */
class SmsCodeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getStringExtra(EXTRA_VALUE) ?: return
        when (intent.action) {
            ACTION_COPY -> {
                if (SmsCodes.copy(context, value)) {
                    SmsCodes.scheduleClear(context)
                    SmsCodes.awaitPaste(value)
                    android.widget.Toast.makeText(context, context.getString(R.string.sms_code_copied, value),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_CLEAR -> SmsCodes.clearNow(context)
            ACTION_EXCLUDE -> {
                val list = Prefs.smsCodeExclude
                if (list.none { it.equals(value, ignoreCase = true) }) Prefs.smsCodeExclude = list + value
                SmsCodes.cancelNotice(context)
                android.widget.Toast.makeText(context, context.getString(R.string.sms_code_excluded, value),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_COPY = "com.netmode.switcher.SMS_CODE_COPY"
        const val ACTION_EXCLUDE = "com.netmode.switcher.SMS_CODE_EXCLUDE"
        const val ACTION_CLEAR = "com.netmode.switcher.SMS_CODE_CLEAR"
        private const val EXTRA_VALUE = "value"

        fun intent(ctx: Context, action: String, value: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, SmsCodeActionReceiver::class.java).setAction(action)
                .setData(Uri.parse("netmode://smscode/$action/${Uri.encode(value)}"))
                .putExtra(EXTRA_VALUE, value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
