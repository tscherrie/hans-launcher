package ai.hans.standard.plugins.install

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

internal class PluginInstallJournalCorruptException(
    cause: Throwable,
) : IllegalStateException("Plugin install journal is corrupt", cause)

/**
 * Atomic, compare-and-set recovery journal stored under Android's no-backup directory.
 *
 * The complete bounded document is replaced in one same-directory atomic move. A malformed,
 * oversized, non-regular or symlinked journal is never treated as an empty journal.
 */
internal class PluginInstallJournal private constructor(
    private val directory: File,
    private val fileName: String,
) {
    constructor(
        context: Context,
        fileName: String = FILE_NAME,
    ) : this(context.applicationContext.noBackupFilesDir, fileName)

    /** Test/recovery constructor. Production callers use the Context constructor above. */
    internal constructor(directory: File) : this(directory, FILE_NAME)

    private val target = File(directory, fileName)
    private val staging = File(directory, "$fileName.tmp")

    init {
        require(fileName == File(fileName).name && FILE_NAME_PATTERN.matches(fileName)) {
            "Invalid plugin install journal file name"
        }
    }

    fun readAll(): List<PluginInstallJournalEntry> = synchronized(PROCESS_LOCK) {
        readLocked()
    }

    fun read(operationId: String): PluginInstallJournalEntry? {
        PluginInstallJournalBounds.requireOpaqueId(operationId, "operation")
        return synchronized(PROCESS_LOCK) {
            readLocked().singleOrNull { it.operationId == operationId }
        }
    }

    fun create(entry: PluginInstallJournalEntry): PluginInstallJournalMutationResult =
        synchronized(PROCESS_LOCK) {
            require(entry.revision == 1L) { "A new plugin install journal entry starts at revision 1" }
            val current = readLocked()
            if (current.any { it.operationId == entry.operationId }) {
                return@synchronized PluginInstallJournalMutationResult.OPERATION_ALREADY_EXISTS
            }
            if (current.size >= PluginInstallJournalBounds.MAX_ENTRIES) {
                return@synchronized PluginInstallJournalMutationResult.JOURNAL_FULL
            }
            writeLocked(current + entry)
            PluginInstallJournalMutationResult.APPLIED
        }

    /**
     * Replaces only the entry named by all three cursor components. The lease may rotate in the
     * replacement, but the new revision must be exactly the old revision plus one.
     */
    fun compareAndSet(
        expected: PluginInstallJournalCursor,
        replacement: PluginInstallJournalEntry,
    ): PluginInstallJournalMutationResult = synchronized(PROCESS_LOCK) {
        val current = readLocked()
        val index = current.indexOfFirst { it.operationId == expected.operationId }
        if (index < 0 || current[index].cursor != expected) {
            return@synchronized PluginInstallJournalMutationResult.CURSOR_MISMATCH
        }
        val previous = current[index]
        requireStableReplacement(previous, replacement)
        val updated = current.toMutableList().also { it[index] = replacement }
        writeLocked(updated)
        PluginInstallJournalMutationResult.APPLIED
    }

    fun remove(expected: PluginInstallJournalCursor): PluginInstallJournalMutationResult =
        synchronized(PROCESS_LOCK) {
            val current = readLocked()
            val index = current.indexOfFirst { it.operationId == expected.operationId }
            if (index < 0 || current[index].cursor != expected) {
                return@synchronized PluginInstallJournalMutationResult.CURSOR_MISMATCH
            }
            writeLocked(current.filterIndexed { candidateIndex, _ -> candidateIndex != index })
            PluginInstallJournalMutationResult.APPLIED
        }

    private fun readLocked(): List<PluginInstallJournalEntry> {
        requireStorageDirectory()
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        requirePrivateRegularFile(target, "journal")
        val size = Files.size(path)
        if (size !in 1..PluginInstallJournalBounds.MAX_DOCUMENT_BYTES.toLong()) {
            throw PluginInstallJournalCorruptException(
                IllegalArgumentException("Invalid plugin install journal size"),
            )
        }
        val bytes = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                require(!buffer.hasRemaining() && channel.read(ByteBuffer.allocate(1)) < 0) {
                    "Plugin install journal changed while reading"
                }
                buffer.array()
            }
        } catch (failure: PluginInstallJournalCorruptException) {
            throw failure
        } catch (failure: Throwable) {
            throw PluginInstallJournalCorruptException(failure)
        }
        return try {
            PluginInstallJournalCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } catch (failure: Throwable) {
            throw PluginInstallJournalCorruptException(failure)
        }
    }

    private fun writeLocked(entries: List<PluginInstallJournalEntry>) {
        requireStorageDirectory()
        val bytes = PluginInstallJournalCodec.encode(entries).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..PluginInstallJournalBounds.MAX_DOCUMENT_BYTES) {
            "Plugin install journal document is too large"
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateRegularFile(target, "journal")
        }
        prepareStagingPath()
        try {
            Files.createFile(staging.toPath())
            requirePrivateRegularFile(staging, "journal staging file")
            FileChannel.open(
                staging.toPath(),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            require(staging.length() == bytes.size.toLong()) {
                "Plugin install journal staging write was incomplete"
            }
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            requirePrivateRegularFile(target, "journal")
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
        requirePrivateRegularFile(staging, "journal staging file")
        Files.delete(path)
    }

    private fun requireStorageDirectory() {
        val path = directory.toPath()
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin install journal directory is absent"
        }
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin install journal directory is not a private regular directory"
        }
        require(target.parentFile == directory && staging.parentFile == directory) {
            "Plugin install journal escaped its storage directory"
        }
    }

    private fun requirePrivateRegularFile(file: File, label: String) {
        val path = file.toPath()
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin install $label is not a regular file"
        }
    }

    private fun requireStableReplacement(
        previous: PluginInstallJournalEntry,
        replacement: PluginInstallJournalEntry,
    ) {
        require(replacement.operationId == previous.operationId) {
            "Plugin install operation identity changed"
        }
        require(replacement.revision == previous.revision + 1L) {
            "Plugin install journal revision must increment exactly once"
        }
        require(replacement.identity == previous.identity) { "Plugin install identity changed" }
        require(replacement.installAttempt == previous.installAttempt) {
            "Plugin install attempt changed"
        }
        require(replacement.timestamps.createdAtWallEpochMillis ==
            previous.timestamps.createdAtWallEpochMillis &&
            replacement.timestamps.createdAtElapsedRealtimeMillis ==
            previous.timestamps.createdAtElapsedRealtimeMillis) {
            "Plugin install creation timestamps changed"
        }
        require(replacement.ownerProcessEpoch != previous.ownerProcessEpoch ||
            replacement.timestamps.updatedAtElapsedRealtimeMillis >=
            previous.timestamps.updatedAtElapsedRealtimeMillis) {
            "Plugin install update timestamp moved backwards within one process epoch"
        }
        require(previous.dependencyRecovery == null ||
            replacement.dependencyRecovery == previous.dependencyRecovery) {
            "Plugin dependency recovery identity changed"
        }
    }

    private companion object {
        const val FILE_NAME = "plugin-install-journal-v1.json"
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val PROCESS_LOCK = Any()
    }
}

