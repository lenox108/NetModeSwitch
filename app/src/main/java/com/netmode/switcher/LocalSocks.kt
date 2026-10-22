package com.netmode.switcher

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The optional VPN relay: the hotspot's tunnel (hev, as for a
 * proxy) ends the clients' TCP connections on the phone, and a SOCKS5 server opens them again
 * bound to Android's VPN network. Splitting TCP changes loss recovery, but whether it improves
 * throughput depends on the device, VPN and path; it is not universally faster.
 *
 * The server runs with the app UID in its own process ([SocksCli], through app_process).
 * Root tries to move it out of the app freezer group; failure is reported, not hidden.
 */
object LocalSocks {

    data class Endpoint(val port: Int, val user: String, val pass: String)

    private val files get() = App.ctx.filesDir
    private val logFile get() = java.io.File(files, "socks.log")
    private val pidPath get() = "${files.absolutePath}/socks.pid"
    private val portFile get() = java.io.File(files, "socks.port")
    private val statsFile get() = java.io.File(files, "socks.stats")

    /** The VPN network, or null. */
    fun vpnNetwork(): Network? = runCatching {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()

    /** Ends the server process, if one of ours runs; for the route's teardown script (root). */
    val killScript: String
        get() = "p=\$(cat $pidPath 2>/dev/null); [ -n \"\$p\" ] && grep -q SocksCli /proc/\$p/cmdline 2>/dev/null && kill \$p; rm -f $pidPath ${portFile.absolutePath} ${statsFile.absolutePath} ${statsFile.absolutePath}.tmp"

    /**
     * Starts the server for [net], a new process each time; null when it does not come up.
     *
     * Started by the app, so it runs as the app: the VPN covers it, as do the app's network
     * permissions — a root process may have no way out at all. Then root moves it out of the
     * app's cgroup: OxygenOS freezes an app in the background together with everything in its
     * group, and the traffic crawled at a few kilobits. Out of it, it is not frozen and outlives
     * the app's process too.
     */
    suspend fun start(net: Network): Endpoint? {
        val e = Endpoint(freePort(), token(), token())
        statsFile.delete()
        java.io.File("${statsFile.absolutePath}.tmp").delete()
        val pid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Network.toString() is its netId: the process binds its sockets to it.
            val pb = ProcessBuilder(
                "/system/bin/app_process", "/system/bin", "com.netmode.switcher.SocksCli",
                "${e.port}", e.user, e.pass, net.toString(), statsFile.absolutePath,
            )
            pb.environment()["CLASSPATH"] = App.ctx.applicationInfo.sourceDir
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            val process = pb.start()
            pidOf(process).also { if (it == null) process.destroy() }
        } ?: return null
        java.io.File(pidPath).writeText(pid.toString())
        portFile.writeText(e.port.toString())
        val protection = runCatching {
            Privileged.exec(
                "echo $pid > /sys/fs/cgroup/cgroup.procs 2>/dev/null\n" +
                    "echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null\n" +
                    "if grep -q '^0::/$' /proc/$pid/cgroup && [ \"\$(cat /proc/$pid/oom_score_adj)\" = -1000 ]; then echo @BACKGROUND_OK; fi"
            )
        }.getOrDefault("")
        if (!protection.contains("@BACKGROUND_OK")) Prefs.log("раздача через VPN: защита посредника от заморозки в фоне не подтверждена прошивкой")
        // Up when it answers a SOCKS greeting.
        repeat(40) {
            if (greets(e.port)) return e
            delay(250)
        }
        Privileged.exec(killScript)
        return null
    }

    @Suppress("DiscouragedPrivateApi")
    private fun pidOf(p: Process): Int? = runCatching {
        generateSequence<Class<*>>(p.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == "pid" }
            .apply { isAccessible = true }
            .getInt(p)
    }.getOrNull()

