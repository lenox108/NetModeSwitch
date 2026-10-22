package com.netmode.switcher

/** Shell fragments run in a subshell: a failure must never close libsu's shared shell. */
internal object NetworkScripts {
    private val iface = Regex("^[A-Za-z0-9_.-]{1,15}$")
    private const val V6 = "/proc/sys/net/ipv6/conf"

    fun validInterface(name: String): Boolean = iface.matches(name)

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun clearTtl(): String = """
        (
          set -e
          if iptables -w 5 -t mangle -S NMS_TTL >/dev/null 2>&1; then
            while iptables -w 5 -t mangle -C FORWARD -j NMS_TTL 2>/dev/null; do
              iptables -w 5 -t mangle -D FORWARD -j NMS_TTL
            done
            iptables -w 5 -t mangle -F NMS_TTL
            iptables -w 5 -t mangle -X NMS_TTL
          fi
          echo @TTL_CLEARED
        )
    """.trimIndent()

    /** An unreferenced chain tests both the userspace extension and the kernel target. */
    fun probeTtl(): String = """
        (
          set -e
          iptables -w 5 -t mangle -N NMS_TTL_TEST 2>/dev/null || iptables -w 5 -t mangle -F NMS_TTL_TEST
          iptables -w 5 -t mangle -A NMS_TTL_TEST -j TTL --ttl-set 64
          echo @TTL_SUPPORTED
        )
        (
          iptables -w 5 -t mangle -F NMS_TTL_TEST 2>/dev/null
          iptables -w 5 -t mangle -X NMS_TTL_TEST 2>/dev/null
        )
    """.trimIndent()

    fun ttl(ap: String, upstreams: List<String>, value: Int): String {
        require(validInterface(ap) && upstreams.isNotEmpty() && upstreams.all(::validInterface))
        require(value in 1..255)
        val rules = upstreams.distinct().map { "-i $ap -o $it -j TTL --ttl-set $value" }
        val checks = rules.joinToString(" &&\n") { "iptables -w 5 -t mangle -C NMS_TTL $it 2>/dev/null" }
        val append = rules.joinToString("\n") { "iptables -w 5 -t mangle -A NMS_TTL $it" }
        return """
            (
              set -e
              if iptables -w 5 -t mangle -C FORWARD -j NMS_TTL 2>/dev/null &&
                 $checks &&
                 [ "${'$'}(iptables -w 5 -t mangle -S NMS_TTL | wc -l)" -eq ${rules.size + 1} ]; then
                echo @TTL_OK
                exit 0
              fi
              ${clearTtl()}
              iptables -w 5 -t mangle -N NMS_TTL
              $append
              iptables -w 5 -t mangle -I FORWARD 1 -j NMS_TTL
              echo @TTL_OK
            )
        """.trimIndent()
    }

    fun clearTetherIpv6(): String = """
        (
          set -e
          if ip6tables -w 5 -S NMS_TETHER6 >/dev/null 2>&1; then
            while ip6tables -w 5 -C FORWARD -j NMS_TETHER6 2>/dev/null; do
              ip6tables -w 5 -D FORWARD -j NMS_TETHER6
            done
            ip6tables -w 5 -F NMS_TETHER6
            ip6tables -w 5 -X NMS_TETHER6
          fi
          echo @TETHER6_CLEARED
        )
    """.trimIndent()

    /** Only routed client traffic; never alter phone sysctls, INPUT, or OUTPUT. */
    fun tetherIpv6(interfaces: List<String>): String {
        val rules = tetherIpv6Rules(interfaces)
        val checks = rules.joinToString(" &&\n") { "ip6tables -w 5 -C NMS_TETHER6 $it 2>/dev/null" }
        val append = rules.joinToString("\n") { "ip6tables -w 5 -A NMS_TETHER6 $it" }
        return """
            (
              set -e
              if $checks && [ "${'$'}(ip6tables -w 5 -S NMS_TETHER6 | wc -l)" -eq ${rules.size + 1} ]; then
                :
              else
                ${clearTetherIpv6()}
                ip6tables -w 5 -N NMS_TETHER6
                $append
              fi
              while ip6tables -w 5 -C FORWARD -j NMS_TETHER6 2>/dev/null; do
                ip6tables -w 5 -D FORWARD -j NMS_TETHER6
              done
              ip6tables -w 5 -I FORWARD 1 -j NMS_TETHER6
              echo @TETHER6_BLOCKED
            )
        """.trimIndent()
    }

