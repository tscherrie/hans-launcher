package ai.hans.standard.runtime.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class AndroidCaBundleMaterializerTest {
    @Test
    fun writesDeterministicValidatedOwnerOnlyBundleAtomically() {
        val directory = Files.createTempDirectory("hans-ca-test").toFile()
        try {
            val source = File(directory, "system-ca.der")
            source.writeBytes(defaultCertificateBytes())
            val output = File(directory, "private/network/ca-bundle.pem").absoluteFile
            val materializer = AndroidCaBundleMaterializer(SystemCaFileSource { listOf(source) })

            val first = materializer.materialize(output)
            val bytes = output.readBytes()
            val second = materializer.materialize(output)

            assertEquals(1, first.certificateCount)
            assertEquals(first.sha256, second.sha256)
            assertArrayEquals(bytes, output.readBytes())
            assertTrue(bytes.toString(Charsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----"))
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(output.toPath()),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failsClosedForEmptyOrInvalidCertificateSources() {
        val directory = Files.createTempDirectory("hans-ca-bad-test").toFile()
        try {
            assertThrows(IllegalStateException::class.java) {
                AndroidCaBundleMaterializer(SystemCaFileSource { emptyList() })
                    .materialize(File(directory, "empty.pem").absoluteFile)
            }
            val invalid = File(directory, "invalid.der").apply { writeText("not a certificate") }
            assertThrows(Exception::class.java) {
                AndroidCaBundleMaterializer(SystemCaFileSource { listOf(invalid) })
                    .materialize(File(directory, "invalid.pem").absoluteFile)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun defaultCertificateBytes(): ByteArray {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return manager.acceptedIssuers.first().encoded
    }
}
