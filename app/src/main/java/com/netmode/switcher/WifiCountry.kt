package com.netmode.switcher

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** The service's selected country and the driver's country are deliberately separate. */
internal data class WifiCountryState(
    val country: String?,
    val driver: String?,
    val overrideKnown: Boolean,
    val override: String?,
    val supported: Boolean,
    val root: Boolean,
)

internal object WifiCountryProtocol {
    fun normalize(value: String): String? = value.trim().uppercase(Locale.ROOT)
        .takeIf { it == "00" || Regex("[A-Z]{2}").matches(it) }

    // Only country fields cross the boundary; dumpsys wifi can also contain SSIDs/passwords
    // and a history of shell invocations, neither of which belongs in this screen or parser.
    val readCommand = """
        echo @COUNTRY_UID=${'$'}(id -u)
        echo @COUNTRY_HELP
        cmd wifi help 2>&1
        echo @COUNTRY_VALUE
        cmd wifi get-country-code 2>&1
        echo @COUNTRY_DUMP
        dumpsys wifi 2>/dev/null | grep -E '^(mOverrideCountryCode:|mDriverCountryCode:|mCountryCodeSentToDriver:)'
        echo @COUNTRY_END
    """.trimIndent()

    fun parse(output: String): WifiCountryState {
        fun section(start: String, end: String) = output.substringAfter("$start\n", "").substringBefore("\n$end")
        val help = section("@COUNTRY_HELP", "@COUNTRY_VALUE")
        val value = section("@COUNTRY_VALUE", "@COUNTRY_DUMP")
        val dump = section("@COUNTRY_DUMP", "@COUNTRY_END")
        fun field(name: String) = Regex("(?m)^$name:[ \\t]*([^\\r\\n]*)$").find(dump)?.groupValues?.get(1)?.trim()
        val forced = field("mOverrideCountryCode")
        return WifiCountryState(
            country = Regex("(?m)^Wifi Country Code = ([^\\r\\n]+)$").find(value)?.groupValues?.get(1)?.let(::normalize),
            driver = (field("mDriverCountryCode") ?: field("mCountryCodeSentToDriver"))?.let(::normalize),
            overrideKnown = forced != null && (forced == "null" || normalize(forced) != null),
            override = forced?.let(::normalize),
            supported = Regex("(?m)^\\s*force-country-code\\s+enabled\\b").containsMatchIn(help),
            root = output.lineSequence().any { it == "@COUNTRY_UID=0" },
        )
    }

    fun changeCommand(country: String?): String {
        val argument = if (country == null) "disabled" else {
            val code = requireNotNull(normalize(country)) { "Нужны две латинские буквы или 00" }
            "enabled $code"
        }
        // Check UID again at the mutation boundary: the selected backend can change since read().
        return """
            if [ "${'$'}(id -u)" = 0 ]; then
                cmd wifi force-country-code $argument 2>&1
                echo @COUNTRY_RESULT=${'$'}?
            else
                echo @COUNTRY_RESULT=ROOT_REQUIRED
            fi
        """.trimIndent()
    }

    fun commandSucceeded(output: String) = output.lineSequence().any { it == "@COUNTRY_RESULT=0" }

    fun confirmed(country: String?, state: WifiCountryState): Boolean = if (state.overrideKnown) {
        state.override == country
    } else country != null && state.country == country
}

/** Injectable transport keeps command acceptance, readback and driver readiness testable. */
internal class WifiCountryClient(private val execute: suspend (String) -> String) {
    suspend fun read() = WifiCountryProtocol.parse(execute(WifiCountryProtocol.readCommand))

    suspend fun change(country: String?): WifiCountryState {
        val normalized = country?.let { requireNotNull(WifiCountryProtocol.normalize(it)) }
        val before = read()
        check(before.root) { "Для смены региона Wi-Fi нужен root. Shizuku через ADB недостаточно." }
        check(before.supported) { "В этой прошивке нет команды смены страны Wi-Fi." }
        val result = execute(WifiCountryProtocol.changeCommand(normalized))
        check(WifiCountryProtocol.commandSucceeded(result)) {
            "Android отклонил смену региона: " + result.lineSequence()
                .filterNot { it.startsWith("@COUNTRY_") }.joinToString(" ").take(240).ifBlank { "команда не выполнена" }
        }
        val after = read()
        check(WifiCountryProtocol.confirmed(normalized, after)) {
            "Команда выполнена, но Android не подтвердил выбранный режим. Обнови состояние."
        }
        return after
    }
}

internal object WifiCountry {
    private val lock = Mutex()
    private val client = WifiCountryClient { Privileged.exec(it) }
    suspend fun read(): WifiCountryState = lock.withLock { client.read() }
    suspend fun change(country: String?): WifiCountryState = lock.withLock { client.change(country) }
}
