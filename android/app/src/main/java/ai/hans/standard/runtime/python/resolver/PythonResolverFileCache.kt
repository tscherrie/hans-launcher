package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Bounded app-private ETag cache. Corrupt or symlinked entries are ignored, never trusted. */
class FilePythonResolverHttpCache(
    rootDirectory: File,
) : PythonResolverHttpCache {
    private val root = rootDirectory.canonicalFile
    private val lock = Any()

    init {
        require(!Files.isSymbolicLink(rootDirectory.toPath()))
        require(this.root.mkdirs() || this.root.isDirectory)
        require(!Files.isSymbolicLink(this.root.toPath()))
        synchronized(lock) { prune() }
    }

    override fun get(url: String): PythonCachedHttpResource? = synchronized(lock) {
        val file = entry(url)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
            file.length() !in 1..MAX_ENTRY_BYTES
        ) return null
        val resource = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val headerSize = input.readInt()
                require(headerSize in 2..MAX_HEADER_BYTES)
                val headerBytes = ByteArray(headerSize)
                input.readFully(headerBytes)
                val header = JSONObject(headerBytes.toString(Charsets.UTF_8))
                val storedUrl = header.optString("url")
                val etag = header.opt("etag").let { if (it == JSONObject.NULL) null else it as? String }
                val sha256 = header.optString("sha256")
                val size = when (val value = header.opt("sizeBytes")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> -1L
                }
                require(storedUrl == url && size in 0..PythonResolverLimits.MAX_SIMPLE_JSON_BYTES.toLong())
                require(PythonEnvironmentContract.isSha256(sha256))
                val body = ByteArray(size.toInt())
                input.readFully(body)
                require(input.read() < 0)
                PythonCachedHttpResource(storedUrl, etag, body, sha256)
            }
        }.getOrNull() ?: run {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        resource
    }

    override fun put(resource: PythonCachedHttpResource) = synchronized(lock) {
        if (resource.body.size > PythonResolverLimits.MAX_SIMPLE_JSON_BYTES) return
        val header = JSONObject()
            .put("url", resource.url)
            .put("etag", resource.etag ?: JSONObject.NULL)
            .put("sha256", resource.sha256)
            .put("sizeBytes", resource.body.size)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES)
        val target = entry(resource.url)
        val staging = File(root, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(staging).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(header.size)
                output.write(header)
                output.write(resource.body)
                output.flush()
                file.fd.sync()
            }
            try {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setReadable(true, true)
            target.setWritable(true, true)
            target.setExecutable(false, false)
            prune()
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun entry(url: String) = File(root, "${PythonEnvironmentContract.sha256(url)}.cache")

    private fun prune() {
        val files = root.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".cache") }
            .sortedByDescending(File::lastModified)
        var count = 0
        var bytes = 0L
        files.forEach { file ->
            count += 1
            bytes += file.length()
            if (count > PythonResolverLimits.MAX_CACHE_ENTRIES ||
                bytes > PythonResolverLimits.MAX_CACHE_BYTES
            ) file.delete()
        }
    }

    private companion object {
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_ENTRY_BYTES = PythonResolverLimits.MAX_SIMPLE_JSON_BYTES + MAX_HEADER_BYTES + 4L
    }
}
