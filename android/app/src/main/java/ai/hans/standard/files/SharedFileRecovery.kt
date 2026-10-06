package ai.hans.standard.files

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Supplied by Android, never by a tool argument. The private directory belongs to Hans. */
internal data class SharedFileRecoveryRoot(val publicRoot: File, val privateDirectory: File)

internal data class SharedFileRecoveryInfo(
    val handle: String,
    val originalPath: String,
    val recoveryPath: String,
    val bytes: Long,
    val entryType: String = "file",
)

/**
 * Recovery retains the captured entry: there is intentionally no purge/unlink API.
 * An old writer may still own its descriptor even after an atomic rename. Moving the
 * entry into Hans' same-volume external-files directory preserves those later writes
 * and prevents other apps from replacing the captured pathname before verification.
 */
internal class SharedFileRecovery(private val roots: () -> List<SharedFileRecoveryRoot>) {
    data class Reservation(val handle: String, val original: Path, val directory: Path, val entry: Path) {
        fun info(): SharedFileRecoveryInfo {
            val captured = attributes(entry)
            return SharedFileRecoveryInfo(handle, original.toString(), entry.toString(), captured?.size() ?: 0L, entryType(captured))
        }
    }

    fun reserve(source: File): Reservation = synchronized(ALLOCATION_LOCK) {
        val sourcePath = source.toPath().toAbsolutePath().normalize()
        val configuredRoots = roots()
        val root = configuredRoots.filter { sourcePath.startsWith(it.publicRoot.canonicalFile.toPath()) }
            .maxByOrNull { it.publicRoot.canonicalFile.toPath().nameCount }
            ?: throw FileAccessFailure("safe_file_recovery_unavailable")
        val privateRoot = checkedRoot(root.privateDirectory, create = false)
        var count = 0
        configuredRoots.distinctBy { it.privateDirectory.canonicalPath }.forEach { configured ->
            val directory = checkedRoot(configured.privateDirectory, create = false)
            if (Files.exists(directory, NOFOLLOW_LINKS)) Files.newDirectoryStream(directory).use { entries ->
                for (ignored in entries) if (++count >= MAX_ENTRIES) throw FileAccessFailure("file_recovery_full")
            }
        }
        checkedRoot(root.privateDirectory, create = true)
        val handle = UUID.randomUUID().toString()
        val directory = Files.createDirectory(privateRoot.resolve(handle))
        val reservation = Reservation(handle, sourcePath, directory, directory.resolve(ENTRY_NAME))
        try {
            DataOutputStream(Files.newOutputStream(directory.resolve(RECEIPT_NAME), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use {
                it.writeInt(RECEIPT_VERSION)
                it.writeUTF(sourcePath.toString())
            }
            reservation
        } catch (failure: Exception) {
            abandonEmpty(reservation)
            throw failure
        }
    }

    fun list(): List<SharedFileRecoveryInfo> {
        var visited = 0
        return roots().distinctBy { it.privateDirectory.canonicalPath }.flatMap { root ->
            val path = checkedRoot(root.privateDirectory, create = false)
            if (!Files.exists(path, NOFOLLOW_LINKS)) emptyList() else {
                val entries = ArrayList<SharedFileRecoveryInfo>()
                Files.newDirectoryStream(path).use { stream ->
                    for (directory in stream) {
                        if (++visited > MAX_ENTRIES) throw FileAccessFailure("file_recovery_full")
                        runCatching { read(directory) }.getOrNull()?.let(entries::add)
                    }
                }
                entries
            }
        }.distinctBy { it.recoveryPath }
    }

    fun find(handle: String): SharedFileRecoveryInfo {
        if (!HANDLE.matches(handle)) throw FileAccessFailure("invalid_file_recovery_handle")
        val matches = roots().distinctBy { it.privateDirectory.canonicalPath }.mapNotNull { root ->
            val privateRoot = checkedRoot(root.privateDirectory, create = false)
            runCatching { read(privateRoot.resolve(handle)) }.getOrNull()
        }
        if (matches.size != 1) throw FileAccessFailure("file_recovery_not_found")
        return matches.single()
    }

    /** Only removes our empty reservation and receipt. A captured user entry is never removed. */
    fun abandonEmpty(reservation: Reservation) {
        if (!Files.exists(reservation.entry, NOFOLLOW_LINKS)) runCatching {
            Files.deleteIfExists(reservation.directory.resolve(RECEIPT_NAME))
            Files.deleteIfExists(reservation.directory)
        }
    }

    private fun read(directory: Path): SharedFileRecoveryInfo {
        if (!HANDLE.matches(directory.fileName.toString()) || Files.isSymbolicLink(directory) ||
            !Files.isDirectory(directory, NOFOLLOW_LINKS)) throw FileAccessFailure("invalid_file_recovery_entry")
        val entry = directory.resolve(ENTRY_NAME)
        if (!Files.exists(entry, NOFOLLOW_LINKS)) {
            throw FileAccessFailure("invalid_file_recovery_entry")
        }
        val original = DataInputStream(Files.newInputStream(directory.resolve(RECEIPT_NAME), StandardOpenOption.READ,
            NOFOLLOW_LINKS)).use {
            if (it.readInt() != RECEIPT_VERSION) throw FileAccessFailure("invalid_file_recovery_entry")
            it.readUTF().also { raw ->
                if (raw.length !in 1..4096 || raw.any(Char::isISOControl) || !File(raw).isAbsolute || it.read() != -1) {
                    throw FileAccessFailure("invalid_file_recovery_entry")
                }
            }
        }
        val attributes = checkNotNull(attributes(entry))
        return SharedFileRecoveryInfo(directory.fileName.toString(), original, entry.toString(), attributes.size(), entryType(attributes))
    }

    private fun checkedRoot(directory: File, create: Boolean): Path {
        val path = directory.toPath().toAbsolutePath().normalize()
        var cursor = path.root
        for (part in path) {
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) throw FileAccessFailure("safe_file_recovery_unavailable")
        }
        if (create) Files.createDirectories(path)
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw FileAccessFailure("safe_file_recovery_unavailable")
        }
        return path
    }

    companion object {
        const val MAX_ENTRIES = 64
        private val ALLOCATION_LOCK = Any()
        private val HANDLE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val ENTRY_NAME = "entry"
        private const val RECEIPT_NAME = "receipt"
        private const val RECEIPT_VERSION = 1
        private fun attributes(path: Path): BasicFileAttributes? = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        }.getOrNull()
        private fun entryType(attributes: BasicFileAttributes?): String = when {
            attributes == null -> "unknown"
            attributes.isSymbolicLink -> "symbolic_link"
            attributes.isDirectory -> "directory"
            attributes.isRegularFile -> "file"
            else -> "other"
        }
    }
}
