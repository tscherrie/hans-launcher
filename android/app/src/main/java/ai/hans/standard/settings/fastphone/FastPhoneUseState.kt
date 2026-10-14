package ai.hans.standard.settings.fastphone

/** Device-local state only. No credential value or provider response belongs in this model. */
enum class FastPhoneCredentialStatus { MISSING, AVAILABLE, TEMPORARILY_UNAVAILABLE }

enum class FastPhoneServiceStatus {
    NOT_VERIFIED,
    VERIFIED,
    INVALID_KEY,
    OUT_OF_CREDITS,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
}

data class FastPhoneUseSettingsSnapshot(
    val requestedEnabled: Boolean = false,
    val credentialStatus: FastPhoneCredentialStatus = FastPhoneCredentialStatus.MISSING,
    val serviceStatus: FastPhoneServiceStatus = FastPhoneServiceStatus.NOT_VERIFIED,
    val revision: Long = 0,
) {
    /** Opt-in and a readable key admit a first request; they do not prove API access. */
    val requestsAllowed: Boolean
        get() = requestedEnabled && credentialStatus == FastPhoneCredentialStatus.AVAILABLE &&
            serviceStatus !in setOf(
                FastPhoneServiceStatus.INVALID_KEY,
                FastPhoneServiceStatus.OUT_OF_CREDITS,
            )

    val verifiedActive: Boolean
        get() = requestsAllowed && serviceStatus == FastPhoneServiceStatus.VERIFIED
}

internal fun fastPhoneServiceFailure(code: String): FastPhoneServiceStatus = when (code) {
    "invalid_api_key", "invalid_key", "unauthorized", "authentication_failed", "http_401",
    "permission_denied", "forbidden", "http_403", "fast_phone_key_rejected",
    "fast_phone_access_denied" -> FastPhoneServiceStatus.INVALID_KEY
    "insufficient_quota", "out_of_credits", "quota_exceeded", "billing_hard_limit_reached",
    "fast_phone_quota_exhausted" ->
        FastPhoneServiceStatus.OUT_OF_CREDITS
    "rate_limit", "rate_limited", "rate_limit_exceeded", "http_429", "fast_phone_rate_limited" ->
        FastPhoneServiceStatus.RATE_LIMITED
    "network_error", "network_unavailable", "timeout", "request_timeout",
    "fast_phone_request_timed_out" ->
        FastPhoneServiceStatus.NETWORK_UNAVAILABLE
    else -> FastPhoneServiceStatus.SERVICE_UNAVAILABLE
}
