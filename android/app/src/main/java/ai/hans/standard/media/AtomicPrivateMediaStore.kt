package ai.hans.standard.media

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Transactional, app-private storage for one imported media item.
 *
 * A selection is first written below a hidden staging directory. The directory
 * becomes visible under its stable import id only after validation and all
 * derived artifacts have completed. A failed/abandoned transaction removes its
 * own staging tree and never traverses symbolic links.
 */
class AtomicPrivateMediaStore(
    context: Context,
    requestedRoot: File = File(context.filesDir, DEFAULT_DIRECTORY),
) {
    private val root: File

    init {
        val filesRoot = context.filesDir.canonicalFile
        val candidate = requestedRoot.canonicalFile
        require(candidate == filesRoot || candidate.path.startsWith(filesRoot.path + File.separator)) {
            "Media storage must remain below the app-private files directory"
        }
        if (!candidate.exists() && !candidate.mkdirs()) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Could not create private media storage.",
            )
        }
        if (!candidate.isDirectory || Files.isSymbolicLink(candidate.toPath())) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Private media storage is not a regular directory.",
            )
        }
        root = candidate
    }

    fun begin(importId: String = UUID.randomUUID().toString()): Transaction {
        requireSafeRoot()
        require(SAFE_IMPORT_ID.matches(importId)) { "Unsafe import id" }
        val finalDirectory = child(importId)
        if (finalDirectory.exists()) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "The private media import id already exists.",
            )
        }
        val stagingDirectory = child("$STAGING_PREFIX$importId-${UUID.randomUUID()}")
        if (!stagingDirectory.mkdir()) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Could not create a private media transaction.",
            )
        }
        return Transaction(importId, stagingDirectory, finalDirectory)
    }

    fun deleteImport(importId: String): Boolean {
        requireSafeRoot()
        require(SAFE_IMPORT_ID.matches(importId)) { "Unsafe import id" }
        val target = child(importId)
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return false
        deleteTreeWithoutFollowingLinks(target)
        return !target.exists() && !Files.isSymbolicLink(target.toPath())
    }

    fun importExists(importId: String): Boolean {
        requireSafeRoot()
        require(SAFE_IMPORT_ID.matches(importId)) { "Unsafe import id" }
        val target = child(importId)
        return target.exists() || Files.isSymbolicLink(target.toPath())
    }

    /** Removes only incomplete transactions created by this module. */
    fun cleanupIncompleteImports(
        olderThanEpochMillis: Long = System.currentTimeMillis() - DEFAULT_STALE_MILLIS,
    ): Int {
        requireSafeRoot()
        var removed = 0
        root.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) }
            .filter { it.lastModified() <= olderThanEpochMillis }
            .forEach { candidate ->
                deleteTreeWithoutFollowingLinks(candidate)
                if (!candidate.exists() && !Files.isSymbolicLink(candidate.toPath())) removed += 1
            }
        return removed
    }

    inner class Transaction internal constructor(
        val importId: String,
        private val stagingDirectory: File,
        private val finalDirectory: File,
    ) : Closeable {
        private var committed = false
        private var closed = false

        fun stagingFile(name: String): File {
            check(!closed) { "Transaction is closed" }
            require(SAFE_FILE_NAME.matches(name)) { "Unsafe private media filename" }
            val target = File(stagingDirectory, name)
            check(target.parentFile == stagingDirectory)
            return target
        }

        fun writeAtomically(
            name: String,
            maxBytes: Long,
            writer: (OutputStream) -> Unit,
        ): Long {
            require(maxBytes > 0)
            val target = stagingFile(name)
            val temporary = temporaryFileFor(name)
            if (target.exists() || temporary.exists()) {
                throw MediaImportException(
                    MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                    "A private media artifact already exists.",
                )
            }
            try {
                FileOutputStream(temporary).use { fileOutput ->
                    val bounded = BoundedOutputStream(fileOutput, maxBytes)
                    writer(bounded)
                    bounded.flush()
                    fileOutput.fd.sync()
                }
                if (!temporary.renameTo(target)) {
                    throw MediaImportException(
                        MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                        "Could not finalize a private media artifact.",
                    )
                }
                return target.length()
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

        internal fun temporaryFileFor(name: String): File {
            check(!closed) { "Transaction is closed" }
            require(SAFE_FILE_NAME.matches(name)) { "Unsafe private media filename" }
            val target = File(stagingDirectory, ".$name.part")
            check(target.parentFile == stagingDirectory)
            return target
        }

        internal fun finalizeTemporary(temporary: File, name: String): File {
            check(!closed) { "Transaction is closed" }
            val expected = temporaryFileFor(name)
            require(temporary == expected) { "Unexpected private media temporary file" }
            val target = stagingFile(name)
            if (!temporary.renameTo(target)) {
                throw MediaImportException(
                    MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                    "Could not finalize a private media artifact.",
                )
            }
            return target
        }

        fun commit(): File {
            check(!closed) { "Transaction is closed" }
            check(!committed) { "Transaction is already committed" }
            verifyRegularStagingTree(stagingDirectory)
            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw MediaImportException(
                    MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                    "Could not commit the private media import.",
                )
            }
            committed = true
            closed = true
            return finalDirectory
        }

        fun finalFile(name: String): File {
            require(committed) { "Transaction has not committed" }
            require(SAFE_FILE_NAME.matches(name)) { "Unsafe private media filename" }
            val result = File(finalDirectory, name)
            if (
                Files.isSymbolicLink(result.toPath()) ||
                !result.isFile ||
                result.canonicalFile.parentFile != finalDirectory.canonicalFile
            ) {
                throw MediaImportException(
                    MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                    "A committed private media artifact is not a regular file.",
                )
            }
            return result
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!committed) deleteTreeWithoutFollowingLinks(stagingDirectory)
        }
    }

    private fun child(name: String): File {
        val target = File(root, name)
        check(target.parentFile == root)
        return target
    }

    private fun requireSafeRoot() {
        if (
            !root.isDirectory ||
            Files.isSymbolicLink(root.toPath()) ||
            root.canonicalFile != root
        ) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Private media storage is no longer a regular app directory.",
            )
        }
    }

    private fun verifyRegularStagingTree(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            throw MediaImportException(
                MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                "Private media staging was replaced during import.",
            )
        }
        directory.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath()) || !child.isFile) {
                throw MediaImportException(
                    MediaImportFailureCode.PRIVATE_STORAGE_FAILURE,
                    "Private media staging contains an unexpected entry.",
                )
            }
        }
    }

    private fun deleteTreeWithoutFollowingLinks(target: File) {
        val targetPath = target.toPath()
        if (!Files.exists(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            targetPath,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        const val DEFAULT_DIRECTORY = "hans-media"
        private const val STAGING_PREFIX = ".import-"
        private const val DEFAULT_STALE_MILLIS = 24L * 60L * 60L * 1_000L
        private val SAFE_IMPORT_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}")
        private val SAFE_FILE_NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    }
}

internal class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    var byteCount: Long = 0
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        byteCount += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        ensureCapacity(length)
        delegate.write(buffer, offset, length)
        byteCount += length
    }

    override fun flush() = delegate.flush()

    private fun ensureCapacity(nextByteCount: Int) {
        if (byteCount + nextByteCount > maxBytes) {
            throw MediaImportException(
                MediaImportFailureCode.READ_LIMIT_EXCEEDED,
                "The selected media exceeds its configured byte limit.",
            )
        }
    }
}
