package com.netmode.switcher

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

    /** Runs [command] as root and returns stdout+stderr. Throws on timeout or missing root. */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        try {
            withTimeout(TIMEOUT_MS) {
                val result = Shell.cmd(command).exec()
                (result.out + result.err).joinToString("\n")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "root command timed out: $command")
            throw IllegalStateException("Root не ответил за ${TIMEOUT_MS / 1000} с")
        }
    }
}
