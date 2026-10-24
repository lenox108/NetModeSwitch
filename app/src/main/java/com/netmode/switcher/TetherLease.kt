package com.netmode.switcher

internal data class TetherRecord(val identity: String, val line: String)

/** Only a verified route may be persisted or adopted, including across app process restarts. */
internal class TetherLease(
    private val read: () -> TetherRecord?,
    private val write: (TetherRecord?) -> Boolean,
) {
    suspend fun adopt(identity: String, verify: suspend () -> Boolean): String? {
        val record = read() ?: return null
        return if (record.identity == identity && verify()) record.line else null
    }

    /** Whether a route was saved, matching or not: building over it is a rebuild. */
    fun saved(): Boolean = read() != null

    fun forget() { check(write(null)) { "не удалось сбросить состояние маршрута" } }

    suspend fun remember(identity: String, line: String, verify: suspend () -> Boolean) {
        check(verify()) { "маршрут не прошёл проверку после настройки" }
        check(write(TetherRecord(identity, line))) { "не удалось сохранить состояние маршрута" }
    }
}
