package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * "Switch Wi-Fi on when this network is nearby", for Wi-Fi rules that switch Wi-Fi off after
 * leaving: with the radio off the phone can never see its home network again by itself.
 *
 * Nearby is judged by cell towers, which the rule learns by itself:
 *  - every tower seen while connected to the network, in any network mode;
 *  - the towers seen in the last [APPROACH_MS] before connecting — the way in, so the next
 *    arrival is noticed a little earlier.
 * Each tower keeps a score of the checks it caused: one that keeps starting checks that find
 * nothing (a big tower also seen from the road past the house) stops counting.
 *
 * Near the towers, with Wi-Fi off because a rule switched it off on leaving, the phone checks
 * for the network: a background scan when the system's "Wi-Fi scanning" happens to be on,
 * otherwise Wi-Fi on for up to [PROBE_MS] and back off if the network is not there. A failed
 * check backs off — 5, 10, 20, 30 minutes — until the phone leaves the towers or connects.
 */
object WifiWake {

    private const val TAG = "netmode"
    private const val LEFT_MS = 10 * 60_000L
    private const val PROBE_MS = 45_000L
    private const val BACKOFF_BASE_MS = 5 * 60_000L
    private const val BACKOFF_MAX_MS = 30 * 60_000L
    private const val APPROACH_MS = 3 * 60_000L
    private const val MAX_CELLS = 60
    private val busy = Mutex()

    /** Towers seen lately, with when; the way in is learned from it on connecting. */
    private val recent = LinkedHashMap<String, Long>()

    fun rules(): List<AppRule> =
        Prefs.appRules.filter { it.enabled && it.trigger == Trigger.WIFI && it.wakeWifi && !it.ssid.isNullOrBlank() }

    fun needed(): Boolean = rules().isNotEmpty()

    fun onWifi(): Boolean = wifiConnected()

    /** Called with every tower read. */
    suspend fun onTowers(now: Set<String>) {
        val t = SystemClock.elapsedRealtime()
        synchronized(recent) {
            now.forEach { recent[it] = t }
            recent.entries.removeAll { t - it.value > APPROACH_MS * 2 }
        }
        val rules = rules()
        if (rules.isEmpty()) return cancelTick()
        if (wifiConnected()) {
            Prefs.wakeArmed = false
            Prefs.wakeFails = 0
            Prefs.lastSsid?.let { ssid -> rules.filter { it.matchesSsid(ssid) }.forEach { learn(it, now) } }
            return cancelTick()
        }
        val near = rules.mapNotNull { r ->
            val hits = now.filter { it in r.cells && !noisy(r, it) }
            if (hits.isEmpty()) null else r to hits
        }
        if (near.isEmpty()) {
            // Left the area: the next arrival starts without the backoff of this one.
            Prefs.wakeFails = 0
            return cancelTick()
        }
        // Near home with the same towers for a while, e.g. back from the shop round the corner:
        // no tower change will come, so look again on a timer.
        scheduleTick()
        tryWake(near)
    }

    /**
     * Just connected to [ssid]: the towers of the last few minutes were the way in. Called by
     * the Wi-Fi rules on every connect.
     */
    fun onConnected(ssid: String) {
        Prefs.wakeArmed = false
        Prefs.wakeFails = 0
        val t = SystemClock.elapsedRealtime()
        val approach = synchronized(recent) { recent.filterValues { t - it <= APPROACH_MS }.keys.toSet() }
        if (approach.isEmpty()) return
        rules().filter { it.matchesSsid(ssid) }.forEach { learn(it, approach) }
    }

    private fun learn(rule: AppRule, towers: Set<String>) {
        val fresh = towers - rule.cells
        if (fresh.isEmpty() || rule.cells.size >= MAX_CELLS) return
        val add = fresh.take(MAX_CELLS - rule.cells.size)
        Prefs.appRules = Prefs.appRules.map { if (it.id == rule.id) it.copy(cells = it.cells + add) else it }
        Log.i(TAG, "wifi wake: learned ${add.size} towers for ${rule.ssid}")
    }

    // --- tower scores ------------------------------------------------------------------------

    private fun key(rule: AppRule, cell: String) = "${rule.id}|$cell"

    private fun scores(): JSONObject = runCatching { JSONObject(Prefs.wakeScores) }.getOrDefault(JSONObject())

    /** A tower whose checks mostly found nothing: at least 3 misses and twice as many as hits. */
    private fun noisy(rule: AppRule, cell: String): Boolean {
        val s = scores().optJSONArray(key(rule, cell)) ?: return false
        val hits = s.optInt(0)
        val misses = s.optInt(1)
        return misses >= 3 && misses > 2 * hits
    }