/** Strict schema-v1 codec. Unknown, missing or mistyped fields fail closed. */
internal object PluginInstallJournalCodec {
    private const val SCHEMA = "hans.plugin-install-journal"
    private const val VERSION = 1

    fun encode(entries: List<PluginInstallJournalEntry>): String {
        require(entries.size <= PluginInstallJournalBounds.MAX_ENTRIES) {
            "Too many plugin install journal entries"
        }
        require(entries.map { it.operationId }.distinct().size == entries.size) {
            "Duplicate plugin install operation"
        }
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

    fun decode(document: String): List<PluginInstallJournalEntry> {
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..PluginInstallJournalBounds.MAX_DOCUMENT_BYTES) {
            "Invalid plugin install journal document size"
        }
        val root = JSONObject(document)
        root.requireExactKeys(setOf("schema", "version", "entries"), "journal")
        require(root.requireString("schema") == SCHEMA) { "Invalid plugin install journal schema" }
        require(root.requireInt("version") == VERSION) { "Invalid plugin install journal version" }
        val values = root.requireArray("entries")
        require(values.length() <= PluginInstallJournalBounds.MAX_ENTRIES) {
            "Too many plugin install journal entries"
        }
        val entries = ArrayList<PluginInstallJournalEntry>(values.length())
        repeat(values.length()) { index ->
            val raw = values.get(index)
            require(raw is JSONObject) { "Invalid plugin install journal entry type" }
            entries += raw.toJournalEntry()
        }
        require(entries.map { it.operationId }.distinct().size == entries.size) {
            "Duplicate plugin install operation"
        }
        return entries
    }

    private fun PluginInstallJournalEntry.toJson(): JSONObject = JSONObject()
        .put("operationId", operationId)
        .put("leaseId", leaseId)
        .put("revision", revision)
        .put("ownerProcessEpoch", ownerProcessEpoch)
        .put("phase", phase.name)
        .put("identity", identity.toJson())
        .put("installAttempt", installAttempt.toJson())
        .put("dependencyRecovery", dependencyRecovery?.toJson() ?: JSONObject.NULL)
        .put("timestamps", timestamps.toJson())

