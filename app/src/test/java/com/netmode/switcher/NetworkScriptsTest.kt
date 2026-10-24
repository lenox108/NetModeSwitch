package com.netmode.switcher

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Run the actual shell fragments against a fake firewall and fake procfs, without root. */
class NetworkScriptsTest {
    private val dir = Files.createTempDirectory("netmode-network-test").toFile()
    private val bin = File(dir, "bin").apply { mkdirs() }
    private val state = File(dir, "state").apply { mkdirs() }
    private val proc = File(dir, "proc").apply { mkdirs() }

    init {
        for (name in listOf("iptables", "ip6tables")) {
            File(bin, name).apply { writeText(firewall); setExecutable(true) }
            for (table in listOf("filter", "mangle", "nat")) for (chain in listOf("INPUT", "OUTPUT", "FORWARD", "PREROUTING", "POSTROUTING")) {
                File(state, "${name}_${table}_$chain").writeText("")
            }
        }
        for (name in listOf("settings", "device_config")) {
            File(bin, name).apply { writeText(settings); setExecutable(true) }
        }
        File(bin, "ip").apply { writeText(ip); setExecutable(true) }
        File(state, "rules").writeText("")
    }

    @After fun clean() { dir.deleteRecursively() }

    private fun run(script: String, failTtl: Boolean = false, failHl: Boolean = false): String {
        // Never let these tests touch the host's /proc, even when run on Linux as root.
        val safe = script.replace("/proc/sys/net/ipv6/conf", proc.absolutePath)
        val process = ProcessBuilder("/bin/sh", "-c", safe).apply {
            environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
            environment()["NMS_STATE"] = state.absolutePath
            environment()["NMS_FAIL_TTL"] = if (failTtl) "1" else "0"
            environment()["NMS_FAIL_HL"] = if (failHl) "1" else "0"
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun flag(name: String, value: Int) {
        File(proc, "$name/disable_ipv6").apply { parentFile!!.mkdirs(); writeText("$value\n") }
    }

    private fun flag(name: String) = File(proc, "$name/disable_ipv6").readText().trim().toInt()
    private fun chain(name: String) = File(state, name).readText()

    @Test fun ttlOnlyTouchesForwardedHotspotTrafficAndIsIdempotent() {
        val foreign = File(state, "iptables_mangle_FORWARD").apply { writeText("-A FORWARD -j OTHER_APP\n") }
        val script = NetworkScripts.ttl("wlan2", listOf("rmnet_data0", "v4-rmnet_data0"), 64)
        assertTrue(run(script).contains("@TTL_OK"))
        val expected = chain("iptables_mangle_NMS_TTL")
        assertTrue(expected.contains("-i wlan2 -o rmnet_data0 -j TTL --ttl-set 64"))
        assertTrue(expected.contains("-i wlan2 -o v4-rmnet_data0 -j TTL --ttl-set 64"))
        assertEquals("", chain("iptables_mangle_OUTPUT"))
        assertTrue(run(script).contains("@TTL_OK"))
        assertEquals(expected, chain("iptables_mangle_NMS_TTL"))
        assertEquals(1, foreign.readLines().count { it.endsWith("-j NMS_TTL") })
        assertTrue(run(NetworkScripts.clearTtl()).contains("@TTL_CLEARED"))
        assertEquals("-A FORWARD -j OTHER_APP\n", foreign.readText())
        assertFalse(File(state, "iptables_mangle_NMS_TTL").exists())
    }

    @Test fun hopLimitMirrorsTtlForIpv6Only() {
        val script = NetworkScripts.hl("wlan2", listOf("rmnet_data0", "v4-rmnet_data0"), 64)
        assertTrue(run(script).contains("@HL_OK"))
        val rules = chain("ip6tables_mangle_NMS_HL")
        assertTrue(rules.contains("-i wlan2 -o rmnet_data0 -j HL --hl-set 64"))
        assertEquals(1, chain("ip6tables_mangle_FORWARD").lines().count { it.endsWith("-j NMS_HL") })
        // IPv4 and the phone's own traffic stay as they were.
        assertEquals("", chain("iptables_mangle_FORWARD"))
        assertEquals("", chain("ip6tables_mangle_OUTPUT"))
        assertTrue(run(script).contains("@HL_OK"))
        assertEquals(rules, chain("ip6tables_mangle_NMS_HL"))
        assertTrue(run(NetworkScripts.clearHl()).contains("@HL_CLEARED"))
        assertFalse(File(state, "ip6tables_mangle_NMS_HL").exists())
        assertEquals("", chain("ip6tables_mangle_FORWARD"))
    }

    @Test fun unsupportedHopLimitFailsCleanly() {
        assertFalse(run(NetworkScripts.hl("wlan2", listOf("rmnet_data0"), 64), failHl = true).contains("@HL_OK"))
        assertTrue(run(NetworkScripts.probeHl()).contains("@HL_SUPPORTED"))
        assertFalse(run(NetworkScripts.probeHl(), failHl = true).contains("@HL_SUPPORTED"))
        assertFalse(File(state, "ip6tables_mangle_NMS_HL_TEST").exists())
        assertTrue(run(NetworkScripts.clearHl()).contains("@HL_CLEARED"))
        assertEquals("", chain("ip6tables_mangle_FORWARD"))
    }

    @Test fun unsupportedTtlNeverReportsSuccessAndCanBeCleaned() {
        assertFalse(run(NetworkScripts.ttl("wlan2", listOf("rmnet_data0"), 64), failTtl = true).contains("@TTL_OK"))
        assertTrue(run(NetworkScripts.clearTtl()).contains("@TTL_CLEARED"))
        assertEquals("", chain("iptables_mangle_FORWARD"))
    }

    @Test fun capabilityProbeDoesNotInstallLiveRulesAndCleansUpOnFailure() {
        assertTrue(run(NetworkScripts.probeTtl()).contains("@TTL_SUPPORTED"))
        assertFalse(File(state, "iptables_mangle_NMS_TTL_TEST").exists())
        assertFalse(run(NetworkScripts.probeTtl(), failTtl = true).contains("@TTL_SUPPORTED"))
        assertFalse(File(state, "iptables_mangle_NMS_TTL_TEST").exists())
        assertEquals("", chain("iptables_mangle_FORWARD"))
    }

    @Test fun rejectsShellInjectionAndOutOfRangeTtl() {
        for (name in listOf("wlan2; echo bad", "$(id)", "../wlan2", "", "0123456789012345")) {
            assertThrows(IllegalArgumentException::class.java) { NetworkScripts.ttl(name, listOf("rmnet0"), 64) }
        }
        for (value in listOf(-1, 0, 256)) {
            assertThrows(IllegalArgumentException::class.java) { NetworkScripts.ttl("wlan2", listOf("rmnet0"), value) }
        }
    }

    @Test fun tetherIpv6LeavesPhoneSettingsAndLocalTrafficUntouched() {
        val previous = mapOf("default" to 0, "lo" to 0, "wlan0" to 1, "rmnet0" to 0, "tun0" to 0)
        previous.forEach(::flag)
        File(state, "ip6tables_filter_INPUT").writeText("-A INPUT -j ADGUARD\n")
        File(state, "ip6tables_filter_OUTPUT").writeText("-A OUTPUT -j ADGUARD\n")
        val script = NetworkScripts.tetherIpv6(listOf("wlan2", "rndis0"))
        repeat(2) { assertTrue(run(script).contains("@TETHER6_BLOCKED")) }
        previous.forEach { (name, value) -> assertEquals(value, flag(name)) }
        assertEquals("-A INPUT -j ADGUARD\n", chain("ip6tables_filter_INPUT"))
        assertEquals("-A OUTPUT -j ADGUARD\n", chain("ip6tables_filter_OUTPUT"))
        assertEquals(listOf(
            "-A NMS_TETHER6 -i wlan2 -j DROP", "-A NMS_TETHER6 -o wlan2 -j DROP",
            "-A NMS_TETHER6 -i rndis0 -j DROP", "-A NMS_TETHER6 -o rndis0 -j DROP",
        ), File(state, "ip6tables_filter_NMS_TETHER6").readLines())
        assertEquals(1, chain("ip6tables_filter_FORWARD").lines().count { it.endsWith("-j NMS_TETHER6") })
        assertTrue(run(NetworkScripts.inspectTetherIpv6(listOf("wlan2", "rndis0"))).contains("@TETHER6_OK"))
    }

    @Test fun tetherInterfaceChangesReplaceOnlyOurForwardRules() {
        File(state, "ip6tables_filter_FORWARD").writeText("-A FORWARD -j OTHER_APP\n")
        run(NetworkScripts.tetherIpv6(listOf("wlan2")))
        assertTrue(run(NetworkScripts.tetherIpv6(listOf("swlan0"))).contains("@TETHER6_BLOCKED"))
        assertFalse(chain("ip6tables_filter_NMS_TETHER6").contains("wlan2"))
        assertFalse(run(NetworkScripts.inspectTetherIpv6(listOf("wlan2"))).contains("@TETHER6_OK"))
        assertTrue(run(NetworkScripts.inspectTetherIpv6(listOf("swlan0"))).contains("@TETHER6_OK"))
        repeat(2) { assertTrue(run(NetworkScripts.clearTetherIpv6()).contains("@TETHER6_CLEARED")) }
        assertEquals("-A FORWARD -j OTHER_APP\n", chain("ip6tables_filter_FORWARD"))
        assertFalse(File(state, "ip6tables_filter_NMS_TETHER6").exists())
    }

    @Test fun tetherRulesRejectEmptyOrUnsafeInterfaceLists() {
        for (names in listOf(emptyList(), listOf("wlan2; echo bad"), listOf("$(id)"), listOf("../wlan2"))) {
            assertThrows(IllegalArgumentException::class.java) { NetworkScripts.tetherIpv6(names) }
            assertThrows(IllegalArgumentException::class.java) { NetworkScripts.inspectTetherIpv6(names) }
        }
    }

    private fun legacyIpv6() {
        // Reproduce the old release, without retaining a phone-wide disable API in the app.
        proc.listFiles().orEmpty().filter { it.name != "all" }.forEach { flag(it.name, 1) }
        run("""
            ip6tables -N NMS_NO6
            ip6tables -A NMS_NO6 -j DROP
            for hook in INPUT OUTPUT FORWARD; do ip6tables -I "${'$'}hook" 1 -j NMS_NO6; done
        """.trimIndent())
    }

    @Test fun legacyMigrationRestoresPhoneWhileKeepingClientBlock() {
        val previous = mapOf("default" to 0, "lo" to 0, "wlan0" to 1, "rmnet0" to 0, "tun0" to 0)
        previous.forEach(::flag)
        flag("all", 0) // This readout must never be used as the effective IPv6 state.
        File(state, "ip6tables_filter_OUTPUT").writeText("-A OUTPUT -j OTHER_APP\n")
        legacyIpv6()
        flag("wlan3", 1) // Created while the old release had disabled the default.
        run(NetworkScripts.tetherIpv6(listOf("wlan2")))
        assertTrue(run(NetworkScripts.restoreLegacyIpv6(previous)).contains("@IPV6_RESTORED"))
        previous.forEach { (name, value) -> assertEquals(value, flag(name)) }
        assertEquals(0, flag("wlan3"))
        assertEquals("-A OUTPUT -j OTHER_APP\n", chain("ip6tables_filter_OUTPUT"))
        assertFalse(File(state, "ip6tables_filter_NMS_NO6").exists())
        assertFalse(chain("ip6tables_filter_INPUT").contains("NMS_NO6"))
        assertTrue(run(NetworkScripts.inspectTetherIpv6(listOf("wlan2"))).contains("@TETHER6_OK"))
    }

    @Test fun restorationAfterAnotherBootDoesNotWriteStaleSysctls() {
        flag("default", 1)
        flag("wlan0", 0)
        legacyIpv6()
        flag("wlan0", 0) // Current boot settings supersede the saved settings.
        assertTrue(run(NetworkScripts.restoreLegacyIpv6(null)).contains("@IPV6_RESTORED"))
        assertEquals(0, flag("wlan0"))
        assertEquals(1, flag("default"))
    }

    @Test fun absentOffloadSettingsAreDeletedOnRestoration() {
        assertTrue(run(NetworkScripts.disableOffload()).contains("@OFFLOAD_OFF"))
        assertEquals("1", File(state, "settings").readText())
        assertEquals("false", File(state, "device_config").readText())
        assertTrue(run(NetworkScripts.restoreOffload("null", "null")).contains("@OFFLOAD_RESTORED"))
        assertFalse(File(state, "settings").exists())
        assertFalse(File(state, "device_config").exists())
    }

    @Test fun restorationDoesNotOverwriteAnExternalOffloadChange() {
        run(NetworkScripts.disableOffload())
        File(state, "settings").writeText("0")
        File(state, "device_config").writeText("true")
        run(NetworkScripts.restoreOffload("2", "old"))
        assertEquals("0", File(state, "settings").readText())
        assertEquals("true", File(state, "device_config").readText())
    }

    @Test fun savedSettingValuesArePassedLiterallyToTheShell() {
        run(NetworkScripts.disableOffload())
        val value = "quoted ' value; touch ${File(dir, "injected").absolutePath}; $(id)"
        run(NetworkScripts.restoreOffload(value, "true"))
        assertEquals(value, File(state, "settings").readText())
        assertFalse(File(dir, "injected").exists())
    }

    private fun installRoute(path: TetherPath) {
        assertTrue(run(TetherScripts.checked(TetherScripts.hold(path.ap), "@HELD")).contains("@HELD"))
        assertTrue(run(TetherScripts.checked(TetherScripts.block(path.ap), "@BLOCKED")).contains("@BLOCKED"))
        assertTrue(run(TetherScripts.checked(TetherScripts.open(path), "@OPEN")).contains("@OPEN"))
        File(state, "routes_${path.table}").writeText("default dev ${path.out} scope link\n")
        run("ip -4 rule add pref 12500 iif ${path.ap} lookup ${path.table}")
    }

    @Test fun routeVerificationRejectsMissingDnsGuardAndDuplicatePolicyRules() {
        val path = TetherPath("wlan2", "nms0", "1500", false, "198.18.0.2")
        installRoute(path)
        val verify = TetherScripts.checked(TetherScripts.verify(path), "@VERIFIED")
        assertTrue(run(verify).contains("@VERIFIED"))
        run("iptables -I FORWARD 1 -j ACCEPT")
        assertFalse("an OEM ACCEPT ahead of the guard must not pass health checks", run(verify).contains("@VERIFIED"))
        run("iptables -D FORWARD -j ACCEPT")
        run("iptables -t nat -D NMS_DNS -i wlan2 -p udp --dport 53 -j DNAT --to-destination 198.18.0.2")
        assertFalse(run(verify).contains("@VERIFIED"))
        run("iptables -t nat -A NMS_DNS -i wlan2 -p udp --dport 53 -j DNAT --to-destination 198.18.0.2")
        assertTrue(run(verify).contains("@VERIFIED"))
        run("ip -4 rule add pref 12500 iif wlan2 lookup 1500")
        assertFalse(run(verify).contains("@VERIFIED"))
        File(state, "rules").writeText("12500: from all iif wlan2 lookup 1500\n")
        assertFalse("unreachable guard must exist", run(verify).contains("@VERIFIED"))
    }

    @Test fun normalVpnRequiresNatMssAndAnUpInterface() {
        val path = TetherPath("wlan2", "tun0", "tun0", true, "1.1.1.1")
        installRoute(path)
        val verify = TetherScripts.checked(TetherScripts.verify(path), "@VERIFIED")
        assertTrue(run(verify).contains("@VERIFIED"))
        File(state, "link_tun0").writeText("20: tun0: <POINTOPOINT> mtu 1280 state DOWN\n")
        assertFalse(run(verify).contains("@VERIFIED"))
        File(state, "link_tun0").delete()
        run("iptables -t mangle -F NMS_MSS")
        assertFalse(run(verify).contains("@VERIFIED"))
    }

    @Test fun clientIpv6IsRefusedWithDropBehind() {
        installRoute(TetherPath("wlan2", "tun0", "tun0", true, "1.1.1.1"))
        assertEquals(listOf(
            "-A NMS_FWD6 -i wlan2 -p tcp -j REJECT --reject-with tcp-reset",
            "-A NMS_FWD6 -i wlan2 -j REJECT --reject-with icmp6-adm-prohibited",
            "-A NMS_FWD6 -i wlan2 -j DROP",
        ), chain("ip6tables_filter_NMS_FWD6").lines().filter { it.isNotBlank() })
    }

    @Test fun normalVpnLimitsTheServersMssToTheVpnMtu() {
        val path = TetherPath("wlan2", "tun0", "tun0", true, "1.1.1.1")
        installRoute(path)
        assertTrue(chain("iptables_mangle_NMS_MSS").contains("-i tun0 -o wlan2 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1240"))
        // A VPN that comes back with another MTU no longer matches the installed route.
        File(state, "link_tun0").writeText("20: tun0: <POINTOPOINT,UP> mtu 1420 state UNKNOWN\n")
        assertFalse(run(TetherScripts.checked(TetherScripts.verify(path), "@VERIFIED")).contains("@VERIFIED"))
    }

    @Test fun routeTeardownLeavesHoldAndPhoneTrafficUntouchedUntilExplicitRelease() {
        val path = TetherPath("wlan2", "nms0", "1500", false, "198.18.0.2")
        File(state, "iptables_filter_OUTPUT").writeText("-A OUTPUT -j PHONE_VPN\n")
        installRoute(path)
        run(TetherScripts.clearRoute())
        assertFalse(File(state, "iptables_filter_NMS_FWD").exists())
        assertTrue(chain("iptables_filter_FORWARD").contains("-j NMS_HOLD"))
        assertTrue(chain("ip6tables_filter_FORWARD").contains("-j NMS_HOLD6"))
        assertTrue(chain("iptables_filter_NMS_HOLD").contains("-i wlan2 -j DROP"))
        assertEquals("-A OUTPUT -j PHONE_VPN\n", chain("iptables_filter_OUTPUT"))
        repeat(2) { run(TetherScripts.hold("wlan2")) }
        assertEquals(1, chain("iptables_filter_FORWARD").lineSequence().count { it == "-A FORWARD -j NMS_HOLD" })
        assertTrue(run(TetherScripts.checked(TetherScripts.clearHold(), "@RELEASED")).contains("@RELEASED"))
        assertFalse(chain("iptables_filter_FORWARD").contains("NMS_HOLD"))
        assertFalse(chain("ip6tables_filter_FORWARD").contains("NMS_HOLD6"))
    }

    @Test fun checkedRouteFailureKeepsSharedShellAliveAndRejectsUnsafeInputs() {
        val result = run(TetherScripts.checked("false\necho should-not-run", "@SUCCESS") + "\necho @SHELL_ALIVE")
        assertFalse(result.contains("@SUCCESS")); assertFalse(result.contains("should-not-run"))
        assertTrue(result.contains("@SHELL_ALIVE"))
        assertThrows(IllegalArgumentException::class.java) { TetherPath("wlan2; id", "nms0", "1500", false, "1.1.1.1") }
        assertThrows(IllegalArgumentException::class.java) { TetherPath("wlan2", "nms0", "1500", false, "1.1.1.1; id") }
    }

    @Test fun proxyConfigAndMultilinePasswordRemainDataInTheShellAndYaml() {
        val config = File(dir, "proxy ' config.yml")
        val attack = "password\nEOF\ntouch ${File(dir, "injected").absolutePath}\n$(id) ' \\\""
        val yaml = "socks5:\n  username: ${hevYamlString("user")}\n  password: ${hevYamlString(attack)}\n"
        assertTrue(run(TetherScripts.checked(TetherScripts.privateConfig(config.absolutePath, yaml), "@WRITTEN")).contains("@WRITTEN"))
        assertEquals(yaml, config.readText())
        assertFalse(File(dir, "injected").exists())
        assertTrue(yaml.contains("\\nEOF\\n"))
        assertEquals("\"a\\\"b\\\\c\\n\\t\"", hevYamlString("a\"b\\c\n\t"))
    }

    companion object {
        private val ip = """
            #!/bin/sh
            [ "${'$'}1" != -4 ] || shift
            file="${'$'}NMS_STATE/rules"
            case "${'$'}1 ${'$'}2" in
              'link show')
                if [ -f "${'$'}NMS_STATE/link_${'$'}4" ]; then cat "${'$'}NMS_STATE/link_${'$'}4";
                else echo "20: ${'$'}4: <POINTOPOINT,UP> mtu 1280 state UNKNOWN"; fi;;
              'route show') cat "${'$'}NMS_STATE/routes_${'$'}4";;
              'route flush') rm -f "${'$'}NMS_STATE/routes_${'$'}4";;
              'rule show') cat "${'$'}file";;
              'rule add')
                shift 2; pref=; iface=; action=
                while [ "${'$'}#" -gt 0 ]; do
                  case "${'$'}1" in
                    pref) pref="${'$'}2"; shift 2;;
                    iif) iface="${'$'}2"; shift 2;;
                    lookup) action="lookup ${'$'}2"; shift 2;;
                    unreachable) action=unreachable; shift;;
                    *) exit 2;;
                  esac
                done
                echo "${'$'}pref: from all iif ${'$'}iface ${'$'}action" >> "${'$'}file";;
              'rule del')
                grep -q "^${'$'}4:" "${'$'}file" || exit 1
                grep -v "^${'$'}4:" "${'$'}file" > "${'$'}file.tmp"
                mv "${'$'}file.tmp" "${'$'}file";;
              *) exit 2;;
            esac
        """.trimIndent() + "\n"

        private val settings = """
            #!/bin/sh
            file="${'$'}NMS_STATE/${'$'}{0##*/}"
            case "${'$'}1" in
              get) if [ -f "${'$'}file" ]; then cat "${'$'}file"; else echo null; fi;;
              put) printf '%s' "${'$'}4" > "${'$'}file";;
              delete) rm -f "${'$'}file";;
              *) exit 2;;
            esac
        """.trimIndent() + "\n"

        private val firewall = """
            #!/bin/sh
            table=filter
            while [ "${'$'}#" -gt 0 ]; do
              case "${'$'}1" in
                -w) shift 2;;
                -t) table="${'$'}2"; shift 2;;
                *) break;;
              esac
            done
            op="${'$'}1"; name="${'$'}2"; shift 2
            file="${'$'}NMS_STATE/${'$'}{0##*/}_${'$'}{table}_${'$'}name"
            case "${'$'}op" in
              -N) [ ! -f "${'$'}file" ] || exit 1; : > "${'$'}file";;
              -S) [ -f "${'$'}file" ] || exit 1; echo "-N ${'$'}name"; cat "${'$'}file";;
              -C) [ -f "${'$'}file" ] || exit 1; grep -Fx -- "-A ${'$'}name ${'$'}*" "${'$'}file" >/dev/null;;
              -A|-I)
                [ -f "${'$'}file" ] || exit 1
                case "${'$'}*" in *'--ttl-set'*) [ "${'$'}NMS_FAIL_TTL" != 1 ] || { echo 'TTL target unavailable'; exit 2; };; esac
                case "${'$'}*" in *'--hl-set'*) [ "${'$'}NMS_FAIL_HL" != 1 ] || { echo 'HL target unavailable'; exit 2; };; esac
                if [ "${'$'}op" = -I ]; then
                  shift
                  { echo "-A ${'$'}name ${'$'}*"; cat "${'$'}file"; } > "${'$'}file.tmp"
                  mv "${'$'}file.tmp" "${'$'}file"
                else echo "-A ${'$'}name ${'$'}*" >> "${'$'}file"; fi;;
              -D)
                case "${'$'}*" in
                  *[!0-9]*) :;;
                  *) sed "${'$'}1 d" "${'$'}file" > "${'$'}file.tmp"; mv "${'$'}file.tmp" "${'$'}file"; exit 0;;
                esac
                grep -Fx -- "-A ${'$'}name ${'$'}*" "${'$'}file" >/dev/null || exit 1
                grep -Fxv -- "-A ${'$'}name ${'$'}*" "${'$'}file" > "${'$'}file.tmp"
                mv "${'$'}file.tmp" "${'$'}file";;
              -F) [ -f "${'$'}file" ] || exit 1; : > "${'$'}file";;
              -X) rm "${'$'}file";;
              *) exit 2;;
            esac
        """.trimIndent() + "\n"
    }
}
