package ai.hans.standard

import ai.hans.standard.voice.PendingDictationDelivery
import ai.hans.standard.voice.PendingDictationStorageException
import ai.hans.standard.voice.PendingDictationStore
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated app-cache fixtures only; never opens/deletes the user's real pending-dictation file. */
@RunWith(AndroidJUnit4::class)
class PendingDictationPersistenceContractTest {
    @Test
    fun partialAndUncertainDraftsSurviveRestartWithoutBecomingRetryable() = withStore { context, store, _ ->
        val partial = store.enqueue("Treffen am", incomplete = true)
        val uncertain = partial.copy(
            delivery = PendingDictationDelivery.OUTCOME_UNKNOWN,
            clientUserMessageId = "dictation-${partial.id}",
        )
        assertTrue(store.update(uncertain))
        val reopened = checkNotNull(PendingDictationStore(context).peek())
        assertEquals(uncertain, reopened)
        assertTrue(reopened.incomplete)
        assertFalse(reopened.canRetry)
    }

    @Test
    fun corruptExistingQueueIsNeverTreatedAsEmptyOrOverwrittenByEnqueue() = withStore { _, store, file ->
        store.enqueue("wichtiger alter Entwurf")
        val corrupt = "{partially-written-existing-private-data".toByteArray()
        file.writeBytes(corrupt)
        assertTrue(runCatching { store.snapshot() }.exceptionOrNull() is PendingDictationStorageException)
        assertTrue(runCatching { store.enqueue("neuer Text") }.isFailure)
        assertTrue(corrupt.contentEquals(file.readBytes()))
    }

    @Test
    fun unreadableExistingQueueIsNotSilentlyReplaced() = withStore { _, store, file ->
        assertTrue(file.mkdir())
        assertTrue(runCatching { store.enqueue("neuer Text") }.exceptionOrNull() is PendingDictationStorageException)
        assertTrue(file.isDirectory)
    }

    @Test
    fun ordinaryReopenDoesNotChangeReceiptButExplicitNewHostRecoveryPersistsUnknown() = withStore { context, store, _ ->
        val original = store.enqueue("Termin erstellen", incomplete = true)
        val unconfirmed = original.copy(
            delivery = PendingDictationDelivery.AWAITING_RECEIPT,
            clientUserMessageId = "dictation-${original.id}",
        )
        assertTrue(store.update(unconfirmed))
        val reopened = PendingDictationStore(context)
        assertEquals(unconfirmed, reopened.peek())
        reopened.markUnconfirmedAfterSessionHostRestart()
        val recovered = checkNotNull(PendingDictationStore(context).peek())
        assertEquals(unconfirmed.copy(delivery = PendingDictationDelivery.OUTCOME_UNKNOWN), recovered)
        assertFalse(recovered.canRetry)
    }

    private fun withStore(action: (Context, PendingDictationStore, File) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(app.cacheDir, "hans-pending-draft-test-${UUID.randomUUID()}")
        check(fixture.mkdir())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = fixture
        }
        try {
            action(isolated, PendingDictationStore(isolated), File(fixture, "pending-dictations-v1.json"))
        } finally {
            check(fixture.deleteRecursively())
        }
    }
}
