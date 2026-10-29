package com.netmode.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** How a freezer app is frozen. DEFAULT follows the freezer's own setting ([Prefs.iceDefaultMode]). */
enum class IceMode { DEFAULT, SOFT, DEEP }

/** One app in the freezer ("Морозилка"): how it freezes, and the state it is in. */
data class IceApp(
    val key: String,
    val mode: IceMode = IceMode.DEFAULT,
    /** Minutes after leaving the app before it freezes again; null: the freezer's default. */
    val delay: Int? = null,
    val group: String? = null,
    /** How it is frozen right now; null while it is thawed. */
    val frozenAs: IceMode? = null,
    /** When it was thawed (wall clock); 0 while frozen. */
    val thawedAt: Long = 0L,
    /** A rule's window that thawed it: it stays thawed until the window closes. 0: none. */
    val heldBy: Long = 0L,
    /** Last launched through the freezer, for the app icon's menu. */
    val launchedAt: Long = 0L,
    /** 0: normal automation, -1: until manual freeze, positive: wall-clock deadline. */
    val keepUntil: Long = 0L,
    val favorite: Boolean = false,
    /** Changes to runtime state invalidate an older undo even after a freeze/thaw round trip. */
    val revision: Long = 0L,
    /** When added to the freezer (wall clock); 0 for entries saved by older versions. */
    val addedAt: Long = 0L,
) {
    val frozen: Boolean get() = frozenAs != null

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("mode", mode.name).put("delay", delay ?: JSONObject.NULL)
        .put("group", group ?: JSONObject.NULL).put("as", frozenAs?.name ?: JSONObject.NULL)
        .put("thawed", thawedAt).put("held", heldBy).put("launched", launchedAt)
        .put("keepUntil", keepUntil).put("favorite", favorite).put("revision", revision)
        .put("addedAt", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = IceApp(
            key = o.getString("key"),
            mode = runCatching { IceMode.valueOf(o.getString("mode")) }.getOrDefault(IceMode.DEFAULT),
            delay = if (o.isNull("delay") || !o.has("delay")) null else o.getInt("delay"),
            group = o.optString("group").takeIf { !o.isNull("group") && it.isNotEmpty() },
            frozenAs = if (o.isNull("as") || !o.has("as")) null else runCatching { IceMode.valueOf(o.getString("as")) }.getOrNull(),
            thawedAt = o.optLong("thawed", 0L),
            heldBy = o.optLong("held", 0L),
            launchedAt = o.optLong("launched", 0L),
            keepUntil = o.optLong("keepUntil", 0L).coerceAtLeast(-1L),
            favorite = o.optBoolean("favorite", false),
            revision = o.optLong("revision", 0L),
            addedAt = o.optLong("addedAt", 0L).coerceAtLeast(0L),
        )
    }
}

/**
 * A rule's "Морозилка" action: thaw or freeze freezer apps, a whole group ("g:<name>") or one
 * app (its key). A thaw by a rule with a window lasts the window: the apps are held thawed and
 * freeze again once it closes.
 */
data class IceAction(val thaw: Boolean, val targets: List<String>) {

    fun toJson(): JSONObject = JSONObject().put("thaw", thaw).put("t", JSONArray(targets))

    /** "разморозить: «Работа», Telegram" */
    val label: String
        get() {
            val groups = targets.filter { it.startsWith(GROUP) }.map { "«${it.removePrefix(GROUP)}»" }
            val apps = AppPickerActivity.labels(targets.filterNot { it.startsWith(GROUP) })
            return (if (thaw) "разморозить: " else "заморозить: ") + (groups + apps).joinToString(", ")
        }

    companion object {
        const val GROUP = "g:"

        fun fromJson(o: JSONObject): IceAction? {
            val a = o.optJSONArray("t") ?: return null
            val targets = (0 until a.length()).map { a.getString(it) }
            return IceAction(o.optBoolean("thaw", true), targets).takeIf { targets.isNotEmpty() }
        }
    }
}

/**
 * The freezer: apps kept frozen until they are wanted, as IceBox does. A tap on the app's
 * shortcut, in the "Морозилка" folder or the shade tile thaws it and opens it at once; once it
 * has been left for its delay it freezes again (see [IceWatch]).
 *
 * Two ways of freezing, both plain shell through root or Shizuku:
 *  - soft: suspended ([SuspendCli]) and stopped. The icon stays, greyed; the app gets no
 *    background work and no push. Tapping the icon itself brings up the system's "app paused"
 *    dialog, which [AppWatchService] can answer (see [fromSuspendDialog]).
 *  - deep: `pm disable-user` — the app is switched off and its icon leaves the launcher. It is
 *    opened only from the freezer's own shortcuts, folder or tile.
 *
 * The freezer keeps its own record; the rules keep theirs ([AppFreezer]). A rule stopping a
 * frozen app finds it suspended already and leaves it so at its end ([holdsSuspended]).
 */
object Freezer {

    private const val TAG = "netmode"

    const val NEVER = -1

    /** The delays the screens offer, in minutes. */
    val DELAYS = listOf(0, 1, 5, 15, 30, NEVER)

    private val lock = Mutex()

    /** A busy app (music, navigation) is looked at again after this. */
    private const val BUSY_RECHECK_MS = 2 * 60_000L

