package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.facts.*
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationFactMemoryProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun noInformativeTermsMeansNoArchiveQuery() {
        val repo = Repo()
        assertTrue(NotificationFactMemoryProjection(repo).candidates(envelope("new notification update")).isEmpty())
        assertEquals(0, repo.queries)
    }

    @Test fun relevantArchiveContextCrossesAppBoundariesWithoutInventingSender() {
        val repo = Repo(listOf(fact("Festival ist am Samstag", "synthetic.chat")))
        val result = NotificationFactMemoryProjection(repo).candidates(envelope("Festival ist da", "synthetic.calendar"))
        assertEquals(1, repo.queries)
        assertEquals(listOf("Festival ist am Samstag"), result)
        assertNull(repo.lastQuery!!.packageName)
        assertEquals(2, repo.lastQuery!!.limit)
        assertEquals(8192, repo.lastQuery!!.maxUtf8Bytes)
        assertEquals(listOf("festival"), repo.lastQuery!!.terms)
    }

    @Test fun unavailableArchiveOnlyRemovesAdditionalHints() {
        val repo = Repo().also { it.unavailable = true }
        // Both sources match the event, but their information terms do not overlap:
        // profile precedence must not legitimately suppress this native-memory hint.
        val profile = "Garten ist interessant"
        val native = "Festival Musik bevorzugt"
        val notification = envelope("Festival Musik und Garten")
        val home = home(native)
        val expected = CodexNotificationMemoryProjection(home,
            confirmedProfileSource = { profile }).project(notification)
        assertEquals(listOf(profile), expected.confirmedProfileFacts)
        assertEquals(listOf(native), expected.unverifiedMemoryHints)
        val result = CodexNotificationMemoryProjection(home,
            confirmedProfileSource = { profile },
            additionalUnverifiedCandidateSource = NotificationFactMemoryProjection(repo)::candidates)
            .project(notification)
        assertEquals(1, repo.queries)
        assertEquals(listOf("festival", "musik", "garten"), repo.lastQuery!!.terms)
        assertEquals(expected.toClassificationJson().toString(), result.toClassificationJson().toString())
        assertEquals(listOf(profile), result.confirmedProfileFacts)
        assertEquals(listOf(native), result.unverifiedMemoryHints)
    }

    @Test fun arbitraryAdditionalFailureDoesNotDiscardNativeProjection() {
        val home = home("Festival Musik bevorzugt")
        val expected = CodexNotificationMemoryProjection(home).project(envelope("Festival"))
        val actual = CodexNotificationMemoryProjection(home,
            additionalUnverifiedCandidateSource = { error("synthetic unavailable") }).project(envelope("Festival"))
        assertEquals(expected.toClassificationJson().toString(), actual.toClassificationJson().toString())
    }

    @Test fun totalHintCountAndSerializedBudgetRemainUnchanged() {
        val home = home("Festival Musik bekannt\nFestival Garten bekannt\nFestival Reise bekannt")
        val result = CodexNotificationMemoryProjection(home,
            additionalUnverifiedCandidateSource = {
                listOf("Festival Sport bekannt", "Festival Theater bekannt", "Festival hidden")
            }).project(envelope("Festival"))
        assertTrue(result.facts.size <= 4)
        assertTrue(result.toClassificationJson().toString().toByteArray(StandardCharsets.UTF_8).size <= 1024)
        assertFalse(result.facts.any { it.contains("hidden") })
    }

    @Test fun confirmedProfileOutranksContradictoryArchiveClaim() {
        val result = CodexNotificationMemoryProjection(home(),
            confirmedProfileSource = { "Festival findet Sonntag statt" },
            additionalUnverifiedCandidateSource = { listOf("Festival findet Samstag statt") })
            .project(envelope("Festival"))
        assertTrue(result.confirmedProfileFacts.any { it.contains("Sonntag") })
        assertTrue(result.unverifiedMemoryHints.none { it.contains("Samstag") })
    }

    @Test fun withheldPrivateDetailGuardSurvivesSingleRawCandidateMix() {
        val notification = envelope("Festival")
        val result = CodexNotificationMemoryProjection(home(),
            additionalUnverifiedCandidateSource = { listOf("Festival beginnt morgen in Rosenheim") })
            .project(notification)
        assertFalse(result.toSynthesisJson().toString().contains("Rosenheim"))
        assertTrue(result.couldRevealWithheldMemoryDetail(
            "Das Festival ist morgen in Rosenheim.", notification, NotificationEnrichmentEvidence.EMPTY))
        assertFalse(result.couldRevealWithheldMemoryDetail(
            "Das Festival ist wichtig.", notification, NotificationEnrichmentEvidence.EMPTY))
    }

    @Test fun unrelatedOrOversizedRepositoryResultsDoNotBecomeHints() {
        val repo = Repo(listOf(fact("Gartenfest am Samstag")))
        assertTrue(NotificationFactMemoryProjection(repo).candidates(envelope("Festival")).isEmpty())
        repo.facts = listOf(fact("Festival eins"), fact("Festival zwei"), fact("Festival drei"))
        assertTrue(NotificationFactMemoryProjection(repo).candidates(envelope("Festival")).isEmpty())
    }

    @Test fun sensitiveNotificationIsSanitizedBeforeSelectingSearchTerms() {
        val repo = Repo()
        NotificationFactMemoryProjection(repo).candidates(envelope("Festival. OTP 123456"))
        assertFalse(repo.lastQuery?.terms.orEmpty().any { it.contains("123456") })
    }

    @Test fun nativeSymlinkFailureStillFailsClosedRatherThanBeingMaskedByArchive() {
        val home = temporary.newFolder()
        val outside = temporary.newFolder()
        java.nio.file.Files.createSymbolicLink(home.toPath().resolve("memories"), outside.toPath())
        var calls = 0
        val result = CodexNotificationMemoryProjection(home,
            additionalUnverifiedCandidateSource = { calls++; listOf("Festival Samstag") })
            .project(envelope("Festival"))
        assertTrue(result.facts.isEmpty())
        assertEquals(0, calls)
    }

    private fun home(native: String? = null): java.io.File {
        val home = temporary.newFolder()
        if (native != null) {
            val memories = java.io.File(home, "memories")
            check(memories.mkdir())
            java.io.File(memories, "memory_summary.md").writeText(native)
        }
        return home
    }
    private fun envelope(text: String, pkg: String = "synthetic.calendar") = UntrustedNotificationEnvelope(
        1, NotificationEventKind.POSTED, 100, pkg, "synthetic-key", "", text, "", "", "", false, true)
    private fun fact(text: String, pkg: String = "synthetic.chat"): NotificationFact {
        val evidence = NotificationMemoryCandidate(NotificationMemoryKind.EVENT_DETAIL,
            NotificationMemorySourceField.TEXT, text, 0, text.length, "a".repeat(64))
        return NotificationFact("fact_" + text.hashCode().toString().replace('-', 'n'), 1,
            NotificationMemoryKind.EVENT_DETAIL, text, NotificationFactAuthority.UNTRUSTED_NOTIFICATION_CLAIM,
            pkg, "source_one", 1, 1, 100, "v1", evidence)
    }
    private class Repo(var facts: List<NotificationFact> = emptyList()) : NotificationFactRepository {
        var queries = 0
        var unavailable = false
        var lastQuery: NotificationFactQuery? = null
        override fun query(query: NotificationFactQuery): NotificationFactQueryResult {
            queries++
            lastQuery = query
            if (unavailable) throw NotificationFactArchiveUnavailableException(NotificationFactUnavailableReason.IO_FAILURE)
            return NotificationFactQueryResult(facts, false)
        }
        override fun captureToken(packageName: String): NotificationArchiveCaptureToken? = error("no intake")
        override fun canStage(batch: NotificationFactBatch): Boolean = error("not expected")
        override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult = error("no writes")
        override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult = error("no writes")
        override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult = error("no writes")
        override fun pendingIntents(): List<NotificationFactPrivacyIntent> = error("no privacy access")
        override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean = error("no writes")
        override fun health(): NotificationFactArchiveHealth = error("query only")
        override fun close() = error("caller owns repository")
    }
}