    fun inspectTetherIpv6(interfaces: List<String>): String {
        val rules = tetherIpv6Rules(interfaces)
        return "(\nset -e\nip6tables -w 5 -C FORWARD -j NMS_TETHER6\n" +
            rules.joinToString("\n") { "ip6tables -w 5 -C NMS_TETHER6 $it" } + "\necho @TETHER6_OK\n)"
    }

    private fun tetherIpv6Rules(interfaces: List<String>): List<String> {
        require(interfaces.isNotEmpty() && interfaces.all(::validInterface))
        return interfaces.distinct().flatMap { listOf("-i $it -j DROP", "-o $it -j DROP") }
    }

    /** Migration from 3.13 only: undo that release's phone-wide IPv6 setting. */
    fun restoreLegacyIpv6(previous: Map<String, Int>?): String {
        require(previous == null || previous.all { validInterface(it.key) && it.value in 0..1 })
        val restore = if (previous == null) "" else buildString {
            // Interfaces created while disabled inherit the original default on restoration.
            appendLine("for f in $V6/*/disable_ipv6; do")
            appendLine("  [ -f \"\${f}\" ] || continue")
            appendLine("  n=\${f%/disable_ipv6}; n=\${n##*/}")
            appendLine("  case \"\${n}\" in")
            appendLine("    all) continue;;")
            previous.forEach { (name, value) -> appendLine("    $name) v=$value;;") }
            appendLine("    *) v=${previous["default"] ?: 0};;")
            appendLine("  esac")
            appendLine("  [ \"\$(cat \"\${f}\")\" = \"\${v}\" ] || echo \"\${v}\" > \"\${f}\"")
            appendLine("done")
        }
        return """
            (
              set -e
              $restore
              if ip6tables -w 5 -S NMS_NO6 >/dev/null 2>&1; then
                for hook in INPUT OUTPUT FORWARD; do
                  while ip6tables -w 5 -C "${'$'}hook" -j NMS_NO6 2>/dev/null; do
                    ip6tables -w 5 -D "${'$'}hook" -j NMS_NO6
                  done
                done
                ip6tables -w 5 -F NMS_NO6
                ip6tables -w 5 -X NMS_NO6
              fi
              echo @IPV6_RESTORED
            )
        """.trimIndent()
    }

    fun disableOffload(): String = """
        (
          set -e
          [ "${'$'}(settings get global tether_offload_disabled)" = 1 ] || settings put global tether_offload_disabled 1
          [ "${'$'}(device_config get connectivity override_tether_enable_bpf_offload)" = false ] ||
            device_config put connectivity override_tether_enable_bpf_offload false
          [ "${'$'}(settings get global tether_offload_disabled)" = 1 ]
          [ "${'$'}(device_config get connectivity override_tether_enable_bpf_offload)" = false ]
          echo @OFFLOAD_OFF
        )
    """.trimIndent()

    fun restoreOffload(hardware: String, bpf: String): String {
        fun restore(command: String, key: String, value: String) =
            if (value == "null") "$command delete $key" else "$command put $key ${quote(value)}"
        return """
            (
              set -e
              if [ "${'$'}(settings get global tether_offload_disabled)" = 1 ]; then
                ${restore("settings", "global tether_offload_disabled", hardware)}
                [ "${'$'}(settings get global tether_offload_disabled)" = ${quote(hardware)} ]
              fi
              if [ "${'$'}(device_config get connectivity override_tether_enable_bpf_offload)" = false ]; then
                ${restore("device_config", "connectivity override_tether_enable_bpf_offload", bpf)}
                [ "${'$'}(device_config get connectivity override_tether_enable_bpf_offload)" = ${quote(bpf)} ]
              fi
              echo @OFFLOAD_RESTORED
            )
        """.trimIndent()
    }
}
