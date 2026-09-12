package ai.hans.standard.files

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class FileAccessFailure(val code: String) : Exception(code)

internal data class SharedFileInfo(
    val path: String,
    val name: String,
    val directory: Boolean,
    val bytes: Long,
    val modifiedMillis: Long,
    val sha256: String? = null,
)

/** Public shared storage only. No root, private-app path, background index or cached grant. */
internal class SharedFileStore(
    private val roots: () -> List<File>,
    private val accessGranted: () -> Boolean,
    private val aliases: () -> Map<String, File> = { emptyMap() },
    private val changed: (File) -> Unit = {},
) {
    private val mutationLock = Any()

    fun granted(): Boolean = runCatching(accessGranted).getOrDefault(false)

    fun locations(): List<String> = roots().map { it.canonicalPath }.distinct()

    fun requireAccess() {
        if (!granted()) throw FileAccessFailure("all_files_access_required")
    }

    fun resolve(raw: String, allowRoot: Boolean = true): File {
        requireAccess()
        if (raw.length !in 1..4096 || raw.any(Char::isISOControl) || '\\' in raw) {
            throw FileAccessFailure("invalid_path")
        }
        val mapped = aliases().entries.firstOrNull { raw == it.key || raw.startsWith(it.key + "/") }
            ?.let { (alias, root) -> root.path + raw.removePrefix(alias) } ?: raw
        val input = File(mapped)
        if (!input.isAbsolute) throw FileAccessFailure("absolute_path_required")
        val path = input.toPath().normalize()
        val root = roots().map { it.canonicalFile.toPath() }
            .filter { path.startsWith(it) }.maxByOrNull { it.nameCount }
            ?: throw FileAccessFailure("outside_shared_storage")
        if (!allowRoot && path == root) throw FileAccessFailure("storage_root_protected")
        val relative = root.relativize(path)
        if (relative.nameCount > 0 && relative.getName(0).toString().equals("Android", true) &&
            (relative.nameCount < 2 || !relative.getName(1).toString().equals("media", true))) {
            throw FileAccessFailure("android_private_storage_protected")
        }
        var cursor = root
        for (part in relative) {
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) throw FileAccessFailure("symbolic_link_not_supported")
        }
        if (!path.toFile().canonicalFile.toPath().startsWith(root)) {
            throw FileAccessFailure("outside_shared_storage")
        }
        return path.toFile()
    }

    fun stat(raw: String, hash: Boolean = false, checkCancelled: () -> Unit = {}): SharedFileInfo {
        val file = resolve(raw)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) throw FileAccessFailure("file_not_found")
        if (!file.isDirectory && !file.isFile) throw FileAccessFailure("not_a_regular_file")
        return SharedFileInfo(file.path, file.name, file.isDirectory, if (file.isFile) file.length() else 0,
            file.lastModified(), if (hash && file.isFile) digest(file, checkCancelled) else null)
    }

    /** Bounded directory enumeration. Large directories are explicitly reported, not silently cut. */
    fun list(raw: String, offset: Int, limit: Int): Pair<List<SharedFileInfo>, Boolean> {
        val directory = resolve(raw)
        if (!directory.isDirectory) throw FileAccessFailure("not_a_directory")
        if (offset !in 0..10_000 || limit !in 1..100) throw FileAccessFailure("invalid_page")
        val entries = ArrayList<Path>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (entry in stream) {
                if (entries.size == 10_000) throw FileAccessFailure("directory_too_large_use_search")
                entries.add(entry)
            }
        }
        val safe = entries.sortedBy { it.fileName.toString() }.mapNotNull { entry ->
            try { stat(entry.toString()) } catch (_: FileAccessFailure) { null }
        }
        return safe.drop(offset).take(limit) to (offset + limit < safe.size)
    }

    fun search(raw: String, query: String, limit: Int, checkCancelled: () -> Unit): Pair<List<SharedFileInfo>, Boolean> {
        val root = resolve(raw)
        if (!root.isDirectory || query.isBlank() || query.length > 255 || limit !in 1..100) {
            throw FileAccessFailure("invalid_search")
        }
        val matches = ArrayList<SharedFileInfo>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            checkCancelled()
            requireAccess()
            val (directory, depth) = queue.removeFirst()
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (entry in stream) {
                    checkCancelled()
                    if (++visited > 2_000) return matches to true
                    val info = try { stat(entry.toString()) } catch (_: FileAccessFailure) { continue }
                    if (info.name.contains(query, ignoreCase = true)) {
                        matches.add(info)
                        if (matches.size == limit) return matches to true
                    }
                    if (info.directory) {
                        if (depth < 8) queue.add(File(info.path) to depth + 1) else truncated = true
                    }
                }
            }
        }
        return matches to truncated
    }

    fun open(raw: String): InputStream {
        val file = resolve(raw)
        if (!file.isFile) throw FileAccessFailure("file_not_found")
        return Files.newInputStream(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS)
    }

    fun readText(raw: String, maxBytes: Int): Pair<String, Boolean> {
        if (maxBytes !in 4..65_536) throw FileAccessFailure("invalid_text_limit")
        open(raw).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            val truncated = bytes.size > maxBytes
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val source = ByteBuffer.wrap(bytes, 0, minOf(bytes.size, maxBytes))
            val text = java.nio.CharBuffer.allocate(maxBytes)
            val result = decoder.decode(source, text, !truncated)
            if (result.isError) throw FileAccessFailure("not_utf8_text_use_load")
            text.flip()
            return text.toString() to truncated
        }
    }

    /** Never overwrites. CREATE_NEW reserves the exact target; failure removes only our new file. */
    fun save(raw: String, input: InputStream, maxBytes: Long, checkCancelled: () -> Unit): SharedFileInfo =
        synchronized(mutationLock) {
            val destination = resolve(raw, allowRoot = false)
            if (destination.parentFile?.isDirectory != true) throw FileAccessFailure("parent_directory_missing")
            if (maxBytes !in 1..MAX_BYTES) throw FileAccessFailure("invalid_byte_limit")
            var created = false
            var completed = false
            try {
                checkCancelled()
                Files.newByteChannel(destination.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { output ->
                    created = true
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw FileAccessFailure("file_byte_limit_exceeded")
                        digest.update(buffer, 0, count)
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) output.write(bytes)
                    }
                    if (output is java.nio.channels.FileChannel) output.force(true)
                    requireAccess()
                    checkCancelled()
                    val receipt = stat(raw, hash = true, checkCancelled = checkCancelled)
                    if (receipt.bytes != total || receipt.sha256 != hex(digest.digest())) {
                        throw FileAccessFailure("file_verification_failed")
                    }
                    completed = true
                    runCatching { changed(destination) }
                    return@synchronized receipt
                }
            } finally {
                if (created && !completed) Files.deleteIfExists(destination.toPath())
            }
        }

    fun mkdir(raw: String): SharedFileInfo = synchronized(mutationLock) {
        val directory = resolve(raw, allowRoot = false)
        Files.createDirectory(directory.toPath())
        stat(raw)
    }

    fun copy(source: String, destination: String, move: Boolean, expectedSha256: String?, checkCancelled: () -> Unit): SharedFileInfo =
        synchronized(mutationLock) {
            val before = stat(source, hash = true, checkCancelled = checkCancelled)
            if (before.directory) throw FileAccessFailure("file_required")
            if (move && before.sha256 != expectedSha256) throw FileAccessFailure("source_changed_read_stat_first")
            val saved = open(source).use { save(destination, it, MAX_BYTES, checkCancelled) }
            if (saved.sha256 != before.sha256) throw FileAccessFailure("source_changed_destination_retained")
            if (move) delete(source, checkNotNull(expectedSha256), checkCancelled)
            saved
        }

    /** Exact regular file only, digest precondition, no recursive delete or root/dir deletion. */
    fun delete(raw: String, expectedSha256: String, checkCancelled: () -> Unit) = synchronized(mutationLock) {
        val file = resolve(raw, allowRoot = false)
        val info = stat(raw, hash = true, checkCancelled = checkCancelled)
        if (info.directory || info.sha256 != expectedSha256) throw FileAccessFailure("file_changed_read_stat_first")
        checkCancelled()
        requireAccess()
        Files.delete(file.toPath())
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) throw FileAccessFailure("delete_not_verified")
        runCatching { changed(file) }
    }

    private fun digest(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(file.path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
