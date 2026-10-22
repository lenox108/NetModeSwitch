package com.netmode.switcher

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root access is a plain `su` shell, deliberately not a bound root service.
 *
 * libsu's RootService needs the root process to hand its binder back to the app through a
 * broadcast, and that handshake never completes when the app is in the background — measured
 * on this device: a bind from a broadcast receiver times out after 30 s while the very same
 * bind from an activity succeeds in seconds. A shell command has no handshake, keeps no
 * daemon process alive, and works identically in the foreground and the background.
 */
object Root {

    private const val TAG = "netmode"
    private const val TIMEOUT_MS = 15_000L

    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    /**
     * Runs [command] as root and returns stdout+stderr. Throws on timeout or missing root.
     *
     * libsu runs every command in one shared shell, and a coroutine timeout cannot interrupt a
     * blocking exec: one hung command would stall every later one. So the job is queued and
     * waited for with a deadline, and on timeout the shell itself is closed — the next command
     * gets a fresh one.
     */
    suspend fun exec(command: String, timeoutMs: Long? = null): String = withContext(Dispatchers.IO) {
        val limit = timeoutMs ?: TIMEOUT_MS
        val job = Shell.cmd(command).enqueue()
        try {
            val result = job.get(limit, java.util.concurrent.TimeUnit.MILLISECONDS)
            (result.out + result.err).joinToString("\n")
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "root command timed out: $command")
            runCatching { Shell.getCachedShell()?.close() }
            throw IllegalStateException("Root не ответил за ${limit / 1000} с")
        }
    }
}
