package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolCatalog
import ai.hans.standard.diagnostics.MeasuredDynamicToolExecutor
import ai.hans.standard.diagnostics.ToolPerformanceRecorder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versioned UI output intentionally changes the exact description contract so old output-
 * parsing assumptions are not silently reused. Callable input schemas stay unchanged.
 * Baseline: metadata-only CatalogContractBaseline runner, 2026-09-12, compiled
 * production classes from the shared-files build before the compact-output changes.
 * These are android_ui-only fingerprints, not claims about the entire installed catalog.
 */
class AndroidUiCatalogContinuityTest {
    @Test
    fun versionedOutputDescriptionIntentionallyTriggersExactContractMigration() {
        val current = DynamicToolContractFingerprint.compute(listOf(AndroidAccessibilityDynamicToolCatalog.namespace))
        assertNotEquals(
            "The compact response contract must be introduced explicitly to a fresh model context",
            BASELINE_FULL_FINGERPRINT,
            current,
        )
        assertEquals(VERSIONED_FULL_FINGERPRINT, current)
    }

    @Test
    fun versionedOutputMigrationPreservesTheCallableAndroidUiStructure() {
        assertEquals(
            BASELINE_STRUCTURE_FINGERPRINT,
            DynamicToolContractFingerprint.computeStructure(listOf(AndroidAccessibilityDynamicToolCatalog.namespace)),
        )
    }

    @Test
    fun optionalPerformanceRecordingCannotAlterTheCatalogOrResponse() {
        val expectedResult = DynamicToolExecutionResult("{\"status\":\"ok\"}", true)
        val delegate = object : DynamicToolExecutor {
            override val specs = listOf(AndroidAccessibilityDynamicToolCatalog.namespace)
            override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
                completion(expectedResult)
            }
            override fun failureResult(call: DynamicToolCallParams, code: String) = expectedResult
        }
        val measured = MeasuredDynamicToolExecutor(delegate, ToolPerformanceRecorder())
        assertSame(delegate.specs, measured.specs)
        assertEquals(
            DynamicToolContractFingerprint.compute(delegate.specs),
            DynamicToolContractFingerprint.compute(measured.specs),
        )
        assertEquals(BASELINE_STRUCTURE_FINGERPRINT, DynamicToolContractFingerprint.computeStructure(measured.specs))

        var actual: DynamicToolExecutionResult? = null
        measured.execute(DynamicToolCallParams("test-thread", "test-turn", "test-call", "android_ui", "inspect_ui", "{}")) {
            actual = it
        }
        assertSame(expectedResult, actual)
    }

    @Test
    fun versionedDescriptionExplainsCompactDefaultsAndReturnedObservation() {
        val description = AndroidAccessibilityDynamicToolCatalog.namespace.description
        listOf(
            "projectionFormat=compact_nodes_v1",
            "{...nodeDefaults, ...node}",
            "explicit fields win",
            "handles stay complete",
            "Small views may stay inline",
            "A verified action can return nextObservation",
            "reuse it instead of inspect_ui unless a fresh view is needed",
            "If unavailable, action is not undone",
            "never repeat mutation just for observation",
        ).forEach { marker ->
            assertTrue("Missing explicit compact-output contract marker: $marker", marker in description)
        }
    }

    @Test
    fun returnedStructuredObservationCanSatisfyTheExistingVerificationInstruction() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val relative = "android/app/src/main/assets/hans/developer-instructions-standard.md"
        val instructions = generateSequence(start) { it.parentFile }
            .map { File(it, relative) }.firstOrNull(File::isFile)
            ?.readText()?.replace(Regex("\\s+"), " ")
            ?: error("Missing Hans Standard developer instructions")
        assertTrue("verify the visible or structured result after an action" in instructions)
        val lower = instructions.lowercase()
        listOf(
            "after every action", "after each action", "after any action", "nach jeder aktion",
        ).forEach { phrase ->
            assertFalse(
                "Do not require another inspect_ui when the action already returned a usable observation",
                Regex("(?:inspect_ui.{0,80}${Regex.escape(phrase)}|${Regex.escape(phrase)}.{0,80}inspect_ui)")
                    .containsMatchIn(lower),
            )
        }
        assertTrue(
            "For retryable=true, call inspect_ui exactly once more." in
                AndroidAccessibilityDynamicToolCatalog.namespace.description,
        )
    }

    private companion object {
        const val VERSIONED_FULL_FINGERPRINT = "610b3ed448f67a4b5ab7c552baa936cc226d3ffa6c3ac3eb7d1b02bf0458f2c1"
        const val BASELINE_FULL_FINGERPRINT = "3eaca8a07828a85e8858ed378e60a38239f6607c3124f2cf0b6daafdcfda6cef"
        const val BASELINE_STRUCTURE_FINGERPRINT = "0148ce067d3768ad9644d2b032a6488db9ceb0891293b77c526a99757891daa3"
    }
}
