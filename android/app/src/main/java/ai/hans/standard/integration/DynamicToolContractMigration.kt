package ai.hans.standard.integration

import android.content.Context
import android.content.SharedPreferences
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * thread/resume cannot replace dynamicTools. Descriptions can remain cached in an old thread,
 * but its callable structure must match. V1's opaque full hash proves compatibility only when
 * it exactly matches today's full contract; otherwise preserve a recovery reference before
 * rotating the selected thread. This never deletes Codex conversation, login or memory files.
 */
internal class DynamicToolContractMigrator(
    private val state: DynamicToolContractStateStore,
    private val sessions: CodexSessionStore,
) {
    fun ensureCurrent(specs: List<DynamicToolNamespaceSpec>): DynamicToolMigrationResult =
        ensureCurrent(specs, DynamicToolCompatibility.STRUCTURE)

    /** Revisioned App Server clients never resume a thread under changed dynamic-tool specs. */
    fun ensureExactCurrent(specs: List<DynamicToolNamespaceSpec>): DynamicToolMigrationResult =
        ensureCurrent(specs, DynamicToolCompatibility.EXACT)

    private fun ensureCurrent(
        specs: List<DynamicToolNamespaceSpec>,
        compatibility: DynamicToolCompatibility,
    ): DynamicToolMigrationResult =
        synchronized(PROCESS_MIGRATION_LOCK) {
            val full = DynamicToolContractFingerprint.compute(specs)
            val structure = DynamicToolContractFingerprint.computeStructure(specs)
            var previous = state.read()
            val recoveredRotation = previous.pendingRotation != null
            if (recoveredRotation) previous = finishRotation(previous)

            val compatible = when (compatibility) {
                DynamicToolCompatibility.EXACT -> previous.fullFingerprint == full
                DynamicToolCompatibility.STRUCTURE -> if (previous.structureFingerprint != null) {
                    previous.structureFingerprint == structure
                } else {
                    previous.fullFingerprint == full
                }
            }
            if (compatible) {
                val upgraded = previous.copy(fullFingerprint = full, structureFingerprint = structure)
                if (upgraded != previous) state.write(upgraded)
                return@synchronized if (recoveredRotation) {
                    DynamicToolMigrationResult.THREAD_ROTATED
                } else {
                    DynamicToolMigrationResult.CURRENT
                }
            }

            val staleThread = sessions.readThreadId()
            if (staleThread == null) {
                state.write(previous.copy(fullFingerprint = full, structureFingerprint = structure))
                return@synchronized if (recoveredRotation) {
                    DynamicToolMigrationResult.THREAD_ROTATED
                } else {
                    DynamicToolMigrationResult.INITIALIZED
                }
            }

            // Never evict the only remaining local reference to an earlier conversation.
            check(previous.preservedThreads.size < MAX_PRESERVED_DYNAMIC_TOOL_THREADS) {
                "Dynamic-tool recovery reference limit reached; selected thread was not changed"
            }
            val pending = PreservedDynamicToolThread(
                threadId = staleThread,
                reason = if (previous.structureFingerprint == null) {
                    DynamicToolRotationReason.LEGACY_CONTRACT_UNVERIFIED
                } else if (previous.structureFingerprint == structure) {
                    DynamicToolRotationReason.FULL_CONTRACT_CHANGED
                } else {
                    DynamicToolRotationReason.STRUCTURE_CHANGED
                },
                previousFullFingerprint = previous.fullFingerprint,
                previousStructureFingerprint = previous.structureFingerprint,
                targetFullFingerprint = full,
                targetStructureFingerprint = structure,
            )
            val staged = previous.copy(pendingRotation = pending)
            state.write(staged) // Durable reference MUST precede removal of the selected pointer.
            finishRotation(staged)
            DynamicToolMigrationResult.THREAD_ROTATED
        }

    private fun finishRotation(previous: DynamicToolContractState): DynamicToolContractState {
        val pending = checkNotNull(previous.pendingRotation)
        val selected = sessions.readThreadId()
        check(selected == null || selected == pending.threadId) {
            "Selected thread changed during dynamic-tool migration; recovery reference retained"
        }
        if (selected != null) sessions.clearThreadId(pending.threadId)
        check(sessions.readThreadId() == null) {
            "Selected thread changed during dynamic-tool migration; recovery reference retained"
        }
        val completed = DynamicToolContractState(
            fullFingerprint = pending.targetFullFingerprint,
            structureFingerprint = pending.targetStructureFingerprint,
            preservedThreads = previous.preservedThreads + pending,
        )
        state.write(completed)
        return completed
    }

    private companion object {
        // Multiple host objects must not interleave the two durable commits and pointer CAS.
        val PROCESS_MIGRATION_LOCK = Any()
    }
}

