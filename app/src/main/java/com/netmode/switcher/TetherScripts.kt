package com.netmode.switcher

/** Route setup and its verification share the same values; all shell inputs are validated. */
internal data class TetherPath(
    val ap: String,
    val out: String,
    val table: String,
    val nat: Boolean,
    val dns: String,
) {
    init {
        require(NetworkScripts.validInterface(ap) && NetworkScripts.validInterface(out))
        require(NetworkScripts.validInterface(table))
        require(dns == "1.1.1.1" || dns == "198.18.0.2")
    }
}

internal object TetherScripts {
    const val PREF = 12500
    const val TABLE = 1500
    const val TUN = "nms0"

    fun checked(body: String, marker: String): String = "(\nset -e\n$body\necho $marker\n)"

    /** Configuration is data even if an imported proxy password contains a heredoc delimiter. */
    fun privateConfig(path: String, content: String): String {
        val data = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        return "(umask 077; printf '%s' ${NetworkScripts.quote(data)} | base64 -d > ${NetworkScripts.quote(path)})\n" +
            "chmod 600 ${NetworkScripts.quote(path)}"
    }

    /** Keep this separate from the route chains: teardown must never open a direct path. */
    fun hold(ap: String): String {
        require(NetworkScripts.validInterface(ap))
        return listOf("iptables" to "NMS_HOLD", "ip6tables" to "NMS_HOLD6").joinToString("\n") { (tool, chain) ->
            """
                $tool -w 5 -N $chain 2>/dev/null || $tool -w 5 -S $chain >/dev/null
                $tool -w 5 -C $chain -i $ap -j DROP 2>/dev/null || $tool -w 5 -A $chain -i $ap -j DROP
                $tool -w 5 -C $chain -o $ap -j DROP 2>/dev/null || $tool -w 5 -A $chain -o $ap -j DROP
                $tool -w 5 -I FORWARD 1 -j $chain
                # Keep the new first hook throughout cleanup, including after a failed retry.
                n=0; duplicates=''
                while IFS= read -r rule; do
                  case "${'$'}rule" in -A\ *) n=${'$'}((n + 1));; *) continue;; esac
                  if [ "${'$'}n" -gt 1 ] && [ "${'$'}rule" = '-A FORWARD -j $chain' ]; then
                    duplicates="${'$'}n ${'$'}duplicates"
                  fi
                done <<NMS_RULES
                ${'$'}($tool -w 5 -S FORWARD)
                NMS_RULES
                for n in ${'$'}duplicates; do $tool -w 5 -D FORWARD "${'$'}n"; done
            """.trimIndent()
        }
    }

    fun clearChain(tool: String, table: String, hook: String, chain: String): String = """
        if $tool -w 5 -t $table -S $chain >/dev/null 2>&1; then
          while $tool -w 5 -t $table -C $hook -j $chain 2>/dev/null; do
            $tool -w 5 -t $table -D $hook -j $chain || break
          done
          $tool -w 5 -t $table -F $chain
          $tool -w 5 -t $table -X $chain
        fi
    """.trimIndent()

    fun clearHold(): String = clearChain("iptables", "filter", "FORWARD", "NMS_HOLD") + "\n" +
        clearChain("ip6tables", "filter", "FORWARD", "NMS_HOLD6")

    fun clearRoute(): String = """
        while ip -4 rule del pref $PREF 2>/dev/null; do :; done
        while ip -4 rule del pref ${PREF + 1} 2>/dev/null; do :; done
        ip -4 route flush table $TABLE 2>/dev/null
    """.trimIndent() + "\n" + listOf(
        clearChain("iptables", "filter", "FORWARD", "NMS_FWD"),
        clearChain("iptables", "nat", "POSTROUTING", "NMS_NAT"),
        clearChain("iptables", "nat", "PREROUTING", "NMS_DNS"),
        clearChain("ip6tables", "filter", "FORWARD", "NMS_FWD6"),
        clearChain("iptables", "mangle", "FORWARD", "NMS_MSS"),
    ).joinToString("\n")

    fun block(ap: String): String {
        require(NetworkScripts.validInterface(ap))
        return """
            iptables -w 5 -N NMS_FWD
            iptables -w 5 -A NMS_FWD -i $ap -j DROP
            iptables -w 5 -I FORWARD 1 -j NMS_FWD
            ip6tables -w 5 -N NMS_FWD6
            ip6tables -w 5 -A NMS_FWD6 -i $ap -j DROP
            ip6tables -w 5 -I FORWARD 1 -j NMS_FWD6
            ip -4 rule add pref ${PREF + 1} iif $ap unreachable
        """.trimIndent()
    }