    /** Whether it answers a SOCKS5 greeting on [port]: "05 02", password login offered. */
    suspend fun greets(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 1_500
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_500)
                socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                val input = socket.getInputStream()
                input.read() == 5 && input.read() == 2
            }
        }.getOrDefault(false)
    }

    suspend fun alive(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt().takeIf { it in 1..65535 } ?: return@runCatching false
        Privileged.exec("p=\$(cat $pidPath 2>/dev/null); [ -n \"\$p\" ] && grep -q SocksCli /proc/\$p/cmdline 2>/dev/null && echo up").contains("up") && greets(port)
    }.getOrDefault(false)

    /** What the server process wrote: its self-test, problems and the first connection. */
    fun readLog(): List<String> = runCatching {
        logFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun readStats(): Map<String, Long> = runCatching {
        val values = statsFile.readLines().associate { line -> line.substringBefore('=') to line.substringAfter('=').toLong() }
        if (System.currentTimeMillis() - values.getValue("at") !in 0..90_000) emptyMap() else values
    }.getOrDefault(emptyMap())

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun token(): String {
        val r = java.security.SecureRandom()
        return (1..16).map { "abcdefghijkmnpqrstuvwxyz23456789"[r.nextInt(32)] }.joinToString("")
    }
}

/**
 * The server process: `app_process … SocksCli <port> <user> <password> <netId>`, as the app.
 * Prints a self-test line, problems (at most twice a minute) and the first connection, for the
 * app to put in its journal.
 */
object SocksCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args[0].toInt()
        val netId = args[3].toInt()
        // The network by its id: a hidden constructor, open to a process outside the app.
        val net = if (netId == 0 && BuildConfig.DEBUG) null
            else Network::class.java.getConstructor(Int::class.javaPrimitiveType).newInstance(netId)
        var lastProblem = 0L
        val server = SocksServer(args[1], args[2], { net }) { line, isProblem ->
            val now = System.currentTimeMillis()
            if (isProblem && now - lastProblem < 30_000L) return@SocksServer
            if (isProblem) lastProblem = now
            println(line)
            System.out.flush()
        }
        val socket = ServerSocket(port, 256, InetAddress.getByName("127.0.0.1"))
        // Listening first: the check of the way out may take seconds.
        Thread {
            println("SELFTEST ${server.selfTest() ?: "ok"}")
            System.out.flush()
            println("UDPTEST ${server.selfTestUdp() ?: "ok"}")
            System.out.flush()
        }.start()
        args.getOrNull(4)?.let { path ->
            Thread {
                while (true) {
                    runCatching {
                        val temp = java.io.File("$path.tmp")
                        temp.writeText(server.stats())
                        check(temp.renameTo(java.io.File(path)))
                    }
                    Thread.sleep(30_000)
                }
            }.apply { isDaemon = true; start() }
        }
        server.serve(socket)
    }
}

/**
 * The SOCKS5 server: TCP (CONNECT) and UDP (ASSOCIATE), password login only, every outgoing
 * socket bound to [network] and names resolved through it. Without that network a connection is
 * refused rather than sent out directly. [log] gets a line and whether it is a problem.
 */
data class SocksLimits(
    val sessions: Int = 256,
    val udpSessions: Int = 64,
    val handshakeMs: Int = 10_000,
    val handshakes: Int = 16,
    val udpIdleMs: Int = 60_000,
    val udpPollMs: Int = 5_000,
) {
    init { require(sessions > 0 && udpSessions in 1..sessions && handshakeMs > 0 && handshakes > 0 && udpIdleMs > 0 && udpPollMs > 0) }
}

