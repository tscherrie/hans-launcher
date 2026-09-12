package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

enum class PythonPluginEntrypointRecoveryState {
    PREPARED,
    COMMITTED,
}

/** Passive proof used only to close the outer install journal after local finalization. */
internal enum class PythonPluginEntrypointFinalizedProof {
    EXACT,
    CHANGED,
    UNAVAILABLE,
}

/** Exact content identity only; callable paths stay private to the proven activation journal. */
data class PythonPluginEntrypointActivationIdentity(
    val pluginId: String,
    val environmentDigest: String,
    val sourceSha256: String,
    val metadataDigest: String,
) {
    init {
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid entrypoint recovery plugin id" }
        require(PythonEnvironmentContract.isSha256(environmentDigest)) {
            "Invalid entrypoint recovery environment identity"
        }
        require(PythonEnvironmentContract.isSha256(sourceSha256)) {
            "Invalid entrypoint recovery source identity"
        }
        require(PythonEnvironmentContract.isSha256(metadataDigest)) {
            "Invalid entrypoint recovery metadata identity"
        }
    }
}

/** Pass [receipt] back to commit/finalize/rollback after reconstructing a registry instance. */
data class PythonPluginEntrypointRecoveryDescriptor(
    val receipt: PythonPluginEntrypointActivationReceipt,
    val state: PythonPluginEntrypointRecoveryState,
    val activation: PythonPluginEntrypointActivationIdentity,
    val previous: PythonPluginEntrypointActivationIdentity?,
)

internal data class PythonPluginEntrypointActivationJournalRecord(
    val transactionId: String,
    val state: PythonPluginEntrypointRecoveryState,
    val activation: PythonPluginEntrypointActivation,
    val previous: PythonPluginEntrypointActivation?,
) {
    init {
        require(TRANSACTION_ID.matches(transactionId)) { "Invalid entrypoint activation transaction id" }
        require(previous == null || previous.pluginId == activation.pluginId) {
            "Entrypoint recovery activations belong to different plugins"
        }
    }

    companion object {
        internal val TRANSACTION_ID = Regex("[a-f0-9]{32}")
    }
}

internal object PythonPluginEntrypointActivationCodec {
    private val ACTIVATION_KEYS = setOf(
        "pluginId", "environmentDigest", "sourceSha256", "metadataDigest",
        "declarations", "provenEntrypointIds",
    )
    private val DECLARATION_KEYS = setOf(
        "entrypointId", "relativePath", "function", "sourceSha256", "declaredCapabilities",
    )

    fun encode(activation: PythonPluginEntrypointActivation): JSONObject =
        activation.canonicalJson().put("metadataDigest", activation.metadataDigest)

    fun decode(encoded: JSONObject): PythonPluginEntrypointActivation {
        JsonContract.requireOnlyKeys(encoded, ACTIVATION_KEYS, "Python plugin activation")
        val declarationsJson = JsonContract.requiredArray(encoded, "declarations")
        require(declarationsJson.length() <=
            PythonPluginEntrypointActivation.MAX_ENTRYPOINTS_PER_PLUGIN) {
            "Too many Python plugin entrypoints"
        }
        val declarations = buildList {
            repeat(declarationsJson.length()) { index ->
                val declaration = declarationsJson.opt(index) as? JSONObject
                    ?: error("Python plugin entrypoint must be an object")
                JsonContract.requireOnlyKeys(
                    declaration,
                    DECLARATION_KEYS,
                    "Python plugin entrypoint",
                )
                add(
                    PythonPluginEntrypointDeclaration(
                        entrypointId = JsonContract.requiredString(
                            declaration,
                            "entrypointId",
                            64,
                        ),
                        relativePath = JsonContract.requiredString(
                            declaration,
                            "relativePath",
                            1_024,
                        ),
                        function = JsonContract.requiredString(declaration, "function", 256),
                        sourceSha256 = JsonContract.requiredString(
                            declaration,
                            "sourceSha256",
                            64,
                        ),
                        declaredCapabilities = declaredCapabilities(declaration),
                    ),
                )
            }
        }
        val provenJson = JsonContract.requiredArray(encoded, "provenEntrypointIds")
        require(provenJson.length() <=
            PythonPluginEntrypointActivation.MAX_ENTRYPOINTS_PER_PLUGIN) {
            "Too many proven Python plugin entrypoints"
        }
        val provenList = List(provenJson.length()) { index ->
            (provenJson.opt(index) as? String)
                ?: error("Proven Python entrypoint id must be a string")
        }
        require(provenList.distinct().size == provenList.size) {
            "Duplicate proven Python plugin entrypoint id"
        }
        val activation = PythonPluginEntrypointActivation(
            pluginId = JsonContract.requiredString(encoded, "pluginId", 128),
            environmentDigest = JsonContract.requiredString(encoded, "environmentDigest", 64),
            sourceSha256 = JsonContract.requiredString(encoded, "sourceSha256", 64),
            declarations = declarations,
            provenEntrypointIds = provenList.toSet(),
        )
        require(JsonContract.requiredString(encoded, "metadataDigest", 64) ==
            activation.metadataDigest) {
            "Python plugin activation metadata identity does not match"
        }
        return activation
    }

