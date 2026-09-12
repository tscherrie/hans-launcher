package ai.hans.standard.automations.androidgateway

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Allocates bounded, attempt-specific client message ids before a thread-bound dispatch.
 *
 * An entry is advanced only when the Codex session rejects the dispatch locally and therefore no
 * App Server request was accepted. Once the host accepts an attempt, the separate correlation
 * ledger becomes authoritative and this allocator is cleared. A terminal turn failure or lost
 * correlation must never come back through this allocator: repeating either could duplicate tool
 * side effects which do not yet share a durable idempotency seam.
 */
internal class ThreadBoundDispatchAttempts(
    private val maximumTrackedKeys: Int = DEFAULT_MAXIMUM_TRACKED_KEYS,
    private val maximumAttemptsPerKey: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) {
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init {
        require(maximumTrackedKeys in 1..MAXIMUM_TRACKED_KEYS_LIMIT)
        require(maximumAttemptsPerKey in 1..MAXIMUM_ATTEMPTS_LIMIT)
    }

    @Synchronized
    fun prepare(
        idempotencyKey: String,
        expectedThreadId: String,
    ): ThreadBoundDispatchAttemptResult {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 256)
        require(idempotencyKey.none(Char::isISOControl))
        require(expectedThreadId.isNotBlank() && expectedThreadId.length <= 256)
        require(expectedThreadId.none(Char::isISOControl))

        val existing = entries[idempotencyKey]
        if (existing != null && existing.expectedThreadId != expectedThreadId) {
            return ThreadBoundDispatchAttemptResult.Rejected(
                code = "automation_idempotency_conflict",
                retryable = false,
            )
        }
        val nextAttempt = (existing?.attemptNumber ?: 0) + 1
        if (nextAttempt > maximumAttemptsPerKey) {
            return ThreadBoundDispatchAttemptResult.Rejected(
                code = "codex_pre_dispatch_attempts_exhausted",
                retryable = false,
            )
        }
        entries[idempotencyKey] = Entry(expectedThreadId, nextAttempt)
        trim()
        return ThreadBoundDispatchAttemptResult.Prepared(
            attemptNumber = nextAttempt,
            clientUserMessageId = clientMessageId(idempotencyKey, nextAttempt),
        )
    }

    /** Stops tracking a key immediately after the first locally accepted dispatch. */
    @Synchronized
    fun accepted(idempotencyKey: String) {
        entries.remove(idempotencyKey)
    }

    @Synchronized
    internal fun trackedKeyCountForTest(): Int = entries.size

    private fun trim() {
        while (entries.size > maximumTrackedKeys) {
            entries.remove(entries.entries.first().key)
        }
    }

    private data class Entry(
        val expectedThreadId: String,
        val attemptNumber: Int,
    )

    private companion object {
        const val DEFAULT_MAXIMUM_TRACKED_KEYS = 256
        const val MAXIMUM_TRACKED_KEYS_LIMIT = 4_096
        const val DEFAULT_MAXIMUM_ATTEMPTS = 20
        const val MAXIMUM_ATTEMPTS_LIMIT = 20

        fun clientMessageId(idempotencyKey: String, attemptNumber: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(idempotencyKey.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "hans-automation-${digest.take(40)}-attempt-$attemptNumber"
        }
    }
}

internal sealed interface ThreadBoundDispatchAttemptResult {
    data class Prepared(
        val attemptNumber: Int,
        val clientUserMessageId: String,
    ) : ThreadBoundDispatchAttemptResult {
        init {
            require(attemptNumber > 0)
            require(clientUserMessageId.isNotBlank() && clientUserMessageId.length <= 256)
            require(clientUserMessageId.none(Char::isISOControl))
        }
    }

    data class Rejected(
        val code: String,
        val retryable: Boolean,
    ) : ThreadBoundDispatchAttemptResult {
        init {
            require(code.matches(Regex("[a-z][a-z0-9_]{2,95}")))
        }
    }
}