    private fun JSONObject.toJournalEntry(): PluginInstallJournalEntry {
        requireExactKeys(
            setOf(
                "operationId",
                "leaseId",
                "revision",
                "ownerProcessEpoch",
                "phase",
                "identity",
                "installAttempt",
                "dependencyRecovery",
                "timestamps",
            ),
            "entry",
        )
        val dependency = get("dependencyRecovery")
        require(dependency === JSONObject.NULL || dependency is JSONObject) {
            "Invalid plugin dependency recovery descriptor type"
        }
        return PluginInstallJournalEntry(
            operationId = requireString("operationId"),
            leaseId = requireString("leaseId"),
            revision = requireLong("revision"),
            ownerProcessEpoch = requireString("ownerProcessEpoch"),
            phase = requireEnum("phase", PluginInstallJournalPhase.entries),
            identity = requireObject("identity").toIdentity(),
            installAttempt = requireObject("installAttempt").toInstallAttempt(),
            dependencyRecovery = (dependency as? JSONObject)?.toDependencyRecovery(),
            timestamps = requireObject("timestamps").toTimestamps(),
        )
    }

    private fun PluginInstallIdentity.toJson(): JSONObject = JSONObject()
        .put("pluginId", pluginId)
        .put("pluginHandleSha256", pluginHandleSha256)
        .put("pluginName", pluginName)
        .put("marketplaceName", marketplaceName)
        .put("marketplacePath", marketplacePath ?: JSONObject.NULL)
        .put("expectedInstalledVersion", expectedInstalledVersion)
        .put("canonicalSourceRoot", canonicalSourceRoot)
        .put("sourceSha256", sourceSha256)

    private fun JSONObject.toIdentity(): PluginInstallIdentity {
        requireExactKeys(
            setOf(
                "pluginId",
                "pluginHandleSha256",
                "pluginName",
                "marketplaceName",
                "marketplacePath",
                "expectedInstalledVersion",
                "canonicalSourceRoot",
                "sourceSha256",
            ),
            "identity",
        )
        return PluginInstallIdentity(
            pluginId = requireString("pluginId"),
            pluginHandleSha256 = requireString("pluginHandleSha256"),
            pluginName = requireString("pluginName"),
            marketplaceName = requireString("marketplaceName"),
            marketplacePath = requireNullableString("marketplacePath"),
            expectedInstalledVersion = requireString("expectedInstalledVersion"),
            canonicalSourceRoot = requireString("canonicalSourceRoot"),
            sourceSha256 = requireString("sourceSha256"),
        )
    }

    private fun PluginInstallAttempt.toJson(): JSONObject = JSONObject()
        .put("installAttemptId", installAttemptId)
        .put("attemptOrdinal", attemptOrdinal)

    private fun JSONObject.toInstallAttempt(): PluginInstallAttempt {
        requireExactKeys(setOf("installAttemptId", "attemptOrdinal"), "install attempt")
        return PluginInstallAttempt(
            installAttemptId = requireString("installAttemptId"),
            attemptOrdinal = requireInt("attemptOrdinal"),
        )
    }

    private fun PluginDependencyRecoveryDescriptor.toJson(): JSONObject = when (kind) {
        PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1 -> JSONObject()
            .put("kind", kind.name)
            .put("environmentTransactionId", environmentTransactionId)
            .put("environmentDigest", environmentDigest)
            .put("entrypointTransactionId", entrypointTransactionId ?: JSONObject.NULL)
            .put("entrypointMetadataDigest", entrypointMetadataDigest ?: JSONObject.NULL)
        PluginDependencyRecoveryKind.COMPOSITE_V1 -> JSONObject()
            .put("kind", kind.name)
            .put(
                "components",
                JSONArray().also { values ->
                    components.forEach { component -> values.put(component.toJson()) }
                },
            )
    }