    private fun declaredCapabilities(declaration: JSONObject): Set<String> {
        val values = JsonContract.requiredArray(declaration, "declaredCapabilities")
        require(values.length() <= 128) { "Too many declared Python plugin capabilities" }
        return buildSet {
            repeat(values.length()) { index ->
                val value = values.opt(index) as? String
                    ?: error("Python plugin declared capability must be a string")
                require(value.toByteArray(StandardCharsets.UTF_8).size <= 128) {
                    "Python plugin declared capability is too large"
                }
                require(add(value)) { "Duplicate Python plugin declared capability" }
            }
        }
    }
}

internal object PythonPluginEntrypointActivationJournalCodec {
    const val FILE_SUFFIX = ".json"
    const val MAX_BYTES = 256 * 1024
    private const val SCHEMA_VERSION = 1

    fun encode(record: PythonPluginEntrypointActivationJournalRecord): ByteArray {
        val body = bodyJson(record)
        val root = JSONObject(body.toString()).put("recordDigest", digest(body))
        return JsonContract.encodeBounded(root, MAX_BYTES).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): PythonPluginEntrypointActivationJournalRecord {
        require(bytes.size in 1..MAX_BYTES) { "Entrypoint activation journal is outside its size limit" }
        val root = JsonContract.parseObject(bytes.toString(StandardCharsets.UTF_8), MAX_BYTES)
        JsonContract.requireOnlyKeys(
            root,
            setOf(
                "schemaVersion", "transactionId", "state", "activation", "previous",
                "recordDigest",
            ),
            "Python entrypoint activation journal",
        )
        require(JsonContract.requiredLong(root, "schemaVersion") == SCHEMA_VERSION.toLong()) {
            "Unsupported entrypoint activation journal schema"
        }
        val record = PythonPluginEntrypointActivationJournalRecord(
            transactionId = JsonContract.requiredString(root, "transactionId", 64),
            state = when (JsonContract.requiredString(root, "state", 32)) {
                "prepared" -> PythonPluginEntrypointRecoveryState.PREPARED
                "committed" -> PythonPluginEntrypointRecoveryState.COMMITTED
                else -> error("Unsupported entrypoint activation journal state")
            },
            activation = PythonPluginEntrypointActivationCodec.decode(
                JsonContract.requiredObject(root, "activation"),
            ),
            previous = if (!root.has("previous") || root.isNull("previous")) {
                null
            } else {
                PythonPluginEntrypointActivationCodec.decode(
                    JsonContract.requiredObject(root, "previous"),
                )
            },
        )
        val claimed = JsonContract.requiredString(root, "recordDigest", 64)
        require(PythonEnvironmentContract.isSha256(claimed) && claimed == digest(bodyJson(record))) {
            "Entrypoint activation journal identity digest does not match"
        }
        return record
    }

    private fun bodyJson(record: PythonPluginEntrypointActivationJournalRecord): JSONObject =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("transactionId", record.transactionId)
            .put(
                "state",
                when (record.state) {
                    PythonPluginEntrypointRecoveryState.PREPARED -> "prepared"
                    PythonPluginEntrypointRecoveryState.COMMITTED -> "committed"
                },
            )
            .put("activation", PythonPluginEntrypointActivationCodec.encode(record.activation))
            .put(
                "previous",
                record.previous?.let(PythonPluginEntrypointActivationCodec::encode)
                    ?: JSONObject.NULL,
            )

    private fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
}