internal enum class DynamicToolMigrationResult {
    CURRENT,
    INITIALIZED,
    THREAD_ROTATED,
}

private enum class DynamicToolCompatibility {
    STRUCTURE,
    EXACT,
}

internal interface DynamicToolContractStateStore {
    fun read(): DynamicToolContractState
    fun write(value: DynamicToolContractState)
}

internal enum class DynamicToolRotationReason {
    LEGACY_CONTRACT_UNVERIFIED,
    FULL_CONTRACT_CHANGED,
    STRUCTURE_CHANGED,
}

/** Recovery metadata only: a reference is not an imported or currently displayed conversation. */
internal data class PreservedDynamicToolThread(
    val threadId: String,
    val reason: DynamicToolRotationReason,
    val previousFullFingerprint: String?,
    val previousStructureFingerprint: String?,
    val targetFullFingerprint: String,
    val targetStructureFingerprint: String,
) {
    init {
        require(threadId.isNotBlank() && threadId.length <= 256 && threadId.none(Char::isISOControl))
        require(previousFullFingerprint == null || previousFullFingerprint.isSha256())
        require(previousStructureFingerprint == null || previousStructureFingerprint.isSha256())
        require(targetFullFingerprint.isSha256() && targetStructureFingerprint.isSha256())
        require(previousStructureFingerprint == null || previousFullFingerprint != null)
        when (reason) {
            DynamicToolRotationReason.LEGACY_CONTRACT_UNVERIFIED ->
                require(previousStructureFingerprint == null && previousFullFingerprint != targetFullFingerprint)
            DynamicToolRotationReason.FULL_CONTRACT_CHANGED ->
                require(
                    previousStructureFingerprint != null &&
                        previousStructureFingerprint == targetStructureFingerprint &&
                        previousFullFingerprint != targetFullFingerprint
                )
            DynamicToolRotationReason.STRUCTURE_CHANGED ->
                require(previousStructureFingerprint != null && previousStructureFingerprint != targetStructureFingerprint)
        }
    }
}

internal data class DynamicToolContractState(
    val fullFingerprint: String? = null,
    val structureFingerprint: String? = null,
    val pendingRotation: PreservedDynamicToolThread? = null,
    val preservedThreads: List<PreservedDynamicToolThread> = emptyList(),
) {
    init {
        require(fullFingerprint == null || fullFingerprint.isSha256())
        require(structureFingerprint == null || structureFingerprint.isSha256())
        require(structureFingerprint == null || fullFingerprint != null)
        require(preservedThreads.size <= MAX_PRESERVED_DYNAMIC_TOOL_THREADS)
        require(preservedThreads.distinct().size == preservedThreads.size)
        pendingRotation?.let {
            require(preservedThreads.size < MAX_PRESERVED_DYNAMIC_TOOL_THREADS)
            require(it !in preservedThreads)
            require(it.previousFullFingerprint == fullFingerprint)
            require(it.previousStructureFingerprint == structureFingerprint)
        }
    }
}

internal class AppPrivateDynamicToolContractStateStore(
    private val preferences: SharedPreferences,
) : DynamicToolContractStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun read(): DynamicToolContractState = synchronized(failedPreferences) {
        check(preferences !in failedPreferences) { "Dynamic-tool persistence failed; restart required" }
        val full = preferences.getString(KEY_FINGERPRINT, null)?.takeIf(String::isSha256)
        if (!preferences.contains(KEY_STATE_V2)) {
            DynamicToolContractState(fullFingerprint = full)
        } else {
            // A malformed/unknown V2 is never downgraded to an apparently fresh V1 state.
            val raw = checkNotNull(preferences.getString(KEY_STATE_V2, null))
            DynamicToolContractStateCodec.decode(raw).also {
                check(it.fullFingerprint == full) { "Dynamic-tool contract versions disagree" }
            }
        }
    }

    override fun write(value: DynamicToolContractState) {
        val encoded = DynamicToolContractStateCodec.encode(value)
        synchronized(failedPreferences) {
            check(preferences !in failedPreferences) { "Dynamic-tool persistence failed; restart required" }
            try {
                check(
                    preferences.edit()
                        .putString(KEY_FINGERPRINT, value.fullFingerprint)
                        .putString(KEY_STATE_V2, encoded)
                        .commit(),
                ) { "Could not persist dynamic-tool contract" }
            } catch (error: Exception) {
                // SharedPreferences can update its in-memory map even when commit() returns false.
                // No new host in this process may mistake that map for a durable recovery journal.
                failedPreferences.add(preferences)
                throw error
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "hans_dynamic_tool_contract_v1"
        const val KEY_FINGERPRINT = "sha256"
        const val KEY_STATE_V2 = "state_v2"
        val failedPreferences: MutableSet<SharedPreferences> =
            Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())
    }
}