    /** Usage events read back at most this far. */
    private const val USAGE_BACK_MS = 6 * 3_600_000L

    /** Last seen on screen while thawed, per key (wall clock); in memory, the usage events back it. */
    private val seenAt = ConcurrentHashMap<String, Long>()

    /** Not before this: an app that was busy or on screen when its time came. */
    private val retryAt = ConcurrentHashMap<String, Long>()
    enum class WaitReason { BUSY, CALL, ON_SCREEN, NO_ACCESS, FAILED }
    private val waiting = ConcurrentHashMap<String, WaitReason>()
    @Volatile private var foregroundKey: String? = null

    fun waitReason(key: String): WaitReason? = waiting[key]
    fun isOnScreen(key: String): Boolean = foregroundKey == key
    fun deadline(a: IceApp): Long? = IcePolicy.deadline(a, Prefs.iceDefaultDelay, seenAt[a.key] ?: 0L)
    fun undoAvailable(): Boolean = Prefs.iceUndo?.let { it.usable(System.currentTimeMillis()) && it.entries.isNotEmpty() } == true

    val apps: List<IceApp> get() = Prefs.iceApps

    fun find(key: String): IceApp? = apps.find { it.key == key }

    fun modeOf(a: IceApp): IceMode = if (a.mode == IceMode.DEFAULT) Prefs.iceDefaultMode else a.mode

    fun delayOf(a: IceApp): Int = a.delay ?: Prefs.iceDefaultDelay

    fun protectedKeys(now: Long = System.currentTimeMillis()): Set<String> =
        apps.filter { IcePolicy.protected(it, now) }.map { it.key }.toSet()

    /** Shared chip order, including groups saved by older versions only on their apps. */
    fun groups(): List<String> = IceGroups.ordered(Prefs.iceGroups, apps)

    fun orderGroups(order: List<String>) {
        Prefs.iceGroups = IceGroups.reconcile(order, groups())
    }

    /** The freezer apps a rule's targets name: every app of a group, or the app itself. */
    fun keysOf(targets: List<String>): List<String> {
        val list = apps
        return targets.flatMap { t ->
            if (t.startsWith(IceAction.GROUP)) list.filter { it.group == t.removePrefix(IceAction.GROUP) }.map { it.key }
            else listOfNotNull(t.takeIf { k -> list.any { it.key == k } })
        }.distinct()
    }

    /** Soft-frozen by the freezer: a rule's end must not unsuspend it (see AppFreezer.restore). */
    fun holdsSuspended(key: String): Boolean =
        Prefs.iceRespectRules && apps.any { it.key == key && it.frozenAs == IceMode.SOFT }

    fun label(key: String): String = AppPickerActivity.labels(listOf(key)).first()

    /** "Telegram: заморожено мягко" style status lines are the screens' business; this is the count. */
    fun thawedCount(): Int = apps.count { !it.frozen }

    @Synchronized private fun edit(change: (List<IceApp>) -> List<IceApp>) {
        val old = Prefs.iceApps
        val byKey = old.associateBy { it.key }
        val next = change(old).map { a ->
            val previous = byKey[a.key]
            if (previous != null && !IcePolicy.sameState(previous, a)) a.copy(revision = previous.revision + 1) else a
        }
        if (next != old) Prefs.iceApps = next
    }

    private fun editApp(key: String, change: (IceApp) -> IceApp) = edit { list -> list.map { if (it.key == key) change(it) else it } }

    // --- settings of one app: no shell, except a frozen app moving to the other way ----------

    suspend fun setMode(key: String, mode: IceMode) = setModes(listOf(key), mode)

    fun setDelay(key: String, delay: Int?) = setDelays(listOf(key), delay)

    fun setGroup(key: String, group: String?) = setGroups(listOf(key), group)

    fun setFavorite(keys: Collection<String>, favorite: Boolean) = edit { list ->
        list.map { if (it.key in keys) it.copy(favorite = favorite) else it }
    }

    /** The same for several apps at once (the freezer screen's selection). */
    suspend fun setModes(keys: Collection<String>, mode: IceMode) {
        edit { list -> list.map { if (it.key in keys) it.copy(mode = mode) else it } }
        refreeze("режим изменён")
    }

    fun setDelays(keys: Collection<String>, delay: Int?) {
        edit { list -> list.map { if (it.key in keys) it.copy(delay = delay) else it } }
    }

    fun setGroups(keys: Collection<String>, group: String?) {
        edit { list -> list.map { if (it.key in keys) it.copy(group = group) else it } }
    }

