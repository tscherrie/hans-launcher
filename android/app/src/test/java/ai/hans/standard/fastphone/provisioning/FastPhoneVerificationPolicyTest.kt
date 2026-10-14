package ai.hans.standard.fastphone.provisioning

import ai.hans.standard.fastphone.DecisionsOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class FastPhoneVerificationPolicyTest {
    private fun code(outcome: DecisionsOutcome, current: Long = 7, enabled: Boolean = true, unlocked: Boolean = true) =
        fastPhoneVerificationCode(outcome, 7, current, enabled, unlocked)

    @Test fun verifiedRequiresReadyAndConfidenceAtLeastPointEightFive() {
        assertEquals("verified", code(DecisionsOutcome.Selected("ready", 0.85)))
        assertEquals("verified", code(DecisionsOutcome.Selected("ready", 1.0)))
        assertEquals("synthetic_unexpected_choice", code(DecisionsOutcome.Selected("ready", 0.849)))
        assertEquals("synthetic_unexpected_choice", code(DecisionsOutcome.Selected("escalate", 1.0)))
    }

    @Test fun invalidConfidenceCannotBeVerified() {
        for (confidence in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertEquals("synthetic_unexpected_choice", code(DecisionsOutcome.Selected("ready", confidence)))
        }
    }

    @Test fun lateSuccessAfterKeyChangeDisableOrLockIsCancelled() {
        val ready = DecisionsOutcome.Selected("ready", 1.0)
        assertEquals("verification_cancelled", code(ready, current = 8))
        assertEquals("verification_cancelled", code(ready, enabled = false))
        assertEquals("verification_cancelled", code(ready, unlocked = false))
    }

    @Test fun providerFailuresAreConstantAllowlistedOnly() {
        assertEquals("fast_phone_quota_exhausted", code(DecisionsOutcome.Failure("fast_phone_quota_exhausted")))
        assertEquals("fast_phone_invalid_response", code(DecisionsOutcome.Failure("freeform-provider-private-data")))
    }
}