internal object DynamicToolContractStateCodec {
    fun encode(state: DynamicToolContractState): String = JsonContract.encodeBounded(
        JSONObject()
            .put("version", 2)
            .put("full", state.fullFingerprint ?: JSONObject.NULL)
            .put("structure", state.structureFingerprint ?: JSONObject.NULL)
            .put("pending", state.pendingRotation?.json() ?: JSONObject.NULL)
            .put("preserved", JSONArray().apply { state.preservedThreads.forEach { put(it.json()) } }),
        MAX_STATE_BYTES,
    )

    fun decode(raw: String): DynamicToolContractState {
        val json = JsonContract.parseObject(raw, MAX_STATE_BYTES)
        json.requireKeys(setOf("version", "full", "structure", "pending", "preserved"))
        require(JsonContract.requiredLong(json, "version") == 2L) { "Unknown dynamic-tool state version" }
        val preserved = JsonContract.requiredArray(json, "preserved")
        require(preserved.length() <= MAX_PRESERVED_DYNAMIC_TOOL_THREADS)
        return DynamicToolContractState(
            fullFingerprint = json.nullableString("full"),
            structureFingerprint = json.nullableString("structure"),
            pendingRotation = if (json.isNull("pending")) null else json.getJSONObject("pending").reference(),
            preservedThreads = (0 until preserved.length()).map { preserved.getJSONObject(it).reference() },
        )
    }

    private fun PreservedDynamicToolThread.json(): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("reason", reason.name)
        .put("previousFull", previousFullFingerprint ?: JSONObject.NULL)
        .put("previousStructure", previousStructureFingerprint ?: JSONObject.NULL)
        .put("targetFull", targetFullFingerprint)
        .put("targetStructure", targetStructureFingerprint)

    private fun JSONObject.reference(): PreservedDynamicToolThread {
        requireKeys(setOf("threadId", "reason", "previousFull", "previousStructure", "targetFull", "targetStructure"))
        return PreservedDynamicToolThread(
            threadId = JsonContract.requiredString(this, "threadId"),
            reason = DynamicToolRotationReason.valueOf(JsonContract.requiredString(this, "reason")),
            previousFullFingerprint = nullableString("previousFull"),
            previousStructureFingerprint = nullableString("previousStructure"),
            targetFullFingerprint = JsonContract.requiredString(this, "targetFull"),
            targetStructureFingerprint = JsonContract.requiredString(this, "targetStructure"),
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else JsonContract.requiredString(this, key)

    private fun JSONObject.requireKeys(expected: Set<String>) =
        require(keys().asSequence().toSet() == expected) { "Invalid dynamic-tool state fields" }

    private const val MAX_STATE_BYTES = 256 * 1024
}

internal object DynamicToolContractFingerprint {
    /** Frozen V1 algorithm. Do not canonicalize it: it is the only available legacy evidence. */
    fun compute(specs: List<DynamicToolNamespaceSpec>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.addField("hans-dynamic-tools-v1")
        specs.sortedBy { it.name }.forEach { namespace ->
            digest.addField(namespace.name)
            digest.addField(namespace.description)
            namespace.tools.sortedBy { it.name }.forEach { tool ->
                digest.addField(tool.name)
                digest.addField(tool.description)
                digest.addField(tool.inputSchemaJson)
                digest.addField(if (tool.deferLoading) "1" else "0")
            }
        }
        return digest.digest().toLowerHex()
    }

    fun computeStructure(specs: List<DynamicToolNamespaceSpec>): String {
        require(specs.map { it.name }.distinct().size == specs.size) { "Duplicate tool namespace" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.addField("hans-dynamic-tools-structure-v2")
        digest.addField(specs.size.toString())
        specs.sortedBy { it.name }.forEach { namespace ->
            digest.addField(namespace.name)
            digest.addField(namespace.tools.size.toString())
            namespace.tools.sortedBy { it.name }.forEach { tool ->
                digest.addField(tool.name)
                digest.addField(canonicalJson(JSONObject(tool.inputSchemaJson)))
                digest.addField(if (tool.deferLoading) "1" else "0")
            }
        }
        return digest.digest().toLowerHex()
    }

    // Object key order is immaterial; array order and every schema value remain conservative.
    // In particular, do not strip a schema property merely because it is named "description".
    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ",",
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ",",
        ) { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Invalid tool schema value")
    }

    private fun MessageDigest.addField(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            ),
        )
        update(bytes)
    }

    private fun ByteArray.toLowerHex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            this@toLowerHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}

internal const val MAX_PRESERVED_DYNAMIC_TOOL_THREADS = 64
private fun String.isSha256(): Boolean = length == 64 && all(Char::isLowerHexDigit)
private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
