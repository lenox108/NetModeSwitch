package com.netmode.switcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the hotspot carried, by day and month. Interface counters, so the phone's own traffic to clients counts too. */
internal data class TrafficBook(
    val day: String = "",
    val dayBytes: Long = 0,
    val month: String = "",
    val monthBytes: Long = 0,
    val identity: String? = null,
    val total: Long = 0,
) {
    /**
     * A sample of the counters on [date]. The same interface adds what it grew by, so a process
     * restart loses nothing. A new or reset one starts from zero and all it shows is new. The very
     * first sample has no base: a hotspot already running may have carried that before.
     */
    fun add(identity: String, total: Long, date: LocalDate): TrafficBook {
        val delta = when {
            this.identity == null -> 0L
            this.identity == identity && total >= this.total -> total - this.total
            else -> total
        }
        val d = date.toString()
        val m = d.take(7)
        return copy(
            day = d, dayBytes = (if (day == d) dayBytes else 0) + delta,
            month = m, monthBytes = (if (month == m) monthBytes else 0) + delta,
            identity = identity, total = total,
        )
    }

    /** The two totals as of [date]: yesterday's no longer count as today's when nothing ran since. */
    fun today(date: LocalDate) = if (day == date.toString()) dayBytes else 0L
    fun thisMonth(date: LocalDate) = if (month == date.toString().take(7)) monthBytes else 0L

    /** Whether this sample is the one that went over [limit] bytes (0: no limit). */
    fun crossed(before: Long, limit: Long) = limit > 0 && before < limit && monthBytes >= limit
}

internal object TetherTraffic {

    private const val SAMPLE_MS = 60_000L
    private const val CHANNEL = "tether_limit"
    private const val MB = 1024L * 1024L
    private val sp by lazy { App.ctx.getSharedPreferences("tether_traffic", Context.MODE_PRIVATE) }

    var limitMb: Long
        get() = sp.getLong("limit_mb", 0)
        set(value) = sp.edit().putLong("limit_mb", value.coerceAtLeast(0)).apply()

    fun book() = TrafficBook(
        sp.getString("day", "").orEmpty(), sp.getLong("day_bytes", 0),
        sp.getString("month", "").orEmpty(), sp.getLong("month_bytes", 0),
        sp.getString("identity", null), sp.getLong("total", 0),
    )

    private fun save(b: TrafficBook) = sp.edit().putString("day", b.day).putLong("day_bytes", b.dayBytes)
        .putString("month", b.month).putLong("month_bytes", b.monthBytes)
        .putString("identity", b.identity).putLong("total", b.total).apply()

    private var job: Job? = null
    private var watching = false

    private val tether = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = !intent.getStringArrayListExtra(HotspotCondition.ACTIVE).isNullOrEmpty()
            if (on) start() else stop()
        }
    }

    /** Sticky, so a hotspot already up is found at once. No shell runs while it is off. */
    @Synchronized
    fun watch() {
        if (watching) return
        watching = true
        App.ctx.registerReceiver(tether, IntentFilter("android.net.conn.TETHER_STATE_CHANGED"), Context.RECEIVER_EXPORTED)
    }

    @Synchronized
    private fun start() {
        if (job?.isActive == true) return
        job = App.scope.launch {
            while (true) {
                runCatching { sample() }
                delay(SAMPLE_MS)
            }
        }
    }

    @Synchronized
    private fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sample() {
        val counters = counters() ?: return
        val date = LocalDate.now()
        val old = book()
        val next = old.add(counters.identity, counters.received + counters.sent, date)
        save(next)
        if (next.crossed(old.thisMonth(date), limitMb * MB)) warn(next)
    }

    private suspend fun counters(): ApCounters? {
        val ifaces = TetherRoute.tetherInterfaces().filter { NetworkScripts.validInterface(it) && it.matches(Regex("^(wlan|softap|ap_br|swlan).*")) }
        if (ifaces.isEmpty()) return null
        val out = Privileged.exec(ifaces.joinToString("\n") { i ->
            "echo @IF=$i; cat /sys/class/net/$i/ifindex /sys/class/net/$i/statistics/rx_bytes /sys/class/net/$i/statistics/tx_bytes"
        }, 5_000)
        return parse(out, ifaces.size)
    }

    fun parse(out: String, expected: Int): ApCounters? {
        val entries = out.split("@IF=").drop(1).mapNotNull { part ->
            val lines = part.trim().lines()
            val index = lines.getOrNull(1)?.trim()?.toLongOrNull()
            val rx = lines.getOrNull(2)?.trim()?.toLongOrNull()
            val tx = lines.getOrNull(3)?.trim()?.toLongOrNull()
            if (index == null || rx == null || tx == null || rx < 0 || tx < 0) null else ApCounters("${lines[0].trim()}:$index", rx, tx)
        }
        if (entries.isEmpty() || entries.size != expected) return null
        return ApCounters(entries.joinToString(",") { it.identity }, entries.sumOf { it.received }, entries.sumOf { it.sent })
    }

    fun label(bytes: Long): String = when {
        bytes >= 1024 * MB -> "%.2f ГБ".format(bytes.toDouble() / (1024 * MB))
        else -> "${bytes / MB} МБ"
    }

    private fun warn(b: TrafficBook) {
        val ctx = App.ctx
        val text = ctx.getString(R.string.traffic_limit_text, label(b.monthBytes), limitMb)
        Prefs.log("раздача: превышен лимит — за месяц ${label(b.monthBytes)} из $limitMb МБ")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!Compat.granted(ctx, android.Manifest.permission.POST_NOTIFICATIONS) || !nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.traffic_limit_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(ctx, 0, Intent(ctx, HotspotActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(CHANNEL, 1, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.traffic_limit_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
