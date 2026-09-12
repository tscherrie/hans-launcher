package ai.hans.standard.mcp

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Shared process lock so read-modify-write operations remain atomic across registry instances. */
internal object RemoteMcpPrivateStateLock {
    val process = Any()
}

internal class RemoteMcpPrivateStateUnavailableException(
    val errorCode: String,
) : IllegalStateException(errorCode) {
    init {
        require(errorCode.matches(Regex("mcp_[a-z0-9_]{1,80}")))
    }
}

/**
 * Bounded, no-follow, same-directory atomic state file.
 *
 * The caller supplies an app-private/no-backup directory. Existing links or special files are
 * rejected rather than followed or replaced. Unknown I/O details are deliberately collapsed to a
 * stable code so paths and credential material cannot escape through public failures.
 */
internal class RemoteMcpPrivateStateFile(
    directory: File,
    fileName: String,
    private val maxBytes: Int,
) {
    private val root = directory.absoluteFile
    private val target = File(root, fileName)
    private val staging = File(root, "$fileName.tmp")

    init {
        require(fileName == File(fileName).name && fileName.matches(SAFE_FILE_NAME)) {
            "Invalid Remote MCP state file name"
        }
        require(maxBytes in 1_024..(4 * 1024 * 1024)) { "Invalid Remote MCP state limit" }
    }

    fun readOrNull(): ByteArray? = synchronized(RemoteMcpPrivateStateLock.process) {
        protect {
            requireRoot()
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@synchronized null
            requireRegularFile(target)
            val size = Files.size(path)
            require(size in 1..maxBytes.toLong()) { "Remote MCP state size is invalid" }
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    require(channel.read(buffer) >= 0) { "Remote MCP state was truncated" }
                }
                require(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "Remote MCP state changed while reading"
                }
                buffer.array()
            }
        }
    }

    fun write(bytes: ByteArray) = synchronized(RemoteMcpPrivateStateLock.process) {
        protect {
            require(bytes.size in 1..maxBytes) { "Remote MCP state size is invalid" }
            requireRoot()
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requireRegularFile(target)
            }
            prepareStaging()
            try {
                Files.createFile(staging.toPath())
                requireRegularFile(staging)
                FileChannel.open(
                    staging.toPath(),
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                require(Files.size(staging.toPath()) == bytes.size.toLong()) {
                    "Remote MCP state write was incomplete"
                }
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                requireRegularFile(target)
            } finally {
                val stagingPath = staging.toPath()
                if (Files.exists(stagingPath, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(stagingPath)
                ) {
                    Files.deleteIfExists(stagingPath)
                }
            }
        }
    }

    fun delete() = synchronized(RemoteMcpPrivateStateLock.process) {
        protect {
            requireRoot()
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@synchronized
            requireRegularFile(target)
            Files.delete(path)
        }
    }

    private fun prepareStaging() {
        val path = staging.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        requireRegularFile(staging)
        Files.delete(path)
    }

    private fun requireRoot() {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(root.mkdirs()) { "Remote MCP state directory could not be created" }
        }
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Remote MCP state directory is not private"
        }
        require(target.parentFile == root && staging.parentFile == root) {
            "Remote MCP state escaped its private directory"
        }
    }

    private fun requireRegularFile(file: File) {
        val path = file.toPath()
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Remote MCP state is not a regular file"
        }
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (failure: RemoteMcpPrivateStateUnavailableException) {
        throw failure
    } catch (_: Throwable) {
        throw RemoteMcpPrivateStateUnavailableException("mcp_private_state_unavailable")
    }

    private companion object {
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
