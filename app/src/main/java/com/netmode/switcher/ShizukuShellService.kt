package com.netmode.switcher

/**
 * The Shizuku user service. Shizuku instantiates this class inside a process running as shell
 * (uid 2000) when started from ADB, or root when started from a root manager — either is
 * enough for `cmd phone`, which accepts both.
 */
class ShizukuShellService : IShellService.Stub() {

    override fun exec(cmd: String): String = runCatching {
        val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        out
    }.getOrElse { "ERR: ${it.message}" }

    override fun destroy() = Unit
}
