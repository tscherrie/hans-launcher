package ai.hans.standard

import ai.hans.standard.voice.PendingDictationStore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingDictationStoreTest {
    @Test
    fun queueSurvivesStoreRecreationAndRemovesOnlyAcknowledgedHead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = PendingDictationStore(context)
        firstStore.clear()
        try {
            val first = firstStore.enqueue("  erste Nachricht  ")
            val second = firstStore.enqueue("zweite Nachricht")

            val reopened = PendingDictationStore(context)
            assertEquals(2, reopened.count())
            assertEquals(first.copy(transcript = "erste Nachricht"), reopened.peek())
            assertFalse(reopened.remove("00000000-0000-0000-0000-000000000000"))
            assertTrue(reopened.remove(first.id))
            assertEquals(second, reopened.peek())
            assertTrue(reopened.remove(second.id))
            assertNull(reopened.peek())
        } finally {
            firstStore.clear()
        }
    }

    @Test
    fun rejectsBlankAndOversizedTranscriptsWithoutMutatingQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PendingDictationStore(context)
        store.clear()
        try {
            assertTrue(runCatching { store.enqueue("   ") }.isFailure)
            assertTrue(runCatching { store.enqueue("x".repeat(200_001)) }.isFailure)
            assertEquals(0, store.count())
        } finally {
            store.clear()
        }
    }
}
