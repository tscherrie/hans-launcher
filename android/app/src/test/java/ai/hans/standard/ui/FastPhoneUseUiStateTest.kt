package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.fastphone.FastPhoneCredentialStatus
import ai.hans.standard.settings.fastphone.FastPhoneServiceStatus
import ai.hans.standard.settings.fastphone.FastPhoneUseSettingsSnapshot
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPhoneUseUiStateTest {
    @Test fun projectionRetainsUnverifiedAndRejectedStatesWithoutSecrets() {
        val unverified = FastPhoneUseUiState.from(FastPhoneUseSettingsSnapshot(
            requestedEnabled = true, credentialStatus = FastPhoneCredentialStatus.AVAILABLE,
        ))
        assertTrue(unverified.requestedEnabled)
        assertEquals(FastPhoneServiceStatus.NOT_VERIFIED, unverified.serviceStatus)
        val rejected = FastPhoneUseUiState.from(FastPhoneUseSettingsSnapshot(
            true, FastPhoneCredentialStatus.AVAILABLE, FastPhoneServiceStatus.INVALID_KEY,
        ))
        assertEquals(FastPhoneServiceStatus.INVALID_KEY, rejected.serviceStatus)
        assertEquals(rejected, SettingsUiState(fastPhoneUse = rejected).fastPhoneUse)
    }

    @Test fun englishAndGermanExplainSeparateBillingAndUnknownLocalesDefaultToEnglish() {
        val en = TestResourceTextResolver(Locale.ENGLISH)
        val de = TestResourceTextResolver(Locale.GERMAN)
        val fr = TestResourceTextResolver(Locale.FRENCH)
        assertTrue(en.text(R.string.fast_phone_description).contains("separately billed"))
        assertTrue(de.text(R.string.fast_phone_description).contains("separat abgerechneter"))
        assertEquals(en.text(R.string.fast_phone_description), fr.text(R.string.fast_phone_description))
        assertTrue(en.text(R.string.fast_phone_status_unverified).contains("first screen task"))
        assertTrue(de.text(R.string.fast_phone_status_unverified).contains("ersten Bildschirmauftrag"))
        assertTrue(en.text(R.string.fast_phone_learning_help).contains("No full chats, screenshots or entered text"))
        assertTrue(de.text(R.string.fast_phone_learning_help).contains("Keine vollständigen Chats, Screenshots oder eingegebenen Texte"))
        assertTrue(en.text(R.string.fast_phone_learning_help).contains("ChatGPT login"))
        assertTrue(de.text(R.string.fast_phone_learning_help).contains("ChatGPT-Anmeldung"))
        assertEquals("Forget learned screen goals", en.text(R.string.fast_phone_forget_routines))
        assertEquals("Gelernte Bildschirmziele vergessen", de.text(R.string.fast_phone_forget_routines))
        assertEquals(en.text(R.string.fast_phone_learning_help), fr.text(R.string.fast_phone_learning_help))
    }
}
