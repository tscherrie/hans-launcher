package ai.hans.standard.runtime.python.resolver

import android.content.Context
import ai.hans.standard.runtime.python.PythonEnvironmentContract
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

data class PythonResolverBundleReceipt(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

/** Materializes the signed APK resolver asset as a non-executable, read-only private file. */
class AndroidPythonResolverBundleMaterializer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val rootPath = File(appContext.noBackupFilesDir, "python/resolver/bundle")
    private val root = rootPath.canonicalFile

    fun materialize(): PythonResolverBundleReceipt = synchronized(MATERIALIZATION_LOCK) {
        require(!java.nio.file.Files.isSymbolicLink(rootPath.toPath()))
        require(root.mkdirs() || root.isDirectory)
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val staging = File(root, ".resolver-${System.nanoTime()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            appContext.assets.open(ASSET_PATH).use { source ->
                FileOutputStream(staging).use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= PythonResolverLimits.MAX_WORKER_ARCHIVE_BYTES) {
                            "Python resolver asset exceeds its byte limit"
                        }
                        digest.update(buffer, 0, read)
                        destination.write(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
            }
            require(size > 0) { "Python resolver asset is empty" }
            verifySourceOnlyArchive(staging)
            val sha256 = digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
            val destination = File(root, "$sha256.pyz")
            if (!destination.exists()) {
                require(staging.renameTo(destination)) { "Cannot publish Python resolver bundle" }
                destination.setReadable(true, true)
                destination.setWritable(false, false)
                destination.setExecutable(false, false)
            }
            require(
                destination.isFile && !java.nio.file.Files.isSymbolicLink(destination.toPath()) &&
                    destination.length() == size,
            )
            require(hash(destination) == sha256) { "Python resolver bundle changed after publication" }
            PythonResolverBundleReceipt(destination.canonicalFile, sha256, size)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun verifySourceOnlyArchive(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 3..512) { "Python resolver bundle has an invalid entry count" }
            require(entries.none { it.isDirectory }) { "Python resolver bundle contains directories" }
            require(entries.map { it.name }.distinct().size == entries.size) {
                "Python resolver bundle contains duplicate entries"
            }
            require(entries.any { it.name == "hans_resolver_worker.py" }) {
                "Python resolver bundle has no worker"
            }
            require(entries.any { it.name == "packaging/__init__.py" })
            require(entries.any { it.name == "resolvelib/__init__.py" })
            entries.forEach { entry ->
                val name = entry.name
                require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name)
                require(name.split('/').all { it.isNotBlank() && it != "." && it != ".." })
                require(name.endsWith(".py") || name.endsWith(".typed")) {
                    "Python resolver bundle contains a non-source payload"
                }
                require(entry.size in 0..(2L * 1024L * 1024L)) {
                    "Python resolver source entry is too large"
                }
                require(!name.startsWith("hans_resolver_payload/") ||
                    name == "hans_resolver_payload/__init__.py") {
                    "Python resolver base bundle already contains invocation data"
                }
            }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    companion object {
        const val ASSET_PATH = "hans/python/resolver.pyz"
        private val MATERIALIZATION_LOCK = Any()
    }
}
