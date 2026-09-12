package ai.hans.standard.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileRepositoryTest {
    @Test
    fun interviewIsResumableAndDoesNotReplaceConfirmedProfileBeforeConfirmation() {
        val storage = MemoryStorage(
            UserProfileDocument(confirmedSummary = "Existing facts", updatedAtMillis = 1),
        )
        val repository = repository(storage)

        repository.beginInterview()
        repository.recordAnswer(ProfileAnswer("name", "Wie soll ich dich nennen?", "Alex"))
        val proposed = repository.proposeSummary("Name: Alex")

        assertEquals("Existing facts", proposed.confirmedSummary)
        assertTrue(proposed.interviewActive)
        assertEquals(1, proposed.draftAnswers.size)
        assertEquals("nonce_1234567890", proposed.confirmationNonce)

        val restored = repository(storage).read()
        assertEquals(proposed, restored)
        val confirmed = repository(storage).confirm("nonce_1234567890", true)
        assertEquals("Name: Alex", confirmed.confirmedSummary)
        assertFalse(confirmed.interviewActive)
        assertNull(confirmed.proposedSummary)
    }

    @Test
    fun explicitConfirmationAndCurrentNonceAreRequired() {
        val repository = repository(MemoryStorage())
        repository.beginInterview()
        repository.recordAnswer(ProfileAnswer("work", "Woran arbeitest du?", "Hans"))
        repository.proposeSummary("Projekt: Hans")

        assertTrue(runCatching { repository.confirm("nonce_1234567890", false) }.isFailure)
        assertTrue(runCatching { repository.confirm("stale_nonce_123", true) }.isFailure)
        assertFalse(repository.read().hasConfirmedProfile)
    }

    @Test
    fun beginInterviewResumesExistingDraftAndPendingReviewWithoutRevisionChurn() {
        val repository = repository(MemoryStorage())
        repository.beginInterview()
        repository.recordAnswer(ProfileAnswer("family", "Wer gehoert zu dir?", "Meine Familie"))
        val proposed = repository.proposeSummary("Familie ist wichtig")

        val resumed = repository.beginInterview()

        assertEquals(proposed, resumed)
        assertEquals(1, resumed.draftAnswers.size)
        assertEquals("Familie ist wichtig", resumed.proposedSummary)
        assertEquals("nonce_1234567890", resumed.confirmationNonce)
    }

    @Test
    fun topicUpdateIsIdempotentAndDeleteRequiresConsent() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        repository.beginInterview()
        repository.recordAnswer(ProfileAnswer("language", "Welche Sprache?", "Deutsch"))
        val updated = repository.recordAnswer(
            ProfileAnswer("language", "Welche Sprachen?", "Deutsch und Englisch"),
        )
        assertEquals(1, updated.draftAnswers.size)
        assertEquals("Deutsch und Englisch", updated.draftAnswers.single().answer)

        assertTrue(runCatching { repository.delete(false) }.isFailure)
        repository.delete(true)
        assertEquals(UserProfileDocument(), repository.read())
    }

    private fun repository(storage: MemoryStorage) = UserProfileRepository(
        storage = storage,
        clock = ProfileClock { 42 },
        nonces = ProfileNonceSource { "nonce_1234567890" },
    )

    private class MemoryStorage(
        private var document: UserProfileDocument = UserProfileDocument(),
    ) : UserProfileStorage {
        override fun read(): UserProfileDocument = document

        override fun write(document: UserProfileDocument) {
            this.document = document
        }

        override fun clear() {
            document = UserProfileDocument()
        }
    }
}
