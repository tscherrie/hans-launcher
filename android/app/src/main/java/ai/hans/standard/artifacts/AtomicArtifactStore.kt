package ai.hans.standard.artifacts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Durable, bounded result storage for data too large for Binder/JSON messages. */
class AtomicArtifactStore(
    requestedRoot: File,
    privateBoundary: File,
    private val quotas: ArtifactQuotas = ArtifactQuotas(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nonce: () -> ByteArray = {
        ByteArray(32).also(SecureRandom()::nextBytes)
    },
) {
    private val boundary = privateBoundary.canonicalFile
    private val root = requestedRoot.canonicalFile
    private val monitor = Any()

    init {
        require(boundary.isDirectory || boundary.mkdirs()) { "Private artifact boundary is unavailable" }
        require(!Files.isSymbolicLink(boundary.toPath())) { "Private artifact boundary is a symbolic link" }
        require(root == boundary || root.path.startsWith(boundary.path + File.separator)) {
            "Artifact storage must remain below the app-private boundary"
        }
        require(root.isDirectory || root.mkdirs()) { "Private artifact storage is unavailable" }
        requireSafeRoot()
    }

    fun put(
        displayName: String,
        mimeType: String,
        origin: ArtifactOrigin,
        source: InputStream,
        maxBytes: Long = quotas.maxArtifactBytes,
        workspaceHandle: String? = null,
    ): ArtifactMetadata = synchronized(monitor) {
        requireSafeRoot()
        require(maxBytes in 1..quotas.maxArtifactBytes) { "Invalid artifact byte limit" }
        val createdAt = nowEpochMillis()
        // Validate user/provider metadata before consuming a potentially large stream.
        ArtifactMetadata(
            handle = ArtifactHandle("art_${"0".repeat(64)}"),
            displayName = displayName,
            mimeType = mimeType,
            byteCount = 0,
            sha256 = "0".repeat(64),
            createdAtEpochMillis = createdAt,
            origin = origin,
            workspaceHandle = workspaceHandle,
        )
        ensureCapacityForAnotherArtifact()
        val existingBytes = committedArtifactDirectories().fold(0L) { total, directory ->
            val payload = File(directory, PAYLOAD_FILE)
            Math.addExact(total, if (payload.isFile) payload.length() else 0L)
        }

        val stagingNonce = nonce().also { require(it.size >= 16) { "Artifact nonce is too short" } }
        val staging = File(root, "$STAGING_PREFIX${hex(stagingNonce)}")
        require(staging.mkdir()) { "Could not create artifact transaction" }
        try {
            val payload = File(staging, PAYLOAD_FILE)
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            FileOutputStream(payload).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    byteCount = Math.addExact(byteCount, count.toLong())
                    require(byteCount <= maxBytes) { "Artifact byte limit exceeded" }
                    require(Math.addExact(existingBytes, byteCount) <= quotas.maxStoreBytes) {
                        "Artifact store byte limit exceeded"
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
            val sha256 = hex(digest.digest())
            val handle = artifactHandle(sha256, byteCount)
            val metadata = ArtifactMetadata(
                handle = handle,
                displayName = displayName,
                mimeType = mimeType,
                byteCount = byteCount,
                sha256 = sha256,
                createdAtEpochMillis = createdAt,
                origin = origin,
                workspaceHandle = workspaceHandle,
            )
            writeMetadata(staging, metadata)
            verifyTransaction(staging, metadata)
            val destination = artifactDirectory(handle)
            require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Artifact handle collision"
            }
            moveAtomically(staging, destination)
            syncDirectory(root)
            metadata
        } finally {
            if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeWithoutFollowingLinks(staging)
            }
        }
    }

    fun metadata(handle: ArtifactHandle): ArtifactMetadata = synchronized(monitor) {
        val directory = verifiedDirectory(handle)
        readMetadata(File(directory, METADATA_FILE)).also { metadata ->
            check(metadata.handle == handle) { "Artifact metadata handle mismatch" }
            verifyPayload(File(directory, PAYLOAD_FILE), metadata)
        }
    }

    /** Explicit bounded inventory for the visible Workbench; no watcher or idle poller exists. */
    fun listMetadata(offset: Int = 0, limit: Int = 100): List<ArtifactMetadata> = synchronized(monitor) {
        require(offset in 0..1_000_000) { "Invalid artifact inventory offset" }
        require(limit in 1..500) { "Invalid artifact inventory limit" }
        requireSafeRoot()
        committedArtifactDirectories()
            .sortedBy(File::getName)
            .drop(offset)
            .take(limit)
            .map { directory -> metadata(ArtifactHandle(directory.name)) }
    }

    fun open(handle: ArtifactHandle): InputStream = synchronized(monitor) {
        val directory = verifiedDirectory(handle)
        val metadata = readMetadata(File(directory, METADATA_FILE))
        val payload = File(directory, PAYLOAD_FILE)
        verifyPayload(payload, metadata)
        FileInputStream(payload)
    }

    fun fileForReadLease(handle: ArtifactHandle): File = synchronized(monitor) {
        val directory = verifiedDirectory(handle)
        val metadata = readMetadata(File(directory, METADATA_FILE))
        File(directory, PAYLOAD_FILE).also { verifyPayload(it, metadata) }
    }

    fun delete(handle: ArtifactHandle): Boolean = synchronized(monitor) {
        val directory = artifactDirectory(handle)
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        deleteTreeWithoutFollowingLinks(directory)
        !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    fun cleanupIncompleteTransactions(
        olderThanEpochMillis: Long = System.currentTimeMillis() - DEFAULT_STALE_MILLIS,
    ): Int = synchronized(monitor) {
        var removed = 0
        root.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) && it.lastModified() <= olderThanEpochMillis }
            .forEach { candidate ->
                deleteTreeWithoutFollowingLinks(candidate)
                if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) removed += 1
            }
        removed
    }

    private fun artifactHandle(contentSha256: String, byteCount: Long): ArtifactHandle {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HANDLE_DOMAIN)
        digest.update(contentSha256.toByteArray(StandardCharsets.US_ASCII))
        digest.update(0)
        digest.update(byteCount.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(0)
        val handleNonce = nonce().also { require(it.size >= 16) { "Artifact nonce is too short" } }
        digest.update(handleNonce)
        return ArtifactHandle("art_${hex(digest.digest())}")
    }

    private fun ensureCapacityForAnotherArtifact() {
        val directories = committedArtifactDirectories()
        require(directories.size < quotas.maxArtifacts) { "Artifact count limit exceeded" }
    }

    private fun committedArtifactDirectories(): List<File> = root.listFiles().orEmpty().filter { candidate ->
        !candidate.name.startsWith(STAGING_PREFIX) &&
            candidate.name.startsWith("art_") &&
            candidate.isDirectory &&
            !Files.isSymbolicLink(candidate.toPath())
    }

    private fun verifiedDirectory(handle: ArtifactHandle): File {
        requireSafeRoot()
        val directory = artifactDirectory(handle)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Unknown artifact handle"
        }
        return directory
    }

    private fun artifactDirectory(handle: ArtifactHandle): File = File(root, handle.value).also {
        check(it.parentFile == root)
    }

    private fun verifyTransaction(directory: File, metadata: ArtifactMetadata) {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Artifact staging was replaced"
        }
        val children = directory.listFiles().orEmpty()
        require(children.map { it.name }.toSet() == setOf(PAYLOAD_FILE, METADATA_FILE)) {
            "Artifact transaction contains unexpected entries"
        }
        require(children.all { it.isFile && !Files.isSymbolicLink(it.toPath()) }) {
            "Artifact transaction contains an unsafe entry"
        }
        verifyPayload(File(directory, PAYLOAD_FILE), metadata)
        check(readMetadata(File(directory, METADATA_FILE)) == metadata) { "Artifact metadata mismatch" }
    }

    private fun verifyPayload(payload: File, metadata: ArtifactMetadata) {
        require(payload.isFile && !Files.isSymbolicLink(payload.toPath())) { "Artifact payload is unsafe" }
        check(payload.length() == metadata.byteCount) { "Artifact size mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(payload).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        check(hex(digest.digest()) == metadata.sha256) { "Artifact digest mismatch" }
    }

    private fun writeMetadata(directory: File, metadata: ArtifactMetadata) {
        val target = File(directory, METADATA_FILE)
        FileOutputStream(target).use { output ->
            output.write(ArtifactMetadataCodec.encode(metadata))
            output.flush()
            output.fd.sync()
        }
    }

    private fun readMetadata(file: File): ArtifactMetadata {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Artifact metadata is unsafe" }
        require(file.length() in 1..MAX_METADATA_BYTES) { "Artifact metadata size is invalid" }
        return ArtifactMetadataCodec.decode(file.readBytes())
    }

    private fun requireSafeRoot() {
        require(boundary.isDirectory && !Files.isSymbolicLink(boundary.toPath())) {
            "Private artifact boundary is no longer safe"
        }
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Private artifact root is no longer safe"
        }
        require(root.canonicalFile == root && (root == boundary || root.path.startsWith(boundary.path + File.separator))) {
            "Private artifact root escaped its boundary"
        }
    }

    companion object {
        private val HANDLE_DOMAIN = "hans-artifact-handle-v1\n".toByteArray(StandardCharsets.US_ASCII)
        private const val PAYLOAD_FILE = "payload"
        private const val METADATA_FILE = "metadata.v1"
        private const val STAGING_PREFIX = ".stage-"
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private const val DEFAULT_STALE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

internal object ArtifactMetadataCodec {
    private const val VERSION = "hans-artifact-v1"
    private const val FIELD_COUNT = 9

    fun encode(metadata: ArtifactMetadata): ByteArray = listOf(
        VERSION,
        metadata.handle.value,
        encodeText(metadata.displayName),
        metadata.mimeType,
        metadata.byteCount.toString(),
        metadata.sha256,
        metadata.createdAtEpochMillis.toString(),
        metadata.origin.name,
        metadata.workspaceHandle.orEmpty(),
    ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): ArtifactMetadata {
        require(bytes.size <= 16 * 1024) { "Artifact metadata is too large" }
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.endsWith('\n')) { "Truncated artifact metadata" }
        val fields = text.dropLast(1).split('\n')
        require(fields.size == FIELD_COUNT && fields[0] == VERSION) { "Unsupported artifact metadata" }
        return ArtifactMetadata(
            handle = ArtifactHandle(fields[1]),
            displayName = decodeText(fields[2]),
            mimeType = fields[3],
            byteCount = fields[4].toLongExact("byteCount"),
            sha256 = fields[5],
            createdAtEpochMillis = fields[6].toLongExact("createdAt"),
            origin = runCatching { ArtifactOrigin.valueOf(fields[7]) }
                .getOrElse { throw IllegalArgumentException("Unknown artifact origin", it) },
            workspaceHandle = fields[8].ifEmpty { null },
        )
    }

    private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = try {
        Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid artifact display name encoding", error)
    }

    private fun String.toLongExact(field: String): Long = toLongOrNull()
        ?: throw IllegalArgumentException("Invalid artifact $field")
}

private fun moveAtomically(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        require(source.renameTo(target)) { "Atomic artifact move is unavailable" }
    }
}

private fun syncDirectory(directory: File) {
    runCatching { FileInputStream(directory).use { it.fd.sync() } }
}

private fun deleteTreeWithoutFollowingLinks(target: File) {
    if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        target.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun hex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
