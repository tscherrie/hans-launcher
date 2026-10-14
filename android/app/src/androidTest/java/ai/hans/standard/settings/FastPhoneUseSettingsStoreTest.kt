package ai.hans.standard.settings

import ai.hans.standard.settings.fastphone.FastPhoneCredentialStatus
import ai.hans.standard.settings.fastphone.FastPhoneServiceStatus
import ai.hans.standard.settings.fastphone.FastPhoneUseSettingsStore
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastPhoneUseSettingsStoreTest {
    @Test fun encryptedKeyRequiresIndependentOptInAndAccessVerification() = withStores { context, first, _, name ->
        assertFalse(first.isEnabled())
        assertNull(first.readApiKey())
        val key = "sk-test-${"a".repeat(48)}".toCharArray()
        try {
            assertTrue(first.saveApiKey(key))
            assertFalse(first.isEnabled())
            assertEquals(FastPhoneServiceStatus.NOT_VERIFIED, first.snapshot().serviceStatus)
            assertFalse(context.getSharedPreferences("${name}_key", Context.MODE_PRIVATE).all.values
                .any { it?.toString()?.contains(key.concatToString()) == true })
            assertTrue(first.setEnabled(true))
            assertTrue(first.isEnabled())
            val loaded = first.readApiKey()!!
            try { assertTrue(key.contentEquals(loaded)) } finally { loaded.fill('\u0000') }
            first.recordSuccess(first.revision())
            assertTrue(first.snapshot().verifiedActive)
            assertTrue(first.setEnabled(false))
            assertFalse(first.isEnabled())
            assertNull(first.readApiKey())
        } finally { key.fill('\u0000') }
    }

    @Test fun separateInstanceImportNotifiesLauncherAndStaleResponsesCannotVerifyNewKey() = withStores { _, first, second, _ ->
        val updates = mutableListOf<Long>()
        val subscription = first.subscribe { updates += it.revision }
        val firstKey = "sk-test-${"b".repeat(48)}".toCharArray()
        val replacement = "sk-test-${"c".repeat(48)}".toCharArray()
        try {
            assertTrue(second.saveApiKey(firstKey))
            assertTrue(second.setEnabled(true))
            assertTrue(first.isEnabled())
            assertEquals(FastPhoneCredentialStatus.AVAILABLE, first.snapshot().credentialStatus)
            val oldRevision = first.revision()
            first.recordSuccess(oldRevision)
            assertTrue(first.snapshot().verifiedActive)
            assertTrue(second.saveApiKey(replacement))
            assertTrue(first.revision() > oldRevision)
            first.recordSuccess(oldRevision)
            first.recordFailure("fast_phone_key_rejected", oldRevision)
            assertEquals(FastPhoneServiceStatus.NOT_VERIFIED, first.snapshot().serviceStatus)
            val loaded = first.readApiKey()!!
            try { assertTrue(replacement.contentEquals(loaded)) } finally { loaded.fill('\u0000') }
            val revisionAfterImport = first.revision()
            // Queued callbacks for the same ciphertext must not invalidate a newly started task.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            assertEquals(revisionAfterImport, first.revision())
            assertTrue(updates.size >= 4)
        } finally {
            subscription.close(); firstKey.fill('\u0000'); replacement.fill('\u0000')
        }
    }

    @Test fun rejectedOrExhaustedKeyStopsPaidRequestsAndKeyRemovalIsImmediatelyEffective() = withStores { _, first, second, _ ->
        val key = "sk-test-${"d".repeat(48)}".toCharArray()
        try {
            assertTrue(first.saveApiKey(key))
            assertTrue(first.setEnabled(true))
            first.recordFailure("fast_phone_quota_exhausted", first.revision())
            assertFalse(first.isEnabled())
            assertNull(first.readApiKey())
            assertTrue(first.setEnabled(true))
            assertTrue(first.isEnabled())
            first.recordFailure("fast_phone_key_rejected", first.revision())
            assertFalse(first.isEnabled())
            assertTrue(second.removeApiKey())
            assertEquals(FastPhoneCredentialStatus.MISSING, first.snapshot().credentialStatus)
            assertNull(first.readApiKey())
        } finally { key.fill('\u0000') }
    }

    @Test fun invalidReplacementLeavesExistingCredentialAndLegacySpeechNamespaceUntouched() = withStores { context, first, _, name ->
        val speech = AndroidKeystoreSpeechCredentialStore(context, "$name.speech", "${name}_speech")
        val key = "sk-test-${"e".repeat(48)}".toCharArray()
        try {
            speech.saveBearerToken("sk-speech-${"f".repeat(48)}")
            assertTrue(first.saveApiKey(key))
            assertTrue(first.setEnabled(true))
            assertFalse(first.saveApiKey(charArrayOf('x')))
            val loaded = first.readApiKey()!!
            try { assertTrue(key.contentEquals(loaded)) } finally { loaded.fill('\u0000') }
            first.removeApiKey()
            assertEquals("sk-speech-${"f".repeat(48)}", speech.loadBearerToken())
        } finally {
            key.fill('\u0000'); speech.clear()
            context.getSharedPreferences("${name}_speech", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun withStores(block: (Context, FastPhoneUseSettingsStore, FastPhoneUseSettingsStore, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fast_phone_test_${UUID.randomUUID()}"
        val first = FastPhoneUseSettingsStore(context, name, "${name}_key", "$name.key")
        val second = FastPhoneUseSettingsStore(context, name, "${name}_key", "$name.key")
        try { block(context, first, second, name) } finally {
            first.removeApiKey()
            first.close(); second.close()
            context.getSharedPreferences("${name}_key", Context.MODE_PRIVATE).edit().clear().commit()
            listOf(".txt", ".txt.bak", ".txt.new").forEach { suffix ->
                File(context.noBackupFilesDir, name + suffix).delete()
            }
        }
    }
}
