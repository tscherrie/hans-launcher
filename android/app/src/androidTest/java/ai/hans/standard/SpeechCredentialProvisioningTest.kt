package ai.hans.standard

import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installer-only hook. The secret is supplied as an instrumentation argument,
 * encrypted immediately, never logged, and is absent from every production APK.
 */
@RunWith(AndroidJUnit4::class)
class SpeechCredentialProvisioningTest {
    @Test
    fun provisionWhenInstallerSuppliesCredentialOtherwiseRemainANoOp() {
        val supplied = InstrumentationRegistry.getArguments().getString(ARGUMENT_NAME)
        if (supplied == null) {
            assertTrue(true)
            return
        }
        val store = AndroidKeystoreSpeechCredentialStore(
            ApplicationProvider.getApplicationContext(),
        )
        store.saveBearerToken(supplied)
        assertTrue(store.hasCredential())
    }

    companion object {
        const val ARGUMENT_NAME = "openaiSpeechKey"
    }
}
