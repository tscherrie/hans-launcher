package ai.hans.standard.automations.androidui

import ai.hans.standard.automations.AutomationToolApprovalReceipt
import ai.hans.standard.automations.AutomationToolApprovalRequest
import ai.hans.standard.automations.AutomationToolRisk
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SwappableAutomationToolApprovalProviderTest {
    @Test
    fun explicitFullAccessApprovesEverySupportedMutationWithoutAnActivity() {
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
            now = { now },
        )
        val operations = mapOf(
            "create" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "update" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "enable" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "disable" to AutomationToolRisk.USER_VISIBLE_CHANGE,
            "delete" to AutomationToolRisk.DESTRUCTIVE,
            "run_now" to AutomationToolRisk.EXECUTE_AGENT,
        )
        operations.forEach { (operation, risk) ->
            val request = request(operation, risk)
            val receipt = checkNotNull(router.approve(request))
            assertEquals(request.callId, receipt.callId)
            assertEquals(operation, receipt.operation)
            assertEquals(request.argumentsSha256, receipt.argumentsSha256)
            assertEquals(now, receipt.approvedAt)
            assertEquals(now.plusSeconds(120), receipt.expiresAt)
        }
    }

    @Test
    fun fullAccessNeverOpensAttachedMutationDialogAndMintsFreshReceipts() {
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        val registration = router.attach { throw AssertionError("No extra mutation dialog in full access") }
        val request = request("create", AutomationToolRisk.USER_VISIBLE_CHANGE)
        val first = checkNotNull(router.approve(request))
        val second = checkNotNull(router.approve(request))
        assertNotEquals(first.nonce, second.nonce)
        registration.close()
        router.clear()
        val third = checkNotNull(router.approve(request.copy(callId = "another-call", argumentsSha256 = "b".repeat(64))))
        assertEquals("another-call", third.callId)
        assertEquals("b".repeat(64), third.argumentsSha256)
    }

    @Test
    fun fullAccessDoesNotSelfConfirmARequiredExactRun() {
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        val request = request("confirm", AutomationToolRisk.EXECUTE_AGENT)
        assertNull(router.approve(request))
        val now = Instant.parse("2026-08-27T10:00:00Z")
        val receipt = AutomationToolApprovalReceipt(request.callId, request.operation,
            request.argumentsSha256, now, now.plusSeconds(60), "approval:exact-run-confirmed")
        val registration = router.attach { receipt }
        assertSame(receipt, router.approve(request))
        registration.close()
        assertNull(router.approve(request))
    }

    @Test
    fun fullAccessDoesNotAuthorizeMalformedOrMisclassifiedRequests() {
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        val create = request("create", AutomationToolRisk.USER_VISIBLE_CHANGE)
        assertNull(router.approve(create.copy(callId = " ")))
        assertNull(router.approve(create.copy(argumentsSha256 = "not-a-hash")))
        assertNull(router.approve(create.copy(risk = AutomationToolRisk.DESTRUCTIVE)))
        assertNull(router.approve(create.copy(operation = "unknown")))
    }

    @Test
    fun explicitConfirmationModeStillRequiresTheAttachedProvider() {
        val router = SwappableAutomationToolApprovalProvider(HansPhoneActionPolicy.CONFIRM_ACTIONS)
        assertNull(router.approve(request("run_now", AutomationToolRisk.EXECUTE_AGENT)))
    }

    private fun request(operation: String, risk: AutomationToolRisk) = AutomationToolApprovalRequest(
        callId = "call-$operation",
        operation = operation,
        targetAutomationId = null,
        argumentsSha256 = "a".repeat(64),
        risk = risk,
    )

    @Test
    fun refusesWithoutVisibleProviderAndDetachesExactlyItsProvider() {
        val router = SwappableAutomationToolApprovalProvider()
        val request = AutomationToolApprovalRequest(
            callId = "call-1",
            operation = "create",
            targetAutomationId = null,
            argumentsSha256 = "a".repeat(64),
            risk = AutomationToolRisk.USER_VISIBLE_CHANGE,
        )
        assertNull(router.approve(request))
        val receipt = AutomationToolApprovalReceipt(
            callId = request.callId,
            operation = request.operation,
            argumentsSha256 = request.argumentsSha256,
            approvedAt = Instant.parse("2026-08-23T00:00:00Z"),
            expiresAt = Instant.parse("2026-08-23T00:01:00Z"),
            nonce = "approval:1234567890",
        )
        val registration = router.attach { receipt }
        assertSame(receipt, router.approve(request))
        registration.close()
        assertNull(router.approve(request))
    }
}
