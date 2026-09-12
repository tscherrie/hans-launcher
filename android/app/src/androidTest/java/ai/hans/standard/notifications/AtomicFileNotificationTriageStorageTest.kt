package ai.hans.standard.notifications

import android.content.Context
import android.content.ContextWrapper
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import ai.hans.standard.phone.notifications.facts.AndroidNotificationFactRepository
import ai.hans.standard.phone.notifications.facts.NotificationFactExternalPrivacy
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyBeginResult
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyRequest
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyScope
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AtomicFileNotificationTriageStorageTest {
    private lateinit var context: Context
    private lateinit var fileName: String
    private lateinit var testDirectory: File
    private val openedArchives = mutableListOf<AndroidNotificationFactRepository>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        testDirectory = Files.createTempDirectory(app.cacheDir.toPath(), "notification-queue-v4-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = testDirectory
            override fun getDatabasePath(name: String): File {
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.db")))
                return File(testDirectory, name)
            }
        }
        fileName = "notification-triage-${UUID.randomUUID()}.json"
    }

    @After
    fun tearDown() {
        openedArchives.forEach { runCatching(it::close) }
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(testDirectory.parentFile == app.cacheDir && testDirectory.name.startsWith("notification-queue-v4-test-"))
        assertTrue(testDirectory.deleteRecursively())
    }

    @Test
    fun genuineFirstUseMaterializesASealedEmptyQueue() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)

        val status = storage.readStatus() as NotificationTriageStorageRead.Available

        assertTrue(status.state.records.isEmpty())
        assertTrue(status.state.factOutbox.isEmpty())
        assertEquals(0L, status.state.factOutboxCapacityDrops)
        assertTrue(context.noBackupFilesDir.resolve(fileName).isFile)
        assertTrue(context.noBackupFilesDir.resolve("$fileName.initialized").isFile)
        val document = JSONObject(context.noBackupFilesDir.resolve(fileName).readText())
        assertEquals(4, document.getInt("version"))
        assertEquals(V4_ROOT_FIELDS, document.keys().asSequence().toSet())
    }

    @Test
    fun backupOnlyQueueDocumentIsRecoveredInsteadOfMisreadAsEmpty() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        val queue = NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        queue.ingest(event(sequence = 40))
        val base = context.noBackupFilesDir.resolve(fileName)
        val backup = context.noBackupFilesDir.resolve("$fileName.bak")
        backup.delete()
        assertTrue(base.renameTo(backup))

        val reopened = AtomicFileNotificationTriageStorage(context, fileName)

        assertEquals(40L, reopened.read().records.single().envelope.sourceSequence)
    }

    @Test
    fun corruptBaseWithValidBackupRecoversTheBackupBeforePublishingHealth() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        val queue = NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        queue.ingest(event(sequence = 400))
        val base = context.noBackupFilesDir.resolve(fileName)
        val backup = context.noBackupFilesDir.resolve("$fileName.bak")
        backup.writeBytes(base.readBytes())
        base.writeText("{corrupt")

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        assertEquals(NotificationTriageQueueHealth.Available(1), reopened.health())
        assertEquals(400L, AtomicFileNotificationTriageStorage(context, fileName)
            .read().records.single().envelope.sourceSequence)
    }

    @Test
    fun exclusionPurgeRecoversBackupAndVerifiesDurableAbsence() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        ).ingest(event(sequence = 401))
        val base = context.noBackupFilesDir.resolve(fileName)
        val backup = context.noBackupFilesDir.resolve("$fileName.bak")
        backup.delete()
        assertTrue(base.renameTo(backup))

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)) {
                it == "com.example.chat"
            },
        )

        assertEquals(1, reopened.purgeExcluded())
        assertTrue(
            (AtomicFileNotificationTriageStorage(context, fileName).readStatus() as
                NotificationTriageStorageRead.Available).state.records.isEmpty(),
        )
    }

    @Test
    fun initializedQueueLossNeedsExplicitAllPrivacyRepairNotOrdinaryClear() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        storage.write(NotificationTriageQueueState(emptyList()))
        context.noBackupFilesDir.resolve(fileName).delete()
        context.noBackupFilesDir.resolve("$fileName.bak").delete()

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun sealedCorruptQueueNeedsExplicitAllPrivacyRepairNotOrdinaryClear() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        val queue = NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        queue.ingest(event(sequence = 402))
        context.noBackupFilesDir.resolve("$fileName.bak").delete()
        context.noBackupFilesDir.resolve(fileName).writeText("{corrupt")

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun backupOnlyInitializationSealIsRecoveredBeforeQueueHealthIsPublished() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        storage.write(NotificationTriageQueueState(emptyList()))
        val seal = context.noBackupFilesDir.resolve("$fileName.initialized")
        val backup = context.noBackupFilesDir.resolve("$fileName.initialized.bak")
        backup.delete()
        assertTrue(seal.renameTo(backup))

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        assertEquals(NotificationTriageQueueHealth.Available(0), reopened.health())
        assertTrue(seal.isFile)
    }

    @Test
    fun versionFourInvalidUuidIsUnavailableInsteadOfDroppingTheReceipt() {
        seedPendingQueue(501)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0).put("id", "not-a-uuid")
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourUnexpectedRootFieldIsUnavailableUntilVerifiedRepair() {
        seedPendingQueue(510)
        mutateVersionFour { root ->
            root.put("unexpectedRoot", "must-not-be-silently-lost")
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourUnexpectedRecordFieldIsUnavailableUntilVerifiedRepair() {
        seedPendingQueue(511)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0)
                .put("unexpectedRecord", "must-not-be-silently-lost")
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourSuggestionSecretFieldIsUnavailableUntilVerifiedRepair() {
        val queue = seedPendingQueue(512)
        val triage = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            triage.receipt.id,
            triage.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Wichtiger Termin.", NotificationUrgency.HIGH),
            ),
        )
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0)
                .getJSONObject("suggestion")
                .put("secret", "must-not-be-silently-lost")
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourExplicitNullOptionalFieldIsNotCanonical() {
        seedPendingQueue(513)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0)
                .put("claimToken", JSONObject.NULL)
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourDuplicateSourceSequenceIsUnavailableInsteadOfDeduplicated() {
        seedPendingQueue(502, 503)
        mutateVersionFour { root ->
            val records = root.getJSONArray("records")
            records.getJSONObject(1).put(
                "sourceSequence",
                records.getJSONObject(0).getLong("sourceSequence"),
            )
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourOversizedFieldIsUnavailableInsteadOfTruncated() {
        seedPendingQueue(504)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0).put(
                "title",
                "x".repeat(NotificationTriageBounds.MAX_TITLE_BYTES + 1),
            )
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourInconsistentClaimStateIsUnavailableInsteadOfReinterpreted() {
        seedPendingQueue(505)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0).apply {
                put("state", NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS.name)
                put("attempts", 1)
                remove("claimToken")
                remove("claimExpiresAt")
            }
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionFourInvalidCommittedReceiptIsUnavailableInsteadOfDropped() {
        val queue = seedPendingQueue(506)
        val triage = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            triage.receipt.id,
            triage.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Wichtiger Termin.", NotificationUrgency.HIGH),
            ),
        )
        val delivery = requireNotNull(queue.claimNextUserDelivery())
        queue.commitUserDeliveryForActivation(delivery.delivery.receiptId, delivery.claimToken)
        mutateVersionFour { root ->
            root.getJSONArray("records").getJSONObject(0).put("sourceSequence", 0)
        }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionTwoKnownTerminalRedactionMigrationRemainsExplicit() {
        writeHistoricalFixture(
            """{
              "version":2,"nextClaimPrefersNewest":false,"recentTriageClaims":[1000],
              "records":[{
                "id":"50700000-0000-4000-8000-000000000507","sourceSequence":507,
                "kind":"POSTED","observedAt":1,"packageName":"com.example.chat",
                "androidKey":"legacy-private-key","title":"legacy private title",
                "text":"legacy private body","subtext":"","category":"message",
                "channelId":"messages","ongoing":false,"clearable":true,
                "state":"DISMISSED_BY_TRIAGE","queuedAt":1000,"updatedAt":1001,
                "attempts":1,"deliveryAttempts":0,"dismissal":"NOT_ACTIONABLE"
              }]
            }""".trimIndent(),
        )

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        assertEquals(NotificationTriageQueueHealth.Available(1), reopened.health())
        val migrated = context.noBackupFilesDir.resolve(fileName).readText()
        assertTrue(migrated.contains("\"version\":4"))
        assertFalse(migrated.contains("legacy private"))
        assertFalse(migrated.contains("legacy-private-key"))
        val record = AtomicFileNotificationTriageStorage(context, fileName).read().records.single()
        assertEquals("50700000-0000-4000-8000-000000000507", record.id)
        assertEquals(NotificationDeliveryState.DISMISSED_BY_TRIAGE, record.state)
        assertEquals(NotificationDismissalReason.NOT_ACTIONABLE, record.dismissalReason)
    }

    @Test
    fun versionTwoUnknownFieldsRemainSeparatelyMigratableToCanonicalVersionFour() {
        writeHistoricalFixture(
            """{
              "version":2,"legacyRoot":"legacy-root",
              "nextClaimPrefersNewest":true,"recentTriageClaims":[],
              "records":[{
                "id":"51400000-0000-4000-8000-000000000514","sourceSequence":514,
                "kind":"POSTED","observedAt":1,"packageName":"com.example.chat",
                "androidKey":"legacy-key-514","title":"Title","text":"Text","subtext":"",
                "category":"message","channelId":"messages","ongoing":false,"clearable":true,
                "state":"SUGGESTED_TO_USER","queuedAt":1000,"updatedAt":1001,
                "attempts":1,"deliveryAttempts":0,"legacyRecord":"legacy-record",
                "suggestion":{"summary":"Wichtiger Termin.","urgency":"HIGH",
                  "legacySuggestion":"legacy-suggestion"}
              }]
            }""".trimIndent(),
        )

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        assertEquals(NotificationTriageQueueHealth.Available(1), reopened.health())
        val migrated = context.noBackupFilesDir.resolve(fileName).readText()
        assertTrue(migrated.contains("\"version\":4"))
        assertFalse(migrated.contains("legacy-root"))
        assertFalse(migrated.contains("legacy-record"))
        assertFalse(migrated.contains("legacy-suggestion"))
    }

    @Test
    fun receiptsAndUntrustedEnvelopeSurviveReopen() {
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        val queue = NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        val input = event(sequence = 41)
        queue.ingest(input)
        val work = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            id = work.receipt.id,
            claimToken = work.claimToken,
            decision = RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Eine wichtige Nachricht wartet.", NotificationUrgency.NORMAL),
            ),
        )

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        val receipt = reopened.receipts().single()
        assertEquals(41L, receipt.sourceSequence)
        assertEquals(NotificationDeliveryState.SUGGESTED_TO_USER, receipt.state)
        assertEquals("Eine wichtige Nachricht wartet.", receipt.suggestion?.summary)
        assertTrue(reopened.claimNextRestrictedTriage() == null)
    }

    @Test
    fun validatedDeliveryLeaseAndIdempotencyKeySurviveReopen() {
        val queue = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        queue.ingest(event(sequence = 42))
        val triage = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            triage.receipt.id,
            triage.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion("Dein Termin beginnt gleich.", NotificationUrgency.HIGH),
            ),
        )
        val delivery = requireNotNull(queue.claimNextUserDelivery())

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        val completion = reopened.commitUserDeliveryForActivation(
            delivery.delivery.receiptId,
            delivery.claimToken,
        ) as UserDeliveryCompletionResult.Accepted

        assertEquals(delivery.delivery.receiptId, delivery.delivery.idempotencyKey)
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            completion.receipt.state,
        )
        assertEquals(delivery.delivery, reopened.nextCommittedUserDelivery())
        assertEquals("Dein Termin beginnt gleich.", completion.receipt.suggestion?.summary)
    }

    @Test
    fun genuineVersionThreeMigratesPendingLiveLeasesAndCommittedReceiptWithoutReinterpretation() {
        // This literal predates outboxes/capture tokens; it is not produced by the v4 writer.
        val historical = JSONObject(HISTORICAL_VERSION_THREE)
        assertEquals(3, historical.getInt("version"))
        assertFalse(historical.has("factOutbox"))
        writeHistoricalFixture(HISTORICAL_VERSION_THREE)
        val storage = AtomicFileNotificationTriageStorage(context, fileName)

        val available = storage.readStatus() as NotificationTriageStorageRead.Available
        val state = available.state
        assertEquals(listOf(PENDING_ID, RESTRICTED_ID, DELIVERY_ID, COMMITTED_ID), state.records.map { it.id })
        assertEquals(listOf(701L, 702L, 703L, 704L), state.records.map { it.envelope.sourceSequence })
        assertEquals(listOf(NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE,
            NotificationDeliveryState.RESTRICTED_TRIAGE_IN_PROGRESS,
            NotificationDeliveryState.USER_DELIVERY_IN_PROGRESS,
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION), state.records.map { it.state })
        assertFalse(state.nextRestrictedClaimPrefersNewest)
        assertEquals(listOf(100_000L, 100_010L), state.recentTriageClaimEpochMillis)
        assertEquals(RESTRICTED_LEASE, state.records[1].claimToken)
        assertEquals(160_000L, state.records[1].claimExpiresAtEpochMillis)
        assertEquals(DELIVERY_LEASE, state.records[2].userDeliveryClaimToken)
        assertEquals(160_000L, state.records[2].userDeliveryClaimExpiresAtEpochMillis)
        assertTrue(state.records.all { it.factCaptureToken == null })
        assertTrue(state.factOutbox.isEmpty())
        assertEquals(0L, state.factOutboxCapacityDrops)

        val migrated = JSONObject(context.noBackupFilesDir.resolve(fileName).readText())
        assertEquals(4, migrated.getInt("version"))
        assertEquals(V4_ROOT_FIELDS, migrated.keys().asSequence().toSet())
        assertJsonEqual(historical.getJSONArray("records"), migrated.getJSONArray("records"))
        assertJsonEqual(historical.getJSONArray("recentTriageClaims"), migrated.getJSONArray("recentTriageClaims"))
        val written = context.noBackupFilesDir.resolve(fileName).readBytes()
        assertEquals(state, AtomicFileNotificationTriageStorage(context, fileName).read())
        assertTrue(written.contentEquals(context.noBackupFilesDir.resolve(fileName).readBytes()))

        val queue = NotificationTriageQueue(storage, HansNotificationExclusionPolicy(setOf(context.packageName)),
            clock = { HISTORICAL_NOW })
        val committed = requireNotNull(queue.nextCommittedUserDelivery())
        assertEquals(COMMITTED_ID, committed.receiptId)
        assertEquals(COMMITTED_ID, committed.idempotencyKey)
        assertEquals("Already committed event.", committed.suggestion.summary)
        assertTrue(queue.completeRestrictedTriage(RESTRICTED_ID, RESTRICTED_LEASE,
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE)) is
            TriageCompletionResult.Accepted)
        assertTrue(queue.commitUserDeliveryForActivation(DELIVERY_ID, DELIVERY_LEASE) is
            UserDeliveryCompletionResult.Accepted)
        assertEquals(PENDING_ID, requireNotNull(queue.claimNextRestrictedTriage()).receipt.id)
        assertTrue(storage.read().records.any {
            it.id == COMMITTED_ID && it.state == NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION
        })
    }

    @Test
    fun unknownFieldInGenuineVersionThreeDoesNotSilentlyDropACommittedReceipt() {
        val historical = JSONObject(HISTORICAL_VERSION_THREE)
            .put("unknownVersionThreeField", "must-remain-unmodified-until-owner-forget")
        writeHistoricalFixture(historical.toString())

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun futureVersionIsUnavailableAndOrdinaryClearCannotRewriteItAsEmpty() {
        seedPendingQueue(601)
        mutateVersionFour { it.put("version", 5) }

        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun malformedUtf8InsideOtherwiseValidJsonIsRejectedRatherThanReplaced() {
        val malformedSequences = listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte()),
        )
        malformedSequences.forEachIndexed { index, malformed ->
            seedPendingQueue(610L + index)
            val file = context.noBackupFilesDir.resolve(fileName)
            val raw = file.readText()
            val marker = "\"text\":\"Text\""
            val offset = raw.indexOf(marker)
            check(offset >= 0 && raw.indexOf(marker, offset + 1) == -1)
            val prefix = raw.substring(0, offset) + "\"text\":\""
            val suffix = "\"" + raw.substring(offset + marker.length)
            context.noBackupFilesDir.resolve("$fileName.bak").delete()
            file.writeBytes(prefix.toByteArray(Charsets.UTF_8) + malformed + suffix.toByteArray(Charsets.UTF_8))

            assertUnavailableUntilExplicitAllPrivacyRepair()
        }
    }

    @Test
    fun duplicateRootNestedAndEscapedJsonKeysCannotBeCollapsedByJSONObject() {
        val replacements = listOf<Pair<String, String>>(
            "\"version\":4" to "\"version\":4,\"version\":4",
            "\"title\":\"Title\"" to "\"title\":\"Title\",\"title\":\"Title\"",
            "\"version\":4" to "\"version\":4,\"\\u0076ersion\":4",
        )
        replacements.forEachIndexed { index, (original, duplicate) ->
            seedPendingQueue(620L + index)
            val file = context.noBackupFilesDir.resolve(fileName)
            val raw = file.readText()
            check(raw.contains(original))
            context.noBackupFilesDir.resolve("$fileName.bak").delete()
            file.writeText(raw.replace(original, duplicate))

            assertUnavailableUntilExplicitAllPrivacyRepair()
        }
    }

    @Test
    fun versionFourCaptureTokenAndOutboxPersistAcrossReopenAndOrdinaryHistoryClear() {
        val archive = isolatedArchive()
        val storage = AtomicFileNotificationTriageStorage(context, fileName)
        val queue = NotificationTriageQueue(storage, HansNotificationExclusionPolicy(setOf(context.packageName)),
            clock = { HISTORICAL_NOW }, factArchive = archive)
        val text = "Concert Friday"
        queue.ingest(event(630).let { it.copy(snapshot = it.snapshot.copy(text = text)) })
        val triage = requireNotNull(queue.claimNextRestrictedTriage())
        val leased = storage.read()
        assertEquals(archive.captureToken("com.example.chat"), leased.records.single().factCaptureToken)
        assertEquals(leased, AtomicFileNotificationTriageStorage(context, fileName).read())
        assertTrue(JSONObject(context.noBackupFilesDir.resolve(fileName).readText())
            .getJSONArray("records").getJSONObject(0).has("factCaptureToken"))
        val candidate = NotificationMemoryCandidate(NotificationMemoryKind.EVENT_DETAIL,
            NotificationMemorySourceField.TEXT, text, 0, text.length,
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertTrue(queue.completeRestrictedTriage(triage.receipt.id, triage.claimToken,
            RestrictedTriageDecision.NotRelevant(NotificationDismissalReason.NOT_ACTIONABLE,
                memoryCandidates = listOf(candidate))) is TriageCompletionResult.Accepted)
        val staged = storage.read()
        val batch = staged.factOutbox.single().batch
        assertEquals(listOf(candidate), batch.validatedCandidates)
        assertEquals(leased.records.single().factCaptureToken, batch.token)
        assertEquals(triage.receipt.id, batch.batchId)
        assertEquals("source_${triage.receipt.id}", batch.sourceRef)
        assertEquals(staged, AtomicFileNotificationTriageStorage(context, fileName).read())

        assertTrue(queue.clearAll())
        val cleared = AtomicFileNotificationTriageStorage(context, fileName).read()
        assertTrue(cleared.records.isEmpty())
        assertEquals(staged.factOutbox, cleared.factOutbox)
        assertTrue(queue.repairToVerifiedEmpty())
        assertEquals(staged.factOutbox, AtomicFileNotificationTriageStorage(context, fileName).read().factOutbox)
        assertEquals(NotificationTriageQueueHealth.Available(0, 1), queue.health())
        // Even with no raw history left, unreadability must not silently discard the selected claim.
        mutateVersionFour { it.put("unknownOutboxContainer", true) }
        assertUnavailableUntilExplicitAllPrivacyRepair()
    }

    @Test
    fun versionOneBacklogIsTerminalizedBeforeAnyAutomaticDrainCanClaimIt() {
        writeHistoricalFixture(
            """{
              "version":1,"records":[{
                "id":"43000000-0000-4000-8000-000000000043","sourceSequence":43,
                "kind":"POSTED","observedAt":1,"packageName":"com.example.chat",
                "androidKey":"legacy-key-43","title":"Legacy title","text":"Legacy body",
                "subtext":"","category":"message","channelId":"messages",
                "ongoing":false,"clearable":true,"state":"PENDING_RESTRICTED_TRIAGE",
                "queuedAt":1,"updatedAt":1,"attempts":0
              }]
            }""".trimIndent(),
        )
        val file = context.noBackupFilesDir.resolve(fileName)

        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )

        assertTrue(reopened.claimNextRestrictedTriage() == null)
        assertEquals(NotificationDeliveryState.TRIAGE_FAILED, reopened.receipts().single().state)
        assertEquals("43000000-0000-4000-8000-000000000043", reopened.receipts().single().id)
        assertEquals(43L, reopened.receipts().single().sourceSequence)
        val migrated = file.readText()
        assertTrue(migrated.contains("\"version\":4"))
        assertFalse(migrated.contains("Legacy body"))
        assertFalse(migrated.contains("Legacy title"))
        assertFalse(migrated.contains("legacy-key-43"))
    }

    private fun seedPendingQueue(vararg sequences: Long): NotificationTriageQueue {
        val queue = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        sequences.forEach { sequence -> queue.ingest(event(sequence)) }
        return queue
    }

    private fun mutateVersionFour(block: (JSONObject) -> Unit) {
        val state = context.noBackupFilesDir.resolve(fileName)
        val root = JSONObject(state.readText())
        assertEquals("Corruption fixture must start with the actual v4 writer", 4, root.getInt("version"))
        block(root)
        context.noBackupFilesDir.resolve("$fileName.bak").delete()
        state.writeText(root.toString())
    }

    private fun assertUnavailableUntilExplicitAllPrivacyRepair() {
        val reopened = NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context, fileName),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        )
        val before = queueFilesSnapshot()
        assertEquals(NotificationTriageQueueHealth.Unavailable, reopened.health())
        assertFalse(reopened.clearAll())
        assertFalse(reopened.repairToVerifiedEmpty())
        assertFalse(reopened.purgeFactOutbox(NotificationFactPrivacyScope.Package("com.example.chat")))
        assertFalse(reopened.purgeFactOutbox(NotificationFactPrivacyScope.Source("com.example.chat", "synthetic-source")))
        assertFalse(reopened.purgeFactOutbox(NotificationFactPrivacyScope.Fact("synthetic-fact", 1)))
        assertQueueFilesUnchanged(before)
        assertEquals(NotificationTriageQueueHealth.Unavailable, reopened.health())

        // This test acts as the trusted coordinator. The Queue API accepts a scope, not a grant:
        // the durable all-data intent must exist BEFORE the exceptional destructive repair call.
        val archive = isolatedArchive()
        val begin = archive.beginPrivacy(NotificationFactPrivacyRequest(UUID.randomUUID().toString(),
            NotificationFactPrivacyScope.All))
        assertTrue(begin is NotificationFactPrivacyBeginResult.Pending)
        val intent = (begin as NotificationFactPrivacyBeginResult.Pending).intent
        assertEquals(listOf(intent), archive.pendingIntents())
        assertFalse(archive.health().available)
        assertQueueFilesUnchanged(before)
        assertTrue(reopened.purgeFactOutbox(intent.scope))
        assertEquals(NotificationTriageQueueHealth.Available(0), reopened.health())
        val repaired = JSONObject(context.noBackupFilesDir.resolve(fileName).readText())
        assertEquals(V4_ROOT_FIELDS, repaired.keys().asSequence().toSet())
        assertEquals(4, repaired.getInt("version"))
        assertEquals(0, repaired.getJSONArray("records").length())
        assertEquals(0, repaired.getJSONArray("factOutbox").length())
        assertEquals(0L, repaired.getLong("factOutboxCapacityDrops"))
        assertEquals(NotificationTriageQueueState(emptyList()),
            AtomicFileNotificationTriageStorage(context, fileName).read())
        assertTrue(archive.acknowledgePrivacy(intent))
        assertTrue(archive.pendingIntents().isEmpty())
    }

    private fun isolatedArchive(): AndroidNotificationFactRepository = AndroidNotificationFactRepository(
        context,
        privacySnapshot = { NotificationFactExternalPrivacy(true, false) },
    ).also(openedArchives::add)

    private fun queueFilesSnapshot(): Map<String, ByteArray?> = listOf(
        "", ".bak", ".new", ".initialized", ".initialized.bak", ".initialized.new",
    ).associateWith { suffix ->
        context.noBackupFilesDir.resolve(fileName + suffix).let { if (it.exists()) it.readBytes() else null }
    }

    private fun assertQueueFilesUnchanged(before: Map<String, ByteArray?>) {
        val after = queueFilesSnapshot()
        assertEquals(before.keys, after.keys)
        before.forEach { (suffix, bytes) ->
            if (bytes == null) assertNull("No new queue artifact is authorized: $suffix", after[suffix])
            else assertTrue("Existing queue artifact must remain byte-identical: $suffix",
                after[suffix]?.contentEquals(bytes) == true)
        }
    }

    private fun writeHistoricalFixture(raw: String) {
        assertFalse(context.noBackupFilesDir.resolve(fileName).exists())
        assertFalse(context.noBackupFilesDir.resolve("$fileName.initialized").exists())
        context.noBackupFilesDir.resolve(fileName).writeText(raw, Charsets.UTF_8)
    }

    private fun assertJsonEqual(expected: Any?, actual: Any?) {
        when (expected) {
            is JSONObject -> {
                assertTrue(actual is JSONObject)
                actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals(keys, actual.keys().asSequence().toSet())
                keys.forEach { assertJsonEqual(expected.get(it), actual.get(it)) }
            }
            is JSONArray -> {
                assertTrue(actual is JSONArray)
                actual as JSONArray
                assertEquals(expected.length(), actual.length())
                repeat(expected.length()) { assertJsonEqual(expected.get(it), actual.get(it)) }
            }
            else -> assertEquals(expected, actual)
        }
    }

    private fun event(sequence: Long): NotificationInboxEvent = NotificationInboxEvent(
        sequence = sequence,
        kind = NotificationEventKind.POSTED,
        observedAtEpochMillis = 1,
        removalReason = null,
        snapshot = NotificationSnapshot(
            packageName = "com.example.chat",
            androidKey = "key-$sequence",
            postTimeEpochMillis = 1,
            notificationWhenEpochMillis = 1,
            title = "Title",
            text = "Text",
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
    )

    companion object {
        private const val HISTORICAL_NOW = 100_020L
        private const val PENDING_ID = "70100000-0000-4000-8000-000000000701"
        private const val RESTRICTED_ID = "70200000-0000-4000-8000-000000000702"
        private const val DELIVERY_ID = "70300000-0000-4000-8000-000000000703"
        private const val COMMITTED_ID = "70400000-0000-4000-8000-000000000704"
        private const val RESTRICTED_LEASE = "71200000-0000-4000-8000-000000000712"
        private const val DELIVERY_LEASE = "71300000-0000-4000-8000-000000000713"
        private val V4_ROOT_FIELDS = setOf("version", "nextClaimPrefersNewest", "recentTriageClaims",
            "records", "factOutbox", "factOutboxCapacityDrops")

        /** Hand-authored historical bytes, with neither an outbox nor v4 capture-token fields. */
        private val HISTORICAL_VERSION_THREE = """{
          "version":3,"nextClaimPrefersNewest":false,"recentTriageClaims":[100000,100010],
          "records":[
            {
              "id":"70100000-0000-4000-8000-000000000701","sourceSequence":701,
              "kind":"POSTED","observedAt":90000,"packageName":"com.example.chat",
              "androidKey":"historic-pending-key","title":"Pending title","text":"Pending body",
              "subtext":"","category":"message","channelId":"messages",
              "ongoing":false,"clearable":true,"state":"PENDING_RESTRICTED_TRIAGE",
              "queuedAt":100000,"updatedAt":100000,"attempts":0,"deliveryAttempts":0
            },
            {
              "id":"70200000-0000-4000-8000-000000000702","sourceSequence":702,
              "kind":"POSTED","observedAt":90001,"packageName":"com.example.chat",
              "androidKey":"historic-restricted-key","title":"Restricted title","text":"Restricted body",
              "subtext":"","category":"message","channelId":"messages",
              "ongoing":false,"clearable":true,"state":"RESTRICTED_TRIAGE_IN_PROGRESS",
              "queuedAt":100000,"updatedAt":100010,"attempts":1,"deliveryAttempts":0,
              "claimToken":"71200000-0000-4000-8000-000000000712","claimExpiresAt":160000
            },
            {
              "id":"70300000-0000-4000-8000-000000000703","sourceSequence":703,
              "kind":"POSTED","observedAt":90002,"packageName":"com.example.chat",
              "androidKey":"historic-delivery-key","title":"Delivery title","text":"Delivery body",
              "subtext":"","category":"message","channelId":"messages",
              "ongoing":false,"clearable":true,"state":"USER_DELIVERY_IN_PROGRESS",
              "queuedAt":100000,"updatedAt":100010,"attempts":1,"deliveryAttempts":1,
              "suggestion":{"summary":"Live leased event.","urgency":"HIGH"},
              "deliveryClaimToken":"71300000-0000-4000-8000-000000000713",
              "deliveryClaimExpiresAt":160000
            },
            {
              "id":"70400000-0000-4000-8000-000000000704","sourceSequence":704,
              "kind":"POSTED","observedAt":90003,"packageName":"com.example.chat",
              "androidKey":"historic-committed-key","title":"Committed title","text":"Committed body",
              "subtext":"","category":"message","channelId":"messages",
              "ongoing":false,"clearable":true,"state":"DELIVERY_COMMITTED_PENDING_ACTIVATION",
              "queuedAt":100000,"updatedAt":100010,"attempts":1,"deliveryAttempts":1,
              "suggestion":{"summary":"Already committed event.","urgency":"HIGH"}
            }
          ]
        }""".trimIndent()
    }
}
