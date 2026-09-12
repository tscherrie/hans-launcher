package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySensitiveActionConfirmationTest {
    @Test
    fun everySensitiveRiskHasDistinctTrustedFixedCopy() {
        val resources = listOf(
            confirmationRiskMessageResource(AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION),
            confirmationRiskMessageResource(AccessibilityConfirmationRisk.DESTRUCTIVE),
            confirmationRiskMessageResource(AccessibilityConfirmationRisk.CREDENTIAL_UI),
        )

        assertEquals(3, resources.toSet().size)
        assertTrue(R.string.accessibility_sensitive_confirmation_risk_external_communication in resources)
        assertTrue(R.string.accessibility_sensitive_confirmation_risk_destructive in resources)
        assertTrue(R.string.accessibility_sensitive_confirmation_risk_credential in resources)
    }

    @Test
    fun sanitizerRemovesControlsAndBidiAndHardBoundsPresentationMetadata() {
        val malicious = "Mail\u0000\n\u202e\u2028\u2029\u200b   App\t${"x".repeat(100)}"

        val label = AccessibilityConfirmationTextSanitizer.appLabel(malicious)
        val packageName = AccessibilityConfirmationTextSanitizer.packageName(
            "org.example\u200f.app",
        )

        assertTrue(label.length <= 80)
        assertTrue(label.none(Char::isISOControl))
        assertTrue('\u202e' !in label)
        assertTrue('\u2028' !in label)
        assertTrue('\u2029' !in label)
        assertTrue('\u200b' !in label)
        assertTrue("Mail App" in label)
        assertTrue("  " !in label)
        assertEquals("org.example.app", packageName)
    }

    @Test
    fun packageVisibilityFailureUsesFixedLocalLabelWithoutChangingTrustedPackage() {
        val fallback = AccessibilityConfirmationAppLabelPolicy.resolve {
            throw IllegalStateException("simulated package visibility filtering")
        }
        val packageName = AccessibilityConfirmationTextSanitizer.packageName(
            "org.example.hidden.application",
        )

        assertEquals("Android-App", fallback)
        assertEquals("org.example.hidden.application", packageName)
        val prompt = prompt().copy(appLabel = fallback, packageName = packageName)
        assertEquals("Android-App", prompt.appLabel)
        assertEquals("org.example.hidden.application", prompt.packageName)
    }

    @Test
    fun approvalRequiresEveryReceiptBindingAndMintsExactOneShotApproval() {
        val prompt = prompt()
        val receipt = receipt(prompt)

        val approval = AccessibilitySensitiveActionReceiptBinding.approvalFor(
            prompt,
            AccessibilitySensitiveActionOutcome.Approved(receipt),
        )

        requireNotNull(approval)
        assertEquals(prompt.idempotencyKey, approval.idempotencyKey)
        assertEquals(prompt.commandFingerprint, approval.commandFingerprint)
        assertEquals(prompt.risk, approval.risk)
        assertEquals(prompt.correlation, approval.correlation)
        assertTrue(approval.approvalId.contains(prompt.promptId))
    }

    @Test
    fun changedPromptIdFingerprintRiskCorrelationOrServiceGenerationFailsClosed() {
        val prompt = prompt()
        val valid = receipt(prompt)
        val mismatches = listOf(
            valid.copy(promptId = "prompt:different-safe-value"),
            valid.copy(commandFingerprint = "b".repeat(64)),
            valid.copy(risk = AccessibilityConfirmationRisk.CREDENTIAL_UI),
            valid.copy(
                correlation = valid.correlation.copy(
                    snapshotId = AccessibilitySnapshotId(2),
                ),
            ),
            valid.copy(serviceGenerationNonce = "service:different-safe-value"),
        )

        mismatches.forEach { receipt ->
            assertNull(
                AccessibilitySensitiveActionReceiptBinding.approvalFor(
                    prompt,
                    AccessibilitySensitiveActionOutcome.Approved(receipt),
                ),
            )
        }
        assertNull(
            AccessibilitySensitiveActionReceiptBinding.approvalFor(
                prompt,
                AccessibilitySensitiveActionOutcome.Denied,
            ),
        )
        assertNull(
            AccessibilitySensitiveActionReceiptBinding.approvalFor(
                prompt,
                AccessibilitySensitiveActionOutcome.Expired,
            ),
        )
        assertNull(
            AccessibilitySensitiveActionReceiptBinding.approvalFor(
                prompt,
                AccessibilitySensitiveActionOutcome.ContextChanged,
            ),
        )
    }

    private fun prompt() = AccessibilitySensitiveActionPrompt(
        promptId = "prompt:12345678-1234-1234-1234-123456789abc",
        idempotencyKey = AccessibilityIdempotencyKey("call:test-sensitive-action"),
        commandFingerprint = "a".repeat(64),
        risk = AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
        correlation = UiSnapshotCorrelation(
            AccessibilitySessionId("session-sensitive-action"),
            AccessibilityWindowId(7),
            AccessibilitySnapshotId(1),
        ),
        serviceGenerationNonce = "service:12345678-1234-1234-1234-123456789abc",
        actionKind = AccessibilitySensitiveActionKind.CLICK,
        appLabel = "Mail",
        packageName = "org.example.mail",
    )

    private fun receipt(prompt: AccessibilitySensitiveActionPrompt) =
        AccessibilitySensitiveActionReceipt(
            prompt.promptId,
            prompt.idempotencyKey,
            prompt.commandFingerprint,
            prompt.risk,
            prompt.correlation,
            prompt.serviceGenerationNonce,
        )
}
