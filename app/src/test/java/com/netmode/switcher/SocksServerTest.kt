package com.netmode.switcher

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual loopback TCP/UDP sockets. No VPN, external server or Android method stubs. */
class SocksServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private class Relay(limits: SocksLimits = SocksLimits()) : AutoCloseable {
        val problems = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val server = SocksServer("user", "pass", { null }, limits) { line, problem -> if (problem) problems.add(line) }
        private val listener = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        private val thread = Thread { server.serve(listener) }.apply { isDaemon = true; start() }
        override fun close() { listener.close(); thread.join(3_000); assertFalse("listener did not stop", thread.isAlive) }
    }

    private fun login(relay: Relay, password: String = "pass"): Socket {
        val c = Socket(loopback, relay.port)
        c.soTimeout = 5_000
        val out = c.getOutputStream()
        val input = DataInputStream(c.getInputStream())
        out.write(byteArrayOf(5, 1, 2))
        assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
        out.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(password.length.toByte()) + password.toByteArray())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(if (password == "pass") 0 else 1, input.readUnsignedByte())
        return c
    }

    private fun request(c: Socket, command: Int, port: Int): Pair<Int, InetSocketAddress> {
        c.getOutputStream().write(byteArrayOf(5, command.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
        val input = DataInputStream(c.getInputStream())
        assertEquals(5, input.readUnsignedByte())
        val code = input.readUnsignedByte()
        assertEquals(0, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
        val addr = ByteArray(4).also { input.readFully(it) }
        return code to InetSocketAddress(InetAddress.getByAddress(addr), input.readUnsignedShort())
    }

    private fun stat(relay: Relay, name: String): Long = relay.server.stats().lineSequence()
        .first { it.startsWith("$name=") }.substringAfter('=').toLong()

    private fun awaitEmpty(relay: Relay) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (stat(relay, "sessions") != 0L && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("TCP/control session leaked", 0L, stat(relay, "sessions"))
        assertEquals("UDP permit leaked", 0L, stat(relay, "udp_sessions"))
    }

    private class DigestTarget : AutoCloseable {
        private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = it.getInputStream().read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                        it.getOutputStream().write(digest.digest())
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(2_000); pool.shutdownNow() }
    }

    private fun transfer(relay: Relay, target: DigestTarget, size: Int) {
        login(relay).use { c ->
            assertEquals(0, request(c, 1, target.port).first)
            val block = ByteArray(16 * 1024) { ((it * 31) xor 0x5a).toByte() }
            val expected = MessageDigest.getInstance("SHA-256")
            var left = size
            while (left > 0) {
                val n = minOf(left, block.size)
                c.getOutputStream().write(block, 0, n)
                expected.update(block, 0, n)
                left -= n
            }
            c.shutdownOutput()
            val actual = ByteArray(32).also { DataInputStream(c.getInputStream()).readFully(it) }
            assertArrayEquals("data or half-close response corrupted", expected.digest(), actual)
            assertEquals(-1, c.getInputStream().read())
        }
    }

    @Test fun tcpPreservesLargeTransfersAndHalfClose() {
        Relay().use { relay -> DigestTarget().use { target ->
            transfer(relay, target, 16 * 1024 * 1024)
            awaitEmpty(relay)
            assertEquals(16L * 1024 * 1024, stat(relay, "tcp_up"))
            assertEquals(32L, stat(relay, "tcp_down"))
        } }
    }

    @Test fun parallelTcpSessionsReleaseTheirPermits() {
        Relay(SocksLimits(sessions = 16, udpSessions = 4)).use { relay -> DigestTarget().use { target ->
            val clients = Executors.newFixedThreadPool(16)
            try {
                val tasks = (0 until 16).map { clients.submit { transfer(relay, target, 256 * 1024) } }
                tasks.forEach { it.get(10, TimeUnit.SECONDS) }
                awaitEmpty(relay)
                transfer(relay, target, 1024) // permits are reusable after both TCP halves finish
            } finally { clients.shutdownNow() }
        } }
    }

    @Test fun invalidLoginAndIncompleteHandshakeDoNotConsumeSlotsForever() {
        Relay(SocksLimits(sessions = 1, udpSessions = 1, handshakeMs = 200)).use { relay ->
            login(relay, "bad").use { assertEquals(-1, it.getInputStream().read()) }
            awaitEmpty(relay)
            Socket(loopback, relay.port).use { c ->
                c.soTimeout = 2_000
                assertEquals(-1, c.getInputStream().read())
            }
            awaitEmpty(relay)
            login(relay).close()
            awaitEmpty(relay)
        }
    }

    private class EchoUdp : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port get() = socket.localPort
        private val thread = Thread {
            val p = DatagramPacket(ByteArray(65535), 65535)
            while (!socket.isClosed) {
                try { p.length = p.data.size; socket.receive(p); socket.send(p) }
                catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(2_000) }
    }

    private fun udpMessage(port: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()) + payload

    private fun roundTrip(c: DatagramSocket, relay: InetSocketAddress, targetPort: Int, payload: ByteArray) {
        val message = udpMessage(targetPort, payload)
        c.send(DatagramPacket(message, message.size, relay))
        val back = DatagramPacket(ByteArray(65535), 65535)
        c.receive(back)
        assertArrayEquals(message, back.data.copyOfRange(back.offset, back.offset + back.length))
    }

    @Test fun udpDropsMalformedPacketsAndPreservesPayloadsWithoutClosingAssociation() {
        Relay().use { relay -> EchoUdp().use { echo -> login(relay).use { control ->
            val (code, address) = request(control, 3, 0)
            assertEquals(0, code)
            DatagramSocket(InetSocketAddress(loopback, 0)).use { client ->
                client.soTimeout = 3_000
                for (bad in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0, 0, 1),
                    byteArrayOf(0, 0, 1, 1, 127, 0, 0, 1, 1, 1), byteArrayOf(0, 0, 0, 3, 50, 1))) {
                    client.send(DatagramPacket(bad, bad.size, address))
                }
                for (size in listOf(0, 32, 1200, 16000, 60000)) {
                    roundTrip(client, address, echo.port, ByteArray(size) { (it * 19).toByte() })
                }
                // An unrelated local UDP source must not take over an authenticated association.
                DatagramSocket(InetSocketAddress(loopback, 0)).use { other ->
                    other.soTimeout = 150
                    val message = udpMessage(echo.port, byteArrayOf(9))
                    other.send(DatagramPacket(message, message.size, address))
                    assertThrows(SocketTimeoutException::class.java) { other.receive(DatagramPacket(ByteArray(50), 50)) }
                }
                roundTrip(client, address, echo.port, byteArrayOf(1, 2, 3))
            }
        } }; awaitEmpty(relay); assertTrue(stat(relay, "dropped") >= 6) }
    }

    @Test fun udpLimitAndControlClosureReleaseEveryResource() {
        Relay(SocksLimits(sessions = 3, udpSessions = 1)).use { relay -> EchoUdp().use { echo ->
            repeat(20) {
            val first = login(relay)
            assertEquals(0, request(first, 3, 0).first)
            login(relay).use { second -> assertEquals(1, request(second, 3, 0).first) }
            first.close()
            awaitEmpty(relay)
            login(relay).use { third ->
                val address = request(third, 3, 0).second
                DatagramSocket(InetSocketAddress(loopback, 0)).use { client ->
                    client.soTimeout = 3_000
                    try { roundTrip(client, address, echo.port, byteArrayOf(4, 5, 6)) }
                    catch (e: Exception) { throw AssertionError(relay.server.stats() + relay.problems.joinToString(), e) }
                }
            }
            awaitEmpty(relay)
            }
        } }
    }

    @Test fun activeOneWayUdpSurvivesAndIdleAssociationClosesControlConnection() {
        Relay(SocksLimits(sessions = 2, udpSessions = 1, udpIdleMs = 250, udpPollMs = 40)).use { relay ->
            DatagramSocket(InetSocketAddress(loopback, 0)).use { sink -> login(relay).use { control ->
                val address = request(control, 3, 0).second
                DatagramSocket(InetSocketAddress(loopback, 0)).use { client ->
                    val message = udpMessage(sink.localPort, byteArrayOf(7))
                    repeat(12) { client.send(DatagramPacket(message, message.size, address)); Thread.sleep(40) }
                    assertEquals(1L, stat(relay, "udp_sessions"))
                    control.soTimeout = 2_000
                    assertEquals(-1, control.getInputStream().read())
                }
            } }
            awaitEmpty(relay)
        }
    }

    @Test fun shutdownClosesEstablishedConnectionsAndReleasesSlots() {
        val relay = Relay()
        val control = login(relay)
        assertEquals(0, request(control, 3, 0).first)
        relay.close()
        assertEquals(-1, control.getInputStream().read())
        control.close()
        awaitEmpty(relay)
    }

    @Test fun saturatedRelayStillAnswersHealthGreetingAndKeepsExistingSession() {
        Relay(SocksLimits(sessions = 1, udpSessions = 1)).use { relay -> login(relay).use { control ->
            assertEquals(0, request(control, 3, 0).first)
            Socket(loopback, relay.port).use { probe ->
                probe.soTimeout = 2_000
                probe.getOutputStream().write(byteArrayOf(5, 1, 2))
                assertEquals(5, probe.getInputStream().read())
                assertEquals(2, probe.getInputStream().read())
            }
            login(relay).use { denied -> assertEquals(1, request(denied, 3, 0).first) }
            assertEquals(1L, stat(relay, "udp_sessions"))
        }; awaitEmpty(relay) }
    }

    @Test fun udpDecoderBoundsCacheAndHeaderWrappingPreservePayload() {
        var lookups = 0
        val decoder = SocksUdpDecoder { lookups++; loopback }
        val name = "example.com".toByteArray()
        val request = byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf(1, 0) + byteArrayOf(10, 20, 30)
        repeat(2) { assertTrue(decoder.decode(request, request.size)) }
        assertEquals(1, lookups)
        assertEquals(256, decoder.port)
        assertEquals(request.size - 3, decoder.payloadOffset)
        for (size in 0 until request.size - 3) assertFalse(decoder.decode(request, size))
        val buf = ByteArray(100)
        val payload = ByteArray(50) { it.toByte() }.also { it.copyInto(buf, 22) }
        val start = wrapSocksUdp(buf, 22, loopback, 12345)
        assertEquals(12, start)
        assertTrue(decoder.decode(buf.copyOfRange(start, 72), 60))
        assertArrayEquals(payload, buf.copyOfRange(22, 72))
    }
}
