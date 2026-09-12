package ai.hans.standard.runtime.network

import ai.hans.standard.runtime.CodexRuntimeDirectories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class RuntimeNetworkStackTest {
    @Test
    fun restartRotatesAuthenticatedProxyAndRetiresOldListener() {
        val root = Files.createTempDirectory("hans-network-stack-test").toFile()
        try {
            val source = File(root, "system-ca.der").apply { writeBytes(defaultCertificateBytes()) }
            val directories = CodexRuntimeDirectories(
                workingDirectory = File(root, "runtime").apply { mkdirs() },
                homeDirectory = File(root, "home").apply { mkdirs() },
                codexHomeDirectory = File(root, "home"),
                temporaryDirectory = File(root, "tmp").apply { mkdirs() },
            )
            val stack = RuntimeNetworkStack(
                caMaterializer = AndroidCaBundleMaterializer(SystemCaFileSource { listOf(source) }),
            )
            val first = stack.restart(directories)
            val firstUri = URI(first.proxyUrl)
            assertEquals("127.0.0.1", firstUri.host)
            assertTrue(firstUri.userInfo.startsWith("hans:"))
            assertTrue(first.caBundleFile.isFile)
            assertTrue(first.toString().contains("<redacted>"))
            assertTrue(!first.toString().contains(firstUri.userInfo))

            val second = stack.restart(directories)
            val secondUri = URI(second.proxyUrl)
            assertNotEquals(firstUri.port, secondUri.port)
            assertListenerClosed(firstUri.port)

            stack.close()
            assertListenerClosed(secondUri.port)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertListenerClosed(port: Int) {
        assertTrue(
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
            }.isFailure,
        )
    }

    private fun defaultCertificateBytes(): ByteArray {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return manager.acceptedIssuers.first().encoded
    }
}
