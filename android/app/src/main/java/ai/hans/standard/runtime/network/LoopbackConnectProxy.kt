package ai.hans.standard.runtime.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LoopbackConnectProxyLimits(
    val headerReadTimeoutMillis: Int = 5_000,
    val connectTimeoutMillis: Int = 10_000,
    val tunnelIdleTimeoutMillis: Int = 5 * 60_000,
    val tunnelLifetimeMillis: Long = 2 * 60 * 60_000L,
    val maximumTunnelBytes: Long = 512L * 1024L * 1024L,
    val maximumConcurrentTunnels: Int = 8,
) {
    init {
        require(headerReadTimeoutMillis in 100..60_000)
        require(connectTimeoutMillis in 100..60_000)
        require(tunnelIdleTimeoutMillis in 1_000..30 * 60_000)
        require(tunnelLifetimeMillis in 1_000..24 * 60 * 60_000L)
        require(maximumTunnelBytes in 1..2L * 1024L * 1024L * 1024L)
        require(maximumConcurrentTunnels in 1..32)
    }
}

/**
 * An authenticated, CONNECT-only proxy for the app-owned native runtime.
 * Binding to loopback prevents network exposure; per-process credentials stop
 * unrelated Android apps sharing the network namespace from using it.
 */
class LoopbackConnectProxy(
    expectedAuthorization: ByteArray,
    private val targetPolicy: ConnectTargetPolicy = ConnectTargetPolicy(),
    private val limits: LoopbackConnectProxyLimits = LoopbackConnectProxyLimits(),
) : Closeable {
    private val parser = ConnectRequestParser(expectedAuthorization.copyOf())
    private val running = AtomicBoolean(false)
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val connectionExecutor = boundedExecutor("hans-proxy-connection", limits.maximumConcurrentTunnels)
    private val reverseRelayExecutor = boundedExecutor("hans-proxy-relay", limits.maximumConcurrentTunnels)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int
        get() = serverSocket?.localPort?.takeIf { running.get() }
            ?: error("CONNECT proxy is not running")

    @Synchronized
    fun start(): LoopbackConnectProxy {
        check(!running.get()) { "CONNECT proxy already started" }
        val server = ServerSocket()
        try {
            server.reuseAddress = false
            server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), ACCEPT_BACKLOG)
            serverSocket = server
            running.set(true)
            acceptThread = Thread({ acceptLoop(server) }, "hans-proxy-accept").apply {
                isDaemon = true
                start()
            }
            return this
        } catch (exception: Exception) {
            runCatching { server.close() }
            serverSocket = null
            running.set(false)
            connectionExecutor.shutdownNow()
            reverseRelayExecutor.shutdownNow()
            throw exception
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            if (!client.inetAddress.isLoopbackAddress || !running.get()) {
                client.closeQuietly()
                continue
            }
            activeSockets += client
            try {
                connectionExecutor.execute { handleClient(client) }
            } catch (_: RejectedExecutionException) {
                activeSockets -= client
                client.closeQuietly()
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = limits.headerReadTimeoutMillis
            val parsed = parser.parse(readHeader(client))
            if (parsed is ConnectParseResult.Rejected) {
                writeRejection(client, parsed)
                return
            }
            val target = targetPolicy.resolveAndValidate((parsed as ConnectParseResult.Accepted).target)
            if (target == null) {
                writeResponse(client, 403, "Forbidden")
                return
            }
            upstream = connectToValidatedAddress(target)
            if (upstream == null) {
                writeResponse(client, 502, "Bad Gateway")
                return
            }
            activeSockets += upstream
            client.soTimeout = limits.tunnelIdleTimeoutMillis
            upstream.soTimeout = limits.tunnelIdleTimeoutMillis
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            client.getOutputStream().write(CONNECTION_ESTABLISHED)
            client.getOutputStream().flush()
            relayBidirectionally(client, upstream)
        } catch (_: Exception) {
            // Deliberately no request, hostname, header, or credential logging.
        } finally {
            activeSockets -= client
            upstream?.let { activeSockets -= it }
            client.closeQuietly()
            upstream?.closeQuietly()
        }
    }

    private fun readHeader(client: Socket): ByteArray {
        val input = client.getInputStream()
        val output = ByteArrayOutputStream(512)
        var matched = 0
        while (output.size() < ConnectRequestParser.MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) break
            output.write(next)
            matched = when {
                next == HEADER_TERMINATOR[matched].toInt() -> matched + 1
                next == HEADER_TERMINATOR[0].toInt() -> 1
                else -> 0
            }
            if (matched == HEADER_TERMINATOR.size) return output.toByteArray()
        }
        return output.toByteArray()
    }

    private fun connectToValidatedAddress(target: ResolvedConnectTarget): Socket? {
        for (address in target.addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, target.port), limits.connectTimeoutMillis)
                return socket
            } catch (_: Exception) {
                socket.closeQuietly()
            }
        }
        return null
    }

    private fun relayBidirectionally(client: Socket, upstream: Socket) {
        val startedAt = System.currentTimeMillis()
        val transferred = AtomicLong(0)
        val stopped = AtomicBoolean(false)
        val reverse = try {
            reverseRelayExecutor.submit {
                relay(
                    source = upstream,
                    destination = client,
                    startedAt = startedAt,
                    transferred = transferred,
                    stopped = stopped,
                )
            }
        } catch (_: RejectedExecutionException) {
            return
        }
        try {
            relay(client, upstream, startedAt, transferred, stopped)
        } finally {
            stopped.set(true)
            client.closeQuietly()
            upstream.closeQuietly()
            reverse.cancel(true)
        }
    }

    private fun relay(
        source: Socket,
        destination: Socket,
        startedAt: Long,
        transferred: AtomicLong,
        stopped: AtomicBoolean,
    ) {
        val input = source.getInputStream()
        val output = destination.getOutputStream()
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        try {
            while (running.get() && !stopped.get()) {
                if (System.currentTimeMillis() - startedAt > limits.tunnelLifetimeMillis) break
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (transferred.addAndGet(count.toLong()) > limits.maximumTunnelBytes) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: SocketTimeoutException) {
            // An idle tunnel is intentionally closed.
        } catch (_: Exception) {
            // Connection reset and lifecycle shutdown are normal terminal states.
        } finally {
            stopped.set(true)
            source.closeQuietly()
            destination.closeQuietly()
        }
    }

    private fun writeRejection(client: Socket, rejection: ConnectParseResult.Rejected) {
        if (rejection.statusCode == 407) {
            val bytes = (
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"Hans Runtime\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            runCatching { client.getOutputStream().write(bytes) }
        } else {
            writeResponse(client, rejection.statusCode, rejection.responseReason)
        }
    }

    private fun writeResponse(client: Socket, statusCode: Int, reason: String) {
        val bytes = (
            "HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        runCatching { client.getOutputStream().write(bytes) }
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) return
        serverSocket?.closeQuietly()
        serverSocket = null
        synchronized(activeSockets) {
            activeSockets.toList().forEach { socket -> socket.closeQuietly() }
            activeSockets.clear()
        }
        connectionExecutor.shutdownNow()
        reverseRelayExecutor.shutdownNow()
        acceptThread?.interrupt()
        runCatching { acceptThread?.join(SHUTDOWN_WAIT_MILLIS) }
        connectionExecutor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        reverseRelayExecutor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        acceptThread = null
    }

    private fun boundedExecutor(name: String, size: Int): ThreadPoolExecutor = ThreadPoolExecutor(
        size,
        size,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(size),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val ACCEPT_BACKLOG = 16
        const val RELAY_BUFFER_BYTES = 16 * 1024
        const val SHUTDOWN_WAIT_MILLIS = 2_000L
        val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val CONNECTION_ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
