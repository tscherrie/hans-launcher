package ai.hans.standard.mcp

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** A verifier compiled into and signed with Hans. Persisted/plugin input can reference only its id. */
internal class RemoteMcpSignedVerifierDefinition internal constructor(
    val id: String,
    val verifier: RemoteMcpPostconditionVerifier,
) {
    init {
        require(id.matches(SAFE_VERIFIER_ID)) { "Invalid Remote MCP verifier id" }
    }

    override fun toString(): String = "RemoteMcpSignedVerifierDefinition(id=$id)"
}

/** Immutable registry whose entries can only be supplied by signed in-app code. */
internal class RemoteMcpSignedVerifierRegistry private constructor(
    definitions: List<RemoteMcpSignedVerifierDefinition>,
) {
    private val verifiers = definitions.associate { it.id to it.verifier }

    init {
        require(definitions.size <= MAX_SIGNED_VERIFIERS)
        require(verifiers.size == definitions.size) { "Duplicate Remote MCP verifier id" }
    }

    fun resolve(id: String): RemoteMcpPostconditionVerifier? = verifiers[id]

    override fun toString(): String = "RemoteMcpSignedVerifierRegistry(count=${verifiers.size})"

    companion object {
        val EMPTY = RemoteMcpSignedVerifierRegistry(emptyList())

        /** This is intentionally a code-only factory; no JSON/plugin/user input factory exists. */
        fun fromSignedInAppCode(
            definitions: List<RemoteMcpSignedVerifierDefinition>,
        ): RemoteMcpSignedVerifierRegistry = RemoteMcpSignedVerifierRegistry(definitions.toList())
    }
}

/** Durable approval bound to one exact activation and one exact discovered tool definition. */
internal data class RemoteMcpToolPolicy(
    val activationIdentity: RemoteMcpActivationIdentity,
    val toolName: String,
    val toolMetadataDigest: String,
    val effect: RemoteMcpToolEffect,
    val verifierId: String?,
    val generation: Long,
) {
    init {
        require(toolName.matches(SAFE_POLICY_TOOL))
        require(toolMetadataDigest.matches(SHA_256))
        require(generation > 0)
        when (effect) {
            RemoteMcpToolEffect.READ_ONLY -> require(verifierId == null) {
                "Read-only MCP policy must not name a verifier"
            }
            RemoteMcpToolEffect.MUTATING -> require(
                verifierId != null && verifierId.matches(SAFE_VERIFIER_ID),
            ) { "Mutating MCP policy requires a signed verifier id" }
        }
    }

    override fun toString(): String =
        "RemoteMcpToolPolicy(effect=$effect, generation=$generation)"

    companion object {
        fun approved(
            activationIdentity: RemoteMcpActivationIdentity,
            tool: RemoteMcpTool,
            effect: RemoteMcpToolEffect,
            verifierId: String? = null,
            generation: Long = 1,
        ): RemoteMcpToolPolicy = RemoteMcpToolPolicy(
            activationIdentity = activationIdentity,
            toolName = tool.name,
            toolMetadataDigest = remoteMcpToolMetadataDigest(tool),
            effect = effect,
            verifierId = verifierId,
            generation = generation,
        )
    }
}

internal data class RemoteMcpToolPolicyStoreSnapshot(
    val available: Boolean,
    val revision: Long,
    val policies: List<RemoteMcpToolPolicy>,
) {
    init {
        require(revision >= 0)
        require(available || (revision == 0L && policies.isEmpty()))
        require(policies.size <= MAX_TOOL_POLICIES)
    }

    override fun toString(): String =
        "RemoteMcpToolPolicyStoreSnapshot(available=$available, revision=$revision, " +
            "policyCount=${policies.size})"
}

/**
 * Crash-safe private approval store. Every write is a whole-document CAS with an atomic
 * same-directory replace. Corrupt, stale, linked or unknown-verifier state is never exposed.
 */
