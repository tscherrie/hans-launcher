package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import ai.hans.standard.workspace.PrivateWorkspaceStore
import ai.hans.standard.workspace.WorkspaceHandle
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class PythonWorkspaceArchiveReceipt(
    val workspaceHandle: String,
    val archive: File,
    val sha256: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val contentBytes: Long,
)

fun interface PythonWorkspaceArchiveProvider {
    /** Resolves an opaque content-addressed workspace to one verified read-only archive. */
    fun open(workspaceHandle: String): PythonWorkspaceArchiveReceipt
}

/**
 * Main-UID adapter from [PrivateWorkspaceStore] to the isolated Python descriptor boundary.
 *
 * The generated ZIP is non-executable, content-verified and replaceable. It contains a bounded
 * manifest plus project files below a reserved prefix. No app-private or external path is ever
 * serialized into it. The isolated worker verifies both the archive digest and workspace content
 * digest before exposing a request-scoped read/import API.
 */
class PrivateWorkspacePythonArchiveProvider(
    private val store: PrivateWorkspaceStore,
    requestedArchiveRoot: File,
    privateBoundary: File,
) : PythonWorkspaceArchiveProvider {
    private val boundary = privateBoundary.canonicalFile
    private val archiveRoot = requestedArchiveRoot.canonicalFile
    private val monitor = Any()
    private val receipts = mutableMapOf<String, PythonWorkspaceArchiveReceipt>()

    init {
        require(boundary.isDirectory || boundary.mkdirs()) { "Private Python boundary is unavailable" }
        require(!Files.isSymbolicLink(boundary.toPath())) { "Private Python boundary is a symbolic link" }
        require(archiveRoot.path.startsWith(boundary.path + File.separator)) {
            "Python workspace archives must remain below the app-private boundary"
        }
        require(archiveRoot.isDirectory || archiveRoot.mkdirs()) {
            "Python workspace archive storage is unavailable"
        }
        requireSafeArchiveRoot()
    }

    override fun open(workspaceHandle: String): PythonWorkspaceArchiveReceipt = synchronized(monitor) {
        val handle = WorkspaceHandle(workspaceHandle)
        val snapshot = store.snapshot(handle)
        receipts[workspaceHandle]
            ?.takeIf { cached ->
                cached.fileCount == snapshot.manifest.files.size &&
                    cached.contentBytes == snapshot.manifest.byteCount &&
                    isValidCachedReceipt(cached)
            }
            ?.let { return@synchronized it }

        require(snapshot.manifest.files.size <= PythonRuntimeFdContract.MAX_WORKSPACE_FILES) {
            "Python workspace file-count limit exceeded"
        }
        require(snapshot.manifest.byteCount <= PythonRuntimeFdContract.MAX_WORKSPACE_CONTENT_BYTES) {
            "Python workspace byte limit exceeded"
        }
        snapshot.manifest.files.forEach { entry ->
            require(entry.relativePath.toByteArray(StandardCharsets.UTF_8).size <=
                PythonRuntimeFdContract.MAX_WORKSPACE_PATH_BYTES) {
                "Python workspace path limit exceeded"
            }
        }

        requireSafeArchiveRoot()
        val staging = File(archiveRoot, ".workspace-${handle.value}-${System.nanoTime()}.tmp")
        val built = try {
            store.withVerifiedReadSnapshot(handle) { verified, copyFile ->
                check(verified == snapshot) { "Workspace changed before archive export" }
                writeArchive(staging, verified.handle, verified.manifest.files, copyFile)
            }
            val size = staging.length()
            require(size in PythonRuntimeFdContract.MIN_WORKSPACE_ARCHIVE_BYTES..
                PythonRuntimeFdContract.MAX_WORKSPACE_ARCHIVE_BYTES) {
                "Python workspace archive-size limit exceeded"
            }
            val digest = sha256(staging)
            val destination = File(archiveRoot, "${handle.value}-$digest.zip")
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(destination.isFile && !Files.isSymbolicLink(destination.toPath())) {
                    "Python workspace archive target is unsafe"
                }
                require(destination.length() == size && sha256(destination) == digest) {
                    "Python workspace archive target is corrupt"
                }
                check(staging.delete()) { "Cannot discard duplicate Python workspace archive" }
            } else {
                require(staging.renameTo(destination)) { "Cannot activate Python workspace archive" }
                destination.setReadable(true, true)
                destination.setWritable(false, false)
                destination.setExecutable(false, false)
            }
            PythonWorkspaceArchiveReceipt(
                workspaceHandle = handle.value,
                archive = destination.canonicalFile,
                sha256 = digest,
                sizeBytes = size,
                fileCount = snapshot.manifest.files.size,
                contentBytes = snapshot.manifest.byteCount,
            )
        } finally {
            if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) staging.delete()
        }
        receipts[workspaceHandle] = built
        built
    }

    private fun writeArchive(
        target: File,
        handle: WorkspaceHandle,
        files: List<ai.hans.standard.workspace.WorkspaceFileEntry>,
        copyFile: (ai.hans.standard.workspace.WorkspaceFileEntry, java.io.OutputStream) -> Unit,
    ) {
        val manifest = JSONObject()
            .put("protocolVersion", PythonRuntimeFdContract.PROTOCOL_VERSION)
            .put("workspaceHandle", handle.value)
            .put(
                "files",
                JSONArray().apply {
                    files.forEach { entry ->
                        put(
                            JSONObject()
                                .put("relativePath", entry.relativePath)
                                .put("byteCount", entry.byteCount)
                                .put("sha256", entry.sha256),
                        )
                    }
                },
            )
        val manifestBytes = JsonContract.encodeBounded(
            manifest,
            PythonRuntimeFdContract.MAX_WORKSPACE_MANIFEST_BYTES,
        ).toByteArray(StandardCharsets.UTF_8)

        FileOutputStream(target).use { fileOutput ->
            val buffered = BufferedOutputStream(fileOutput, 64 * 1024)
            val archive = ZipOutputStream(buffered, StandardCharsets.UTF_8).apply {
                setLevel(Deflater.BEST_SPEED)
            }
            try {
                archive.putNextEntry(stableEntry(PythonRuntimeFdContract.WORKSPACE_MANIFEST_MEMBER))
                archive.write(manifestBytes)
                archive.closeEntry()
                files.forEach { entry ->
                    archive.putNextEntry(
                        stableEntry(PythonRuntimeFdContract.WORKSPACE_FILE_PREFIX + entry.relativePath),
                    )
                    copyFile(entry, archive)
                    archive.closeEntry()
                }
                archive.finish()
                buffered.flush()
                fileOutput.fd.sync()
            } finally {
                archive.close()
            }
        }
    }

    private fun stableEntry(name: String) = ZipEntry(name).apply {
        time = 0L
        comment = null
        extra = null
    }

    private fun isValidCachedReceipt(receipt: PythonWorkspaceArchiveReceipt): Boolean {
        requireSafeArchiveRoot()
        val file = receipt.archive
        return file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.toPath().startsWith(archiveRoot.toPath()) &&
            file.length() == receipt.sizeBytes &&
            receipt.sizeBytes in PythonRuntimeFdContract.MIN_WORKSPACE_ARCHIVE_BYTES..
                PythonRuntimeFdContract.MAX_WORKSPACE_ARCHIVE_BYTES &&
            sha256(file) == receipt.sha256
    }

    private fun requireSafeArchiveRoot() {
        require(boundary.isDirectory && !Files.isSymbolicLink(boundary.toPath())) {
            "Private Python boundary is no longer safe"
        }
        require(archiveRoot.isDirectory && !Files.isSymbolicLink(archiveRoot.toPath())) {
            "Python workspace archive root is no longer safe"
        }
        require(archiveRoot.canonicalFile == archiveRoot &&
            archiveRoot.path.startsWith(boundary.path + File.separator)) {
            "Python workspace archive root escaped its boundary"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
