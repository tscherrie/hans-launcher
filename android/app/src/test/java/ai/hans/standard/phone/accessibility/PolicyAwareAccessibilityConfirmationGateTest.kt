package ai.hans.standard.phone.accessibility

import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAwareAccessibilityConfirmationGateTest {
    @Test
    fun narrowerModeStillRequiresAnExactlyBoundUserApproval() {
        val gate = PolicyAwareAccessibilityConfirmationGate(ExactAccessibilityUserConfirmationGate)
        val request = request(AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION)
        val approval = AccessibilityUserApproval(
            "approval-synthetic",
            request.idempotencyKey,
            request.commandFingerprint,
            request.risk,
            request.correlation,
        )

        assertFalse(gate.isApproved(request, null))
        assertTrue(gate.isApproved(request, approval))
        assertFalse(gate.isApproved(request, approval.copy(commandFingerprint = "b".repeat(64))))
        assertFalse(gate.isApproved(request, approval.copy(risk = AccessibilityConfirmationRisk.DESTRUCTIVE)))
    }

    @Test
    fun fullAccessNeedsNoAdditionalApprovalForAnyCorrelatedRisk() {
        val gate = PolicyAwareAccessibilityConfirmationGate(
            delegate = AccessibilityUserConfirmationGate { _, _ ->
                error("Full-access execution must not request another Hans approval")
            },
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )

        AccessibilityConfirmationRisk.entries.forEach { risk ->
            assertTrue(risk.name, gate.isApproved(request(risk), null))
        }
    }

    @Test
    fun fullAccessDoesNotInventAMissingUiCorrelation() {
        val gate = PolicyAwareAccessibilityConfirmationGate(
            delegate = AccessibilityUserConfirmationGate { _, _ -> error("No prompt is allowed") },
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )

        assertFalse(gate.isApproved(request(AccessibilityConfirmationRisk.CREDENTIAL_UI).copy(correlation = null), null))
    }

    @Test
    fun productServiceSelectsFullAccessOnlyAtTheConfirmationBoundary() {
        val relative = "src/main/java/ai/hans/standard/phone/accessibility/android/HansAccessibilityService.kt"
        val source = listOf(File(relative), File("android/app/$relative"), File("app/$relative"))
            .first(File::isFile).readText()
        val executorWiring = source.substringAfter("val domainExecutor = AccessibilityCommandExecutor(")
            .substringBefore("BoundedAccessibilityCommandSession(")

        assertTrue(executorWiring.contains("confirmationGate = PolicyAwareAccessibilityConfirmationGate("))
        assertTrue(executorWiring.contains("delegate = confirmationGate"))
        assertTrue(executorWiring.contains("actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS"))
        assertTrue(executorWiring.contains("snapshots = snapshots"))
        assertTrue(executorWiring.contains("adapter = androidAdapter"))
        assertTrue(executorWiring.contains("riskPolicy = TrustedAndroidAccessibilityRiskPolicy(packageName)"))
        assertTrue(executorWiring.contains("dictationGuard = AndroidDictationLifecycleRegistry"))
        assertTrue(executorWiring.contains("uiAvailability = uiAvailability"))
    }

    private fun request(risk: AccessibilityConfirmationRisk) = AccessibilityConfirmationRequest(
        idempotencyKey = AccessibilityIdempotencyKey("action-synthetic"),
        commandFingerprint = "a".repeat(64),
        risk = risk,
        correlation = UiSnapshotCorrelation(
            AccessibilitySessionId("session-synthetic"),
            AccessibilityWindowId(1),
            AccessibilitySnapshotId(1),
        ),
    )
}
