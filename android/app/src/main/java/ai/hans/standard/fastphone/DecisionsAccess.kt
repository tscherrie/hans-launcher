package ai.hans.standard.fastphone

/** Device-local opt-in only. Implementations must never expose a credential in their state model. */
interface DecisionsAccess {
    fun isEnabled(): Boolean
    /** Changes when the credential or opt-in changes, not after ordinary request telemetry. */
    fun revision(): Long
    /** Returns an owned copy. The caller wipes it immediately after the request completes. */
    fun readApiKey(): CharArray?
    fun recordFailure(code: String, expectedRevision: Long)
    fun recordSuccess(expectedRevision: Long)
}
