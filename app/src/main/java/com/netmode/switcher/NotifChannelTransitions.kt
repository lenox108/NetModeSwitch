package com.netmode.switcher

/** Ownership is kept until Android confirms restoration; shared channels are restored once. */
internal object NotifChannelTransitions {
    suspend fun update(owned: Map<Pair<String, String>, Int>, wanted: Set<Pair<String, String>>,
        restore: suspend (Pair<String, String>, Int) -> Int?,
        disable: suspend (Pair<String, String>) -> Int?): Map<Pair<String, String>, Int> {
        for ((key, was) in owned) {
            if (key in wanted) continue
            check(restore(key, was) == was) {
                "Не удалось восстановить канал «${key.second}». Правило сохранено без изменений; проверь root и повтори"
            }
        }
        return buildMap {
            for (key in wanted) {
                val was = owned[key] ?: disable(key)
                if (was != null) put(key, was)
            }
        }
    }
}
