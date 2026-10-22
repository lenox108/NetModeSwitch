package com.netmode.switcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Rules started and ended from outside: Tasker, Home Assistant, adb, an NFC tag.
 *
 *  - a link, `netmode://run?id=<rule>&do=toggle|on|off&key=<key>`, opened by Tasker or written
 *    to an NFC tag — Android opens it on a tap;
 *  - a broadcast, action [ACTION] with extras `id`, `do`, `key`, for Tasker's "Send Intent".
 *
 * Both are open to any app on the phone, so each carries the key only the app's owner sees
 * ([Prefs.apiKey]); the link is not offered to the browser, so no web page can open it. A
 * press goes through the rules widget's button, as a scene's shortcut does.
 */
object Api {

    const val ACTION = "com.netmode.switcher.RUN"
    private const val TAG = "netmode"

    fun link(ruleId: Long, mode: String = "toggle") = "netmode://run?id=$ruleId&do=$mode&key=${Prefs.apiKey}"

    /** Does what was asked; returns what to tell. */
    fun handle(ctx: Context, id: Long?, key: String?, mode: String?): String {
        val expected = Prefs.apiKey
        if (key == null || !java.security.MessageDigest.isEqual(key.toByteArray(), expected.toByteArray())) {
            Log.w(TAG, "api: wrong key for rule $id")
            return ctx.getString(R.string.api_bad_key)
        }
        val rule = Prefs.appRules.find { it.id == id } ?: return ctx.getString(R.string.scene_gone)
        val name = rule.name ?: rule.timeLabel
        val active = Want.rules[rule.id] ?: (AppFreezer.isActive(rule) || rule.id in Prefs.pendingStarts)
        val want = when (mode) { "on" -> true; "off" -> false; else -> !active }
        if (want == active) return ctx.getString(R.string.api_already, name)
        if (want && !rule.enabled) return ctx.getString(R.string.scene_disabled, name)
        WidgetActionReceiver.rule(ctx, rule.id).send()
        Prefs.log("(«$name»): извне — ${if (want) "включить" else "выключить"}")
        return ctx.getString(if (want) R.string.scene_on else R.string.scene_off, name)
    }
}

/** The link: from Tasker, or from an NFC tag that holds it. Nothing on screen but a toast. */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val text = if (uri == null) null else Api.handle(
            applicationContext, uri.getQueryParameter("id")?.toLongOrNull(),
            uri.getQueryParameter("key"), uri.getQueryParameter("do"),
        )
        text?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
        finish()
    }
}

/** The broadcast, for Tasker's "Send Intent"; nothing on screen, the log says what happened. */
class ApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Api.ACTION) return
        val id = intent.getStringExtra("id")?.toLongOrNull() ?: intent.getLongExtra("id", 0L).takeIf { it != 0L }
        Api.handle(context.applicationContext, id, intent.getStringExtra("key"), intent.getStringExtra("do"))
    }
}