    fun addGroup(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in groups()) return
        Prefs.iceGroups = groups() + n
    }

    fun removeGroup(name: String) {
        Prefs.iceGroups = Prefs.iceGroups - name
        edit { list -> list.map { if (it.group == name) it.copy(group = null) else it } }
    }

    /** Frozen apps whose way of freezing has changed (their own, or the default) go over to it. */
    suspend fun refreeze(why: String) = lock.withLock {
        val moved = apps.filter { it.frozen && it.frozenAs != modeOf(it) }.map { it.key }
        if (moved.isNotEmpty() && Privileged.available()) freezeLocked(moved, why)
    }

    // --- the work -----------------------------------------------------------------------------

    /** Puts [keys] into the freezer and freezes them. Returns how many were added. */
    suspend fun add(keys: List<String>, why: String = "добавлено"): Int {
        val have = apps.map { it.key }.toSet()
        val guarded = AppFreezer.protectedPackages()
        val fresh = keys.filter { it !in have && AppKey.valid(it) && AppKey.pkg(it) !in guarded }
        if (fresh.isEmpty()) return 0
        val now = System.currentTimeMillis()
        edit { it + fresh.map { k -> IceApp(k, thawedAt = now, addedAt = now) } }
        ConditionWatch.grantUsageAccess()
        lock.withLock { if (Privileged.available()) freezeLocked(fresh, why) }
        ensureInstant()
        return fresh.size
    }

    /** Takes [keys] out of the freezer, thawed. */
    suspend fun remove(keys: List<String>) = lock.withLock {
        val frozen = apps.filter { it.key in keys && it.frozen }
        if (frozen.isNotEmpty() && Privileged.available()) thawLocked(frozen.map { it.key }, "убрано из морозилки")
        edit { list -> list.filterNot { it.key in keys } }
        keys.forEach { seenAt -= it; retryAt -= it }
        IceShortcuts.forget(App.ctx, keys)
    }

    suspend fun freeze(keys: List<String>, why: String): Int = manual(keys, true, why).changed

    /** Thaws [keys]; [hold] — the rule whose window holds them thawed. */
    suspend fun thaw(keys: List<String>, why: String, hold: Long = 0L): Boolean = lock.withLock {
        if (!Privileged.available()) return@withLock false
        val done = thawLocked(keys, why, hold)
        keys.all { it in done }
    }

    /** Everything thawed is frozen, rules' holds too: the user asked. Returns how many. */
    suspend fun freezeAll(why: String): Int = lock.withLock {
        val keys = apps.map { it.key }
        if (keys.isEmpty()) return@withLock 0
        manualLocked(keys, true, why).changed
    }

    data class ToggleResult(val freeze: Boolean, val requested: Int, val changed: Int, val undoAt: Long? = null)

    /** minutes: null uses existing automation, -1 holds until manual freeze, positive is a timer. */
    suspend fun manual(keys: List<String>, freeze: Boolean, why: String, minutes: Int? = null): ToggleResult =
        lock.withLock { manualLocked(keys, freeze, why, minutes) }

    private suspend fun manualLocked(keys: List<String>, freeze: Boolean, why: String, minutes: Int? = null): ToggleResult {
        syncLocked(boot = false)
        val before = apps.filter { it.key in keys && (!freeze || !it.frozen) }
        if (before.isEmpty()) return ToggleResult(freeze, 0, 0)
        if ((freeze || before.any { it.frozen }) && !Privileged.available()) {
            before.forEach { waiting[it.key] = WaitReason.NO_ACCESS }
            return ToggleResult(freeze, before.size, -1)
        }
        val wanted = before.map { it.key }
        val count = if (freeze) freezeLocked(wanted, why) else {
            val done = thawLocked(wanted, why)
            if (minutes != null) {
                val until = if (minutes < 0) -1L else System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
                edit { list -> list.map { if (it.key in done) it.copy(keepUntil = until) else it } }
            }
            done.size
        }
        val undoAt = remember(before)
        IceWatch.sync()
        return ToggleResult(freeze, before.size, count, undoAt)
    }

    private fun remember(before: List<IceApp>): Long? {
        val after = apps.associateBy { it.key }
        val entries = before.mapNotNull { b -> after[b.key]?.takeUnless { IcePolicy.sameState(b, it) }?.let { IceUndoEntry(b, it) } }
        if (entries.isEmpty()) return null
        val saved = IceUndo(System.currentTimeMillis(), entries)
        Prefs.iceUndo = saved
        return saved.at
    }

    suspend fun resumeAuto(keys: List<String>) = lock.withLock {
        val now = System.currentTimeMillis()
        edit { list -> list.map { if (it.key in keys && !it.frozen) it.copy(keepUntil = 0L, thawedAt = now) else it } }
        keys.forEach { retryAt -= it; waiting -= it }
        IceWatch.sync()
    }

    data class UndoResult(val restored: Int, val skipped: Int, val failed: Int, val noAccess: Boolean = false)

    suspend fun undo(expectedAt: Long? = null): UndoResult = lock.withLock {
        val saved = Prefs.iceUndo ?: return@withLock UndoResult(0, 0, 0)
        if (expectedAt != null && saved.at != expectedAt) return@withLock UndoResult(0, 0, 0)
        if (!saved.usable(System.currentTimeMillis())) { Prefs.iceUndo = null; return@withLock UndoResult(0, saved.entries.size, 0) }
        syncLocked(boot = false)
        val current = apps.associateBy { it.key }
        val use = lastUse(apps.filter { !it.frozen })
        val eligible = saved.entries.filter { e ->
            current[e.after.key]?.let { e.eligible(it, saved.at, maxOf(seenAt[it.key] ?: 0L, use[it.key]?.first ?: 0L)) } == true
        }
        if (eligible.isEmpty()) { Prefs.iceUndo = null; return@withLock UndoResult(0, saved.entries.size, 0) }
        val needsShell = eligible.any { it.before.frozenAs != it.after.frozenAs }
        if (needsShell && !Privileged.available()) return@withLock UndoResult(0, saved.entries.size - eligible.size, eligible.size, true)
        val freeze = eligible.filter { it.before.frozen }
        val thaw = eligible.filterNot { it.before.frozen }
        val done = mutableSetOf<String>()
        if (freeze.isNotEmpty()) {
            freezeLocked(freeze.map { it.before.key }, "отмена", freeze.associate { it.before.key to it.before.frozenAs!! })
            val state = apps.associateBy { it.key }
            done += freeze.filter { state[it.before.key]?.frozenAs == it.before.frozenAs }.map { it.before.key }
        }
        if (thaw.isNotEmpty()) done += thawLocked(thaw.map { it.before.key }, "отмена")
        val prior = eligible.associateBy { it.before.key }
        val open = Prefs.openWindows.keys
        edit { list -> list.map { a ->
            val b = prior[a.key]?.before
            if (a.key !in done || b == null) a else a.copy(thawedAt = b.thawedAt,
                keepUntil = b.keepUntil, heldBy = b.heldBy.takeIf { it in open } ?: 0L)
        } }
        done.forEach { waiting -= it; retryAt -= it }
        val retry = eligible.filterNot { it.before.key in done }
        Prefs.iceUndo = saved.copy(entries = retry).takeIf { retry.isNotEmpty() }
        IceWatch.sync()
        UndoResult(done.size, saved.entries.size - eligible.size, retry.size)
    }

    /** Choose and perform the tile's action under the same lock as rules and auto-freezing. */
    suspend fun toggleAll(why: String): ToggleResult = lock.withLock {
        syncLocked(boot = false)
        val list = apps
        val freeze = IceTileState.of(list).freezeOnClick
        val keys = list.filter { it.frozen != freeze }.map { it.key }
        if (keys.isEmpty()) return@withLock ToggleResult(freeze, 0, 0)
        manualLocked(keys, freeze, why)
    }

    /**
     * Thaws [key] if it is frozen and opens it, in one shell call: what a shortcut, the folder,
     * the tile or the app's own icon do. False without root or Shizuku.
     */
    suspend fun launch(key: String, why: String): Boolean = lock.withLock {
        val a = apps.find { it.key == key } ?: return@withLock false
        if (!Privileged.available()) return@withLock false
        val script = (if (a.frozen) thawScript(listOf(a)) else "") + launchScript(key)
        val out = runCatching { Privileged.exec(script, timeoutMs = 20_000L) }
            .onFailure { Prefs.log("морозилка ($why): «${label(key)}» не открылось — ${it.message}") }
            .getOrNull() ?: return@withLock false
        val failed = "@F $key" in out
        val now = System.currentTimeMillis()
        if (!failed) {
            seenAt[key] = now
            retryAt -= key
            waiting -= key
            editApp(key) { it.copy(frozenAs = null, thawedAt = if (it.frozen) now else it.thawedAt.takeIf { t -> t > 0 } ?: now, launchedAt = now) }
            Prefs.log("морозилка ($why): «${label(key)}» открыто")
        } else {
            Prefs.log("морозилка ($why): «${label(key)}» не удалось разморозить")
        }
        IceWatch.sync()
        !failed
    }

    /** A rule's start: its action on the freezer. */
    suspend fun ruleStart(rule: AppRule, why: String) {
        val act = rule.ice ?: return
        val keys = keysOf(act.targets)
        if (keys.isEmpty()) {
            Prefs.log("($why): морозилка — нечего ${if (act.thaw) "размораживать" else "замораживать"}")
            return
        }
        lock.withLock {
            if (!Privileged.available()) return@withLock
            if (act.thaw) thawLocked(keys, why, hold = if (rule.restores) rule.id else 0L)
            else freezeLocked(apps.filter { it.key in keys && !IcePolicy.protected(it, System.currentTimeMillis()) }.map { it.key }, why)
        }
    }

    /**
     * A rule's window has closed: what it held thawed freezes again — at once, unless it is on
     * screen or busy, then by its delay as usual.
     */
    suspend fun ruleEnd(ruleId: Long, why: String) {
        val held = lock.withLock {
            val keys = apps.filter { it.heldBy == ruleId }.map { it.key }
            val now = System.currentTimeMillis()
            edit { list -> list.map { if (it.heldBy == ruleId) it.copy(heldBy = 0L,
                keepUntil = if (it.keepUntil == 0L) now else it.keepUntil) else it } }
            keys
        }
        if (held.isNotEmpty()) check(why, force = held.toSet())
    }

    /**
     * Freezes the thawed apps whose time has come: left for their delay — or, with [screenOff],
     * all of them — unless on screen or busy. [force]: these are due now whatever their delay.
     */
    suspend fun check(why: String, screenOff: Boolean = false, force: Set<String> = emptySet()) = lock.withLock {
        val now = System.currentTimeMillis()
        val thawed = apps.filter { a -> !a.frozen && a.heldBy == 0L && a.keepUntil != -1L &&
            (a.key in force || a.keepUntil > 0L || delayOf(a) != NEVER) }
        if (thawed.isEmpty()) return@withLock IceWatch.arm(nextDue(now))
        val used = lastUse(thawed)
        val offNow = screenOff && Prefs.iceScreenOff
        val due = thawed.filter { a ->
            val (lastAt, onScreen) = used[a.key] ?: (0L to false)
            if (lastAt > 0L) seenAt.merge(a.key, lastAt, ::maxOf)
            when {
                IcePolicy.protected(a, now) -> false
                (retryAt[a.key] ?: 0L) > now -> false
                offNow || a.key in force -> true
                onScreen -> { waiting[a.key] = WaitReason.ON_SCREEN; false }
                else -> (deadline(a) ?: Long.MAX_VALUE) <= now
            }
        }
        if (due.isEmpty()) return@withLock IceWatch.arm(nextDue(now))
        if (!Privileged.available()) {
            due.forEach { waiting[it.key] = WaitReason.NO_ACCESS; retryAt[it.key] = now + 60_000L }
            return@withLock IceWatch.arm(nextDue(now))
        }
        // Busy/foreground checks use shell only once the deadline has actually arrived.
        val out = runCatching { Privileged.exec(lookScript(due)) }.getOrElse {
            due.forEach { a -> waiting[a.key] = WaitReason.FAILED; retryAt[a.key] = now + 60_000L }
            IceWatch.arm(nextDue(now))
            return@withLock
        }
        val resumed = out.lineSequence().firstOrNull { it.startsWith("@R ") }.orEmpty()
        val busy = out.lineSequence().filter { it.startsWith("@B ") }.map { it.removePrefix("@B ").trim() }.toSet()
        val call = inCall()
        val ready = due.filter { a ->
            val onScreen = !offNow && onScreen(resumed, a.key)
            val isBusy = Prefs.iceKeepBusy && (call || a.key in busy)
            when {
                onScreen -> { seenAt[a.key] = now; waiting[a.key] = WaitReason.ON_SCREEN }
                isBusy -> { retryAt[a.key] = now + BUSY_RECHECK_MS; waiting[a.key] = if (call) WaitReason.CALL else WaitReason.BUSY }
                else -> waiting.remove(a.key)
            }
            !onScreen && !isBusy
        }
        if (ready.isNotEmpty()) freezeLocked(ready.map { it.key }, why)
        IceWatch.arm(nextDue(System.currentTimeMillis()))
    }

    /** Usage events update the status display too; no root commands until an app is due. */
    fun anyDue(): Boolean {
        val now = System.currentTimeMillis()
        val thawed = apps.filter { IcePolicy.deadline(it, Prefs.iceDefaultDelay) != null }
        if (thawed.isEmpty()) return false
        val used = lastUse(thawed)
        var due = false
        thawed.forEach { a ->
            val (lastAt, onScreen) = used[a.key] ?: (0L to false)
            if (lastAt > 0L) seenAt.merge(a.key, lastAt, ::maxOf)
            if (onScreen) waiting[a.key] = WaitReason.ON_SCREEN
            else if (waiting[a.key] == WaitReason.ON_SCREEN) waiting.remove(a.key)
            if (!onScreen && !IcePolicy.protected(a, now) && (retryAt[a.key] ?: 0L) <= now &&
                (deadline(a) ?: Long.MAX_VALUE) <= now) due = true
        }
        return due
    }

    /** The earliest moment a thawed app may be due; null when none will be. */
    fun nextDue(now: Long = System.currentTimeMillis()): Long? = apps
        .mapNotNull { a -> deadline(a)?.let { maxOf(it, retryAt[a.key] ?: 0L) } }
        .minOrNull()?.coerceAtLeast(now + 10_000L)

    /**
     * Something came to the front ([AppWatchService] or the look every few seconds). A thawed
     * app is seen in use; a frozen one on screen was let out past the freezer — "unpause" in
     * the system's dialog, or by hand in the settings — and its delay now applies.
     */
    fun noteForeground(pkg: String) {
        val now = System.currentTimeMillis()
        val list = apps
        foregroundKey = list.firstOrNull { AppKey.pkg(it.key) == pkg && !AppKey.isClone(it.key) }?.key
        list.filter { AppKey.pkg(it.key) == pkg && !AppKey.isClone(it.key) }.forEach { a ->
            if (!a.frozen) seenAt[a.key] = now
            else if (a.frozenAs == IceMode.SOFT && real(a.key) == null) {
                editApp(a.key) { it.copy(frozenAs = null, thawedAt = now) }
                seenAt[a.key] = now
                Prefs.log("морозилка: «${label(a.key)}» разморожено в обход морозилки")
            }
        }
    }

    /**
     * The system's "app paused" dialog is on screen ([AppWatchService]): the freezer app it is
     * for, told by its name in the message the freezer gave it ([R.string.ice_suspend_message]).
     */
    fun fromSuspendDialog(texts: List<String>, ownMessageOnly: Boolean = false): String? {
        val soft = apps.filter { it.frozenAs == IceMode.SOFT }
        if (soft.isEmpty() || texts.isEmpty()) return null
        val labels = soft.associateBy({ label(it.key) }, { it.key })
        val names = texts.flatMap { t -> Regex("«([^»]+)»").findAll(t).map { it.groupValues[1] }.toList() }
        names.firstNotNullOfOrNull { labels[it] }?.let { return it }
        if (ownMessageOnly) return null
        // The system's own message ("Приложение Telegram приостановлено"), when the freezer's
        // could not be set: the longest name in it, so "Telegram X" is not taken for "Telegram".
        val all = texts.joinToString("\n")
        return labels.entries.filter { all.contains(it.key, ignoreCase = true) }.maxByOrNull { it.key.length }?.value
    }

    /**
     * Brings the record in line with the phone: an app thawed or frozen past the freezer, a
     * rule's hold whose window is gone, an app uninstalled. With [boot] and [Prefs.iceBootCheck]
     * everything thawed freezes again — nobody is using an app right after a reboot.
     */
    suspend fun sync(boot: Boolean = false) {
        lock.withLock { syncLocked(boot) }
        IceWatch.sync()
    }

    private suspend fun syncLocked(boot: Boolean) {
        val now = System.currentTimeMillis()
        val open = Prefs.openWindows.keys
        val pm = App.ctx.packageManager
        edit { list -> list.mapNotNull { a ->
            if (!AppKey.isClone(a.key) && !installed(pm, a.key)) return@mapNotNull null
            var b = a
            if (b.heldBy != 0L && b.heldBy !in open) b = b.copy(heldBy = 0L,
                keepUntil = if (b.keepUntil == 0L && !b.frozen) now else b.keepUntil)
            if (!AppKey.isClone(b.key)) {
                val r = real(b.key)
                if (b.frozen && r == null) b = b.copy(frozenAs = null, thawedAt = now)
                else if (r != null && b.frozenAs != r) b = b.copy(frozenAs = r, thawedAt = 0L, heldBy = 0L, keepUntil = 0L)
            }
            if (!b.frozen && b.thawedAt == 0L) b = b.copy(thawedAt = now)
            b
        } }
        if (boot && Prefs.iceBootCheck) {
            val thawed = apps.filter { !it.frozen && it.heldBy == 0L && !IcePolicy.protected(it, now) }.map { it.key }
            if (thawed.isNotEmpty() && Privileged.available()) freezeLocked(thawed, "после перезагрузки")
        }
    }

    // --- shell ---------------------------------------------------------------------------------

    /** Freezes [keys] each its own way; returns how many froze. Under [lock]. */
    private suspend fun freezeLocked(keys: List<String>, why: String, restoreModes: Map<String, IceMode> = emptyMap()): Int {
        val guarded = AppFreezer.protectedPackages()
        val targets = apps.filter { it.key in keys && AppKey.valid(it.key) && AppKey.pkg(it.key) !in guarded }
            .map { a -> a.copy(mode = restoreModes[a.key] ?: modeOf(a)) }
        if (targets.isEmpty()) return 0
        // Frozen the other way: thawed first, or a disabled app would stay disabled under a suspension.
        val moving = targets.filter { it.frozen && it.frozenAs != modeOf(it) }
        val script = (if (moving.isNotEmpty()) thawScript(moving) else "") + freezeScript(targets.filter { !it.frozen || it in moving })
        val out = runCatching { Privileged.exec(script, timeoutMs = 15_000L + 3_000L * targets.size) }
            .onFailure { Prefs.log("морозилка ($why): ошибка — ${it.message}") }
            .getOrNull() ?: run {
                targets.forEach { waiting[it.key] = WaitReason.FAILED; retryAt[it.key] = System.currentTimeMillis() + 60_000L }
                return 0
            }
        val failed = failures(out)
        val done = targets.filter { it.key !in failed }.associateBy { it.key }
        edit { list ->
            list.map { a ->
                if (a.key in done) a.copy(frozenAs = modeOf(done.getValue(a.key)), thawedAt = 0L, heldBy = 0L, keepUntil = 0L) else a
            }
        }
        done.keys.forEach { seenAt -= it; retryAt -= it; waiting -= it }
        failed.forEach { waiting[it] = WaitReason.FAILED; retryAt[it] = System.currentTimeMillis() + 60_000L }
        Prefs.log("морозилка ($why): заморожено ${done.size}" +
            (if (done.size <= 3) " — " + done.keys.joinToString(", ") { label(it) } else "") +
            if (failed.isNotEmpty()) ", не удалось: " + failed.joinToString(", ") { label(it) } else "")
        Log.i(TAG, "freezer froze ${done.keys}, failed $failed")
        return done.size
    }

    /** Thaws [keys] (those frozen); [hold] holds them for a rule's window. Under [lock]. */
    private suspend fun thawLocked(keys: List<String>, why: String, hold: Long = 0L): Set<String> {
        val targets = apps.filter { it.key in keys && AppKey.valid(it.key) }
        if (targets.isEmpty()) return emptySet()
        val frozen = targets.filter { it.frozen }
        val failed = if (frozen.isEmpty()) emptySet() else {
            val out = runCatching { Privileged.exec(thawScript(frozen), timeoutMs = 15_000L + 3_000L * frozen.size) }
                .onFailure { Prefs.log("морозилка ($why): ошибка — ${it.message}") }
                .getOrNull() ?: run { targets.forEach { waiting[it.key] = WaitReason.FAILED }; return emptySet() }
            failures(out)
        }
        val now = System.currentTimeMillis()
        val ok = targets.map { it.key }.filter { it !in failed }.toSet()
        edit { list ->
            list.map { a ->
                if (a.key !in ok) a
                else a.copy(frozenAs = null, thawedAt = if (a.frozen || a.thawedAt == 0L) now else a.thawedAt,
                    heldBy = if (hold != 0L) hold else a.heldBy)
            }
        }
        ok.forEach { retryAt -= it; waiting -= it }
        failed.forEach { waiting[it] = WaitReason.FAILED }
        if (frozen.isNotEmpty()) {
            Prefs.log("морозилка ($why): разморожено ${frozen.size - failed.size}" +
                (if (frozen.size <= 3) " — " + frozen.joinToString(", ") { label(it.key) } else "") +
                if (failed.isNotEmpty()) ", не удалось: " + failed.joinToString(", ") { label(it) } else "")
        }
        return ok
    }

    private fun failures(out: String): Set<String> =
        out.lineSequence().filter { it.startsWith("@F ") }.map { it.removePrefix("@F ").trim() }.toSet()

    private fun cli(): String = "CLASSPATH=${App.ctx.applicationInfo.sourceDir} app_process /system/bin"

    /** The dialog's text for one app; quotes would end the shell's own. */
    private fun message(key: String): String =
        App.ctx.getString(R.string.ice_suspend_message, label(key)).replace("'", "").replace("\n", " ")

    /**
     * Soft: one app_process per user suspends them all, each with its own message, and `pm
     * suspend` takes any it could not. Deep: `pm disable-user`. Both are then stopped — frozen
     * first, stopped second, as the rules do, or the shade restarts the app to rebind a tile.
     * Prints "@F key" for an app that did not freeze.
     */
    private fun freezeScript(targets: List<IceApp>): String = buildString {
        val d = "$"
        val soft = targets.filter { modeOf(it) == IceMode.SOFT }.map { it.key }
        for ((u, group) in soft.groupBy { AppKey.user(it) }) {
            val args = group.joinToString(" ") { k -> "${AppKey.pkg(k)} '${message(k)}'" }
            appendLine("r=$d(${cli()} com.netmode.switcher.SuspendCli suspend-each $u $args)")
            appendLine("case \"${d}r\" in ok=*) f=\",${d}{r#ok=},\" ;; *) f=all ;; esac")
            for (k in group) {
                val p = AppKey.pkg(k)
                appendLine("case \"${d}f\" in all|*,$p,*) pm suspend --user $u $p 2>&1 | grep -q 'suspended state: true' || echo \"@F $k\" ;; esac")
            }
        }
        for (a in targets.filter { modeOf(it) == IceMode.DEEP }) {
            appendLine("pm disable-user --user ${AppKey.user(a.key)} ${AppKey.pkg(a.key)} 2>&1 | grep -q 'new state' || echo \"@F ${a.key}\"")
        }
        for (a in targets) appendLine("am force-stop ${AppKey.shell(a.key)} >/dev/null 2>&1")
    }

    /** The reverse, by how each is frozen now; prints "@F key" for one that did not thaw. */
    private fun thawScript(targets: List<IceApp>): String = buildString {
        val d = "$"
        val soft = targets.filter { it.frozenAs != IceMode.DEEP }.map { it.key }
        for ((u, group) in soft.groupBy { AppKey.user(it) }) {
            appendLine("r=$d(${cli()} com.netmode.switcher.SuspendCli unsuspend $u ${group.joinToString(" ") { AppKey.pkg(it) }})")
            appendLine("case \"${d}r\" in ok=*) f=\",${d}{r#ok=},\" ;; *) f=all ;; esac")
            for (k in group) {
                val p = AppKey.pkg(k)
                appendLine("case \"${d}f\" in all|*,$p,*) pm unsuspend --user $u $p >/dev/null 2>&1 || echo \"@F $k\" ;; esac")
            }
        }
        for (a in targets.filter { it.frozenAs == IceMode.DEEP }) {
            appendLine("pm enable --user ${AppKey.user(a.key)} ${AppKey.pkg(a.key)} 2>&1 | grep -q 'new state' || echo \"@F ${a.key}\"")
        }
    }

    /**
     * Opens the app's launcher activity, resolved by the package service once the app is
     * enabled again; monkey when that cannot be read.
     */
    private fun launchScript(key: String): String {
        val d = "$"
        val u = AppKey.user(key)
        val p = AppKey.pkg(key)
        return "c=$d(cmd package resolve-activity --brief --user $u -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $p 2>/dev/null | tail -n 1)\n" +
            "case \"${d}c\" in */*) am start --user $u -n \"${d}c\" >/dev/null 2>&1 ;; " +
            "*) monkey -p $p -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 ;; esac\n"
    }

    /** The activity on screen ("@R …"), and "@B key" for each app running a foreground service. */
    private fun lookScript(targets: List<IceApp>): String = buildString {
        appendLine("echo \"@R $(dumpsys activity activities 2>/dev/null | grep -m 1 -E 'mResumedActivity|topResumedActivity')\"")
        for (a in targets) {
            appendLine("dumpsys activity services ${AppKey.pkg(a.key)} 2>/dev/null | grep -q 'isForeground=true' && echo \"@B ${a.key}\"")
        }
    }

    /** "ActivityRecord{… u0 org.telegram.messenger/… t12}" is this app in its own user. */
    private fun onScreen(resumed: String, key: String): Boolean =
        Regex("\\bu${AppKey.user(key)} ${Regex.escape(AppKey.pkg(key))}/").containsMatchIn(resumed)

    private fun inCall(): Boolean = runCatching {
        val mode = App.ctx.getSystemService(AudioManager::class.java).mode
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    /**
     * Per thawed app of the main user: when it was last on screen or left it, and whether it
     * is on screen now, from the usage events. Clones are not in them; the look before
     * freezing covers those.
     */
    private fun lastUse(thawed: List<IceApp>): Map<String, Pair<Long, Boolean>> = runCatching {
        val keys = thawed.filterNot { AppKey.isClone(it.key) }.associateBy({ AppKey.pkg(it.key) }, { it.key })
        if (keys.isEmpty()) return@runCatching emptyMap()
        val now = System.currentTimeMillis()
        val from = maxOf(thawed.minOf { it.thawedAt } - 1_000L, now - USAGE_BACK_MS)
        val usm = App.ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        val out = mutableMapOf<String, Pair<Long, Boolean>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val k = keys[e.packageName] ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> out[k] = e.timeStamp to true
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> out[k] = e.timeStamp to false
            }
        }
        out
    }.getOrDefault(emptyMap())

    private const val MATCH_ANY = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS

    /** The app's info even while it is disabled; null when it is not installed. */
    fun info(pm: PackageManager, key: String): ApplicationInfo? = runCatching {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(AppKey.pkg(key), MATCH_ANY)
    }.getOrNull()

    private fun installed(pm: PackageManager, key: String) = info(pm, key) != null

    /** How the main user's copy is frozen as the phone sees it; null: not frozen (or unknown). */
    private fun real(key: String): IceMode? {
        val info = info(App.ctx.packageManager, key) ?: return null
        return when {
            !info.enabled -> IceMode.DEEP
            info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 -> IceMode.SOFT
            else -> null
        }
    }

    // --- following changes --------------------------------------------------------------------

    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ice_groups") IceShortcuts.refresh()
        if (key == Prefs.KEY_ICE_APPS) {
            FreezerTile.refresh()
            IceShortcuts.refresh()
            IceWatch.sync()
        }
    }

    /** From the process start: the tile, the shortcuts and the watch follow the record. */
    fun watch() {
        Prefs.listen(prefs)
        IceShortcuts.refresh()
        if (apps.isEmpty()) return
        App.scope.launch {
            runCatching { sync() }
            ensureInstant()
        }
    }

    /**
     * A soft-frozen app opens from its own icon through the accessibility service: it is
     * switched on as soon as the freezer has a soft app, unless the setting is off.
     */
    suspend fun ensureInstant() {
        if (!Prefs.iceNativeInstant || AppWatchService.connected) return
        if (apps.none { modeOf(it) == IceMode.SOFT }) return
        if (!Privileged.available()) return
        runCatching { AppWatchService.enable(true) }
            .onSuccess { Prefs.log("морозилка: включены спец. возможности — своя иконка открывает приложение без «Возобновить»") }
    }
}

