package ai.hans.standard.plugins.uninstall

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject

internal class PluginUninstallJournalCorruptException(cause: Throwable) :
    IllegalStateException("Plugin uninstall journal is corrupt", cause)

/** Atomic exact-CAS journal in Android's no-backup directory. */
internal class PluginUninstallJournal private constructor(
    private val directory: File,
    private val fileName: String,
) {
    constructor(
        context: Context,
        fileName: String = FILE_NAME,
    ) : this(context.applicationContext.noBackupFilesDir, fileName)

    internal constructor(directory: File) : this(directory, FILE_NAME)

    private val target = File(directory, fileName)
    private val staging = File(directory, "$fileName.tmp")

    init {
        require(fileName == File(fileName).name && FILE_NAME_PATTERN.matches(fileName))
    }

    fun readAll(): List<PluginUninstallJournalEntry> = synchronized(PROCESS_LOCK) { readLocked() }

    fun create(entry: PluginUninstallJournalEntry): PluginUninstallJournalMutationResult =
        synchronized(PROCESS_LOCK) {
            require(entry.revision == 1L)
            val current = readLocked()
            if (current.any { it.operationId == entry.operationId }) {
                return@synchronized PluginUninstallJournalMutationResult.OPERATION_ALREADY_EXISTS
            }
            if (current.size >= PluginUninstallBounds.MAX_ENTRIES) {
                return@synchronized PluginUninstallJournalMutationResult.JOURNAL_FULL
            }
            writeLocked(current + entry)
            PluginUninstallJournalMutationResult.APPLIED
        }

    fun compareAndSet(
        expected: PluginUninstallJournalCursor,
        replacement: PluginUninstallJournalEntry,
    ): PluginUninstallJournalMutationResult = synchronized(PROCESS_LOCK) {
        val current = readLocked()
        val index = current.indexOfFirst { it.operationId == expected.operationId }
        if (index < 0 || current[index].cursor != expected) {
            return@synchronized PluginUninstallJournalMutationResult.CURSOR_MISMATCH
        }
        requireStableReplacement(current[index], replacement)
        writeLocked(current.toMutableList().also { it[index] = replacement })
        PluginUninstallJournalMutationResult.APPLIED
    }

    fun remove(expected: PluginUninstallJournalCursor): PluginUninstallJournalMutationResult =
        synchronized(PROCESS_LOCK) {
            val current = readLocked()
            val index = current.indexOfFirst { it.operationId == expected.operationId }
            if (index < 0 || current[index].cursor != expected) {
                return@synchronized PluginUninstallJournalMutationResult.CURSOR_MISMATCH
            }
            writeLocked(current.filterIndexed { candidate, _ -> candidate != index })
            PluginUninstallJournalMutationResult.APPLIED
        }

    private fun readLocked(): List<PluginUninstallJournalEntry> {
        requireStorageDirectory()
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        requireRegular(target, "journal")
        val size = Files.size(path)
        if (size !in 1..PluginUninstallBounds.MAX_DOCUMENT_BYTES.toLong()) {
            throw PluginUninstallJournalCorruptException(
                IllegalArgumentException("Invalid plugin uninstall journal size"),
            )
        }
        val bytes = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
                require(!buffer.hasRemaining() && channel.read(ByteBuffer.allocate(1)) < 0)
                buffer.array()
            }
        } catch (failure: Throwable) {
            throw PluginUninstallJournalCorruptException(failure)
        }
        return try {
            PluginUninstallJournalCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } catch (failure: Throwable) {
            throw PluginUninstallJournalCorruptException(failure)
        }
    }

    private fun writeLocked(entries: List<PluginUninstallJournalEntry>) {
        requireStorageDirectory()
        val bytes = PluginUninstallJournalCodec.encode(entries).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..PluginUninstallBounds.MAX_DOCUMENT_BYTES)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) requireRegular(target, "journal")
        prepareStagingPath()
        try {
            Files.createFile(staging.toPath())
            requireRegular(staging, "staging file")
            FileChannel.open(
                staging.toPath(),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            require(staging.length() == bytes.size.toLong())
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            requireRegular(target, "journal")
        } finally {
            val path = staging.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun prepareStagingPath() {
        val path = staging.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        requireRegular(staging, "staging file")
        Files.delete(path)
    }

    private fun requireStorageDirectory() {
        val path = directory.toPath()
        require(
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
        ) { "Plugin uninstall journal directory is unavailable" }
        require(target.parentFile == directory && staging.parentFile == directory)
    }

    private fun requireRegular(file: File, label: String) {
        val path = file.toPath()
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin uninstall $label is not a regular file"
        }
    }

    private fun requireStableReplacement(
        previous: PluginUninstallJournalEntry,
        replacement: PluginUninstallJournalEntry,
    ) {
        require(replacement.operationId == previous.operationId)
        require(replacement.revision == previous.revision + 1L)
        require(replacement.target == previous.target) { "Plugin uninstall target changed" }
        require(replacement.runtime == previous.runtime) { "Plugin runtime uninstall target changed" }
        require(
            replacement.timestamps.createdAtWallEpochMillis ==
                previous.timestamps.createdAtWallEpochMillis &&
                replacement.timestamps.createdAtElapsedRealtimeMillis ==
                previous.timestamps.createdAtElapsedRealtimeMillis,
        )
        require(
            replacement.ownerProcessEpoch != previous.ownerProcessEpoch ||
                replacement.timestamps.updatedAtElapsedRealtimeMillis >=
                previous.timestamps.updatedAtElapsedRealtimeMillis,
        )
    }

    private companion object {
        const val FILE_NAME = "plugin-uninstall-journal-v1.json"
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val PROCESS_LOCK = Any()
    }
}

