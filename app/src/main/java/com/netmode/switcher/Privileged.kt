package com.netmode.switcher

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Backend { AUTO, ROOT, SHIZUKU }

class NoPrivilege(message: String) : Exception(message)

/**
 * Runs privileged shell commands through whichever channel is available.
 *
 * Everything the app needs — reading and writing the network mode, reading the SSID — is a
 * shell command rather than a binder call, so root and Shizuku are interchangeable here.
 * Measured on a OnePlus 15: `cmd phone set-allowed-network-types-for-users` succeeds from
 * uid 2000 (shell), which is what Shizuku provides when started from ADB.
 */
object Privileged {

    private const val TAG = "netmode"

    /** Which channel actually served the last command; null until something has run. */
    @Volatile
    var lastUsed: Backend? = null
        private set

    /**
     * The watchdog against a runaway loop of privileged commands — what once kept a tile's
     * root shell forking `cmd phone` 32 times a second for six hours, the phone at 99 °C. Real
     * work comes in bursts of a few; past [BURST] in [BURST_WINDOW_MS] every further command
     * waits [THROTTLE_MS], which caps a loop at two a second, and the log says so.
     */
    private val guard = RateGuard(BURST_WINDOW_MS, BURST)
    private const val BURST = 60
    private const val BURST_WINDOW_MS = 10_000L
    private const val THROTTLE_MS = 500L
    @Volatile private var warnedAt = 0L

    private suspend fun throttle() {
        val now = android.os.SystemClock.elapsedRealtime()
        val count = guard.hit(now)
        if (count <= BURST) return
        if (now - warnedAt > 60_000L) {
            warnedAt = now
            Log.w(TAG, "privileged commands: $count in ${BURST_WINDOW_MS / 1000} s, throttling")
            Prefs.log("слишком частые root-команды: $count за ${BURST_WINDOW_MS / 1000} с — притормаживаю")
        }
        kotlinx.coroutines.delay(THROTTLE_MS)
    }

    /** [timeoutMs]: null for the channel's own limit; long scripts (many apps) pass their own. */
    suspend fun exec(command: String, timeoutMs: Long? = null): String = throttle().let { execNow(command, timeoutMs) }

    private suspend fun execNow(command: String, timeoutMs: Long?): String = when (choose()) {
        Backend.ROOT -> Root.exec(command, timeoutMs).also { lastUsed = Backend.ROOT }
        Backend.SHIZUKU -> ShizukuChannel.exec(command, timeoutMs).also { lastUsed = Backend.SHIZUKU }
        Backend.AUTO -> throw NoPrivilege("Нет ни root, ни Shizuku")
    }

    suspend fun available(): Boolean = choose() != Backend.AUTO

    /** Resolves the preference into the channel to actually use, or AUTO when none works. */
    suspend fun choose(): Backend = when (Prefs.backend) {
        Backend.ROOT -> if (Root.available()) Backend.ROOT else Backend.AUTO
        Backend.SHIZUKU -> if (ShizukuChannel.available()) Backend.SHIZUKU else Backend.AUTO
        Backend.AUTO -> when {
            // Root first: it survives a reboot, while ADB-started Shizuku does not.
            Root.available() -> Backend.ROOT
            ShizukuChannel.available() -> Backend.SHIZUKU
            else -> Backend.AUTO
        }
    }
}

/** Counts events in a sliding window: [hit] returns how many fell within the last [windowMs]. */
class RateGuard(private val windowMs: Long, private val keep: Int) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun hit(now: Long): Int {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        // Past the limit only the count matters; the window need not grow without end.
        while (times.size > keep * 4) times.removeFirst()
        return times.size
    }
}

private object ShizukuChannel {

    private const val TAG = "netmode"
    private const val TIMEOUT_MS = 20_000L

    /**
     * Waits briefly for Shizuku's binder instead of answering straight away.
     *
     * The binder arrives through a provider call shortly after the process starts, so an
     * immediate check loses a race that the app hits constantly: at launch, and — worse —
     * whenever an alarm or a Wi-Fi event starts the process in the background. Measured: the
     * first query after launch failed with "Нет ни root, ни Shizuku" while the very next one
     * succeeded.
     */
    suspend fun available(): Boolean = runCatching {
        if (!awaitBinder()) return@runCatching false
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun awaitBinder(timeoutMs: Long = 4_000L): Boolean {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(200)
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * Runs the command as a process spawned by the Shizuku server, rather than through a bound
     * user service.
     *
     * The user-service route needs a binder handshake and Shizuku remembers each service in a
     * record: when its server restarts — which an ADB-started Shizuku does on every reboot —
     * the record can outlive the process behind it and every later bind then hangs until the
     * timeout. Measured on device: "Found existing service record" on each attempt, 15 s of
     * nothing, and clearing the record did not help. Spawning a process keeps no state at all,
     * which is all this app needs: it only ever runs shell commands.
     */
    suspend fun exec(command: String, timeoutMs: Long? = null): String = withContext(Dispatchers.IO) {
        val limit = timeoutMs ?: TIMEOUT_MS
        // stderr joins stdout in the shell: reading one pipe to the end while the other fills
        // up would deadlock both processes.
        val process = newProcess(arrayOf("sh", "-c", "{\n$command\n} 2>&1"))
        val reader = async { process.inputStream.bufferedReader().use { it.readText() } }
        // A blocked read cannot be cancelled, but killing the process ends it.
        val out = withTimeoutOrNull(limit) { reader.await() }
        if (out == null) {
            runCatching { process.destroy() }
            throw IllegalStateException("Shizuku: команда не завершилась за ${limit / 1000} с")
        }
        runCatching { process.waitFor() }
        out
    }

    /**
     * Shizuku marks newProcess as library-internal, so it is called reflectively: the method is
     * stable in practice and the alternative (a user service) is what proved unreliable here.
     */
    private fun newProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as? Process
            ?: throw NoPrivilege("Shizuku не смог запустить процесс")
    }
}