class SocksServer(
    private val user: String,
    private val pass: String,
    private val network: () -> Network?,
    private val limits: SocksLimits = SocksLimits(),
    private val log: (String, Boolean) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nms-socks").apply { isDaemon = true } }
    private val slots = Semaphore(limits.sessions)
    private val handshakes = Semaphore(limits.handshakes)
    private val udpSlots = Semaphore(limits.udpSessions)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val tcpUp = AtomicLong()
    private val tcpDown = AtomicLong()
    private val udpUp = AtomicLong()
    private val udpDown = AtomicLong()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var firstOk = false

    private fun problem(what: String) { failures.incrementAndGet(); log("! $what", true) }

    fun stats(): String = "at=${System.currentTimeMillis()}\nsessions=${sessions.size}\n" +
        "udp_sessions=${limits.udpSessions - udpSlots.availablePermits()}\ntcp_up=${tcpUp.get()}\ntcp_down=${tcpDown.get()}\n" +
        "udp_up=${udpUp.get()}\nudp_down=${udpDown.get()}\ndropped=${dropped.get()}\nfailures=${failures.get()}\n"

    /** A permit belongs to the entire connection, including its asynchronous return pump. */
    private class Session(val client: Socket, private val ended: (Session) -> Unit) {
        private val resources = mutableListOf<java.io.Closeable>(client)
        private var closed = false
        @Synchronized fun add(resource: java.io.Closeable) {
            if (closed) runCatching { resource.close() } else resources.add(resource)
        }
        @Synchronized fun remove(resource: java.io.Closeable) { resources.remove(resource) }
        fun close() {
            val closing = synchronized(this) {
                if (closed) return
                closed = true
                resources.toList().also { resources.clear() }
            }
            closing.forEach { runCatching { it.close() } }
            ended(this)
        }
    }

    /** A way out through the network: null when it works, or what stops it. */
    fun selfTest(): String? {
        val net = network()
        var last: String? = null
        // Two addresses, a second round after a pause: the very first connection of a fresh
        // process now and then times out while the next ones go through.
        for (round in 0 until 2) {
            for (ip in listOf("1.1.1.1", "8.8.8.8")) {
                last = runCatching {
                    Socket().use { s ->
                        net?.bindSocket(s)
                        s.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 5_000)
                    }
                    null
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                if (last == null) return null
            }
            Thread.sleep(1_000)
        }
        return last
    }

    /** A tiny diagnostic only; lack of an answer is never a reason to rebuild the tunnel. */
    fun selfTestUdp(): String? = runCatching {
        DatagramSocket().use { socket ->
            network()?.bindSocket(socket)
            socket.soTimeout = 3_000
            socket.connect(InetAddress.getByName("1.1.1.1"), 53)
            val query = byteArrayOf(0x4e, 0x4d, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
            socket.send(DatagramPacket(query, query.size))
            val answer = DatagramPacket(ByteArray(512), 512)
            socket.receive(answer)
            check(answer.length >= 12 && answer.data[0] == query[0] && answer.data[1] == query[1] &&
                answer.data[2].toInt() and 128 != 0) { "неверный ответ тестового DNS" }
        }
        null
    }.getOrElse { "тестовый DNS 1.1.1.1 не ответил (${it.javaClass.simpleName}); это не проверка скорости" }

    /** Accepts until [s] closes; blocks. */
    fun serve(s: ServerSocket) {
        try { while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            // Greetings have their own small budget: a saturated data relay must still answer
            // its health probe instead of being mistaken for a dead process and restarted.
            if (!handshakes.tryAcquire()) { dropped.incrementAndGet(); c.close(); continue }
            val pending = AtomicBoolean(true)
            val handshakeDone = { if (pending.compareAndSet(true, false)) handshakes.release() }
            val session = Session(c) { sessions.remove(it) }
            session.add(java.io.Closeable { handshakeDone() })
            sessions.add(session)
            pool.execute {
                try {
                    handle(session, handshakeDone)
                } catch (t: java.io.EOFException) {
                    session.close() // the app's own greeting check intentionally stops here
                } catch (_: SocketTimeoutException) {
                    dropped.incrementAndGet()
                    session.close()
                } catch (t: Throwable) {
                    problem("ошибка соединения — ${t.javaClass.simpleName}: ${t.message}")
                    session.close()
                }
            }
        } } finally {
            sessions.forEach { it.close() }
            pool.shutdownNow()
        }
    }

    private fun handle(session: Session, handshakeDone: () -> Unit) {
        val c = session.client
        c.tcpNoDelay = true
        c.soTimeout = limits.handshakeMs
        val input = DataInputStream(c.getInputStream())
        val out = c.getOutputStream()
        // Greeting: only username/password is offered.
        val ver = input.readUnsignedByte()
        if (ver != 5) { problem("туннель прислал не SOCKS5 (версия $ver)"); return session.close() }
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (methods.none { it.toInt() == 2 }) {
            problem("туннель не предложил вход по паролю (методы ${methods.joinToString { it.toString() }})")
            out.write(byteArrayOf(5, 0xFF.toByte())); return session.close()
        }
        out.write(byteArrayOf(5, 2))
        val authVer = input.readUnsignedByte()
        if (authVer != 1) { problem("неверная версия входа от туннеля ($authVer)"); return session.close() }
        val u = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
        val p = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
        if (u != user || p != pass) { problem("неверный логин от туннеля"); out.write(byteArrayOf(1, 1)); return session.close() }
        out.write(byteArrayOf(1, 0))

        // Request.
        if (input.readUnsignedByte() != 5) { problem("неверный запрос от туннеля"); return session.close() }
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte()
        val (host, port) = readAddress(input)
        if (!slots.tryAcquire()) { dropped.incrementAndGet(); reply(out, 1); session.close(); return }
        session.add(java.io.Closeable { slots.release() })
        handshakeDone()
        c.soTimeout = 0 // authentication has a deadline; established TCP streams may be idle
        when (cmd) {
            1 -> connect(session, input, out, host, port)
            3 -> associate(session, input, out)
            else -> { problem("неизвестная команда от туннеля ($cmd)"); reply(out, 7); session.close() }
        }
    }

    /** ATYP + address + port; an IP as its text, a name as given. */
    private fun readAddress(input: DataInputStream): Pair<String, Int> {
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            else -> throw IllegalArgumentException("address type")
        }
        return host to input.readUnsignedShort()
    }

    private fun reply(out: OutputStream, code: Int, bind: InetSocketAddress? = null) {
        val addr = bind?.address?.address ?: byteArrayOf(0, 0, 0, 0)
        val port = bind?.port ?: 0
        out.write(byteArrayOf(5, code.toByte(), 0, if (addr.size == 16) 4 else 1) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
    }

    /**
     * IPv4 only: a name through the network's own DNS, its IPv4 addresses; an IPv4 as it is. A VPN
     * server without a working IPv6 path crawls on it, and the hotspot's own IPv6 is closed anyway.
     */
    private fun resolveAll(net: Network?, host: String): List<InetAddress> {
        val all = if (host.any { it.isLetter() } && !host.contains(':')) {
            (net?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()
        } else listOf(InetAddress.getByName(host))
        return all.filterIsInstance<java.net.Inet4Address>().ifEmpty { throw java.net.UnknownHostException("$host: нет адреса IPv4") }
    }

    private fun resolve(net: Network?, host: String): InetAddress = resolveAll(net, host).first()

    private fun connect(session: Session, input: InputStream, out: OutputStream, host: String, port: Int) {
        val c = session.client
        val net = network()
        val remote = try {
            // Each address in turn, IPv4 first; a short wait each while more are left to try.
            val addrs = resolveAll(net, host).take(4)
            var last: Throwable? = null
            var sock: Socket? = null
            for ((i, addr) in addrs.withIndex()) {
                val s = Socket()
                session.add(s)
                try {
                    net?.bindSocket(s)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, port), if (i < addrs.lastIndex) 5_000 else CONNECT_TIMEOUT_MS)
                    sock = s
                    break
                } catch (t: Throwable) {
                    runCatching { s.close() }
                    session.remove(s)
                    last = t
                }
            }
            sock ?: throw (last ?: java.net.ConnectException("no address"))
        } catch (t: Throwable) {
            // A site that refuses is the site's business; anything else is worth a line.
            if (t !is java.net.ConnectException) problem("не удалось соединиться с $host:$port через VPN — ${t.javaClass.simpleName}: ${t.message}")
            reply(out, if (t is java.net.ConnectException) 5 else 4)
            session.close()
            return
        }
        reply(out, 0, InetSocketAddress(remote.localAddress, remote.localPort))
        if (!firstOk) {
            firstOk = true
            log("OK соединения идут через VPN", false)
        }
        // One direction on another thread, the other on this one. An end that finishes sending
        // closes only that direction — the other may still be answering (a request, then the
        // reply) — and both sockets close when both directions are done or one fails.
        val left = java.util.concurrent.atomic.AtomicInteger(2)
        pool.execute {
            try { pump(remote.getInputStream(), out, c, session, left, tcpDown) }
            catch (_: Exception) { session.close() }
        }
        pump(input, remote.getOutputStream(), remote, session, left, tcpUp)
    }

    /** Copies [from] into [to]; at its end shuts the output of [toSocket]. */
    private fun pump(from: InputStream, to: OutputStream, toSocket: Socket, session: Session, left: java.util.concurrent.atomic.AtomicInteger, bytes: AtomicLong) {
        val buf = ByteArray(BUF)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                bytes.addAndGet(n.toLong())
            }
            runCatching { toSocket.shutdownOutput() }
            if (left.decrementAndGet() > 0) return
        } catch (_: Throwable) {
        }
        session.close()
    }

    /**
     * UDP ASSOCIATE: datagrams from the client to a local socket, each with a SOCKS header naming
     * where it goes; sent on from a socket bound to the network, and answers wrapped back. It
     * lasts as long as the control connection does.
     */
    private fun associate(session: Session, input: InputStream, out: OutputStream) {
        val c = session.client
        if (!udpSlots.tryAcquire()) { dropped.incrementAndGet(); reply(out, 1); session.close(); return }
        session.add(java.io.Closeable { udpSlots.release() })
        val net = network()
        val local = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        session.add(local)
        val remote = DatagramSocket()
        session.add(remote)
        try {
            net?.bindSocket(remote)
        } catch (t: Throwable) {
            problem("UDP через VPN не открылся — ${t.javaClass.simpleName}: ${t.message}")
            reply(out, 1); session.close(); return
        }
        reply(out, 0, InetSocketAddress(local.localAddress, local.localPort))
        val client = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val activeAt = AtomicLong(System.nanoTime())
        local.soTimeout = minOf(limits.udpPollMs, limits.udpIdleMs)
        remote.soTimeout = local.soTimeout
        fun expired() = System.nanoTime() - activeAt.get() >= limits.udpIdleMs.toLong() * 1_000_000

        pool.execute {
            val buf = ByteArray(BUF)
            val p = DatagramPacket(buf, buf.size)
            val forward = DatagramPacket(buf, 0)
            val decoder = SocksUdpDecoder { resolve(net, it) }
            try {
                while (!local.isClosed) {
                    p.length = buf.size
                    try { local.receive(p) } catch (_: SocketTimeoutException) { if (expired()) break else continue }
                    if (p.address != c.inetAddress) { dropped.incrementAndGet(); continue }
                    val back = client.get()
                    if (back != null && (back.address != p.address || back.port != p.port)) { dropped.incrementAndGet(); continue }
                    try {
                        if (!decoder.decode(buf, p.length)) { dropped.incrementAndGet(); continue }
                        if (back == null) client.set(InetSocketAddress(p.address, p.port))
                        forward.setData(buf, decoder.payloadOffset, p.length - decoder.payloadOffset)
                        forward.address = decoder.address
                        forward.port = decoder.port
                        remote.send(forward)
                        activeAt.set(System.nanoTime())
                        udpUp.addAndGet(forward.length.toLong())
                    } catch (e: java.io.IOException) {
                        if (local.isClosed || remote.isClosed) break
                        problem("UDP-пакет не отправлен — ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                if (!local.isClosed) problem("UDP-приём остановлен — ${e.javaClass.simpleName}")
            } finally {
                session.close()
            }
        }
        pool.execute {
            val headroom = 22
            val buf = ByteArray(BUF + headroom)
            val p = DatagramPacket(buf, headroom, BUF)
            val backPacket = DatagramPacket(buf, 0)
            try {
                while (!remote.isClosed) {
                    p.setData(buf, headroom, BUF)
                    try { remote.receive(p) } catch (_: SocketTimeoutException) { if (expired()) break else continue }
                    val back = client.get() ?: continue
                    val start = wrapSocksUdp(buf, headroom, p.address, p.port)
                    backPacket.setData(buf, start, headroom - start + p.length)
                    backPacket.socketAddress = back
                    local.send(backPacket)
                    activeAt.set(System.nanoTime())
                    udpDown.addAndGet(p.length.toLong())
                }
            } catch (e: Exception) {
                if (!remote.isClosed) problem("UDP-ответы остановлены — ${e.javaClass.simpleName}")
            } finally {
                session.close()
            }
        }
        // The association ends with its control connection.
        try { while (input.read() >= 0) Unit } catch (_: Throwable) {}
        session.close()
    }

    private companion object {
        const val BUF = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
