package com.netmode.switcher

/**
 * Tells a rule action's log line that reports a failure or a skipped action from one that
 * reports what was done. An action whose target simply was not there ("VPN X не подключён —
 * пропущено", "уже выкл") has nothing to retry and does not count.
 */
internal object RunFailure {
    private val WORDS = listOf(
        "ошибка", "не удалось", "не удаётся", "неизвестно", "не подтвердилось", "не подключился", "не выключился",
        "подключилось снова", "не активна", "только плиткой", "не выбрано", "неверн", "недопустим",
        "недоступен", "нужен root", "подожди", "заблокирована", "не применён", "не остановилась",
        "не запущена", "оставлены без изменений",
    )
    private val HTTP_ERROR = Regex("^HTTP [45]\\d\\d")

    fun isFailure(line: String): Boolean {
        // After this prefix comes the command's own output, which may say anything.
        if (line.startsWith("команда выполнена")) return false
        return HTTP_ERROR.containsMatchIn(line) || WORDS.any { line.contains(it, ignoreCase = true) }
    }
}
