package ai.hans.standard.runtime.network

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

class LoopbackConnectProxyTest {
    private val closeables = Collections.synchronizedList(mutableListOf<AutoCloseable>())

    @After
    fun closeResources() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun authenticatedConnectRelaysBytesToValidatedAddress() {
        val echo = EchoServer().also { closeables += it }
        val credential = ConnectRequestParser.basicAuthorization("hans", "secret")
        val proxy = LoopbackConnectProxy(
            expectedAuthorization = credential,
            targetPolicy = ConnectTargetPolicy(
                resolver = HostResolver { listOf(InetAddress.getLoopbackAddress()) },
                allowedPorts = setOf(echo.port),
                addressPolicy = DestinationAddressPolicy { it.isLoopbackAddress },
            ),
        ).start().also { closeables += it }
        val client = Socket("127.0.0.1", proxy.port).also { closeables += it }
        client.soTimeout = 3_000
        client.getOutputStream().write(
            request(echo.port, credential.toString(Charsets.US_ASCII)),
        )
        client.getOutputStream().flush()
        val response = readHeader(client)
        assertTrue(response.startsWith("HTTP/1.1 200"))

        val payload = "bounded tunnel".toByteArray()
        client.getOutputStream().write(payload)
        client.getOutputStream().flush()
        assertArrayEquals(payload, client.getInputStream().readNBytes(payload.size))
    }

    @Test
    fun unauthenticatedClientIsRejectedAndShutdownClosesListener() {
        val credential = ConnectRequestParser.basicAuthorization("hans", "secret")
        val proxy = LoopbackConnectProxy(
            expectedAuthorization = credential,
            targetPolicy = ConnectTargetPolicy(
                resolver = HostResolver { listOf(InetAddress.getByName("8.8.8.8")) },
            ),
        ).start()
        val port = proxy.port
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 3_000
            client.getOutputStream().write(request(443, null))
            assertTrue(readHeader(client).startsWith("HTTP/1.1 407"))
        }
        proxy.close()
        assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) } }.isFailure)
    }

    private fun request(port: Int, authorization: String?): ByteArray = buildString {
        append("CONNECT test.invalid:$port HTTP/1.1\r\n")
        append("Host: test.invalid:$port\r\n")
        if (authorization != null) append("Proxy-Authorization: $authorization\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun readHeader(socket: Socket): String {
        val output = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read()
            check(next >= 0)
            output.write(next)
            tail = (tail + next.toChar()).takeLast(4)
        }
        return output.toString(Charsets.US_ASCII.name())
    }

    private class EchoServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val thread = Thread({ run() }, "test-echo").apply {
            isDaemon = true
            start()
        }
        val port: Int get() = server.localPort

        private fun run() {
            runCatching {
                server.accept().use { socket ->
                    val buffer = ByteArray(1_024)
                    while (true) {
                        val count = socket.getInputStream().read(buffer)
                        if (count < 0) break
                        socket.getOutputStream().write(buffer, 0, count)
                        socket.getOutputStream().flush()
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
            thread.join(1_000)
        }
    }
}