internal object PluginUninstallJournalCodec {
    private const val SCHEMA = "hans.plugin-uninstall-journal"
    private const val VERSION = 1

    fun encode(entries: List<PluginUninstallJournalEntry>): String {
        require(entries.size <= PluginUninstallBounds.MAX_ENTRIES)
        require(entries.map { it.operationId }.distinct().size == entries.size)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put(
                "entries",
                JSONArray().also { array ->
                    entries.sortedBy { it.operationId }.forEach { array.put(it.toJson()) }
                },
            )
            .toString()
    }

    fun decode(document: String): List<PluginUninstallJournalEntry> {
        require(document.toByteArray(StandardCharsets.UTF_8).size in
            1..PluginUninstallBounds.MAX_DOCUMENT_BYTES)
        val root = JSONObject(document)
        root.requireExact("schema", "version", "entries")
        require(root.strictString("schema") == SCHEMA && root.strictInt("version") == VERSION)
        val values = root.strictArray("entries")
        require(values.length() <= PluginUninstallBounds.MAX_ENTRIES)
        val entries = (0 until values.length()).map { index ->
            (values.get(index) as? JSONObject ?: error("Invalid uninstall journal entry")).toEntry()
        }
        require(entries.map { it.operationId }.distinct().size == entries.size)
        return entries
    }

    private fun PluginUninstallJournalEntry.toJson() = JSONObject()
        .put("operationId", operationId)
        .put("leaseId", leaseId)
        .put("revision", revision)
        .put("ownerProcessEpoch", ownerProcessEpoch)
        .put("phase", phase.name)
        .put("target", target.toJson())
        .put("runtime", runtime.toJson())
        .put("timestamps", timestamps.toJson())

    private fun JSONObject.toEntry(): PluginUninstallJournalEntry {
        requireExact(
            "operationId", "leaseId", "revision", "ownerProcessEpoch", "phase",
            "target", "runtime", "timestamps",
        )
        return PluginUninstallJournalEntry(
            operationId = strictString("operationId"),
            leaseId = strictString("leaseId"),
            revision = strictLong("revision"),
            ownerProcessEpoch = strictString("ownerProcessEpoch"),
            phase = strictEnum("phase", PluginUninstallJournalPhase.entries),
            target = strictObject("target").toTarget(),
            runtime = strictObject("runtime").toRuntime(),
            timestamps = strictObject("timestamps").toTimestamps(),
        )
    }

    private fun PluginUninstallTargetIdentity.toJson() = JSONObject()
        .put("pluginId", pluginId)
        .put("pluginHandleSha256", pluginHandleSha256)
        .put("pluginName", pluginName)
        .put("marketplaceName", marketplaceName)
        .put("marketplacePath", marketplacePath ?: JSONObject.NULL)
        .put("installedVersion", installedVersion ?: JSONObject.NULL)
        .put("canonicalSourceRoot", canonicalSourceRoot ?: JSONObject.NULL)
        .put("sourceSha256", sourceSha256 ?: JSONObject.NULL)

    private fun JSONObject.toTarget(): PluginUninstallTargetIdentity {
        requireExact(
            "pluginId", "pluginHandleSha256", "pluginName", "marketplaceName",
            "marketplacePath", "installedVersion", "canonicalSourceRoot", "sourceSha256",
        )
        return PluginUninstallTargetIdentity(
            pluginId = strictString("pluginId"),
            pluginHandleSha256 = strictString("pluginHandleSha256"),
            pluginName = strictString("pluginName"),
            marketplaceName = strictString("marketplaceName"),
            marketplacePath = nullableString("marketplacePath"),
            installedVersion = nullableString("installedVersion"),
            canonicalSourceRoot = nullableString("canonicalSourceRoot"),
            sourceSha256 = nullableString("sourceSha256"),
        )
    }

