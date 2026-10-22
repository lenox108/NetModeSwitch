package com.netmode.switcher

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Debug-only client inside Android: ADB TCP forwarding does not preserve TCP half-close. */
object SocksProbeCli {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args[0]
        val relayPort = args[1].toInt()
        val user = args[2].toByteArray()
        val pass = args[3].toByteArray()
        val target = InetAddress.getByName(args[4]).address
        val targetPort = args[5].toInt()
        check(target.size == 4 && user.size in 1..255 && pass.size <= 255)
        Socket("127.0.0.1", relayPort).use { control ->
            control.soTimeout = 10_000
            val input = DataInputStream(control.getInputStream())
            val out = control.getOutputStream()
            out.write(byteArrayOf(5, 1, 2))
            check(input.readUnsignedShort() == 0x0502)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            check(input.readUnsignedShort() == 0x0100)
            val command = if (mode == "udp") 3 else 1
            out.write(byteArrayOf(5, command.toByte(), 0, 1) + target + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            val reply = ByteArray(10).also(input::readFully)
            check(reply.take(4) == listOf<Byte>(5, 0, 0, 1)) { "SOCKS request failed" }
            if (mode == "udp") {
                val port = ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255)
                DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { socket ->
                    socket.soTimeout = 5_000
                    val payload = ByteArray(1200) { (it * 19).toByte() }
                    val packet = byteArrayOf(0, 0, 0, 1) + target + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()) + payload
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), port))
                    val answer = DatagramPacket(ByteArray(65535), 65535)
                    socket.receive(answer)
                    check(answer.data.copyOfRange(answer.offset, answer.offset + answer.length).contentEquals(packet))
                    println("PASS Android detached helper: 1200-byte UDP integrity")
                }
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                val block = ByteArray(16 * 1024) { (it * 31).toByte() }
                repeat(512) { out.write(block); digest.update(block) }
                control.shutdownOutput()
                val answer = ByteArray(32).also(input::readFully)
                check(answer.contentEquals(digest.digest()))
                check(input.read() == -1)
                println("PASS Android detached helper: 8 MiB TCP integrity and half-close")
            }
        }
    }
}
