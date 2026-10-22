package com.netmode.switcher

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** IPv4 TTL for direct cellular tethering and IPv6 blocking only for tethered clients. */
object NetworkTweaks {
    private val lock = Mutex()
    @Volatile var status: String = ""
        private set

    fun needed(): Boolean = Prefs.tetherTtlEnabled || Prefs.disableTetherIpv6 || Prefs.networkTweaksState != null

    private suspend fun checked(script: String, marker: String): String {
        val out = Root.exec(script, 30_000)
        check(out.lineSequence().any { it.trim() == marker }) {
            out.lineSequence().filterNot { it.startsWith("@") }.joinToString(" ").take(240)
                .ifBlank { "команда не выполнилась" }
        }
        return out
    }

    private fun ttlFailure(e: Exception): String {
        val message = e.message.orEmpty()
        return if (message.contains("TTL", ignoreCase = true) &&
            listOf("not supported", "unavailable", "Couldn't load", "No chain/target/match").any { message.contains(it, ignoreCase = true) }) {
            "ядро не поддерживает изменение TTL. Нужна поддержка TTL / xt_HL в ядре; можно использовать раздачу через VPN / Proxy"
        } else message
    }

    suspend fun apply(): String = lock.withLock {
        // Avoid a root probe on every network event for users who never enabled either switch.
        if (!needed()) return@withLock "".also { status = it }
        if (Privileged.choose() != Backend.ROOT) return@withLock "TTL / IPv6: нужен root".also { status = it }
        val saved = JSONObject(Prefs.networkTweaksState ?: "{}")
        fun save() { Prefs.networkTweaksState = if (saved.length() == 0) null else saved.toString() }
        val lines = mutableListOf<String>()
        suspend fun attempt(name: String, action: suspend () -> Unit) {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                lines += "$name: ошибка — ${e.message}"
            }
        }

        // Offload may skip netfilter entirely, including the tethered clients' IPv6 firewall.
        val ttl = Prefs.tetherTtlEnabled && Prefs.tetherMode == TetherMode.DIRECT
        var offloadReady = false
        attempt("Ускорение раздачи") {
            if (ttl || Prefs.disableTetherIpv6) {
                if (!saved.has("offload")) {
                    val out = Root.exec("echo @HW \$(settings get global tether_offload_disabled)\necho @BPF \$(device_config get connectivity override_tether_enable_bpf_offload)")
                    fun value(tag: String) = out.lineSequence().firstOrNull { it.startsWith("$tag ") }
                        ?.substringAfter(' ')?.trim()?.takeIf { it.isNotEmpty() }
                        ?: error("не удалось сохранить настройки ускорения")
                    saved.put("offload", JSONObject().put("hardware", value("@HW")).put("bpf", value("@BPF")))
                    save() // Persist before the first mutation, including across process death.
                }
                checked(NetworkScripts.disableOffload(), "@OFFLOAD_OFF")
                offloadReady = true
            } else saved.optJSONObject("offload")?.let { old ->
                checked(NetworkScripts.restoreOffload(old.getString("hardware"), old.getString("bpf")), "@OFFLOAD_RESTORED")
                saved.remove("offload")
                save()
            }
        }

        attempt("IPv6") {
            val interfaces = if (Prefs.disableTetherIpv6) TetherRoute.tetherInterfaces() else emptyList()
            if (interfaces.isNotEmpty()) {
                saved.put("tetherIpv6", true)
                save()
                checked(NetworkScripts.tetherIpv6(interfaces), "@TETHER6_BLOCKED")
                lines += "IPv6: заблокирован у устройств на раздаче (${interfaces.joinToString()})"
                if (!offloadReady) lines += "IPv6 раздачи: ускорение не отключилось — перезапусти точку доступа и проверь блокировку"
            } else {
                checked(NetworkScripts.clearTetherIpv6(), "@TETHER6_CLEARED")
                saved.remove("tetherIpv6")
                save()
                if (Prefs.disableTetherIpv6) lines += "IPv6: блокировка включится вместе с раздачей"
            }
        }

        // Restore the phone even if installing the client guard failed. A failed tethering
        // command must not leave AdGuard or the phone's VPN with the old global IPv6 ban.
        // When the guard succeeded it stays in place throughout this migration.
        attempt("IPv6 телефона") {
            saved.optJSONObject("ipv6")?.let { old ->
                val boot = Root.exec("cat /proc/sys/kernel/random/boot_id").trim()
                check(boot.isNotEmpty()) { "не удалось проверить загрузку" }
                // sysctls reset at boot; never restore values from an earlier boot to new interfaces.
                val values = if (boot == old.getString("boot")) old.getJSONObject("values").let { v ->
                    v.keys().asSequence().associateWith { v.getInt(it) }
                } else null
                checked(NetworkScripts.restoreLegacyIpv6(values), "@IPV6_RESTORED")
                saved.remove("ipv6")
                save()
                lines += "IPv6 телефона: прежние настройки восстановлены"
            }
        }