internal class RemoteMcpToolPolicyStore internal constructor(
    directory: File,
    private val signedVerifiers: RemoteMcpSignedVerifierRegistry,
) {
    constructor(
        context: Context,
        signedVerifiers: RemoteMcpSignedVerifierRegistry,
    ) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        signedVerifiers,
    )

    private val stateFile = RemoteMcpPrivateStateFile(directory, FILE_NAME, MAX_DOCUMENT_BYTES)
    private val lock = Any()
    @Volatile
    private var unavailable = false

    init {
        synchronized(RemoteMcpPrivateStateLock.process) {
            runCatching(::readState).onFailure { unavailable = true }
        }
    }

    fun snapshot(): RemoteMcpToolPolicyStoreSnapshot = synchronized(lock) {
        if (unavailable) return@synchronized UNAVAILABLE_SNAPSHOT
        synchronized(RemoteMcpPrivateStateLock.process) {
            try {
                readState().snapshot
            } catch (_: Throwable) {
                unavailable = true
                UNAVAILABLE_SNAPSHOT
            }
        }
    }

    /** Replaces the exact policy set if and only if [expectedRevision] is still current. */
    fun compareAndSet(
        expectedRevision: Long,
        approvals: List<RemoteMcpToolPolicyApproval>,
    ): RemoteMcpToolPolicyStoreSnapshot = synchronized(lock) {
        require(expectedRevision >= 0)
        require(approvals.size <= MAX_TOOL_POLICIES)
        ensureAvailable()
        synchronized(RemoteMcpPrivateStateLock.process) {
            val current = readStateOrFail()
            if (current.revision != expectedRevision) {
                throw RemoteMcpFailure("mcp_tool_policy_revision_stale")
            }
            val nextRevision = Math.addExact(current.revision, 1L)
            val policies = approvals.map { approval ->
                approval.toPolicy(nextRevision, signedVerifiers)
            }
            val replacement = RemoteMcpToolPolicyState(nextRevision, policies)
            persistOrFail(replacement)
            replacement.snapshot
        }
    }

    /**
     * Replaces approvals for one exact activation while preserving every unrelated activation.
     *
     * An empty [approvals] list is an exact revocation. The activation identity is supplied
     * separately so a caller cannot accidentally revoke the whole store by omitting rows. Every
     * replacement policy receives the new whole-document generation; preserved policies retain
     * the generation at which they were approved.
     */
    fun compareAndSetForActivation(
        expectedRevision: Long,
        identity: RemoteMcpActivationIdentity,
        approvals: List<RemoteMcpToolPolicyApproval>,
    ): RemoteMcpToolPolicyStoreSnapshot = synchronized(lock) {
        require(expectedRevision >= 0)
        require(approvals.size <= MAX_TOOL_POLICIES)
        require(approvals.all { it.activationIdentity == identity }) {
            "Remote MCP approval activation identity changed"
        }
        ensureAvailable()
        synchronized(RemoteMcpPrivateStateLock.process) {
            val current = readStateOrFail()
            if (current.revision != expectedRevision) {
                throw RemoteMcpFailure("mcp_tool_policy_revision_stale")
            }
            val nextRevision = Math.addExact(current.revision, 1L)
            val preserved = current.policies.filterNot { it.activationIdentity == identity }
            val replacementPolicies = approvals.map { approval ->
                approval.toPolicy(nextRevision, signedVerifiers)
            }
            val replacement = RemoteMcpToolPolicyState(
                revision = nextRevision,
                policies = preserved + replacementPolicies,
            )
            persistOrFail(replacement)
            replacement.snapshot
        }
    }

    private fun readStateOrFail(): RemoteMcpToolPolicyState = try {
        readState()
    } catch (_: Throwable) {
        unavailable = true
        throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
    }

    private fun persistOrFail(replacement: RemoteMcpToolPolicyState) {
        val encoded = RemoteMcpToolPolicyStoreCodec.encode(replacement)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            stateFile.write(encoded)
        } catch (_: Throwable) {
            unavailable = true
            throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
        } finally {
            encoded.fill(0)
        }
    }

    private fun readState(): RemoteMcpToolPolicyState {
        val bytes = stateFile.readOrNull() ?: return RemoteMcpToolPolicyState.EMPTY
        return try {
            RemoteMcpToolPolicyStoreCodec.decode(
                bytes.toString(StandardCharsets.UTF_8),
                signedVerifiers,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureAvailable() {
        if (unavailable) throw RemoteMcpFailure("mcp_tool_policy_store_unavailable")
    }

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp"
        const val FILE_NAME = "trusted-tool-policies-v1.json"
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        val UNAVAILABLE_SNAPSHOT = RemoteMcpToolPolicyStoreSnapshot(false, 0, emptyList())
    }
}

/** Input used by a future approval UI; the complete schema never enters the persisted policy. */
internal data class RemoteMcpToolPolicyApproval(
    val activationIdentity: RemoteMcpActivationIdentity,
    val tool: RemoteMcpTool,
    val effect: RemoteMcpToolEffect,
    val verifierId: String? = null,
) {
    internal fun toPolicy(
        generation: Long,
        signedVerifiers: RemoteMcpSignedVerifierRegistry,
    ): RemoteMcpToolPolicy {
        val policy = RemoteMcpToolPolicy.approved(
            activationIdentity,
            tool,
            effect,
            verifierId,
            generation,
        )
        if (effect == RemoteMcpToolEffect.MUTATING &&
            signedVerifiers.resolve(requireNotNull(verifierId)) == null
        ) {
            throw RemoteMcpFailure("mcp_tool_policy_verifier_untrusted")
        }
        return policy
    }

    override fun toString(): String = "RemoteMcpToolPolicyApproval(effect=$effect)"
}

private data class RemoteMcpToolPolicyState(
    val revision: Long,
    val policies: List<RemoteMcpToolPolicy>,
) {
    init {
        require(revision >= 0)
        require(policies.size <= MAX_TOOL_POLICIES)
        require(policies.map(RemoteMcpToolPolicy::policyKey).distinct().size == policies.size) {
            "Duplicate Remote MCP policy"
        }
        require(policies.all { it.generation in 1..revision }) {
            "Remote MCP policy generation is stale or future"
        }
    }

    val snapshot: RemoteMcpToolPolicyStoreSnapshot
        get() = RemoteMcpToolPolicyStoreSnapshot(true, revision, policies.toList())

    companion object {
        val EMPTY = RemoteMcpToolPolicyState(0, emptyList())
    }
}

private data class RemoteMcpToolPolicyKey(
    val activationIdentity: RemoteMcpActivationIdentity,
    val toolName: String,
)

private val RemoteMcpToolPolicy.policyKey: RemoteMcpToolPolicyKey
    get() = RemoteMcpToolPolicyKey(activationIdentity, toolName)

private object RemoteMcpToolPolicyStoreCodec {
    private const val SCHEMA = "hans.remote-mcp-tool-policies"
    private const val VERSION = 1

    fun encode(state: RemoteMcpToolPolicyState): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("revision", state.revision)
        .put(
            "policies",
            JSONArray(
                state.policies.sortedWith(
                    compareBy(
                        { it.activationIdentity.pluginId },
                        { it.activationIdentity.serverId },
                        { it.activationIdentity.configurationDigest },
                        RemoteMcpToolPolicy::toolName,
                    ),
                ).map(::encodePolicy),
            ),
        )
        .toString()

    fun decode(
        raw: String,
        signedVerifiers: RemoteMcpSignedVerifierRegistry,
    ): RemoteMcpToolPolicyState {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024)
        val root = JSONObject(raw)
        requireExactKeys(root, "schema", "version", "revision", "policies")
        require(root.getString("schema") == SCHEMA && root.getInt("version") == VERSION)
        require(root.get("revision") is Number)
        val values = root.getJSONArray("policies")
        require(values.length() <= MAX_TOOL_POLICIES)
        val state = RemoteMcpToolPolicyState(
            revision = root.getLong("revision"),
            policies = (0 until values.length()).map { index ->
                decodePolicy(values.getJSONObject(index), signedVerifiers)
            },
        )
        require(encode(state) == raw) { "Remote MCP policy store is not canonical" }
        return state
    }

    private fun encodePolicy(policy: RemoteMcpToolPolicy): JSONObject = JSONObject()
        .put("pluginId", policy.activationIdentity.pluginId)
        .put("serverId", policy.activationIdentity.serverId)
        .put("configurationDigest", policy.activationIdentity.configurationDigest)
        .put("toolName", policy.toolName)
        .put("toolMetadataDigest", policy.toolMetadataDigest)
        .put("effect", policy.effect.name)
        .put("verifierId", policy.verifierId ?: JSONObject.NULL)
        .put("generation", policy.generation)

    private fun decodePolicy(
        value: JSONObject,
        signedVerifiers: RemoteMcpSignedVerifierRegistry,
    ): RemoteMcpToolPolicy {
        requireExactKeys(
            value,
            "pluginId",
            "serverId",
            "configurationDigest",
            "toolName",
            "toolMetadataDigest",
            "effect",
            "verifierId",
            "generation",
        )
        require(value.get("generation") is Number)
        val policy = RemoteMcpToolPolicy(
            activationIdentity = RemoteMcpActivationIdentity(
                value.getString("pluginId"),
                value.getString("serverId"),
                value.getString("configurationDigest"),
            ),
            toolName = value.getString("toolName"),
            toolMetadataDigest = value.getString("toolMetadataDigest"),
            effect = RemoteMcpToolEffect.valueOf(value.getString("effect")),
            verifierId = if (value.isNull("verifierId")) null else value.getString("verifierId"),
            generation = value.getLong("generation"),
        )
        if (policy.effect == RemoteMcpToolEffect.MUTATING &&
            signedVerifiers.resolve(requireNotNull(policy.verifierId)) == null
        ) {
            throw RemoteMcpFailure("mcp_tool_policy_verifier_untrusted")
        }
        return policy
    }
}