    fun open(p: TetherPath): String = with(p) {
        """
            iptables -w 5 -I NMS_FWD 1 -i $out -o $ap -m state --state RELATED,ESTABLISHED -j ACCEPT
            iptables -w 5 -I NMS_FWD 1 -i $ap -o $out -j ACCEPT
            iptables -w 5 -t nat -N NMS_DNS
            iptables -w 5 -t nat -A NMS_DNS -i $ap -p udp --dport 53 -j DNAT --to-destination $dns
            iptables -w 5 -t nat -A NMS_DNS -i $ap -p tcp --dport 53 -j DNAT --to-destination 1.1.1.1
            iptables -w 5 -t nat -I PREROUTING 1 -j NMS_DNS
        """.trimIndent() + if (!nat) "" else "\n" + """
            iptables -w 5 -t nat -N NMS_NAT
            iptables -w 5 -t nat -A NMS_NAT -o $out -j MASQUERADE
            iptables -w 5 -t nat -I POSTROUTING 1 -j NMS_NAT
            iptables -w 5 -t mangle -N NMS_MSS
            iptables -w 5 -t mangle -A NMS_MSS -i $ap -o $out -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
            iptables -w 5 -t mangle -A NMS_MSS -i $out -o $ap -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
            iptables -w 5 -t mangle -I FORWARD 1 -j NMS_MSS
        """.trimIndent()
    }

    /** Check contents, both policy rules, and output routes; a PID alone is not sufficient. */
    fun verify(p: TetherPath): String = with(p) {
        """
            ip link show dev $out | grep -qE '<([^>]*,)?UP(,|>)'
            [ "${'$'}(iptables -w 5 -S FORWARD | sed -n '/^-A /{p;q;}')" = '-A FORWARD -j NMS_FWD' ]
            iptables -w 5 -C FORWARD -j NMS_FWD
            iptables -w 5 -C NMS_FWD -i $ap -o $out -j ACCEPT
            iptables -w 5 -C NMS_FWD -i $out -o $ap -m state --state RELATED,ESTABLISHED -j ACCEPT
            iptables -w 5 -C NMS_FWD -i $ap -j DROP
            ip6tables -w 5 -C FORWARD -j NMS_FWD6
            ip6tables -w 5 -C NMS_FWD6 -i $ap -j DROP
            case "${'$'}(ip6tables -w 5 -S FORWARD | sed -n '/^-A /{p;q;}')" in
              '-A FORWARD -j NMS_FWD6'|'-A FORWARD -j NMS_TETHER6') :;;
              *) exit 1;;
            esac
            [ "${'$'}(iptables -w 5 -t nat -S PREROUTING | sed -n '/^-A /{p;q;}')" = '-A PREROUTING -j NMS_DNS' ]
            iptables -w 5 -t nat -C PREROUTING -j NMS_DNS
            iptables -w 5 -t nat -C NMS_DNS -i $ap -p udp --dport 53 -j DNAT --to-destination $dns
            iptables -w 5 -t nat -C NMS_DNS -i $ap -p tcp --dport 53 -j DNAT --to-destination 1.1.1.1
            rules=${'$'}(ip -4 rule show)
            [ "${'$'}(printf '%s\n' "${'$'}rules" | grep -c '^$PREF:')" = 1 ]
            [ "${'$'}(printf '%s\n' "${'$'}rules" | grep -c '^${PREF + 1}:')" = 1 ]
            printf '%s\n' "${'$'}rules" | grep -qE '^$PREF:.*iif $ap .*lookup $table( |${'$'})'
            printf '%s\n' "${'$'}rules" | grep -qE '^${PREF + 1}:.*iif $ap .*unreachable'
            ip -4 route show table $table | grep -qE '(^| )dev $out( |${'$'})'
        """.trimIndent() + if (!nat) "" else "\n" + """
            iptables -w 5 -t nat -C POSTROUTING -j NMS_NAT
            iptables -w 5 -t nat -C NMS_NAT -o $out -j MASQUERADE
            iptables -w 5 -t mangle -C FORWARD -j NMS_MSS
            iptables -w 5 -t mangle -C NMS_MSS -i $ap -o $out -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
            iptables -w 5 -t mangle -C NMS_MSS -i $out -o $ap -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
        """.trimIndent()
    }
}

/** Length framing prevents settings containing separators from sharing the same identity. */
internal fun tetherIdentity(vararg values: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** JSON-style escapes are valid in YAML double-quoted strings, including embedded newlines. */
internal fun hevYamlString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32 || c.code in listOf(0x85, 0x2028, 0x2029)) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
