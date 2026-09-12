package ai.hans.standard

import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCredentialKeystoreTest {
    @Test
    fun credentialRoundTripsAsCiphertextAndClearsWithoutPlaintextPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "hans_speech_credential_test_$suffix"
        val store = AndroidKeystoreSpeechCredentialStore(
            context = context,
            keyAlias = "ai.hans.standard.test.speech.$suffix",
            preferencesName = preferencesName,
        )
        val syntheticToken = "sk-test-${"a".repeat(48)}"
        try {
            store.saveBearerToken(syntheticToken)
            assertEquals(syntheticToken, store.loadBearerToken())
            assertEquals(SpeechCredentialStatus.AVAILABLE, store.credentialStatus())
            assertFalse(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                    .all.values.any { it?.toString()?.contains(syntheticToken) == true },
            )

            store.clear()
            assertNull(store.loadBearerToken())
            assertEquals(SpeechCredentialStatus.MISSING, store.credentialStatus())
        } finally {
            store.clear()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun mutableCredentialBoundaryStoresWithoutPersistingPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "hans_speech_credential_chars_test_$suffix"
        val store = AndroidKeystoreSpeechCredentialStore(
            context = context,
            keyAlias = "ai.hans.standard.test.speech.chars.$suffix",
            preferencesName = preferencesName,
        )
        val credential = "sk-test-${"b".repeat(48)}".toCharArray()
        val expected = credential.concatToString()
        try {
            store.saveBearerToken(credential)
            credential.fill('\u0000')
            assertEquals(expected, store.loadBearerToken())
            assertFalse(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                    .all.values.any { it?.toString()?.contains(expected) == true },
            )
        } finally {
            credential.fill('\u0000')
            store.clear()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }
}
