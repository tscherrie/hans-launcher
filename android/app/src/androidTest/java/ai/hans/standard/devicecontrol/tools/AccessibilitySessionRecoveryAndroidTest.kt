package ai.hans.standard.devicecontrol.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySessionRecoveryAndroidTest {
    @Test fun freshEffectiveStateChangesNeverReusePreviousPermissionDiagnosis() {
        var enabled = true
        var connected = false
        var reads = 0
        fun response() = JSONObject(AccessibilitySessionRecovery.unavailable(
            AccessibilitySpecialAccessProbe { reads++; enabled },
            AccessibilityServiceConnectionProbe { connected },
        ).contentText)
        assertEquals("accessibility_service_reconnecting", response().getString("errorCode"))
        enabled = false
        assertEquals("accessibility_permission_required", response().getString("errorCode"))
        connected = true
        val restored = response()
        assertEquals("accessibility_service_reconnecting", restored.getString("errorCode"))
        assertTrue(restored.getBoolean("retryable"))
        assertFalse(restored.has("requiredSpecialAccess"))
        assertEquals(2, reads)
    }

    @Test fun unknownPlatformProbeCannotBecomePermissionDeniedOrLeakExceptionText() {
        val response = AccessibilitySessionRecovery.unavailable(
            AccessibilitySpecialAccessProbe { error("private platform state") },
            AccessibilityServiceConnectionProbe { false },
        )
        val json = JSONObject(response.contentText)
        assertFalse(response.success)
        assertEquals("accessibility_state_unavailable", json.getString("errorCode"))
        assertTrue(json.getBoolean("retryable"))
        assertFalse(json.has("requiredSpecialAccess"))
        assertFalse(json.has("specialAccessGranted"))
        assertFalse(response.contentText.contains("private"))
    }
}
