package ai.hans.standard.diagnostics

import ai.hans.standard.phone.keys.KeySemanticAction
import ai.hans.standard.phone.keys.KeyTestFixtures
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelShortcutDiagnosticsTest {
    @Test
    fun exposesOnlySavedModelGestureWithoutPrivateDeviceIdentityOrKeyHistory() {
        val mapping = KeyTestFixtures.mapping().copy(
            keyCode = 63, scanCode = 249, metaState = 4,
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        )
        val encoded = ModelShortcutDiagnostics.encode(mapping)
        val payload = JSONObject(encoded)
        assertTrue(payload.getBoolean("configured"))
        assertEquals(63, payload.getInt("keyCode"))
        assertEquals(249, payload.getInt("scanCode"))
        assertEquals("hans_foreground_only", payload.getString("scope"))
        assertEquals("gpt-6-astra/ultra", payload.getString("highPreset"))
        assertEquals("gpt-6-luna/max", payload.getString("lowPreset"))
        assertEquals("gpt-5.6-luna/max", payload.getString("lowPresetFallback"))
        assertFalse(encoded.contains(KeyTestFixtures.DEVICE.descriptorSha256))
        assertFalse(encoded.contains("runtimeDeviceId"))
        assertFalse(encoded.contains("vendorId"))
        assertFalse(encoded.contains("eventTime"))
    }

    @Test
    fun absentOrDifferentActionCannotBecomeAModelMapping() {
        for (mapping in listOf(null, KeyTestFixtures.mapping())) {
            val payload = JSONObject(ModelShortcutDiagnostics.encode(mapping))
            assertFalse(payload.getBoolean("configured"))
            assertTrue(payload.isNull("keyCode"))
            assertTrue(payload.isNull("scanCode"))
        }
    }
}
