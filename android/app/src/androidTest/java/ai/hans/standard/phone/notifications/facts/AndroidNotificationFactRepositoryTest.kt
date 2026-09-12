package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.NotificationMemoryCandidate
import ai.hans.standard.notifications.NotificationMemoryKind
import ai.hans.standard.notifications.NotificationMemorySourceField
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Only synthetic, UUID-isolated cache databases; never personal inbox, Codex or settings files. */
@RunWith(AndroidJUnit4::class)
class AndroidNotificationFactRepositoryTest {
    private lateinit var testDirectory: File
    private lateinit var context: Context
    private lateinit var current: AndroidNotificationFactRepository
    private val opened = mutableListOf<AndroidNotificationFactRepository>()
    private val monitor = Any()
    @Volatile private var privacy = NotificationFactExternalPrivacy(true, false)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        testDirectory = Files.createTempDirectory(app.cacheDir.toPath(), "notification-facts-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.db")))
                return File(testDirectory, name)
            }
        }
        privacy = NotificationFactExternalPrivacy(true, false)
        current = repository()
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching(it::close) }
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(testDirectory.parentFile == app.cacheDir && testDirectory.name.startsWith("notification-facts-test-"))
        assertTrue(testDirectory.deleteRecursively())
    }

    @Test
    fun pendingLookupUsesCoveringIndexBeforeAndAfterRepositoryReopen() {
        assertNotNull(current.captureToken(PACKAGE))
        val completed = pending(current.beginPrivacy(NotificationFactPrivacyRequest(
            "completed-index-fixture", NotificationFactPrivacyScope.Source(PACKAGE, "source-completed"))))
        assertTrue(current.acknowledgePrivacy(completed))
        val outstanding = pending(current.beginPrivacy(NotificationFactPrivacyRequest(
            "pending-index-fixture", NotificationFactPrivacyScope.Source(PACKAGE, "source-pending"))))
        repeat(2) {
            assertEquals(listOf(outstanding), current.pendingIntents())
            current.close()
            rawDatabase { db ->
                val columns = db.rawQuery("PRAGMA index_info(operations_pending_lookup)", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                }
                assertEquals(listOf("operation_kind", "status"), columns)
                val sql = "SELECT COUNT(*) FROM operations WHERE operation_kind='privacy' AND status='pending'"
                db.rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor ->
                    val plans = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                    assertTrue(plans.toString(), plans.any {
                        it.contains("operations_pending_lookup") && it.contains("COVERING INDEX")
                    })
                }
                db.rawQuery(sql, null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
            current = repository()
        }
        assertEquals(listOf(outstanding), current.pendingIntents())
    }

    @Test
    fun durableBatchReplayDoesNotAdvanceRevisionAndConflictingPayloadIsRejected() {
        val original = batch(quote = "Festival Freitag")
        val id = stored(current.commit(original)).single()
        assertEquals(1L, query().single().revision)
        val originalToken = original.token
        reopen()
        assertEquals(originalToken, current.captureToken(PACKAGE))
        assertEquals(NotificationFactCommitResult.Replay(listOf(id)), current.commit(original))
        assertEquals(1L, query().single().revision)
        val conflicting = original.copy(validatedCandidates = listOf(candidate("Festival Samstag")))
        assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.ID_CONFLICT),
            current.commit(conflicting))
        assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.STALE_SOURCE),
            current.commit(original.copy(batchId = "other-batch")))
        stored(current.commit(original.copy(batchId = "source-revision-2", sourceRevision = 2,
            sequence = 2, observedAtEpochMillis = 2_000)))
        reopen()
        assertEquals(2L, query().single().revision)
        assertEquals(2L, query().single().sourceRevision)
        assertEquals(NotificationFactAuthority.UNTRUSTED_NOTIFICATION_CLAIM, query().single().authority)
    }

    @Test
    fun stagingCheckDoesNotInitializeReserveCapacityOrWriteReceipts() {
        val freshHealth = current.health()
        assertFalse(freshHealth.available)
        assertEquals(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING, freshHealth.unavailableReason)
        assertFalse(databaseFile.exists())
        assertFalse(sealFile.exists())
        val notInitialized = NotificationFactBatch(UUID.randomUUID().toString(),
            NotificationArchiveCaptureToken(UUID.randomUUID().toString(), 0, 0), PACKAGE,
            "never-captured", 1, 1, 1_000, "extractor-1", listOf(candidate("Unbound event")))
        assertFalse(current.canStage(notInitialized))
        assertFalse(databaseFile.exists())
        assertFalse(sealFile.exists())
        assertNotNull("Read-only missing-store health must not poison later legitimate initialization",
            current.captureToken(PACKAGE))
        assertTrue(current.health().available)
        assertTrue(databaseFile.isFile)
        assertTrue(sealFile.isFile)
        current.close()
        current = repository(NotificationFactCapacity(maxFacts = 1))
        val accepted = batch()
        val before = databaseFile.readBytes()
        val seal = sealFile.readBytes()
        assertTrue(current.canStage(accepted))
        assertTrue(current.canStage(accepted))
        assertTrue(before.contentEquals(databaseFile.readBytes()))
        assertTrue(seal.contentEquals(sealFile.readBytes()))
        assertTrue(query().isEmpty())
        stored(current.commit(accepted))
        val noSpace = batch(sourceRef = "future-outbox", quote = "Future selected event")
        assertTrue(current.health().capacityExceeded)
        assertTrue("Privacy eligibility is separate from archive capacity", current.canStage(noSpace))
        assertEquals(NotificationFactCommitResult.CapacityExceeded, current.commit(noSpace))
        assertEquals(1, query().size)
    }

    @Test
    fun correctionIsCasIdempotentAndNeverOverwrittenByLaterExternalEvidence() {
        val original = batch(quote = "Festival Freitag")
        val id = stored(current.commit(original)).single()
        val correction = NotificationFactCorrection(id, 1, "owner-correction", NotificationMemoryKind.EVENT_DETAIL,
            "Festival Samstag")
        assertEquals(NotificationFactCorrectionResult.Applied(id, 2), current.correct(correction))
        assertEquals(NotificationFactCorrectionResult.Replay(id, 2), current.correct(correction))
        assertEquals(NotificationFactCorrectionResult.Conflict(NotificationFactCorrectionResult.Reason.ID_CONFLICT),
            current.correct(correction.copy(text = "Festival Sonntag")))
        assertEquals(NotificationFactCorrectionResult.Conflict(NotificationFactCorrectionResult.Reason.REVISION_CHANGED),
            current.correct(correction.copy(mutationId = "stale-correction")))
        assertTrue(current.query(NotificationFactQuery(terms = listOf("Freitag"))).facts.isEmpty())
        assertEquals(1, current.query(NotificationFactQuery(terms = listOf("Samstag"))).facts.size)
        stored(current.commit(original.copy(batchId = "new-external-revision", sourceRevision = 2,
            sequence = 2, observedAtEpochMillis = 2_000)))
        reopen()
        val restored = query().single()
        assertEquals(2L, restored.revision)
        assertEquals("Festival Samstag", restored.text)
        assertEquals("Festival Freitag", restored.evidence.quote)
        assertEquals(NotificationFactAuthority.OWNER_CORRECTION, restored.authority)
        assertNotNull(restored.correctedAtEpochMillis)
        assertEquals(NotificationFactCorrectionResult.Replay(id, 2), current.correct(correction))
    }

    @Test
    fun keywordCountUsesNumericBindingAndKeepsAndSemanticsAfterReopen() {
        val matching = stored(current.commit(batch(sourceRef = "matching-terms",
            quote = "Festival München Café"))).single()
        stored(current.commit(batch(sourceRef = "partial-terms",
            quote = "Festival München")))

        // Android rawQuery(String[]) binds TEXT. COUNT has no column affinity, unlike
        // observed_at; a text count must be explicitly cast to compare numerically.
        current.close()
        rawDatabase { db ->
            db.rawQuery("SELECT COUNT(*) = ?, COUNT(*) = CAST(? AS INTEGER) FROM facts",
                arrayOf("2", "2")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
        current = repository()
        repeat(2) {
            val allTerms = current.query(NotificationFactQuery(terms = listOf("MÜNCHEN", "café")))
            assertEquals(listOf(matching), allTerms.facts.map { it.factId })
            assertEquals(2, current.query(NotificationFactQuery(terms = listOf("München"))).facts.size)
            assertTrue(current.query(NotificationFactQuery(terms = listOf("München", "Berlin"))).facts.isEmpty())
            reopen()
        }
    }

    @Test
    fun queryUsesLocalNormalizedKeywordsExactFiltersAndCompleteUtf8Budget() {
        repeat(12) { index ->
            stored(current.commit(batch(sourceRef = "source-$index", sequence = index + 1L,
                quote = "München Café Lichter 🌟 $index")))
        }
        stored(current.commit(batch(packageName = OTHER_PACKAGE, sourceRef = "other-source", sequence = 20,
            quote = "München Café Fremd")))
        val result = current.query(NotificationFactQuery(terms = listOf("MÜNCHEN", "café"), packageName = PACKAGE))
        assertEquals(8, result.facts.size)
        assertTrue(result.truncated)
        assertEquals("source-11", result.facts.first().sourceRef)
        assertTrue(result.facts.all { it.packageName == PACKAGE })
        assertTrue(result.encodedUtf8Bytes <= NotificationFactBounds.DEFAULT_QUERY_UTF8_BYTES)
        val narrow = current.query(NotificationFactQuery(terms = listOf("Café"), packageName = PACKAGE,
            maxUtf8Bytes = 1_500))
        assertTrue(narrow.facts.isNotEmpty())
        assertTrue(narrow.truncated)
        assertTrue(narrow.encodedUtf8Bytes <= 1_500)
        assertEquals(narrow.toJsonProjection().toString().toByteArray(Charsets.UTF_8).size, narrow.encodedUtf8Bytes)
        val tooSmall = current.query(NotificationFactQuery(packageName = PACKAGE, maxUtf8Bytes = 512))
        assertTrue(tooSmall.facts.isEmpty())
        assertTrue(tooSmall.truncated)
        assertEquals(1, current.query(NotificationFactQuery(packageName = PACKAGE, sourceRef = "source-0")).facts.size)
        assertTrue(current.query(NotificationFactQuery(sinceEpochMillis = 30_000)).facts.isEmpty())
    }

    @Test
    fun packagePrivacyAdvancesGenerationEvenWithZeroRowsAndIncludeCannotReviveOldWork() {
        val old = batch(quote = "Old captured work")
        privacy = privacy.copy(excludedPackages = setOf(PACKAGE), purgeRequired = true)
        val request = NotificationFactPrivacyRequest("exclude-empty-package", NotificationFactPrivacyScope.Package(PACKAGE))
        val intent = pending(current.beginPrivacy(request))
        assertEquals(0, intent.removedFacts)
        assertEquals(PACKAGE, intent.affectedPackageName)
        assertEquals(old.token.packageGeneration + 1, intent.packageGeneration)
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.canStage(old))
        reopen()
        assertEquals(listOf(intent), current.pendingIntents())
        assertTrue(current.acknowledgePrivacy(intent))
        privacy = privacy.copy(purgeRequired = false)
        assertNull(current.captureToken(PACKAGE))
        privacy = privacy.copy(excludedPackages = emptySet())
        assertNotEquals(old.token, current.captureToken(PACKAGE))
        assertFalse(current.canStage(old))
        assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.STALE_TOKEN),
            current.commit(old))
        stored(current.commit(batch(sourceRef = "new-after-include", quote = "New permitted work")))
        assertEquals(1, query().size)
    }

    @Test
    fun factForgetBlocksItsOldCandidateButKeepsPreviouslyCapturedOtherBatches() {
        val first = batch(quote = "Concert Friday").copy(validatedCandidates =
            listOf(candidate("Concert Friday"), candidate("Tickets available")))
        val ids = stored(current.commit(first))
        val earlierDifferentSource = batch(sourceRef = "already-validated-other-source", quote = "Retained selected event")
        assertTrue(current.canStage(earlierDifferentSource))
        val request = NotificationFactPrivacyRequest("forget-one-fact", NotificationFactPrivacyScope.Fact(ids.first(), 1))
        val intent = pending(current.beginPrivacy(request))
        assertEquals(PACKAGE, intent.affectedPackageName)
        assertEquals(first.sourceRef, intent.affectedSourceRef)
        assertEquals(first.token.packageGeneration, intent.packageGeneration)
        assertEquals(1, intent.removedFacts)
        assertNull(current.captureToken(OTHER_PACKAGE))
        assertFalse(current.canStage(first))
        assertFalse(current.canStage(earlierDifferentSource))
        expectUnavailable(NotificationFactUnavailableReason.PRIVACY_RECOVERY_REQUIRED) { query() }
        assertEquals(NotificationFactCommitResult.Unavailable(NotificationFactUnavailableReason.PRIVACY_RECOVERY_REQUIRED),
            current.commit(earlierDifferentSource))
        reopen()
        assertTrue(current.acknowledgePrivacy(current.pendingIntents().single()))
        assertEquals(ids.last(), query().single().factId)
        assertEquals(first.token, current.captureToken(PACKAGE))
        assertFalse(current.canStage(first))
        assertTrue(current.canStage(earlierDifferentSource))
        stored(current.commit(earlierDifferentSource))
        assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.TOMBSTONED),
            current.commit(first.copy(batchId = "forgotten-later-revision",
                sourceRevision = 2, sequence = 2)))
        assertEquals(2, query().size)
        assertTrue(query().any { it.factId == ids.last() })
        assertTrue(query().any { it.sourceRef == earlierDifferentSource.sourceRef })
    }

    @Test
    fun sourceForgetSurvivesReopenAndBlocksLaterRevisionsWithoutDeletingOtherSources() {
        val first = batch(sourceRef = "forgotten-source", quote = "Source event")
        stored(current.commit(first))
        stored(current.commit(batch(sourceRef = "kept-source", quote = "Another event")))
        val previouslySelected = batch(sourceRef = "pending-other-source", quote = "Already selected future event")
        assertTrue(current.canStage(first))
        assertTrue(current.canStage(previouslySelected))
        val intent = pending(current.beginPrivacy(NotificationFactPrivacyRequest("forget-source",
            NotificationFactPrivacyScope.Source(PACKAGE, first.sourceRef))))
        assertEquals(1, intent.removedFacts)
        assertEquals(first.token.packageGeneration, intent.packageGeneration)
        assertFalse(current.canStage(first))
        assertFalse(current.canStage(previouslySelected))
        reopen()
        assertTrue(current.acknowledgePrivacy(current.pendingIntents().single()))
        assertEquals("kept-source", query().single().sourceRef)
        assertEquals(first.token, current.captureToken(PACKAGE))
        assertFalse(current.canStage(first))
        assertTrue(current.canStage(previouslySelected))
        stored(current.commit(previouslySelected))
        assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.TOMBSTONED),
            current.commit(first.copy(batchId = "late-source",
                sourceRevision = 2, sequence = 2)))
        assertEquals(setOf("kept-source", "pending-other-source"), query().map { it.sourceRef }.toSet())
    }

    @Test
    fun allForgetIsDurableScopedAndHistoricalAckCannotDeleteLaterAuthorizedFacts() {
        val first = batch(quote = "First app")
        val other = batch(packageName = OTHER_PACKAGE, sourceRef = "second-source", quote = "Second app")
        stored(current.commit(first)); stored(current.commit(other))
        val request = NotificationFactPrivacyRequest("forget-all", NotificationFactPrivacyScope.All)
        val intent = pending(current.beginPrivacy(request))
        assertEquals(2, intent.removedFacts)
        assertEquals(first.token.allGeneration + 1, intent.allGeneration)
        assertNull(intent.affectedPackageName)
        assertFalse(current.acknowledgePrivacy(intent.copy(mutationId = "different-intent")))
        reopen()
        assertEquals(listOf(intent), current.pendingIntents())
        assertTrue(current.acknowledgePrivacy(intent))
        assertTrue(query().isEmpty())
        listOf(first, other).forEach { old ->
            assertFalse(current.canStage(old))
            assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.STALE_TOKEN),
                current.commit(old))
        }
        stored(current.commit(batch(sourceRef = "after-forget", quote = "Later authorized event")))
        assertEquals(NotificationFactPrivacyBeginResult.Completed(intent), current.beginPrivacy(request))
        assertTrue(current.acknowledgePrivacy(intent))
        assertEquals("Later authorized event", query().single().text)
    }

    @Test
    fun externalRawFencePolicyFailureAndExclusionsBlockIntakeWithoutHiddenArchiveMutation() {
        val original = batch(quote = "Previously retained event")
        val id = stored(current.commit(original)).single()
        val correction = NotificationFactCorrection(id, 1, "blocked-correction", NotificationMemoryKind.EVENT_DETAIL, "Changed")
        privacy = privacy.copy(purgeRequired = true)
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.canStage(original))
        expectUnavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE) { query() }
        assertEquals(NotificationFactCommitResult.Unavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE),
            current.commit(original))
        assertEquals(NotificationFactCorrectionResult.Unavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE),
            current.correct(correction))
        assertEquals(1, current.health().factCount)
        privacy = privacy.copy(purgeRequired = false, excludedPackages = setOf(PACKAGE))
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.canStage(original))
        assertTrue(query().isEmpty())
        expectUnavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE) {
            current.query(NotificationFactQuery(packageName = PACKAGE))
        }
        privacy = privacy.copy(excludedPackages = emptySet(), policyAvailable = false)
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.canStage(original))
        privacy = privacy.copy(policyAvailable = true)
        assertEquals(id, query().single().factId)
        assertEquals(1L, query().single().revision)
        assertTrue(current.canStage(original))
    }

    @Test
    fun defaultPrivacyNeverExposesExistingFactsAndPrivacyRecoveryDoesNotNeedAnOpenGate() {
        stored(current.commit(batch()))
        current.close()
        current = AndroidNotificationFactRepository(context, mutationMonitor = monitor).also(opened::add)
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.health().available)
        expectUnavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE) { query() }
        val intent = pending(current.beginPrivacy(NotificationFactPrivacyRequest("closed-gate-forget", NotificationFactPrivacyScope.All)))
        assertEquals(1, intent.removedFacts)
        assertTrue(current.acknowledgePrivacy(intent))
        assertNull(current.captureToken(PACKAGE))
    }

    @Test
    fun countCapacityRollsBackWholeBatchHeadIndexAndReceiptInsteadOfEvictingOldFacts() {
        current.close()
        current = repository(NotificationFactCapacity(maxFacts = 2))
        val tooLarge = batch().copy(validatedCandidates = listOf(candidate("One event"), candidate("Two event"), candidate("Three event")))
        assertEquals(NotificationFactCommitResult.CapacityExceeded, current.commit(tooLarge))
        assertTrue(query().isEmpty())
        // Reusing this ID with a smaller payload succeeds only if the failed transaction wrote no receipt/head.
        val accepted = tooLarge.copy(validatedCandidates = tooLarge.validatedCandidates.take(2))
        val ids = stored(current.commit(accepted))
        assertEquals(2, current.health().factCount)
        assertEquals(NotificationFactCommitResult.CapacityExceeded,
            current.commit(batch(sourceRef = "would-evict", quote = "New third event")))
        assertEquals(ids.toSet(), query().map { it.factId }.toSet())
        val intent = pending(current.beginPrivacy(NotificationFactPrivacyRequest("make-space",
            NotificationFactPrivacyScope.Fact(ids.first(), 1))))
        assertTrue(current.acknowledgePrivacy(intent))
        stored(current.commit(batch(sourceRef = "fits-after-forget", quote = "New third event")))
        assertEquals(2, query().size)
        assertTrue(query().any { it.factId == ids.last() })
    }

    @Test
    fun byteCapacityReturnsExplicitFailureAndPreservesEarliestFacts() {
        current.close()
        val capacity = NotificationFactCapacity(maxDatabaseBytes = 256 * 1_024L, privacyReserveBytes = 64 * 1_024L)
        current = repository(capacity)
        var committed = 0
        var exhausted = false
        for (index in 0 until 512) {
            val words = (0 until 25).joinToString(" ") { "word${index}x$it" }.take(250)
            when (val result = current.commit(batch(sourceRef = "capacity-$index", quote = words, sequence = index + 1L))) {
                is NotificationFactCommitResult.Stored -> committed++
                NotificationFactCommitResult.CapacityExceeded -> { exhausted = true; break }
                else -> throw AssertionError("Unexpected capacity outcome: $result")
            }
        }
        assertTrue("Fixture must actually reach the byte bound", exhausted)
        assertTrue(committed > 0)
        assertEquals(committed, current.health().factCount)
        assertEquals(1, current.query(NotificationFactQuery(packageName = PACKAGE, sourceRef = "capacity-0")).facts.size)
        assertTrue(databaseFile.length() <= capacity.maxDatabaseBytes)
    }

    @Test
    fun mutationIdCannotBeReusedAcrossCorrectionPrivacyOrAnotherPayload() {
        val id = stored(current.commit(batch())).single()
        assertTrue(current.correct(NotificationFactCorrection(id, 1, "shared-id", NotificationMemoryKind.EVENT_DETAIL,
            "Owner version")) is NotificationFactCorrectionResult.Applied)
        assertEquals(NotificationFactPrivacyBeginResult.Conflict(NotificationFactPrivacyBeginResult.Reason.ID_CONFLICT),
            current.beginPrivacy(NotificationFactPrivacyRequest("shared-id", NotificationFactPrivacyScope.All)))
        val request = NotificationFactPrivacyRequest("privacy-id", NotificationFactPrivacyScope.Package(OTHER_PACKAGE))
        val intent = pending(current.beginPrivacy(request))
        assertEquals(NotificationFactPrivacyBeginResult.Pending(intent, replay = true), current.beginPrivacy(request))
        assertEquals(NotificationFactPrivacyBeginResult.Conflict(NotificationFactPrivacyBeginResult.Reason.ID_CONFLICT),
            current.beginPrivacy(request.copy(scope = NotificationFactPrivacyScope.All)))
        assertTrue(current.acknowledgePrivacy(intent))
        assertEquals(NotificationFactCorrectionResult.Conflict(NotificationFactCorrectionResult.Reason.ID_CONFLICT),
            current.correct(NotificationFactCorrection(id, 2, "privacy-id", NotificationMemoryKind.EVENT_DETAIL, "Changed")))
    }

    @Test
    fun separateWrappersSerializeRealCasWithoutLosingTheWinningCorrection() {
        val id = stored(current.commit(batch())).single()
        val second = repository()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = listOf(current, second).mapIndexed { index, repository ->
                pool.submit<NotificationFactCorrectionResult> {
                    check(start.await(10, TimeUnit.SECONDS))
                    repository.correct(NotificationFactCorrection(id, 1, "racing-$index",
                        NotificationMemoryKind.EVENT_DETAIL, "Winner $index"))
                }
            }
            start.countDown()
            val results = requests.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is NotificationFactCorrectionResult.Applied })
            assertEquals(1, results.count { it == NotificationFactCorrectionResult.Conflict(
                NotificationFactCorrectionResult.Reason.REVISION_CHANGED) })
            assertEquals(2L, query().single().revision)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun pendingForgetCannotBeOvertakenByAConcurrentBatchOnAnotherWrapper() {
        val accepted = batch(quote = "Concurrent event")
        val enteredCommit = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val enteredPrivacy = CountDownLatch(1)
        val committing = AndroidNotificationFactRepository(context, privacySnapshot = {
            enteredCommit.countDown()
            check(finishCommit.await(10, TimeUnit.SECONDS))
            privacy
        }, mutationMonitor = monitor).also(opened::add)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val commit = pool.submit<NotificationFactCommitResult> { committing.commit(accepted) }
            assertTrue(enteredCommit.await(10, TimeUnit.SECONDS))
            val forget = pool.submit<NotificationFactPrivacyBeginResult> {
                enteredPrivacy.countDown()
                current.beginPrivacy(NotificationFactPrivacyRequest("concurrent-forget", NotificationFactPrivacyScope.All))
            }
            assertTrue(enteredPrivacy.await(10, TimeUnit.SECONDS))
            finishCommit.countDown()
            stored(commit.get(15, TimeUnit.SECONDS))
            val intent = pending(forget.get(15, TimeUnit.SECONDS))
            assertEquals(1, intent.removedFacts)
            assertNull(current.captureToken(PACKAGE))
            assertTrue(current.acknowledgePrivacy(intent))
            assertTrue(query().isEmpty())
            assertEquals(NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.STALE_TOKEN),
                current.commit(accepted))
        } finally {
            finishCommit.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun oldObservationAndLaterClockDoNotCauseAutomaticAgeEviction() {
        val accepted = batch(quote = "Long lived event", sequence = 1)
        stored(current.commit(accepted))
        current.close()
        current = AndroidNotificationFactRepository(context, privacySnapshot = { privacy }, mutationMonitor = monitor,
            clock = { 4_000_000_000_000L }).also(opened::add)
        assertEquals("Long lived event", query().single().text)
        assertEquals(1_000L, query().single().observedAtEpochMillis)
        assertEquals(NotificationFactCommitResult.Replay(listOf(query().single().factId)), current.commit(accepted))
    }

    @Test
    fun futureSchemaIsRejectedWithoutChangingOrDeletingTheExistingDatabase() {
        stored(current.commit(batch())); current.close()
        rawDatabase { it.version = 99 }
        val before = databaseFile.readBytes()
        val seal = sealFile.readBytes()
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        assertEquals(NotificationFactUnavailableReason.INVALID_SCHEMA, current.health().unavailableReason)
        assertTrue(before.contentEquals(databaseFile.readBytes()))
        assertTrue(seal.contentEquals(sealFile.readBytes()))
    }

    @Test
    fun unknownSameVersionSchemaFailsClosedWithoutResetOrQuietUpgrade() {
        stored(current.commit(batch())); current.close()
        rawDatabase { it.execSQL("ALTER TABLE facts ADD COLUMN unknown_column TEXT") }
        val before = databaseFile.readBytes()
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        assertEquals(NotificationFactUnavailableReason.INVALID_SCHEMA, current.health().unavailableReason)
        assertTrue(before.contentEquals(databaseFile.readBytes()))
    }

    @Test
    fun unexpectedTriggerIsUnknownSchemaEvenWhenTablesAndVersionLookUnchanged() {
        val accepted = batch()
        stored(current.commit(accepted)); current.close()
        rawDatabase { it.execSQL("CREATE TRIGGER unknown_trigger AFTER INSERT ON facts " +
            "BEGIN DELETE FROM fact_terms; END") }
        val before = databaseFile.readBytes()
        current = repository()
        assertFalse(current.canStage(accepted))
        assertEquals(NotificationFactUnavailableReason.INVALID_SCHEMA, current.health().unavailableReason)
        assertTrue(before.contentEquals(databaseFile.readBytes()))
    }

    @Test
    fun corruptDatabaseUsesNonDeletingHandlerAndNeverCreatesAnEmptyReplacement() {
        stored(current.commit(batch())); current.close()
        RandomAccessFile(databaseFile, "rw").use { file -> file.seek(0); file.write(ByteArray(64) { 0x5a }) }
        val corrupt = databaseFile.readBytes()
        val seal = sealFile.readBytes()
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.health().available)
        assertTrue(databaseFile.isFile)
        assertTrue(corrupt.contentEquals(databaseFile.readBytes()))
        assertTrue(seal.contentEquals(sealFile.readBytes()))
    }

    @Test
    fun initializedButMissingDatabaseStaysMissingInsteadOfBeingRecreated() {
        stored(current.commit(batch())); current.close()
        val seal = sealFile.readBytes()
        assertTrue(databaseFile.delete())
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        assertEquals(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING, current.health().unavailableReason)
        assertFalse(databaseFile.exists())
        assertTrue(seal.contentEquals(sealFile.readBytes()))
    }

    @Test
    fun initializedButMissingSealNeverReinitializesTheExistingDatabase() {
        stored(current.commit(batch())); current.close()
        val before = databaseFile.readBytes()
        assertTrue(sealFile.delete())
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        assertFalse(sealFile.exists())
        assertTrue(before.contentEquals(databaseFile.readBytes()))
    }

    @Test
    fun malformedPendingIntentBlocksRecoveryWithoutClearingOrAcknowledgingAnything() {
        stored(current.commit(batch()))
        val intent = pending(current.beginPrivacy(NotificationFactPrivacyRequest("pending-corrupt", NotificationFactPrivacyScope.All)))
        current.close()
        rawDatabase { it.execSQL("UPDATE operations SET intent_json='{}' WHERE mutation_id='pending-corrupt'") }
        val before = databaseFile.readBytes()
        current = repository()
        assertNull(current.captureToken(PACKAGE))
        expectUnavailable(NotificationFactUnavailableReason.CORRUPT) { current.pendingIntents() }
        assertFalse(current.acknowledgePrivacy(intent))
        assertTrue(before.contentEquals(databaseFile.readBytes()))
    }

    @Test
    fun fractionalStringAndOverflowIntentFieldsFailClosedInsteadOfBeingCoerced() {
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", 1.9) },
            { it.getJSONObject("scope").put("expectedRevision", 1.9) },
            { it.put("removedFacts", 4_294_967_296L) },
            { it.put("allGeneration", "0") },
            { it.put("packageGeneration", 0.5) },
            { it.getJSONObject("scope").put("expectedRevision", "1") },
        )
        corruptions.forEachIndexed { index, corrupt ->
            val databaseName = "strict-intent-$index.db"
            val file = context.getDatabasePath(databaseName)
            val store = AndroidNotificationFactRepository(context, databaseName = databaseName,
                privacySnapshot = { privacy }, mutationMonitor = monitor).also(opened::add)
            val accepted = NotificationFactBatch(UUID.randomUUID().toString(),
                requireNotNull(store.captureToken(PACKAGE)), PACKAGE, "typed-intent-source", 1, 1,
                1_000, "extractor-1", listOf(candidate("Synthetic event")))
            val factId = stored(store.commit(accepted)).single()
            val intent = pending(store.beginPrivacy(NotificationFactPrivacyRequest("typed-intent-$index",
                NotificationFactPrivacyScope.Fact(factId, 1))))
            store.close()
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                val raw = db.rawQuery("SELECT intent_json FROM operations WHERE mutation_id=?",
                    arrayOf(intent.mutationId)).use { cursor ->
                    check(cursor.moveToFirst()); cursor.getString(0)
                }
                val malformed = JSONObject(raw).also(corrupt).toString()
                assertEquals(1, db.update("operations", ContentValues().apply {
                    put("intent_json", malformed)
                }, "mutation_id=?", arrayOf(intent.mutationId)))
            }
            val before = file.readBytes()
            val reopened = AndroidNotificationFactRepository(context, databaseName = databaseName,
                privacySnapshot = { privacy }, mutationMonitor = monitor).also(opened::add)
            expectUnavailable(NotificationFactUnavailableReason.CORRUPT) { reopened.pendingIntents() }
            assertFalse("Coerced privacy intent $index must never be acknowledged", reopened.acknowledgePrivacy(intent))
            assertEquals(NotificationFactUnavailableReason.CORRUPT, reopened.health().unavailableReason)
            assertTrue("No repair or purge may rewrite malformed intent $index", before.contentEquals(file.readBytes()))
        }
    }

    @Test
    fun closingRepositoryDoesNotExposeOrMutateTheRetainedStore() {
        val accepted = batch()
        stored(current.commit(accepted)); current.close()
        val before = databaseFile.readBytes()
        assertNull(current.captureToken(PACKAGE))
        assertFalse(current.canStage(accepted))
        expectUnavailable(NotificationFactUnavailableReason.CLOSED) { query() }
        assertEquals(NotificationFactCommitResult.Unavailable(NotificationFactUnavailableReason.CLOSED), current.commit(accepted))
        assertTrue(before.contentEquals(databaseFile.readBytes()))
    }

    private fun repository(capacity: NotificationFactCapacity = NotificationFactCapacity()) =
        AndroidNotificationFactRepository(context, capacity = capacity, privacySnapshot = { privacy },
            mutationMonitor = monitor, clock = { 50_000L }).also(opened::add)

    private fun reopen() { current.close(); current = repository() }
    private fun query(): List<NotificationFact> = current.query(NotificationFactQuery()).facts
    private val databaseFile: File get() = context.getDatabasePath(AndroidNotificationFactRepository.DEFAULT_DATABASE_NAME)
    private val sealFile: File get() = File(databaseFile.path + ".archive-state")

    private fun batch(
        packageName: String = PACKAGE,
        sourceRef: String = "source-a",
        quote: String = "Concert Friday",
        sequence: Long = 1,
    ) = NotificationFactBatch(UUID.randomUUID().toString(), requireNotNull(current.captureToken(packageName)),
        packageName, sourceRef, 1, sequence, sequence * 1_000, "extractor-1", listOf(candidate(quote)))

    private fun candidate(quote: String) = NotificationMemoryCandidate(
        NotificationMemoryKind.EVENT_DETAIL, NotificationMemorySourceField.TEXT, quote, 0, quote.length,
        MessageDigest.getInstance("SHA-256").digest(quote.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

    private fun stored(result: NotificationFactCommitResult): List<String> {
        assertTrue("Expected a committed synthetic batch, got $result", result is NotificationFactCommitResult.Stored)
        return (result as NotificationFactCommitResult.Stored).factIds
    }

    private fun pending(result: NotificationFactPrivacyBeginResult): NotificationFactPrivacyIntent {
        assertTrue("Expected a durable pending privacy intent, got $result", result is NotificationFactPrivacyBeginResult.Pending)
        return (result as NotificationFactPrivacyBeginResult.Pending).intent
    }

    private fun expectUnavailable(reason: NotificationFactUnavailableReason, action: () -> Any?) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected typed unavailable($reason), got $failure", failure is NotificationFactArchiveUnavailableException)
        assertEquals(reason, (failure as NotificationFactArchiveUnavailableException).reason)
    }

    private fun rawDatabase(action: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(databaseFile.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(action)
    }

    companion object {
        private const val PACKAGE = "example.messages"
        private const val OTHER_PACKAGE = "other.messages"
    }
}