    private fun score(rule: AppRule, cells: List<String>, found: Boolean) {
        val o = scores()
        for (c in cells) {
            val k = key(rule, c)
            val a = o.optJSONArray(k) ?: org.json.JSONArray().put(0).put(0)
            val i = if (found) 0 else 1
            a.put(i, a.optInt(i) + 1)
            o.put(k, a)
        }
        Prefs.wakeScores = o.toString()
    }

    /** Clears what a rule learned, towers and scores; for "forget towers" in the editor. */
    fun forget(ruleId: Long) {
        val o = scores()
        o.keys().asSequence().toList().filter { it.startsWith("$ruleId|") }.forEach { o.remove(it) }
        Prefs.wakeScores = o.toString()
    }

    // --- the check ---------------------------------------------------------------------------

    private suspend fun tryWake(near: List<Pair<AppRule, List<String>>>) {
        val ctx = App.ctx
        val wm = ctx.getSystemService(WifiManager::class.java)
        // Only Wi-Fi a rule switched off on leaving; never what the user switched off.
        if (wm.isWifiEnabled || !Prefs.wakeArmed) return
        if (android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1) return
        // The hotspot shares the radio; switching Wi-Fi on may take it down.
        if (HotspotControl.lastOn) return
        val now = System.currentTimeMillis()
        // Just left, still under the home towers: Wi-Fi was switched off on purpose.
        if (now - Prefs.selfWifiAt < LEFT_MS) return
        val backoff = (BACKOFF_BASE_MS shl Prefs.wakeFails.coerceAtMost(3)).coerceAtMost(BACKOFF_MAX_MS)
        if (now - Prefs.wakeAt < backoff) return
        // Another rule holds Wi-Fi off right now (the night rule, the car): leave it off.
        val open = Prefs.openWindows.keys
        if (Prefs.appRules.any { it.id in open && it.wifi == Toggle.OFF }) return
        if (!busy.tryLock()) return
        // The whole check runs awake: in a pocket the phone would otherwise doze through the
        // 45 seconds and leave Wi-Fi on for much longer.
        val wake = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmode:wifiwake")
        wake.acquire(PROBE_MS + 15_000L)
        try {
            Prefs.wakeAt = now
            val names = near.mapNotNull { it.first.ssid }
            val found = if (scanAlways()) scanFor(names) != null else probe()
            near.forEach { (rule, cells) -> score(rule, cells, found) }
            if (found) {
                Prefs.log("рядом с «${Prefs.lastSsid ?: names.first()}» — Wi-Fi включён")
            } else {
                Prefs.wakeFails = Prefs.wakeFails + 1
                Log.i(TAG, "wifi wake: ${names.joinToString()} not in range (miss ${Prefs.wakeFails})")
            }
        } finally {
            if (wake.isHeld) wake.release()
            busy.unlock()
        }
    }

    /** Wi-Fi on; true when it joins a network within [PROBE_MS], otherwise back off. */
    private suspend fun probe(): Boolean {
        Prefs.selfWifiAt = System.currentTimeMillis()
        Privileged.exec("cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || svc wifi enable")
        val deadline = System.currentTimeMillis() + PROBE_MS
        while (System.currentTimeMillis() < deadline) {
            delay(3_000)
            if (wifiConnected()) return true
        }
        Prefs.selfWifiAt = System.currentTimeMillis()
        Privileged.exec("cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || svc wifi disable")
        return false
    }

    private fun scanAlways(): Boolean =
        android.provider.Settings.Global.getInt(App.ctx.contentResolver, "wifi_scan_always_enabled", 0) == 1

    /** A scan with Wi-Fi off (system scanning mode); switches Wi-Fi on if a wanted network is seen. */
    private suspend fun scanFor(ssids: List<String>): String? {
        Privileged.exec("cmd wifi start-scan >/dev/null 2>&1")
        delay(5_000)
        val out = Privileged.exec("cmd wifi list-scan-results 2>/dev/null")
        val seen = ssids.firstOrNull { s -> out.lineSequence().any { it.contains(s) } } ?: return null
        Prefs.selfWifiAt = System.currentTimeMillis()
        Privileged.exec("cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || svc wifi enable")
        return seen
    }

    private fun wifiConnected(): Boolean = runCatching {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 600, Intent(ctx, WifiWakeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleTick() {
        val ctx = App.ctx
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + BACKOFF_BASE_MS
        if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx))
    }

    private fun cancelTick() {
        App.ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(App.ctx))
    }
}

/** Near home with Wi-Fi off: reads the towers again; see [WifiWake]. */
class WifiWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConditionWatch.refreshTowers()
    }
}
