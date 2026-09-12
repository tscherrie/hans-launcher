package ai.hans.standard.voice.tts.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCredentialFailurePolicyTest {
    @Test
    fun transientLockedDeviceFailurePreservesCredentialMaterial() {
        val recovery = recoveryFor(SpeechCredentialFailureDisposition.PRESERVE_FOR_RETRY)
        var cleared = false

        val status = recovery.recoverReadFailure(TestFailure()) { cleared = true }

        assertEquals(SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE, status)
        assertFalse(cleared)
        assertFalse(recovery.shouldClearBeforeReplacing(TestFailure()))
    }

    @Test
    fun provenInvalidMaterialIsClearedBeforeReportingMissing() {
        val recovery = recoveryFor(SpeechCredentialFailureDisposition.CLEAR_INVALID_MATERIAL)
        var cleared = false

        val status = recovery.recoverReadFailure(TestFailure()) { cleared = true }

        assertEquals(SpeechCredentialStatus.MISSING, status)
        assertTrue(cleared)
        assertTrue(recovery.shouldClearBeforeReplacing(TestFailure()))
    }

    private fun recoveryFor(
        disposition: SpeechCredentialFailureDisposition,
    ): SpeechCredentialFailureRecovery = SpeechCredentialFailureRecovery(
        SpeechCredentialFailureClassifier { disposition },
    )

    private class TestFailure : Exception()
}
