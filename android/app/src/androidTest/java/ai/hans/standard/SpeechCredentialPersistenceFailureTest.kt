package ai.hans.standard

import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCredentialPersistenceFailureTest {
    @Test fun failedSaveSuspendsAllStoreInstancesUntilAConfirmedReplacement() = withStores { first, failing, _, _ ->
        first.saveBearerToken("sk-test-${"a".repeat(48)}")
        assertTrue(runCatching { failing.saveBearerToken("sk-test-${"b".repeat(48)}") }.isFailure)
        assertNull(first.loadBearerToken())
        assertEquals(SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE, first.credentialStatus())
        first.saveBearerToken("sk-test-${"c".repeat(48)}")
        assertEquals("sk-test-${"c".repeat(48)}", first.loadBearerToken())
    }

    @Test fun failedClearRetainsNonexportableKeyAndNeverClaimsCredentialWasRemoved() = withStores { first, failing, _, alias ->
        first.saveBearerToken("sk-test-${"d".repeat(48)}")
        assertTrue(runCatching { failing.clear() }.isFailure)
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertNull(first.loadBearerToken())
        assertEquals(SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE, first.credentialStatus())
        first.clear()
        assertEquals(SpeechCredentialStatus.MISSING, first.credentialStatus())
    }

    private fun withStores(block: (AndroidKeystoreSpeechCredentialStore,
        AndroidKeystoreSpeechCredentialStore, SharedPreferences, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "speech_persistence_test_${UUID.randomUUID()}"
        val alias = "$name.key"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val first = AndroidKeystoreSpeechCredentialStore(context, alias, name)
        val failing = AndroidKeystoreSpeechCredentialStore(context, alias, name,
            preferencesOverride = FailingCommitPreferences(preferences))
        try { block(first, failing, preferences, alias) } finally {
            first.clear()
            preferences.edit().clear().commit()
        }
    }

    private class FailingCommitPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        override fun edit(): SharedPreferences.Editor {
            val editor = actual.edit()
            return object : SharedPreferences.Editor by editor {
                override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    editor.putInt(key, value); return this
                }
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value); return this
                }
                override fun commit(): Boolean = false
            }
        }
    }
}
