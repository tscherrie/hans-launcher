package ai.hans.standard.plugins

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

internal fun interface PluginSourceIdentityHasher {
    fun digest(sourceRoot: File, cancellation: PluginSourceHashCancellation): String
}

internal fun interface PluginSourceHashCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = PluginSourceHashCancellation { false }
    }
}

/** Bounded, cancellation-aware identity for the exact plugin tree sent to App Server. */
internal class BoundedPluginSourceIdentityHasher(
    private val maximumFiles: Int = MAXIMUM_FILES,
    private val maximumBytes: Long = MAXIMUM_BYTES,
    private val maximumFileBytes: Long = MAXIMUM_FILE_BYTES,
    private val maximumRelativePathBytes: Int = MAXIMUM_RELATIVE_PATH_BYTES,
) : PluginSourceIdentityHasher {
    init {
        require(maximumFiles in 1..MAXIMUM_FILES)
        require(maximumBytes in 1..MAXIMUM_BYTES)
        require(maximumFileBytes in 1..maximumBytes)
        require(maximumRelativePathBytes in 1..MAXIMUM_RELATIVE_PATH_BYTES)
    }

    override fun digest(
        sourceRoot: File,
        cancellation: PluginSourceHashCancellation,
    ): String {
        cancellation.throwIfCancelled()
        require(sourceRoot.isDirectory && !Files.isSymbolicLink(sourceRoot.toPath())) {
            "Plugin source root is invalid"
        }
        val root = sourceRoot.canonicalFile.toPath()
        var bytes = 0L
        val entries = buildList {
            Files.walk(root).use { paths ->
                paths.sorted().forEach { path ->
                    cancellation.throwIfCancelled()
                    if (path == root) return@forEach
                    require(!Files.isSymbolicLink(path)) { "Plugin source symlinks are forbidden" }
                    require(path.toAbsolutePath().normalize().startsWith(root)) {
                        "Plugin source escaped its root"
                    }
                    if (Files.isDirectory(path)) return@forEach
                    require(Files.isRegularFile(path)) { "Plugin source contains a special file" }
                    val relative = root.relativize(path).joinToString("/") { it.toString() }
                    require(relative.toByteArray(StandardCharsets.UTF_8).size in
                        1..maximumRelativePathBytes) { "Plugin source path is too long" }
                    val fileBytes = Files.size(path)
                    require(fileBytes in 0..maximumFileBytes) { "Plugin source file is too large" }
                    bytes = Math.addExact(bytes, fileBytes)
                    require(bytes <= maximumBytes) { "Plugin source is too large" }
                    add(SourceEntry(relative, path.toFile(), fileBytes))
                    require(this.size <= maximumFiles) { "Plugin source has too many files" }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy(SourceEntry::relativePath).forEach { entry ->
            cancellation.throwIfCancelled()
            digest.update(entry.relativePath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            BufferedInputStream(FileInputStream(entry.file)).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var readBytes = 0L
                while (true) {
                    cancellation.throwIfCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    readBytes = Math.addExact(readBytes, read.toLong())
                    require(readBytes <= entry.size) { "Plugin source changed while hashing" }
                    digest.update(buffer, 0, read)
                }
                require(readBytes == entry.size) { "Plugin source changed while hashing" }
            }
            require(entry.file.length() == entry.size) { "Plugin source changed while hashing" }
        }
        return digest.digest().joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
    }

    private fun PluginSourceHashCancellation.throwIfCancelled() {
        if (isCancellationRequested()) throw PluginRuntimePreparationCancelledException()
    }

    private data class SourceEntry(
        val relativePath: String,
        val file: File,
        val size: Long,
    )

    private companion object {
        const val MAXIMUM_FILES = 8_192
        const val MAXIMUM_BYTES = 64L * 1024L * 1024L
        const val MAXIMUM_FILE_BYTES = 16L * 1024L * 1024L
        const val MAXIMUM_RELATIVE_PATH_BYTES = 4_096
        const val BUFFER_BYTES = 64 * 1024
    }
}
