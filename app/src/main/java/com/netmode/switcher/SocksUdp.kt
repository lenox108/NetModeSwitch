package com.netmode.switcher

import java.net.InetAddress
import java.net.Inet4Address

/** Reused by one UDP reader. No stream wrappers or payload copies are needed per packet. */
internal class SocksUdpDecoder(private val resolve: (String) -> InetAddress) {
    lateinit var address: InetAddress
        private set
    var port = 0
        private set
    var payloadOffset = 0
        private set
    private var lastIpv4: Int? = null
    private var lastAddress: InetAddress? = null
    private data class Cached(val address: InetAddress, val until: Long)
    private val names = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 128
    }

    fun decode(buf: ByteArray, size: Int): Boolean {
        if (size < 4 || size > buf.size || buf[0] != 0.toByte() || buf[1] != 0.toByte() || buf[2] != 0.toByte()) return false
        val to: InetAddress
        val end: Int
        when (buf[3].toInt() and 255) {
            1 -> {
                if (size < 10) return false
                val key = ((buf[4].toInt() and 255) shl 24) or ((buf[5].toInt() and 255) shl 16) or
                    ((buf[6].toInt() and 255) shl 8) or (buf[7].toInt() and 255)
                if (lastIpv4 != key) {
                    lastAddress = InetAddress.getByAddress(buf.copyOfRange(4, 8))
                    lastIpv4 = key
                }
                to = lastAddress!!
                end = 8
            }
            3 -> {
                if (size < 5) return false
                val length = buf[4].toInt() and 255
                end = 5 + length
                if (length == 0 || end + 2 > size) return false
                val name = String(buf, 5, length, Charsets.US_ASCII)
                val now = System.nanoTime()
                val cached = names[name]
                to = if (cached != null && now < cached.until) cached.address else resolve(name).also {
                    names[name] = Cached(it, now + 60_000_000_000L)
                }
            }
            // The client route is IPv4; never accidentally open an IPv6 path on its behalf.
            else -> return false
        }
        if (to !is Inet4Address) return false
        port = ((buf[end].toInt() and 255) shl 8) or (buf[end + 1].toInt() and 255)
        if (port == 0) return false
        address = to
        payloadOffset = end + 2
        return true
    }
}

/** Payload is received with reserved headroom. Prepend only the small SOCKS address header. */
internal fun wrapSocksUdp(buf: ByteArray, offset: Int, address: InetAddress, port: Int): Int {
    val bytes = address.address
    val start = offset - bytes.size - 6
    require(start >= 0 && offset <= buf.size && port in 1..65535)
    buf[start] = 0
    buf[start + 1] = 0
    buf[start + 2] = 0
    buf[start + 3] = if (bytes.size == 16) 4 else 1
    bytes.copyInto(buf, start + 4)
    buf[offset - 2] = (port shr 8).toByte()
    buf[offset - 1] = port.toByte()
    return start
}
