package ai.hans.standard.integration

import ai.hans.standard.devicecontrol.tools.AccessibilityServiceConnectionProbe
import ai.hans.standard.devicecontrol.tools.AccessibilitySessionRecovery
import ai.hans.standard.diagnostics.ToolFailureCode
import ai.hans.standard.phone.capabilities.AccessibilityGrantSetting
import ai.hans.standard.phone.capabilities.AccessibilityGrantState
import ai.hans.standard.phone.capabilities.readAccessibilityGrantState
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccessibilityToolAccessProbeTest {
    @Test fun productionProbeReadsEveryTimeAndDistinguishesGrantDenialAndUnknown() {
        var state = AccessibilityGrantState.GRANTED
        var reads = 0
        val probe = accessibilityToolAccessProbe { reads++; state }
        assertTrue(probe.isEnabled())
        state = AccessibilityGrantState.NOT_GRANTED
        assertFalse(probe.isEnabled())
        state = AccessibilityGrantState.UNKNOWN
        assertTrue(runCatching { probe.isEnabled() }.isFailure)
        assertEquals(3, reads)
    }

    @Test fun actualPublicSettingUnknownCannotBecomePermissionDeniedWhenServiceIsDisconnected() {
        val privateError = "private provider failure must not be exported"
        val sources: List<() -> AccessibilityGrantSetting?> = listOf(
            { null },
            { error(privateError) },
        )
        sources.forEach { source ->
            val probe = accessibilityToolAccessProbe {
                readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT, source)
            }
            val result = AccessibilitySessionRecovery.unavailable(probe, AccessibilityServiceConnectionProbe { false })
            val json = JSONObject(result.contentText)
            assertFalse(result.success)
            assertEquals("accessibility_state_unavailable", json.getString("errorCode"))
            assertEquals(ToolFailureCode.ACCESSIBILITY_STATE_UNAVAILABLE, result.failureDiagnostic?.code)
            assertTrue(json.getBoolean("retryable"))
            assertFalse(json.has("specialAccessGranted"))
            assertFalse(json.has("requiredSpecialAccess"))
            assertFalse(result.contentText.contains(privateError))
        }
    }

    @Test fun actualPublicSettingGrantAndConfirmedAbsenceRetainDistinctRecoveryResults() {
        listOf(
            FULL_COMPONENT to "accessibility_service_reconnecting",
            SHORT_COMPONENT to "accessibility_service_reconnecting",
            null to "accessibility_permission_required",
        ).forEach { (setting, expected) ->
            val probe = accessibilityToolAccessProbe {
                readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) { AccessibilityGrantSetting(setting) }
            }
            val result = AccessibilitySessionRecovery.unavailable(probe, AccessibilityServiceConnectionProbe { false })
            assertEquals(expected, JSONObject(result.contentText).getString("errorCode"))
        }
    }

    @Test fun mainSessionWiresTheTriStateProbeRatherThanLossyBooleanCapabilityProjection() {
        val sourceFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "android/app/src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt") }
            .first { it.isFile }
        val source = sourceFile.readText().replace(Regex("\\s+"), " ")
        assertTrue(source.contains(
            "accessibilitySpecialAccess = accessibilityToolAccessProbe(capabilityEnvironment::accessibilityGrantState)"))
    }

    private companion object {
        const val FULL_COMPONENT = "ai.hans.standard/ai.hans.standard.phone.accessibility.android.HansAccessibilityService"
        const val SHORT_COMPONENT = "ai.hans.standard/.phone.accessibility.android.HansAccessibilityService"
    }
}
