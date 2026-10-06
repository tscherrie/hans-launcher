package ai.hans.standard.diagnostics

import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolProtocol
import ai.hans.standard.codex.ServerRequestId
import ai.hans.standard.phone.accessibility.android.AccessibilitySnapshotFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureDiagnosticTest {
    @Test
    fun exactAllowlistedCodesAndDetailsRoundTrip() {
        ToolFailureCode.entries.forEach { assertEquals(it, ToolFailureCode.fromCode(it.wire)) }
        ToolFailureDetail.entries.forEach { assertEquals(it, ToolFailureDetail.fromCode(it.wire)) }
        assertEquals(ToolFailureCode.entries.size, ToolFailureCode.entries.map { it.wire }.distinct().size)
        assertEquals(ToolFailureDetail.entries.size, ToolFailureDetail.entries.map { it.wire }.distinct().size)
    }

    @Test
    fun unknownEvenSyntacticallySafeValuesAreDiscarded() {
        listOf(
            "private_contact_name", "confirmation_required_private_contact_name",
            "visual_capture_failed_private_contact_name", "VISUAL_CAPTURE_FAILED",
            " visual_capture_failed", "visual_capture_failed\n", "fallback:secret-token",
            "someone@example.com", "https://private.example/path", "a".repeat(50_000),
        ).forEach { input ->
            val diagnostic = ToolFailureDiagnostic.fromCodes(input, input)
            assertEquals(ToolFailureDiagnostic(ToolFailureCode.UNKNOWN, ToolFailureDetail.UNKNOWN), diagnostic)
            assertFalse(diagnostic.toString().contains(input))
        }
        assertEquals(ToolFailureCode.UNKNOWN, ToolFailureDiagnostic.fromCodes(null).code)
        assertNull(ToolFailureDiagnostic.fromCodes(null).detail)
    }

    @Test
    fun exactKnownConfirmationVariantsNormalizeWithoutAcceptingArbitrarySuffixes() {
        listOf("none", "external_communication", "destructive", "credential_ui").forEach {
            assertEquals(ToolFailureCode.CONFIRMATION_REQUIRED, ToolFailureCode.fromCode("confirmation_required_$it"))
        }
        assertEquals(ToolFailureCode.UNKNOWN, ToolFailureCode.fromCode("confirmation_required_secret"))
    }

    @Test
    fun everyExistingSnapshotFailureIsExplicitlyRepresented() {
        AccessibilitySnapshotFailure.entries.forEach {
            assertTrue(ToolFailureDetail.fromCode(it.detailCode) != ToolFailureDetail.UNKNOWN)
        }
    }

    @Test
    fun diagnosticFieldsCannotRetainUnboundedContent() {
        val instanceFields = ToolFailureDiagnostic::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        assertEquals(setOf(ToolFailureCode::class.java, ToolFailureDetail::class.java), instanceFields.map { it.type }.toSet())
        (ToolFailureCode.entries.map { it.wire } + ToolFailureDetail.entries.map { it.wire }).forEach {
            assertTrue(it.matches(Regex("[a-z][a-z0-9_]{0,79}")))
        }
    }

    @Test
    fun metadataDoesNotChangeExactWireBytes() {
        val original = DynamicToolExecutionResult(
            contentText = "{\"errorCode\":\"semantic_fallback_proof_required\",\"status\":\"failed\"}",
            success = false,
        )
        val enriched = original.copy(failureDiagnostic = ToolFailureDiagnostic(
            ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, ToolFailureDetail.PROOF_EXPIRED,
        ))
        val id = ServerRequestId.Number(81)
        assertEquals(original.contentText, enriched.contentText)
        assertEquals(original.imageUrls, enriched.imageUrls)
        assertEquals(original.success, enriched.success)
        assertEquals(DynamicToolProtocol.response(id, original), DynamicToolProtocol.response(id, enriched))
        assertFalse(DynamicToolProtocol.response(id, enriched).contains("proof_expired"))
    }
}