/**
 * Notices an app being left, so it freezes after its delay: a look at the usage events every
 * few seconds while the screen is on and something is thawed, the screen going off, and an
 * exact alarm for the next deadline — the one that still comes when the process is gone.
 */
object IceWatch {

    private const val POLL_MS = 4_000L

    private var poll: Job? = null
    private var registered = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    poll?.cancel()
                    if (Prefs.iceScreenOff) App.scope.launch { runCatching { Freezer.check("экран погашен", screenOff = true) } }
                }
                Intent.ACTION_SCREEN_ON -> sync()
            }
        }
    }

    /** Thawed apps the freezer will freeze again by itself. */
    private fun watched() = Freezer.apps.any { IcePolicy.deadline(it, Prefs.iceDefaultDelay) != null }

    @Synchronized
    fun sync() {
        val ctx = App.ctx
        val want = watched()
        if (want && !registered) {
            ContextCompat.registerReceiver(ctx, screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } else if (!want && registered) {
            runCatching { ctx.unregisterReceiver(screen) }
            registered = false
        }
        val on = ctx.getSystemService(PowerManager::class.java).isInteractive
        if (want && on) {
            if (poll?.isActive != true) poll = App.scope.launch { loop() }
        } else {
            poll?.cancel()
        }
        arm(if (want) Freezer.nextDue() else null)
    }

    private suspend fun loop() {
        val pm = App.ctx.getSystemService(PowerManager::class.java)
        while (pm.isInteractive && watched()) {
            // Without the accessibility service, the front app from the usage events.
            if (!AppWatchService.connected) foreground()?.let { Freezer.noteForeground(it) }
            if (Freezer.anyDue()) runCatching { Freezer.check("ушли из приложения") }
            delay(POLL_MS)
        }
    }

    /** The last app to come to the front in the last minute. */
    private fun foreground(): String? = runCatching {
        val usm = App.ctx.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60_000L, end)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        last
    }.getOrNull()

    fun arm(at: Long?) {
        val ctx = App.ctx
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 700, Intent(ctx, IceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) return am.cancel(pi)
        if (Compat.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

/** A thawed app's time may have come: the freezer looks. */
class IceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmode:freezer")
        wake.acquire(2 * 60_000L)
        val work = App.scope.launch {
            try {
                val off = !context.getSystemService(PowerManager::class.java).isInteractive
                Freezer.check(if (off) "экран погашен" else "время вышло", screenOff = off)
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
        App.scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { work.join() }
            pending.finish()
        }
    }
}
