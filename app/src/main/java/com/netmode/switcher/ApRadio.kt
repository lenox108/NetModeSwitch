package com.netmode.switcher

/** A band mask is one AP with automatic selection; two map entries are concurrent APs. */
internal object ApRadio {
    fun parse(value: String): Map<Int, Int> = runCatching {
        val pairs = value.split(',').map {
            val fields = it.split(':')
            require(fields.size == 2)
            val band = fields[0].toInt()
            val channel = fields[1].toInt()
            require(band > 0 && band and 11 == band && channel in 0..233)
            require(channel == 0 || band in listOf(1, 2, 8))
            band to channel
        }
        require(pairs.size in 1..2 && pairs.map { it.first }.distinct().size == pairs.size)
        pairs.toMap()
    }.getOrDefault(emptyMap())

    fun legacy(band: String, channel: Int): String {
        val mask = (if ('2' in band) 1 else 0) or (if ('5' in band) 2 else 0) or (if ('6' in band) 8 else 0)
        return "${mask.coerceAtLeast(1)}:$channel"
    }

    fun key(radio: String): String = when (parse(radio).keys) {
        setOf(1) -> "2"
        setOf(2) -> "5"
        setOf(8) -> "6"
        setOf(3) -> "auto25"
        setOf(1, 2) -> "dual25"
        else -> "keep"
    }

    fun value(key: String, channel: Int = 0, original: String = "1:0"): String = when (key) {
        "2" -> "1:$channel"
        "5" -> "2:$channel"
        "6" -> "8:$channel"
        "auto25" -> "3:0"
        "dual25" -> "1:0,2:0"
        else -> original
    }

    fun label(key: String): String = when (key) {
        "2" -> "2,4 ГГц"
        "5" -> "5 ГГц"
        "6" -> "6 ГГц"
        "auto25" -> "Авто: 2,4 или 5 ГГц"
        "dual25" -> "2,4 + 5 ГГц одновременно"
        else -> "Системная конфигурация (сохранить)"
    }
}
