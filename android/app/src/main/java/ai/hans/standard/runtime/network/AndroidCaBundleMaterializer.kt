package ai.hans.standard.runtime.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

fun interface SystemCaFileSource {
    fun certificateFiles(): List<File>
}

class AndroidSystemCaFileSource(
    private val directories: List<File> = DEFAULT_DIRECTORIES,
) : SystemCaFileSource {
    override fun certificateFiles(): List<File> = directories
        .asSequence()
        .filter { it.isDirectory && it.canRead() }
        .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
        .filter { file -> SYSTEM_CERTIFICATE_FILE.matches(file.name) }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        .sortedBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        .toList()

    private companion object {
        val DEFAULT_DIRECTORIES = listOf(
            File("/apex/com.android.conscrypt/cacerts"),
            File("/system/etc/security/cacerts"),
            File("/product/etc/security/cacerts"),
        )
        val SYSTEM_CERTIFICATE_FILE = Regex("[0-9a-fA-F]{8}\\.[0-9]+")
    }
}

data class MaterializedCaBundle(
    val file: File,
    val certificateCount: Int,
    val sha256: String,
)

class AndroidCaBundleMaterializer(
    private val source: SystemCaFileSource = AndroidSystemCaFileSource(),
) {
    fun materialize(outputFile: File): MaterializedCaBundle {
        require(outputFile.isAbsolute) { "CA bundle path must be absolute" }
        val parent = outputFile.parentFile ?: error("CA bundle has no parent")
        check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
            "CA bundle directory is unavailable"
        }
        val canonicalParent = parent.canonicalFile
        val canonicalOutput = File(canonicalParent, outputFile.name).canonicalFile
        check(canonicalOutput.parentFile == canonicalParent) { "CA bundle escaped its directory" }

        val files = source.certificateFiles()
        check(files.isNotEmpty()) { "No readable Android system CAs" }
        check(files.size <= MAX_SOURCE_FILES) { "Too many Android system CA files" }
        val certificates = files.flatMap(::readCertificates)
            .associateBy { certificate -> sha256(certificate.encoded) }
            .toSortedMap()
        check(certificates.isNotEmpty()) { "Android system CA set is empty" }
        check(certificates.size <= MAX_CERTIFICATES) { "Too many Android system certificates" }
        val bundleBytes = encodePemBundle(certificates.values.toList())
        check(bundleBytes.size in 1..MAX_BUNDLE_BYTES) { "Android CA bundle is oversized" }
        val bundleDigest = sha256(bundleBytes)

        val outputPath = canonicalOutput.toPath()
        if (Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(outputPath, LinkOption.NOFOLLOW_LINKS)) {
                "CA bundle target is not a regular file"
            }
        }
        if (
            Files.isRegularFile(outputPath, LinkOption.NOFOLLOW_LINKS) &&
            canonicalOutput.length() == bundleBytes.size.toLong() &&
            boundedRead(canonicalOutput, MAX_BUNDLE_BYTES).contentEquals(bundleBytes)
        ) {
            applyOwnerOnlyMode(canonicalOutput)
            validateBundle(canonicalOutput, certificates.size)
            return MaterializedCaBundle(canonicalOutput, certificates.size, bundleDigest)
        }

        val temporary = File.createTempFile("ca-bundle-", ".tmp", canonicalParent)
        try {
            applyOwnerOnlyMode(temporary)
            FileOutputStream(temporary).use { output ->
                output.write(bundleBytes)
                output.flush()
                output.fd.sync()
            }
            applyOwnerOnlyMode(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    canonicalOutput.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic CA bundle replacement is unsupported")
            }
            applyOwnerOnlyMode(canonicalOutput)
            validateBundle(canonicalOutput, certificates.size)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return MaterializedCaBundle(canonicalOutput, certificates.size, bundleDigest)
    }

    private fun readCertificates(file: File): List<X509Certificate> {
        val path = file.toPath()
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Android CA source is not a regular file"
        }
        val bytes = boundedRead(file, MAX_CERTIFICATE_FILE_BYTES)
        check(bytes.isNotEmpty()) { "Android CA source is empty" }
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .map { it as? X509Certificate ?: error("Non-X509 Android certificate") }
        check(parsed.isNotEmpty()) { "Android CA source did not contain a certificate" }
        parsed.forEach { certificate ->
            check(certificate.encoded.isNotEmpty()) { "Android CA encoding is empty" }
        }
        return parsed
    }

    private fun encodePemBundle(certificates: List<X509Certificate>): ByteArray {
        val output = ByteArrayOutputStream()
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        certificates.forEach { certificate ->
            output.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
            output.write(encoder.encode(certificate.encoded))
            output.write('\n'.code)
            output.write("-----END CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
        }
        return output.toByteArray()
    }

    private fun validateBundle(file: File, expectedCertificateCount: Int) {
        check(
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.canRead() && file.length() in 1..MAX_BUNDLE_BYTES.toLong(),
        ) {
            "Materialized CA bundle is unavailable"
        }
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(boundedRead(file, MAX_BUNDLE_BYTES)))
        check(parsed.size == expectedCertificateCount) { "Materialized CA bundle validation failed" }
        val permissions = Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        check(permissions == OWNER_READ_WRITE) { "Materialized CA bundle is not mode 0600" }
    }

    private fun applyOwnerOnlyMode(file: File) {
        Files.setPosixFilePermissions(file.toPath(), OWNER_READ_WRITE)
        check(Files.getPosixFilePermissions(file.toPath()) == OWNER_READ_WRITE) {
            "Could not restrict CA bundle permissions"
        }
    }

    private fun boundedRead(file: File, maximumBytes: Int): ByteArray {
        check(file.length() in 0..maximumBytes.toLong()) { "Certificate input is oversized" }
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 16_384))
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maximumBytes) { "Certificate input exceeded its limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_SOURCE_FILES = 1_024
        const val MAX_CERTIFICATES = 1_024
        const val MAX_CERTIFICATE_FILE_BYTES = 128 * 1_024
        const val MAX_BUNDLE_BYTES = 8 * 1_024 * 1_024
        val OWNER_READ_WRITE = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
