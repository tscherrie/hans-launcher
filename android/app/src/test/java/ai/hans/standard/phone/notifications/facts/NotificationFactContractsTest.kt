package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.NotificationMemoryCandidate
import ai.hans.standard.notifications.NotificationMemoryKind
import ai.hans.standard.notifications.NotificationMemorySourceField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactContractsTest {
    @Test
    fun tokenNeverInventsEpochOrNegativeGenerations() {
        assertRejected { NotificationArchiveCaptureToken("unknown", 0, 0) }
        assertRejected { NotificationArchiveCaptureToken(EPOCH, -1, 0) }
        assertRejected { NotificationArchiveCaptureToken(EPOCH, 0, -1) }
        assertEquals(EPOCH, token().storeEpoch)
    }

    @Test
    fun batchAcceptsOnlyBoundedHostIdentifiersAndPositiveSourceCoordinates() {
        val valid = batch()
        assertRejected { valid.copy(batchId = "../../source") }
        assertRejected { valid.copy(sourceRef = "content://private") }
        assertRejected { valid.copy(packageName = "example.app;DROP TABLE facts") }
        assertRejected { valid.copy(sourceRevision = 0) }
        assertRejected { valid.copy(sequence = 0) }
        assertRejected { valid.copy(observedAtEpochMillis = -1) }
        assertRejected { valid.copy(extractorVersion = "arbitrary instructions here") }
    }

    @Test
    fun candidateCountUtf8BudgetAndDuplicateIdentityAreValidatedWithoutASecondDto() {
        assertRejected { batch().copy(validatedCandidates = emptyList()) }
        assertRejected { batch().copy(validatedCandidates = List(4) { candidate("event-$it") }) }
        assertRejected { batch().copy(validatedCandidates = listOf(candidate(), candidate())) }
        assertRejected {
            batch().copy(validatedCandidates = List(3) { candidate("界".repeat(128) + it) })
        }
        assertEquals(3, batch().copy(validatedCandidates = List(3) {
            candidate("a".repeat(255) + it)
        }).validatedCandidates.size)
    }

    @Test
    fun factIdentitySurvivesSourceOffsetButIsBoundToExactClaimSourceAndKind() {
        val first = candidate("Café öffnet morgen")
        val moved = first.copy(startUtf16 = 7, endUtf16 = 7 + first.quote.length,
            sourceSha256 = "b".repeat(64))
        val id = NotificationFactIds.forCandidate(PACKAGE, "source-a", first)
        assertEquals(id, NotificationFactIds.forCandidate(PACKAGE, "source-a", moved))
        assertNotEquals(id, NotificationFactIds.forCandidate(PACKAGE, "source-b", first))
        assertNotEquals(id, NotificationFactIds.forCandidate("other.app", "source-a", first))
        assertNotEquals(id, NotificationFactIds.forCandidate(PACKAGE, "source-a",
            first.copy(kind = NotificationMemoryKind.AVAILABILITY_UPDATE)))
        assertNotEquals(id, NotificationFactIds.forCandidate(PACKAGE, "source-a", candidate("Café öffnet heute")))
    }

    @Test
    fun queryHasClosedCountAndUtf8LimitsAndNeverAcceptsAnUnscopedSourceReference() {
        assertRejected { NotificationFactQuery(limit = 9) }
        assertRejected { NotificationFactQuery(limit = 0) }
        assertRejected { NotificationFactQuery(maxUtf8Bytes = 511) }
        assertRejected { NotificationFactQuery(maxUtf8Bytes = 16_385) }
        assertRejected { NotificationFactQuery(terms = List(9) { "word$it" }) }
        assertRejected { NotificationFactQuery(terms = listOf("WORD", "word")) }
        assertRejected { NotificationFactQuery(terms = listOf("界".repeat(22))) }
        assertRejected { NotificationFactQuery(sourceRef = "source-a") }
        assertRejected { NotificationFactQuery(sinceEpochMillis = 10, untilEpochMillis = 9) }
        assertEquals(8, NotificationFactQuery().limit)
    }

    @Test
    fun correctionUsesExactRevisionAndPreservesWellFormedUnicode() {
        val correction = NotificationFactCorrection("fact-a", 1, "mutation-a",
            NotificationMemoryKind.EVENT_DETAIL, "Konzert 🌟 um 19 Uhr")
        assertRejected { correction.copy(expectedRevision = 0) }
        assertRejected { correction.copy(text = "") }
        assertRejected { correction.copy(text = "a".repeat(257)) }
        assertRejected { correction.copy(text = "lost\uD800") }
        assertRejected { correction.copy(text = "\uDC00orphan") }
        assertRejected { correction.copy(text = "control\u0000character") }
        assertEquals("Konzert 🌟 um 19 Uhr", correction.text)
    }

    @Test
    fun ownerCorrectionProvenanceCannotMasqueradeAsAnUnchangedSourceClaim() {
        val claim = fact()
        assertRejected { claim.copy(authority = NotificationFactAuthority.OWNER_CORRECTION) }
        assertRejected { claim.copy(correctedAtEpochMillis = 12) }
        assertRejected { claim.copy(text = "Not the source quote") }
        assertRejected { claim.copy(kind = NotificationMemoryKind.AVAILABILITY_UPDATE) }
        assertRejected { claim.copy(extractorVersion = "arbitrary instructions here") }
        val corrected = claim.copy(authority = NotificationFactAuthority.OWNER_CORRECTION,
            correctedAtEpochMillis = 12, revision = 2, text = "Owner correction")
        assertEquals("explicit_owner_correction", corrected.toJsonProjection().getString("authority"))
        assertEquals(claim.evidence, corrected.evidence)
    }

    @Test
    fun projectionReportsExternalClaimTrustWithoutExposingRawSequenceOrNativeMemoryPaths() {
        val projection = NotificationFactQueryResult(listOf(fact()), false).toJsonProjection()
        val raw = projection.toString()
        assertTrue(projection.getString("trust").contains("not_instructions_or_authorization"))
        assertTrue(raw.contains("untrusted_notification_claim"))
        assertTrue(raw.contains("sourceRef"))
        assertFalse(raw.contains("\"sequence\""))
        assertFalse(raw.contains("androidKey"))
        assertFalse(raw.contains("CODEX_HOME"))
        assertFalse(raw.contains("MEMORY.md"))
    }

    @Test
    fun encodedQueryBudgetIncludesEscapedJsonAndAllProvenance() {
        val claim = fact(candidate("A quoted \"event\"\nCafé 🌟"))
        val result = NotificationFactQueryResult(listOf(claim), true)
        assertEquals(result.toJsonProjection().toString().toByteArray(Charsets.UTF_8).size,
            result.encodedUtf8Bytes)
        assertTrue(result.encodedUtf8Bytes > claim.text.toByteArray(Charsets.UTF_8).size)
        assertRejected { NotificationFactQueryResult(List(9) { fact(candidate("event-$it")) }, false) }
        assertRejected { NotificationFactQueryResult(listOf(claim, claim), false) }
    }

    @Test
    fun privacyIntentRequiresResolvedScopeButFactAndSourceKeepTheExistingGeneration() {
        val factScope = NotificationFactPrivacyScope.Fact("fact-a", 1)
        val intent = NotificationFactPrivacyIntent(EPOCH, "forget-a", factScope,
            PACKAGE, "source-a", 0, 1, 1)
        assertRejected { intent.copy(affectedPackageName = null) }
        assertRejected { intent.copy(affectedSourceRef = null) }
        assertRejected { intent.copy(packageGeneration = null) }
        assertEquals(0L, intent.copy(packageGeneration = 0).packageGeneration)
        val source = intent.copy(scope = NotificationFactPrivacyScope.Source(PACKAGE, "source-a"),
            packageGeneration = 0)
        assertEquals(0L, source.packageGeneration)
        assertRejected { intent.copy(scope = NotificationFactPrivacyScope.Source(PACKAGE, "other-source")) }
        assertRejected { intent.copy(scope = NotificationFactPrivacyScope.All) }
        assertFalse(intent.toString().contains(candidate().quote))
        val all = NotificationFactPrivacyIntent(EPOCH, "forget-all", NotificationFactPrivacyScope.All,
            null, null, 1, null, 0)
        assertRejected { all.copy(allGeneration = 0) }
    }

    @Test
    fun unknownExternalPrivacyNeverPermitsCaptureAndExplicitExclusionsRemainSeparate() {
        assertFalse(NotificationFactExternalPrivacy.UNAVAILABLE.permits(PACKAGE))
        val open = NotificationFactExternalPrivacy(true, false)
        assertTrue(open.permits(PACKAGE))
        assertFalse(open.copy(purgeRequired = true).permits(PACKAGE))
        assertFalse(open.copy(excludedPackages = setOf(PACKAGE)).permits(PACKAGE))
        assertFalse(open.copy(protectedPackages = setOf(PACKAGE)).permits(PACKAGE))
        assertFalse(open.permits("ai.hans.standard"))
    }

    @Test
    fun capacityIsExplicitAndBoundedWithoutAnEvictionOption() {
        val capacity = NotificationFactCapacity()
        assertEquals(100_000, capacity.maxFacts)
        assertEquals(128L * 1_024 * 1_024, capacity.maxDatabaseBytes)
        assertTrue(capacity.privacyReserveBytes > 0)
        assertRejected { capacity.copy(maxFacts = 100_001) }
        assertRejected { capacity.copy(privacyReserveBytes = capacity.maxDatabaseBytes) }
    }

    private fun batch() = NotificationFactBatch("batch-a", token(), PACKAGE, "source-a", 1, 1, 1,
        "extractor-1", listOf(candidate()))

    private fun token() = NotificationArchiveCaptureToken(EPOCH, 0, 0)

    private fun candidate(quote: String = "Event Friday") = NotificationMemoryCandidate(
        NotificationMemoryKind.EVENT_DETAIL, NotificationMemorySourceField.TEXT, quote, 0,
        quote.length, "a".repeat(64),
    )

    private fun fact(evidence: NotificationMemoryCandidate = candidate()) = NotificationFact(
        NotificationFactIds.forCandidate(PACKAGE, "source-a", evidence), 1, evidence.kind,
        evidence.quote, NotificationFactAuthority.UNTRUSTED_NOTIFICATION_CLAIM,
        PACKAGE, "source-a", 1, 17, 123, "extractor-1", evidence,
    )

    private fun assertRejected(block: () -> Unit) {
        assertTrue("Malformed archive contract must be rejected", runCatching(block).isFailure)
    }

    companion object {
        private const val PACKAGE = "example.messages"
        private const val EPOCH = "e0128b70-5d60-4f1d-a77a-636e889fe2da"
    }
}
