package ai.hans.standard.diagnostics

import android.content.ComponentName
import android.content.Intent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class MigrationDiagnosticsPhase(val wireValue: String) {
    PREFLIGHT("preflight"),
    AFTER_TRANSITION("after_transition"),
    AFTER_PUBLISHER("after_publisher"),
    ;

    companion object {
        fun fromWireValue(value: String): MigrationDiagnosticsPhase? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class MigrationDiagnosticsRequest(
    val protocolVersion: Int,
    val requestId: String,
    val phase: MigrationDiagnosticsPhase,
) {
    init {
        require(protocolVersion == MigrationDiagnosticsContract.PROTOCOL_VERSION)
        require(MigrationDiagnosticsContract.canonicalRequestId(requestId) == requestId)
    }
}

object MigrationDiagnosticsContract {
    const val ACTION = "ai.hans.standard.action.MIGRATION_DIAGNOSTICS_V1"
    const val PROTOCOL_VERSION = 1
    const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_PHASE = "phase"
    const val RESULT_MALFORMED = "hans.migration-diagnostics.v1:rejected_malformed"
    const val RESULT_NOT_ORDERED = "hans.migration-diagnostics.v1:rejected_not_ordered"
    const val RESULT_COLLECTION_FAILED = "hans.migration-diagnostics.v1:collection_failed"
    const val RESULT_TIMEOUT = "hans.migration-diagnostics.v1:timeout"
    const val ENVELOPE_SCHEMA = "hans.migration-diagnostics"
    const val MAX_RESULT_BYTES = 16 * 1_024
    const val RECEIVER_TIMEOUT_MILLIS = 5_000L

    private const val UUID_TEXT_LENGTH = 36
    private val REQUIRED_EXTRA_KEYS = setOf(
        EXTRA_PROTOCOL_VERSION,
        EXTRA_REQUEST_ID,
        EXTRA_PHASE,
    )

    fun parse(intent: Intent, expectedComponent: ComponentName): MigrationDiagnosticsRequest? =
        runCatching {
            if (intent.action != ACTION || intent.component != expectedComponent) {
                return@runCatching null
            }
            val extras = intent.extras ?: return@runCatching null
            if (extras.keySet() != REQUIRED_EXTRA_KEYS) return@runCatching null
            if (!extras.containsKey(EXTRA_PROTOCOL_VERSION)) return@runCatching null
            if (!extras.containsKey(EXTRA_REQUEST_ID)) return@runCatching null
            if (!extras.containsKey(EXTRA_PHASE)) return@runCatching null
            parseFields(
                action = intent.action,
                componentMatches = intent.component == expectedComponent,
                protocolVersion = extras.get(EXTRA_PROTOCOL_VERSION),
                requestId = extras.get(EXTRA_REQUEST_ID),
                phase = extras.get(EXTRA_PHASE),
                extraKeys = extras.keySet(),
            )
        }.getOrNull()

    internal fun parseFields(
        action: String?,
        componentMatches: Boolean,
        protocolVersion: Any?,
        requestId: Any?,
        phase: Any?,
        extraKeys: Set<String>,
    ): MigrationDiagnosticsRequest? {
        if (action != ACTION || !componentMatches) return null
        if (extraKeys != REQUIRED_EXTRA_KEYS) return null
        if (protocolVersion !is Int || protocolVersion != PROTOCOL_VERSION) return null
        if (requestId !is String || phase !is String) return null
        val canonicalId = canonicalRequestId(requestId) ?: return null
        val parsedPhase = MigrationDiagnosticsPhase.fromWireValue(phase) ?: return null
        return MigrationDiagnosticsRequest(protocolVersion, canonicalId, parsedPhase)
    }

    internal fun canonicalRequestId(value: String): String? {
        if (value.length != UUID_TEXT_LENGTH || value.any(Char::isISOControl)) return null
        return runCatching { UUID.fromString(value).toString() }
            .getOrNull()
            ?.takeIf { it == value }
    }
}

internal enum class MigrationDiagnosticsDispatchDecision {
    ACCEPT,
    REJECT_NOT_ORDERED,
}

internal object MigrationDiagnosticsDispatchGate {
    fun evaluate(
        ordered: Boolean,
    ): MigrationDiagnosticsDispatchDecision {
        if (!ordered) return MigrationDiagnosticsDispatchDecision.REJECT_NOT_ORDERED
        return MigrationDiagnosticsDispatchDecision.ACCEPT
    }
}

/** Small canonical JSON writer shared with the host verifier. Keys are always UTF-16 sorted. */
internal object MigrationCanonicalJson {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is String -> appendString(value)
            is Map<*, *> -> {
                val entries = value.entries.map { entry ->
                    val key = entry.key as? String ?: error("migration_json_key")
                    key to entry.value
                }.sortedBy { it.first }
                append('{')
                entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> error("migration_json_type")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

internal object MigrationDiagnosticsHashes {
    private val PAYLOAD_DOMAIN = "hans-migration-payload-v1\u0000"
        .toByteArray(StandardCharsets.UTF_8)
    private val THREAD_DOMAIN = "hans-thread-v1\u0000"
        .toByteArray(StandardCharsets.UTF_8)

    fun payload(payloadCanonicalJson: String): String = digest(
        PAYLOAD_DOMAIN,
        payloadCanonicalJson.toByteArray(StandardCharsets.UTF_8),
    )

    fun thread(threadId: String): String = digest(
        THREAD_DOMAIN,
        threadId.toByteArray(StandardCharsets.UTF_8),
    )

    private fun digest(domain: ByteArray, value: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(domain)
            digest.update(value)
            digest.digest().toLowerHex()
        } finally {
            value.fill(0)
        }
    }
}

internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return CharArray(size * 2).also { output ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = alphabet[value ushr 4]
            output[index * 2 + 1] = alphabet[value and 0x0f]
        }
    }.concatToString()
}
