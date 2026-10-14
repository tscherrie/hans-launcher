package ai.hans.standard.devicecontrol.tools

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccessibilitySessionRecoveryTest {
    @Test fun connectedServiceWithoutSessionIsRetryableAndDoesNotRequestPermission() {
        val result = AccessibilitySessionRecovery.unavailable(
            AccessibilitySpecialAccessProbe { error("must not read unnecessary probe") },
            AccessibilityServiceConnectionProbe { true },
        )
        val json = JSONObject(result.contentText)
        assertFalse(result.success)
        assertEquals("accessibility_service_reconnecting", json.getString("errorCode"))
        assertTrue(json.getBoolean("retryable"))
        assertFalse(json.has("requiredSpecialAccess"))
    }

    @Test fun grantedSpecialAccessWithoutConnectionIsReconnectingNotDenied() {
        val json = JSONObject(AccessibilitySessionRecovery.unavailable(
            AccessibilitySpecialAccessProbe { true }, AccessibilityServiceConnectionProbe { false },
        ).contentText)
        assertEquals("accessibility_service_reconnecting", json.getString("errorCode"))
        assertTrue(json.getBoolean("specialAccessGranted"))
    }

    @Test fun twoNegativeProbesReallyRequirePermission() {
        val json = JSONObject(AccessibilitySessionRecovery.unavailable(
            AccessibilitySpecialAccessProbe { false }, AccessibilityServiceConnectionProbe { false },
        ).contentText)
        assertEquals("accessibility_permission_required", json.getString("errorCode"))
        assertEquals("accessibility_service", json.getString("requiredSpecialAccess"))
        assertFalse(json.optBoolean("retryable"))
    }

    @Test fun probeFailuresNeverClaimPermissionGrantedOrDeniedOrLeakErrors() {
        for (failure in listOf("connection", "permission", "both")) {
            val result = AccessibilitySessionRecovery.unavailable(
                AccessibilitySpecialAccessProbe {
                    if (failure != "connection") error("sensitive permission detail") else false
                },
                AccessibilityServiceConnectionProbe {
                    if (failure != "permission") error("sensitive connection detail") else false
                },
            )
            val json = JSONObject(result.contentText)
            assertEquals("accessibility_state_unavailable", json.getString("errorCode"))
            assertTrue(json.getBoolean("retryable"))
            assertFalse(json.has("specialAccessGranted"))
            assertFalse(json.has("requiredSpecialAccess"))
            assertFalse(result.contentText.contains("sensitive"))
        }
    }
}