/** Canonical digest of every RemoteMcpTool field Hans exposes or routes. */
internal fun remoteMcpToolMetadataDigest(tool: RemoteMcpTool): String = sha256(
    canonicalRemoteMcpJsonObject(
        JSONObject()
            .put("name", tool.name)
            .put("title", tool.title ?: JSONObject.NULL)
            .put("description", tool.description ?: JSONObject.NULL)
            .put("inputSchema", JSONObject(tool.inputSchemaJson))
            .put(
                "outputSchema",
                tool.outputSchemaJson?.let { JSONObject(it) } ?: JSONObject.NULL,
            )
            .put(
                "annotations",
                tool.annotationsJson?.let { JSONObject(it) } ?: JSONObject.NULL,
            )
            .put(
                "icons",
                tool.iconsJson?.let { JSONArray(it) } ?: JSONObject.NULL,
            )
            .put(
                "_meta",
                tool.metaJson?.let { JSONObject(it) } ?: JSONObject.NULL,
            )
            .toString(),
        MAX_TOOL_METADATA_DIGEST_BYTES,
    )
        .toByteArray(StandardCharsets.UTF_8),
)

private const val MAX_TOOL_POLICIES = 1024
private const val MAX_SIGNED_VERIFIERS = 256
private const val MAX_TOOL_METADATA_DIGEST_BYTES = 1024 * 1024
private val SAFE_POLICY_TOOL = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
private val SAFE_VERIFIER_ID = Regex("[a-z][a-z0-9._:-]{0,95}")