        attempt("TTL") {
            if (!ttl) {
                checked(NetworkScripts.clearTtl(), "@TTL_CLEARED")
                saved.remove("ttl")
                save()
                if (Prefs.tetherTtlEnabled) lines += "TTL: пауза — выбран VPN / Proxy"
            } else {
                val ap = TetherRoute.apInterface()
                val upstreams = cellularInterfaces()
                if (ap == null || upstreams.isEmpty() || !offloadReady) {
                    checked(NetworkScripts.clearTtl(), "@TTL_CLEARED")
                    saved.remove("ttl")
                    save()
                    lines += when {
                        !offloadReady -> "TTL: не применён — ускорение раздачи не отключилось"
                        ap == null -> "TTL: включится вместе с точкой доступа"
                        else -> "TTL: ожидание мобильной сети"
                    }
                } else {
                    val value = Prefs.tetherTtlValue.takeIf { it != 0 } ?: Root.exec("cat /proc/sys/net/ipv4/ip_default_ttl")
                        .trim().toIntOrNull()?.takeIf { it in 1..255 } ?: error("не удалось определить TTL телефона")
                    saved.put("ttl", true)
                    save()
                    try {
                        checked(NetworkScripts.ttl(ap, upstreams, value), "@TTL_OK")
                    } catch (e: Exception) {
                        Root.exec(NetworkScripts.clearTtl())
                        throw IllegalStateException(ttlFailure(e), e)
                    }
                    lines += "TTL: $value · правила установлены"
                }
            }
        }
        status = lines.joinToString("\n")
        status
    }

    /** Explicit egress interfaces keep Wi-Fi sharing, LAN traffic and the phone itself intact. */
    @Suppress("DEPRECATION")
    private fun cellularInterfaces(): List<String> {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.flatMap { network ->
            val caps = cm.getNetworkCapabilities(network)
            val name = cm.getLinkProperties(network)?.interfaceName
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && name != null) {
                // 464XLAT may expose a stacked IPv4 interface on IPv6 cellular networks.
                listOf(name, "v4-$name")
            } else emptyList()
        }.filter(NetworkScripts::validInterface).distinct()
    }

    /** Counters prove packets reached the rule; installation alone is not an operator test. */
    suspend fun inspect(): String = lock.withLock {
        if (Privileged.choose() != Backend.ROOT) return@withLock "Нужен root"
        buildList {
            if (status.isNotBlank()) add(status)
            if (Prefs.tetherTtlEnabled && Prefs.tetherMode == TetherMode.DIRECT) {
                try {
                    checked(NetworkScripts.probeTtl(), "@TTL_SUPPORTED")
                    add("Проверка TTL: ядро поддерживает изменение")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    add("Проверка TTL: ${ttlFailure(e)}")
                }
                val out = Root.exec("iptables -w 5 -t mangle -L NMS_TTL -nvx 2>/dev/null")
                val packets = Regex("(?m)^\\s*(\\d+)\\s+\\d+\\s+TTL\\b").findAll(out)
                    .sumOf { it.groupValues[1].toLongOrNull() ?: 0 }
                add(if (packets > 0) "Через правило TTL прошло пакетов: $packets"
                    else "Пакетов через правило TTL пока нет. Создай трафик на подключённом устройстве; если счётчик не растёт, перезапусти раздачу.")
            }
            if (Prefs.disableTetherIpv6) {
                val interfaces = TetherRoute.tetherInterfaces()
                if (interfaces.isEmpty()) add("Проверка IPv6: включи раздачу для проверки блокировки устройств.")
                else {
                    val out = Root.exec(NetworkScripts.inspectTetherIpv6(interfaces))
                    val blocked = out.lineSequence().any { it.trim() == "@TETHER6_OK" }
                    add(if (blocked) "Проверка IPv6: трафик устройств на раздаче заблокирован в обоих направлениях. IPv6 телефона не отключается."
                        else "Проверка IPv6: блокировка раздачи неполная — смотри ошибку выше.")
                }
            }
        }.joinToString("\n\n").ifBlank { "Фиксация TTL и отключение IPv6 выключены" }
    }
}
