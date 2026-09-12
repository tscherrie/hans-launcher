package ai.hans.standard.mcp

import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteMcpActivationIdentity(
    val pluginId: String,
    val serverId: String,
    val configurationDigest: String,
) {
    init {
        require(pluginId.matches(SAFE_LOGICAL_ID)) { "Invalid Remote MCP plugin id" }
        require(serverId.matches(SAFE_LOGICAL_ID)) { "Invalid Remote MCP server id" }
        require(configurationDigest.matches(SHA_256)) {
            "Invalid Remote MCP configuration identity"
        }
    }
}

/** Exact persisted activation input; the digest covers every security-relevant server option. */
internal class RemoteMcpActivationDefinition(
    val identity: RemoteMcpActivationIdentity,
    val requirement: RemoteMcpRequirement,
) {
    init {
        require(identity.serverId == requirement.id) { "Remote MCP server identity changed" }
        require(identity.configurationDigest == RemoteMcpConfigurationIdentity.digest(requirement)) {
            "Remote MCP configuration identity changed"
        }
    }

    val metadataDigest: String
        get() = sha256(
            JSONObject()
                .put("pluginId", identity.pluginId)
                .put("requirement", RemoteMcpConfigurationIdentity.canonicalJson(requirement))
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )
}

internal object RemoteMcpConfigurationIdentity {
    fun digest(requirement: RemoteMcpRequirement): String = sha256(
        canonicalJson(requirement).toString().toByteArray(StandardCharsets.UTF_8),
    )

    fun canonicalJson(requirement: RemoteMcpRequirement): JSONObject = JSONObject()
        .put("id", requirement.id)
        .put("endpoint", requirement.endpoint)
        .put("oauthHandle", requirement.oauthHandle?.value ?: JSONObject.NULL)
        .put("allowedTools", JSONArray(requirement.allowedTools.sorted()))
        .put("required", requirement.required)
        .put("requestTimeoutMillis", requirement.requestTimeoutMillis)
        .put("maxResponseBytes", requirement.maxResponseBytes)

    fun decodeRequirement(value: JSONObject): RemoteMcpRequirement {
        requireExactKeys(
            value,
            "id",
            "endpoint",
            "oauthHandle",
            "allowedTools",
            "required",
            "requestTimeoutMillis",
            "maxResponseBytes",
        )
        val tools = value.getJSONArray("allowedTools")
        require(tools.length() in 1..256) { "Invalid Remote MCP tool count" }
        val decodedTools = (0 until tools.length()).map { index -> tools.getString(index) }
        require(decodedTools == decodedTools.sorted() && decodedTools.distinct().size == decodedTools.size) {
            "Remote MCP tools are not canonical"
        }
        require(value.get("required") is Boolean)
        require(value.get("requestTimeoutMillis") is Number)
        require(value.get("maxResponseBytes") is Number)
        val handle = if (value.isNull("oauthHandle")) {
            null
        } else {
            OAuthCredentialHandle(value.getString("oauthHandle"))
        }
        return RemoteMcpRequirement(
            id = value.getString("id"),
            endpoint = value.getString("endpoint"),
            oauthHandle = handle,
            allowedTools = decodedTools.toSet(),
            required = value.getBoolean("required"),
            requestTimeoutMillis = value.getLong("requestTimeoutMillis"),
            maxResponseBytes = value.getInt("maxResponseBytes"),
        )
    }
}

internal enum class RemoteMcpActivationPhase {
    PREPARED,
    COMMITTED,
    FINALIZED,
}

/** Opaque transaction cursor suitable for embedding in the later plugin install journal. */
internal class RemoteMcpActivationReceipt internal constructor(
    internal val transactionId: String,
    val pluginId: String,
    val serverId: String,
    internal val metadataDigest: String,
) {
    override fun toString(): String =
        "RemoteMcpActivationReceipt(pluginId=$pluginId, serverId=$serverId)"
}

internal data class RemoteMcpActivationRecoveryDescriptor(
    val transactionId: String,
    val identity: RemoteMcpActivationIdentity,
    val metadataDigest: String,
    val phase: RemoteMcpActivationPhase,
)

/** Passive UI/diagnostic projection: no endpoint, handle, digest, token or file path. */
internal data class RemoteMcpActivationStatus(
    val pluginId: String,
    val serverId: String,
    val phase: RemoteMcpActivationPhase,
    val sessionOpen: Boolean,
)

internal data class RemoteMcpSessionRegistryStatus(
    val available: Boolean,
    val finalizedActivationCount: Int,
    val preparedTransactionCount: Int,
    val committedTransactionCount: Int,
    val activations: List<RemoteMcpActivationStatus>,
)

internal enum class RemoteMcpDeactivationResult {
    DEACTIVATED,
    MISSING,
    STALE_IDENTITY,
    TRANSACTION_PENDING,
    REGISTRY_UNAVAILABLE,
}

internal enum class RemoteMcpFinalizedActivationProof {
    EXACT,
    MISSING,
    CHANGED,
    UNAVAILABLE,
}

internal interface RemoteMcpActivationTransaction {
    /** Persists exact activation input. It does not open a session or expose discovery. */
    fun prepare(definition: RemoteMcpActivationDefinition): RemoteMcpActivationReceipt

    /** Records the caller's commit intent. Existing finalized activation stays in force. */
    fun commit(receipt: RemoteMcpActivationReceipt)

    /** Atomically selects the committed definition. Only after this may discovery be requested. */
    fun finalize(receipt: RemoteMcpActivationReceipt)

    /** Removes prepared/committed state. The previous finalized activation remains untouched. */
    fun rollback(receipt: RemoteMcpActivationReceipt): Boolean

    /** Durable cursors for deterministic plugin-journal recovery after process death. */
    fun pendingRecovery(): List<RemoteMcpActivationRecoveryDescriptor>
}

internal fun requireExactKeys(value: JSONObject, vararg expected: String) {
    require(value.keys().asSequence().toSet() == expected.toSet()) {
        "Remote MCP persisted schema changed"
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal val SAFE_LOGICAL_ID = Regex("[a-z][a-z0-9._:-]{0,95}")
internal val SHA_256 = Regex("[0-9a-f]{64}")