    private fun JSONObject.toDependencyRecovery(): PluginDependencyRecoveryDescriptor {
        val kind = requireEnum("kind", PluginDependencyRecoveryKind.entries)
        return when (kind) {
            PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1 -> {
                // This is the original schema. Keeping its exact shape allows journals written by
                // pre-composite Hans builds to recover without migration or lossy rewriting.
                requireExactKeys(
                    setOf(
                        "kind",
                        "environmentTransactionId",
                        "environmentDigest",
                        "entrypointTransactionId",
                        "entrypointMetadataDigest",
                    ),
                    "dependency recovery descriptor",
                )
                PluginDependencyRecoveryDescriptor(
                    kind = kind,
                    environmentTransactionId = requireString("environmentTransactionId"),
                    environmentDigest = requireString("environmentDigest"),
                    entrypointTransactionId = requireNullableString("entrypointTransactionId"),
                    entrypointMetadataDigest = requireNullableString("entrypointMetadataDigest"),
                )
            }
            PluginDependencyRecoveryKind.COMPOSITE_V1 -> {
                requireExactKeys(
                    setOf("kind", "components"),
                    "dependency recovery descriptor",
                )
                val values = requireArray("components")
                require(values.length() in 1..PluginInstallJournalBounds.MAX_DEPENDENCY_COMPONENTS) {
                    "Invalid composite dependency component count"
                }
                val components = (0 until values.length()).map { index ->
                    val raw = values.get(index)
                    require(raw is JSONObject) { "Invalid dependency component type" }
                    raw.toDependencyRecoveryComponent()
                }
                PluginDependencyRecoveryDescriptor(
                    kind = kind,
                    components = components,
                )
            }
        }
    }

    private fun PluginDependencyRecoveryComponent.toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("componentId", componentId)
        .put("transactionId", transactionId)
        .put("stateDigest", stateDigest)
        .put("secondaryTransactionId", secondaryTransactionId ?: JSONObject.NULL)
        .put("secondaryStateDigest", secondaryStateDigest ?: JSONObject.NULL)

    private fun JSONObject.toDependencyRecoveryComponent(): PluginDependencyRecoveryComponent {
        requireExactKeys(
            setOf(
                "kind",
                "componentId",
                "transactionId",
                "stateDigest",
                "secondaryTransactionId",
                "secondaryStateDigest",
            ),
            "dependency recovery component",
        )
        return PluginDependencyRecoveryComponent(
            kind = requireEnum("kind", PluginDependencyRecoveryComponentKind.entries),
            componentId = requireString("componentId"),
            transactionId = requireString("transactionId"),
            stateDigest = requireString("stateDigest"),
            secondaryTransactionId = requireNullableString("secondaryTransactionId"),
            secondaryStateDigest = requireNullableString("secondaryStateDigest"),
        )
    }

    private fun PluginInstallJournalTimestamps.toJson(): JSONObject = JSONObject()
        .put("createdAtWallEpochMillis", createdAtWallEpochMillis)
        .put("updatedAtWallEpochMillis", updatedAtWallEpochMillis)
        .put("createdAtElapsedRealtimeMillis", createdAtElapsedRealtimeMillis)
        .put("updatedAtElapsedRealtimeMillis", updatedAtElapsedRealtimeMillis)

    private fun JSONObject.toTimestamps(): PluginInstallJournalTimestamps {
        requireExactKeys(
            setOf(
                "createdAtWallEpochMillis",
                "updatedAtWallEpochMillis",
                "createdAtElapsedRealtimeMillis",
                "updatedAtElapsedRealtimeMillis",
            ),
            "timestamps",
        )
        return PluginInstallJournalTimestamps(
            createdAtWallEpochMillis = requireLong("createdAtWallEpochMillis"),
            updatedAtWallEpochMillis = requireLong("updatedAtWallEpochMillis"),
            createdAtElapsedRealtimeMillis = requireLong("createdAtElapsedRealtimeMillis"),
            updatedAtElapsedRealtimeMillis = requireLong("updatedAtElapsedRealtimeMillis"),
        )
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>, label: String) {
        require(keys().asSequence().toSet() == expected) { "Unexpected plugin install $label fields" }
    }

    private fun JSONObject.requireString(key: String): String {
        val value = get(key)
        require(value is String) { "Invalid plugin install $key type" }
        return value
    }

    private fun JSONObject.requireNullableString(key: String): String? {
        val value = get(key)
        require(value === JSONObject.NULL || value is String) { "Invalid plugin install $key type" }
        return value as? String
    }

    private fun JSONObject.requireLong(key: String): Long {
        val value = get(key)
        require(value is Byte || value is Short || value is Int || value is Long) {
            "Invalid plugin install $key type"
        }
        return (value as Number).toLong()
    }

    private fun JSONObject.requireInt(key: String): Int {
        val value = requireLong(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid plugin install $key range" }
        return value.toInt()
    }

    private fun JSONObject.requireObject(key: String): JSONObject {
        val value = get(key)
        require(value is JSONObject) { "Invalid plugin install $key type" }
        return value
    }

    private fun JSONObject.requireArray(key: String): JSONArray {
        val value = get(key)
        require(value is JSONArray) { "Invalid plugin install $key type" }
        return value
    }

    private fun <T : Enum<T>> JSONObject.requireEnum(key: String, values: List<T>): T {
        val raw = requireString(key)
        return values.singleOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Invalid plugin install $key")
    }
}