    private fun PluginRuntimeUninstallDescriptor.toJson() = JSONObject()
        .put("pluginId", pluginId)
        .put("descriptorSha256", descriptorSha256)
        .put(
            "components",
            JSONArray().also { values -> components.forEach { values.put(it.toJson()) } },
        )

    private fun JSONObject.toRuntime(): PluginRuntimeUninstallDescriptor {
        requireExact("pluginId", "descriptorSha256", "components")
        val values = strictArray("components")
        require(values.length() <= PluginRuntimeUninstallDescriptor.MAX_COMPONENTS)
        val descriptor = PluginRuntimeUninstallDescriptor(
            pluginId = strictString("pluginId"),
            components = (0 until values.length()).map { index ->
                (values.get(index) as? JSONObject ?: error("Invalid uninstall component"))
                    .toComponent()
            },
        )
        require(descriptor.descriptorSha256 == strictString("descriptorSha256")) {
            "Plugin runtime uninstall descriptor digest changed"
        }
        return descriptor
    }

    private fun PluginRuntimeUninstallComponent.toJson() = JSONObject()
        .put("kind", kind.name)
        .put("componentId", componentId)
        .put("identity", identity)
        .put("stateSha256", stateSha256)
        .put("secondaryStateSha256", secondaryStateSha256 ?: JSONObject.NULL)

    private fun JSONObject.toComponent(): PluginRuntimeUninstallComponent {
        requireExact("kind", "componentId", "identity", "stateSha256", "secondaryStateSha256")
        return PluginRuntimeUninstallComponent(
            kind = strictEnum("kind", PluginRuntimeUninstallComponentKind.entries),
            componentId = strictString("componentId"),
            identity = strictString("identity"),
            stateSha256 = strictString("stateSha256"),
            secondaryStateSha256 = nullableString("secondaryStateSha256"),
        )
    }

    private fun PluginUninstallJournalTimestamps.toJson() = JSONObject()
        .put("createdAtWallEpochMillis", createdAtWallEpochMillis)
        .put("updatedAtWallEpochMillis", updatedAtWallEpochMillis)
        .put("createdAtElapsedRealtimeMillis", createdAtElapsedRealtimeMillis)
        .put("updatedAtElapsedRealtimeMillis", updatedAtElapsedRealtimeMillis)

    private fun JSONObject.toTimestamps(): PluginUninstallJournalTimestamps {
        requireExact(
            "createdAtWallEpochMillis", "updatedAtWallEpochMillis",
            "createdAtElapsedRealtimeMillis", "updatedAtElapsedRealtimeMillis",
        )
        return PluginUninstallJournalTimestamps(
            createdAtWallEpochMillis = strictLong("createdAtWallEpochMillis"),
            updatedAtWallEpochMillis = strictLong("updatedAtWallEpochMillis"),
            createdAtElapsedRealtimeMillis = strictLong("createdAtElapsedRealtimeMillis"),
            updatedAtElapsedRealtimeMillis = strictLong("updatedAtElapsedRealtimeMillis"),
        )
    }

    private fun JSONObject.requireExact(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) {
            "Unexpected plugin uninstall journal fields"
        }
    }

    private fun JSONObject.strictString(key: String): String = get(key).also {
        require(it is String) { "Invalid plugin uninstall $key type" }
    } as String

    private fun JSONObject.nullableString(key: String): String? = get(key).let {
        require(it === JSONObject.NULL || it is String) { "Invalid plugin uninstall $key type" }
        it as? String
    }

    private fun JSONObject.strictLong(key: String): Long = get(key).let {
        require(it is Byte || it is Short || it is Int || it is Long)
        (it as Number).toLong()
    }

    private fun JSONObject.strictInt(key: String): Int = strictLong(key).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    }.toInt()

    private fun JSONObject.strictObject(key: String): JSONObject = get(key).also {
        require(it is JSONObject)
    } as JSONObject

    private fun JSONObject.strictArray(key: String): JSONArray = get(key).also {
        require(it is JSONArray)
    } as JSONArray

    private fun <T : Enum<T>> JSONObject.strictEnum(key: String, values: List<T>): T {
        val raw = strictString(key)
        return values.singleOrNull { it.name == raw } ?: error("Invalid plugin uninstall $key")
    }
}
