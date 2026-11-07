package com.netmode.switcher

import android.app.NotificationManager
import android.content.Context

/**
 * A line under each of the app's channels in the system notification settings, saying what
 * switching it off changes. Only channels that already exist are touched; for them Android
 * takes the new description and keeps everything the user set.
 */
object ChannelDescriptions {

    private val ALL = mapOf(
        StatusNotification.CHANNEL to R.string.notif_kind_watch,
        "rule_ask" to R.string.notif_kind_ask,
        "rule_done" to R.string.notif_kind_done,
        "rule_failed" to R.string.notif_kind_failed,
        "tether_limit" to R.string.notif_kind_tether,
        SmsCodes.CHANNEL to R.string.notif_kind_sms,
    )

    fun apply(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (ch in nm.notificationChannels) {
            val text = ALL[ch.id]?.let(ctx::getString) ?: continue
            if (ch.description == text) continue
            ch.description = text
            runCatching { nm.createNotificationChannel(ch) }
        }
    }
}
