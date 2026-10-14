package ai.hans.standard.fastphone.provisioning

import ai.hans.standard.fastphone.DecisionsOutcome

/** A smoke request proves access only for the same unlocked, still-enabled credential revision. */
internal fun fastPhoneVerificationCode(
    outcome: DecisionsOutcome,
    expectedRevision: Long,
    currentRevision: Long,
    enabled: Boolean,
    unlocked: Boolean,
): String {
    if (expectedRevision != currentRevision || !enabled || !unlocked) return "verification_cancelled"
    return when (outcome) {
        is DecisionsOutcome.Selected -> if (outcome.id == "ready" && outcome.confidence.isFinite() && outcome.confidence in 0.85..1.0) {
            "verified"
        } else "synthetic_unexpected_choice"
        is DecisionsOutcome.Failure -> outcome.code.takeIf { it in FAST_PHONE_VERIFY_FAILURE_CODES } ?: "fast_phone_invalid_response"
    }
}

private val FAST_PHONE_VERIFY_FAILURE_CODES = setOf("fast_phone_key_rejected", "fast_phone_access_denied",
    "fast_phone_quota_exhausted", "fast_phone_rate_limited", "fast_phone_service_unavailable",
    "fast_phone_invalid_response", "fast_phone_cancelled", "fast_phone_request_timed_out")
