package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiNode
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiCoordinateGesture
import ai.hans.standard.phone.accessibility.UiPoint
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilitySecurityTest {
    private val policy = TrustedAndroidAccessibilityRiskPolicy("ai.hans.standard")

    @Test
    fun coordinateGesturesAlwaysElevateToCredentialRisk() {
        val command = AccessibilityCommand.CoordinateGesture(
            key("unknown-gesture"),
            testCorrelation(),
            UiCoordinateGesture(UiPoint(10, 10)),
        )

        assertEquals(
            AccessibilityConfirmationRisk.CREDENTIAL_UI,
            policy.requiredRisk(command, null),
        )
    }

    @Test
    fun labelsElevateSendAndDeleteWithoutTrustingCallerDeclaration() {
        val send = target(text = "Send message")
        val delete = target(contentDescription = "Endgültig löschen")

        assertEquals(
            AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
            policy.requiredRisk(click(send), send),
        )
        assertEquals(
            AccessibilityConfirmationRisk.DESTRUCTIVE,
            policy.requiredRisk(click(delete), delete),
        )
    }

    @Test
    fun passwordProtectedPackageAndSensitiveClassElevateToCredentialRisk() {
        val password = target(role = SemanticUiRole.PASSWORD_FIELD)
        val permissionController = target(packageName = "com.android.permissioncontroller")
        val sensitiveClass = target(className = "example.ConfirmDeviceCredentialActivity")
        val ownUi = target(packageName = "ai.hans.standard")

        listOf(password, permissionController, sensitiveClass, ownUi).forEach { node ->
            assertEquals(
                AccessibilityConfirmationRisk.CREDENTIAL_UI,
                policy.requiredRisk(click(node), node),
            )
        }
    }

    @Test
    fun derivedPolicyCanNeverLowerCallerDeclaredRisk() {
        val neutral = target(text = "Open details")
        val command = AccessibilityCommand.Click(
            key("declared-risk"),
            neutral.handle,
            confirmationRisk = AccessibilityConfirmationRisk.DESTRUCTIVE,
        )

        assertEquals(
            AccessibilityConfirmationRisk.DESTRUCTIVE,
            policy.requiredRisk(command, neutral),
        )
    }

    @Test
    fun neutralSemanticClickNeedsNoExtraRisk() {
        val neutral = target(text = "Open details")

        assertEquals(
            AccessibilityConfirmationRisk.NONE,
            policy.requiredRisk(click(neutral), neutral),
        )
    }

    @Test
    fun oneShotGateRequiresPreauthorizationAndConsumesSuccessfulApprovalExactlyOnce() {
        var now = 100L
        val gate = ExpiringOneShotAccessibilityConfirmationGate({ now })
        val request = request()
        val approval = approval(request)

        assertFalse(gate.isApproved(request, approval))
        gate.authorize(approval, validityMillis = 20)
        assertTrue(gate.isApproved(request, approval))
        assertFalse(gate.isApproved(request, approval))
        now += 1
        assertFalse(gate.isApproved(request, approval))
    }

    @Test
    fun mismatchedApprovalFailsAndConsumesItsAuthorization() {
        val gate = ExpiringOneShotAccessibilityConfirmationGate({ 1_000L })
        val request = request()
        val approval = approval(request)
        gate.authorize(approval)

        val mismatch = approval.copy(commandFingerprint = "b".repeat(64))
        assertFalse(gate.isApproved(request, mismatch))
        assertFalse(gate.isApproved(request, approval))
    }

    @Test
    fun expiredAndClearedApprovalsFailClosed() {
        var now = 10L
        val gate = ExpiringOneShotAccessibilityConfirmationGate({ now })
        val request = request()
        val approval = approval(request)
        gate.authorize(approval, validityMillis = 5)
        now = 16L
        assertFalse(gate.isApproved(request, approval))

        now = 20L
        gate.authorize(approval, validityMillis = 5)
        gate.clear()
        assertFalse(gate.isApproved(request, approval))
        assertThrows(IllegalArgumentException::class.java) {
            gate.authorize(approval, validityMillis = 0)
        }
    }

    @Test
    fun concurrentConsumersCanUseAnApprovalAtMostOnce() {
        val gate = ExpiringOneShotAccessibilityConfirmationGate({ 100L })
        val request = request()
        val approval = approval(request)
        gate.authorize(approval)
        val start = CountDownLatch(1)
        val done = CountDownLatch(12)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())

        repeat(12) {
            Thread {
                start.await()
                results += gate.isApproved(request, approval)
                done.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, results.count { it })
        assertEquals(11, results.count { !it })
    }

    @Test
    fun prohibitedAutomationTargetsAreLocallyExcluded() {
        val own = locator(target(packageName = "ai.hans.standard"))
        val installer = locator(target(packageName = "com.google.android.packageinstaller"))
        val biometric = locator(target(className = "example.BiometricPromptActivity"))
        val neutral = locator(target(packageName = "com.example.notes"))

        assertTrue(AndroidAutomationExclusions.isProhibited("ai.hans.standard", own))
        assertTrue(AndroidAutomationExclusions.isProhibited("ai.hans.standard", installer))
        assertTrue(AndroidAutomationExclusions.isProhibited("ai.hans.standard", biometric))
        assertFalse(AndroidAutomationExclusions.isProhibited("ai.hans.standard", neutral))
    }

    private fun target(
        text: String? = null,
        contentDescription: String? = null,
        packageName: String = "com.example.app",
        className: String = "android.widget.Button",
        role: SemanticUiRole = SemanticUiRole.BUTTON,
    ): SemanticUiNode = semanticSnapshot(
        roots = listOf(
            rawNode(
                text = text,
                contentDescription = contentDescription,
                packageName = packageName,
                className = className,
                role = role,
                bounds = UiBounds(10, 10, 200, 100),
                clickable = true,
                editable = role == SemanticUiRole.PASSWORD_FIELD,
                actions = if (role == SemanticUiRole.PASSWORD_FIELD) {
                    setOf(SemanticUiAction.SET_TEXT)
                } else {
                    setOf(SemanticUiAction.CLICK)
                },
            ),
        ),
    ).nodes.single()

    private fun click(target: SemanticUiNode) = AccessibilityCommand.Click(
        key("policy-click"),
        target.handle,
    )

    private fun locator(node: SemanticUiNode) = AndroidNodeLocator(
        nodeOrdinal = node.handle.nodeOrdinal,
        displayId = 0,
        windowId = node.handle.correlation.windowId.value,
        uniqueId = "security-target-id",
        viewIdResourceName = null,
        structuralPath = emptyList(),
        fingerprint = AndroidNodeFingerprint(
            packageName = node.packageName?.value,
            className = node.className?.value,
            role = node.role,
            bounds = node.bounds,
            visible = node.visible,
            enabled = node.enabled,
            clickable = node.clickable,
            editable = node.editable,
            scrollable = node.scrollable,
            actions = node.actions,
        ),
    )

    private fun request() = AccessibilityConfirmationRequest(
        idempotencyKey = key("approval-key"),
        commandFingerprint = "a".repeat(64),
        risk = AccessibilityConfirmationRisk.DESTRUCTIVE,
        correlation = testCorrelation(),
    )

    private fun approval(request: AccessibilityConfirmationRequest) = AccessibilityUserApproval(
        approvalId = "approval-security-0001",
        idempotencyKey = request.idempotencyKey,
        commandFingerprint = request.commandFingerprint,
        risk = request.risk,
        correlation = request.correlation,
    )

    private fun key(value: String) = AccessibilityIdempotencyKey(value.padEnd(8, '-'))
}
