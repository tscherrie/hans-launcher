package ai.hans.standard.phone.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp01AndComposerKeyPolicyTest {
    @Test
    fun mp01AdapterIsInertUntilEmpiricalDeviceScanCodesAreConfigured() {
        val event = KeyTestFixtures.event(scanCode = 173, keyCode = KeyEvent.KEYCODE_SYM)
        assertNull(Mp01OptionalKeyAdapter(profile = null).recognize(event))

        val profile = Mp01EmpiricalKeyProfile(
            device = KeyTestFixtures.DEVICE.selector(),
            actionScanCode = 172,
            symScanCode = 173,
        )
        val adapter = Mp01OptionalKeyAdapter(profile)
        assertNull(adapter.recognize(event.copy(scanCode = 999)))
        assertNull(adapter.recognize(event.copy(physicalDevice = KeyTestFixtures.OTHER_DEVICE)))
        assertEquals(Mp01ObservedControl.SYM_KEY, adapter.recognize(event))

        val mapping = adapter.mappingFromObservedDown(
            event = event,
            mappingId = "mp01-sym",
            actionTrigger = ActionKeyTrigger.HOLD_TO_TALK,
        )!!
        assertEquals(173, mapping.scanCode)
        assertEquals(ActionKeyTrigger.PRESS, mapping.trigger)
        assertEquals(KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA, mapping.action)
    }

    @Test
    fun altPrintableCharacterUsesAndroidUnicodeVerbatimInHansComposerOnly() {
        val altAt = KeyTestFixtures.event(
            scanCode = 30,
            keyCode = KeyEvent.KEYCODE_A,
            metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            unicodeChar = '@'.code,
        )
        val translation = ComposerKeyTranslationPolicy.translate(altAt)
            as ComposerKeyTranslation.InsertAndroidUnicode

        assertEquals('@'.code, translation.rawUnicodeChar)
        assertEquals("@", translation.text)
        assertEquals(altAt.metaState, translation.metaState)
        assertFalse(ComposerKeyTranslationPolicy.CAN_REMAP_OTHER_APPS_GLOBALLY)
        assertFalse(RootFreeActionKeyCapability.CAN_REMAP_OTHER_APPS_GLOBALLY)
        assertFalse(RootFreeActionKeyCapability.REQUIRES_ROOT)
        assertTrue(RootFreeActionKeyCapability.REQUIRES_USER_ENABLED_ACCESSIBILITY)
        assertTrue(RootFreeActionKeyCapability.CAN_TRIGGER_DICTATION_ACROSS_APPS)
    }

    @Test
    fun nonPrintableAndCombiningUnicodeStayWithAndroid() {
        assertEquals(
            ComposerKeyPassReason.NO_PRINTABLE_ANDROID_UNICODE,
            (ComposerKeyTranslationPolicy.translate(KeyTestFixtures.event(unicodeChar = 0))
                as ComposerKeyTranslation.PassToAndroid).reason,
        )
        assertEquals(
            ComposerKeyPassReason.ANDROID_COMBINING_ACCENT,
            (ComposerKeyTranslationPolicy.translate(
                KeyTestFixtures.event(unicodeChar = Int.MIN_VALUE or '\''.code),
            ) as ComposerKeyTranslation.PassToAndroid).reason,
        )
        assertEquals(
            ComposerKeyPassReason.NOT_KEY_DOWN,
            (ComposerKeyTranslationPolicy.translate(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 110L,
                    unicodeChar = 'x'.code,
                ),
            ) as ComposerKeyTranslation.PassToAndroid).reason,
        )
    }
}
