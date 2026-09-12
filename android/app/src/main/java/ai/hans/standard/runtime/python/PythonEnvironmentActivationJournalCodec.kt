package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal data class PythonEnvironmentActivationJournalRecord(
    val transactionId: String,
    val state: PythonEnvironmentRecoveryState,
    val installed: PythonEnvironmentActivationIdentity,
    val previous: PythonEnvironmentActivationIdentity?,
    val newlyInstalled: Boolean,
) {
    init {
        require(TRANSACTION_ID.matches(transactionId)) { "Invalid environment transaction id" }
        require(previous == null || previous.pluginId == installed.pluginId) {
            "Recovery identities belong to different plugins"
        }
    }

    companion object {
        private val TRANSACTION_ID = Regex("pyenv-[a-f0-9]{32}")
    }
}

internal object PythonEnvironmentActivationJournalCodec {
    const val FILE_SUFFIX = ".json"
    const val MAX_BYTES = 32 * 1024
    private const val SCHEMA_VERSION = 1

    fun encode(record: PythonEnvironmentActivationJournalRecord): ByteArray {
        val body = bodyJson(record)
        val root = JSONObject(body.toString())
            .put("recordDigest", digest(body))
        return JsonContract.encodeBounded(root, MAX_BYTES).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): PythonEnvironmentActivationJournalRecord {
        require(bytes.size in 1..MAX_BYTES) { "Activation journal is outside its size limit" }
        val root = JsonContract.parseObject(bytes.toString(StandardCharsets.UTF_8), MAX_BYTES)
        JsonContract.requireOnlyKeys(
            root,
            setOf(
                "schemaVersion", "transactionId", "state", "installed", "previous",
                "newlyInstalled", "recordDigest",
            ),
            "Python environment activation journal",
        )
        require(root.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported activation journal schema"
        }
        val record = PythonEnvironmentActivationJournalRecord(
            transactionId = JsonContract.requiredString(root, "transactionId", 128),
            state = when (JsonContract.requiredString(root, "state", 32)) {
                "prepared" -> PythonEnvironmentRecoveryState.PREPARED
                "committed" -> PythonEnvironmentRecoveryState.COMMITTED
                else -> error("Unsupported activation journal state")
            },
            installed = decodeIdentity(JsonContract.requiredObject(root, "installed")),
            previous = if (!root.has("previous") || root.isNull("previous")) {
                null
            } else {
                decodeIdentity(JsonContract.requiredObject(root, "previous"))
            },
            newlyInstalled = JsonContract.requiredBoolean(root, "newlyInstalled"),
        )
        val claimed = JsonContract.requiredString(root, "recordDigest", 64)
        require(PythonEnvironmentContract.isSha256(claimed) && claimed == digest(bodyJson(record))) {
            "Activation journal identity digest does not match"
        }
        return record
    }

    private fun bodyJson(record: PythonEnvironmentActivationJournalRecord): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("transactionId", record.transactionId)
        .put(
            "state",
            when (record.state) {
                PythonEnvironmentRecoveryState.PREPARED -> "prepared"
                PythonEnvironmentRecoveryState.COMMITTED -> "committed"
            },
        )
        .put("installed", identityJson(record.installed))
        .put("previous", record.previous?.let(::identityJson) ?: JSONObject.NULL)
        .put("newlyInstalled", record.newlyInstalled)

    private fun identityJson(identity: PythonEnvironmentActivationIdentity): JSONObject = JSONObject()
        .put("pluginId", identity.pluginId)
        .put("target", PythonEnvironmentLockCodec.targetJson(identity.target))
        .put("lockDigest", identity.lockDigest)
        .put("environmentDigest", identity.environmentDigest)

    private fun decodeIdentity(value: JSONObject): PythonEnvironmentActivationIdentity {
        JsonContract.requireOnlyKeys(
            value,
            setOf("pluginId", "target", "lockDigest", "environmentDigest"),
            "Python environment recovery identity",
        )
        val target = JsonContract.requiredObject(value, "target")
        JsonContract.requireOnlyKeys(
            target,
            setOf("pythonVersion", "interpreterTag", "androidAbi", "minimumAndroidApi"),
            "Python environment recovery target",
        )
        return PythonEnvironmentActivationIdentity(
            pluginId = JsonContract.requiredString(value, "pluginId", 128),
            target = PythonEnvironmentTarget(
                pythonVersion = JsonContract.requiredString(target, "pythonVersion", 32),
                interpreterTag = JsonContract.requiredString(target, "interpreterTag", 16),
                androidAbi = JsonContract.requiredString(target, "androidAbi", 32),
                minimumAndroidApi = target.requiredInt("minimumAndroidApi"),
            ),
            lockDigest = JsonContract.requiredString(value, "lockDigest", 64),
            environmentDigest = JsonContract.requiredString(value, "environmentDigest", 64),
        )
    }

    private fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }

    private fun JSONObject.requiredInt(name: String): Int {
        val value = JsonContract.requiredLong(this, name)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer '$name' is out of range" }
        return value.toInt()
    }
}
