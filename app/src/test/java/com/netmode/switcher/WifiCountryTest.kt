package com.netmode.switcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class WifiCountryTest {
    private fun snapshot(uid: Int = 0, country: String = "US", driver: String = "US",
                         override: String = "null", supported: Boolean = true) = """
        @COUNTRY_UID=$uid
        @COUNTRY_HELP
          ${if (supported) "force-country-code enabled <two-letter code> | disabled" else "help"}
        @COUNTRY_VALUE
        Wifi Country Code = $country
        @COUNTRY_DUMP
        mOverrideCountryCode: $override
        mDriverCountryCode: $driver
        @COUNTRY_END
    """.trimIndent()

    @Test fun normalizesWithoutDependingOnThePhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("IN", WifiCountryProtocol.normalize(" in "))
            assertEquals("00", WifiCountryProtocol.normalize("00"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun rejectsShellInputAndNonCountryValues() {
        listOf("", "R", "USA", "12", "U1", "РФ", "US;id", "US\nreboot", "${'$'}(id)").forEach {
            assertNull(it, WifiCountryProtocol.normalize(it))
            assertThrows(IllegalArgumentException::class.java) { WifiCountryProtocol.changeCommand(it) }
        }
    }

    @Test fun separatesTheSelectedCountryFromTheDriver() {
        val state = WifiCountryProtocol.parse(snapshot(country = "JP", driver = "US", override = "JP"))
        assertEquals("JP", state.country)
        assertEquals("JP", state.override)
        assertEquals("US", state.driver)
        assertTrue(state.overrideKnown)
        assertTrue(state.root)
        assertTrue(state.supported)
    }

    @Test fun automaticModeIsNotWorldMode() {
        assertNull(WifiCountryProtocol.parse(snapshot()).override)
        assertEquals("00", WifiCountryProtocol.parse(snapshot(override = "00")).override)
        assertTrue(WifiCountryProtocol.changeCommand(null).contains("force-country-code disabled"))
        assertTrue(WifiCountryProtocol.changeCommand("00").contains("force-country-code enabled 00"))
    }

    @Test fun ignoresDumpHistoryAndMalformedValues() {
        val output = snapshot(country = "null", driver = "null", override = "bad") +
            "\nLog: mOverrideCountryCode: RU\nforce-country-code enabled US\nmOverrideCountryCode: US"
        val state = WifiCountryProtocol.parse(output)
        assertFalse(state.overrideKnown)
        assertNull(state.country)
        assertNull(state.driver)
        assertNull(state.override)
    }

    @Test fun missingFieldsNeverMeanAutomaticOrSuccessful() {
        val state = WifiCountryProtocol.parse("SecurityException: permission denied")
        assertFalse(state.root)
        assertFalse(state.supported)
        assertFalse(state.overrideKnown)
        assertFalse(WifiCountryProtocol.confirmed(null, state))
        assertFalse(WifiCountryProtocol.commandSucceeded("ok"))
        assertFalse(WifiCountryProtocol.commandSucceeded("@COUNTRY_RESULT=-1"))
        assertFalse(WifiCountryProtocol.commandSucceeded("@COUNTRY_RESULT=ROOT_REQUIRED"))
    }

    @Test fun olderDriverFieldRemainsReadable() {
        val state = WifiCountryProtocol.parse(snapshot().replace("mDriverCountryCode: US", "mCountryCodeSentToDriver: DE"))
        assertEquals("DE", state.driver)
    }

    @Test fun rootIsCheckedAgainInsideTheMutationScript() {
        assertTrue(WifiCountryProtocol.changeCommand("ru").contains("if [ \"${'$'}(id -u)\" = 0 ]"))
        assertTrue(WifiCountryProtocol.changeCommand("ru").contains("enabled RU"))
    }

    @Test fun shellShizukuCannotReachTheMutation() = runBlocking {
        val commands = mutableListOf<String>()
        val client = WifiCountryClient { commands += it; snapshot(uid = 2000) }
        val error = runCatching { client.change("RU") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("root"))
        assertEquals(listOf(WifiCountryProtocol.readCommand), commands)
    }

    @Test fun unsupportedFirmwareCannotReachTheMutation() = runBlocking {
        var calls = 0
        val client = WifiCountryClient { calls++; snapshot(supported = false) }
        assertTrue(runCatching { client.change("RU") }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun zeroExitWithoutReadbackIsNotSuccess() = runBlocking {
        val replies = ArrayDeque(listOf(snapshot(), "@COUNTRY_RESULT=0", snapshot()))
        val client = WifiCountryClient { replies.removeFirst() }
        assertTrue(runCatching { client.change("RU") }.isFailure)
        assertTrue(replies.isEmpty())
    }

    @Test fun commandFailureDoesNotClaimSuccessFromACachedCountry() = runBlocking {
        val replies = ArrayDeque(listOf(snapshot(country = "RU", override = "RU"), "SecurityException\n@COUNTRY_RESULT=-1"))
        val client = WifiCountryClient { replies.removeFirst() }
        assertTrue(runCatching { client.change("RU") }.isFailure)
        assertTrue(replies.isEmpty())
    }

    @Test fun acceptedOverrideCanStillBePendingInTheDriver() = runBlocking {
        val replies = ArrayDeque(listOf(snapshot(), "@COUNTRY_RESULT=0", snapshot(country = "RU", override = "RU")))
        val state = WifiCountryClient { replies.removeFirst() }.change("ru")
        assertEquals("RU", state.override)
        assertEquals("US", state.driver)
    }

    @Test fun automaticReadbackMayKeepThePreviousDriverCountry() = runBlocking {
        val replies = ArrayDeque(listOf(snapshot(country = "JP", driver = "JP", override = "JP"),
            "@COUNTRY_RESULT=0", snapshot(country = "JP", driver = "JP")))
        val state = WifiCountryClient { replies.removeFirst() }.change(null)
        assertTrue(state.overrideKnown)
        assertNull(state.override)
        assertEquals("JP", state.driver)
    }

    @Test fun automaticModeNeedsConfirmationOfTheClearedOverride() = runBlocking {
        val replies = ArrayDeque(listOf(snapshot(), "@COUNTRY_RESULT=0", snapshot().replace("mOverrideCountryCode: null", "")))
        assertTrue(runCatching { WifiCountryClient { replies.removeFirst() }.change(null) }.isFailure)
    }
}
