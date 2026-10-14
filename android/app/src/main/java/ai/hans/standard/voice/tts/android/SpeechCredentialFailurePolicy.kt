package ai.hans.standard.voice.tts.android

/** Effective device-local availability, without exposing credential material. */
enum class SpeechCredentialStatus {
    MISSING,
    AVAILABLE,
    TEMPORARILY_UNAVAILABLE,
}

internal enum class SpeechCredentialFailureDisposition {
    /** Preserve both the ciphertext and its non-exportable key for a later retry. */
    PRESERVE_FOR_RETRY,

    /** The envelope/key can no longer form a valid pair and should be removed. */
    CLEAR_INVALID_MATERIAL,
}

internal fun interface SpeechCredentialFailureClassifier {
    fun classify(failure: Throwable): SpeechCredentialFailureDisposition
}

/** Pure recovery policy so locked-device behavior is deterministic in JVM tests. */
internal class SpeechCredentialFailureRecovery(
    private val classifier: SpeechCredentialFailureClassifier,
) {
    fun recoverReadFailure(
        failure: Throwable,
        clearInvalidMaterial: () -> Unit,
    ): SpeechCredentialStatus = when (classifier.classify(failure)) {
        SpeechCredentialFailureDisposition.PRESERVE_FOR_RETRY ->
            SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE

        SpeechCredentialFailureDisposition.CLEAR_INVALID_MATERIAL -> {
            // Failed durable clearing must not be reported as missing. Preserve retryability
            // when private storage is unavailable, just as with a temporarily locked Keystore.
            if (runCatching { clearInvalidMaterial() }.isSuccess) SpeechCredentialStatus.MISSING
            else SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE
        }
    }

    fun shouldClearBeforeReplacing(failure: Throwable): Boolean =
        classifier.classify(failure) ==
            SpeechCredentialFailureDisposition.CLEAR_INVALID_MATERIAL
}
