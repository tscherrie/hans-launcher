package ai.hans.standard.integration

import android.content.Context
import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationDeliveryState
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueState
import ai.hans.standard.notifications.NotificationTriageStorage
import ai.hans.standard.notifications.NotificationValidatedDeliveryAuthority
import ai.hans.standard.notifications.RestrictedTriageDecision
import ai.hans.standard.notifications.UserDeliveryCompletionResult
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationActivationDisposition
import ai.hans.standard.notifications.UserFacingDeliveryDisposition
import ai.hans.standard.notifications.UserFacingNotificationSuggestion
import ai.hans.standard.notifications.UserFacingNotificationSuggestionSink
import ai.hans.standard.phone.notifications.NotificationActionMetadata
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationSnapshot
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
class ValidatedNotificationAnnouncementCenterTest {
    private lateinit var context: Context
    private lateinit var fileName: String
    private lateinit var deliveryFenceFileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "validated-announcement-test-${System.nanoTime()}.json"
        deliveryFenceFileName = "$fileName.delivery-fence"
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        // First initialization is itself a coordinated destructive baseline: missing center files
        // remain blocked until Queue + Center have both been verified empty.
        val bootstrap = ValidatedNotificationAnnouncementCenter(
            context = context,
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(sharedFence.isRequired())
        assertTrue(bootstrap.clearAll())
        assertTrue(sharedFence.markClean())
        NotificationValidatedDeliveryAuthority.markReady()
    }

    @After
    fun tearDown() {
        NotificationValidatedDeliveryAuthority.quarantine()
        context.noBackupFilesDir.resolve(fileName).delete()
        context.noBackupFilesDir.resolve("$fileName.bak").delete()
        context.noBackupFilesDir.resolve("$fileName.new").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-generation").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-generation.bak").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-generation.new").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-fence").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-fence.bak").delete()
        context.noBackupFilesDir.resolve("$fileName.privacy-fence.new").delete()
        context.noBackupFilesDir.resolve(deliveryFenceFileName).delete()
        context.noBackupFilesDir.resolve("$deliveryFenceFileName.bak").delete()
        context.noBackupFilesDir.resolve("$deliveryFenceFileName.new").delete()
    }

    @Test
    fun acceptanceIsDurableIdempotentAndRestartSkipsUnspokenBacklog() {
        var now = 122L
        val delivery = UserFacingNotificationDelivery(
            receiptId = "receipt-1",
            idempotencyKey = "stable-key-1",
            suggestion = UserFacingNotificationSuggestion(
                summary = "  Dein   Termin\n beginnt gleich.  ",
                urgency = NotificationUrgency.HIGH,
            ),
            sourceReceivedAtEpochMillis = 123L,
        )
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { now },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )

        now = 123L
        assertTrue(center.accept(delivery, timelineAnchorId = "agent-message-7"))
        assertTrue(center.accept(delivery, timelineAnchorId = "ignored-duplicate-anchor"))
        val accepted = center.snapshot().single()
        assertEquals("Dein Termin beginnt gleich.", accepted.summary)
        assertTrue(accepted.id.startsWith("notification:"))
        assertNull(accepted.spokenAtEpochMillis)
        assertNull(accepted.contextInjectedAtEpochMillis)
        assertEquals("agent-message-7", accepted.timelineAnchorId)
        assertEquals(123L, accepted.sourceReceivedAtEpochMillis)
        assertEquals(listOf(accepted.id), center.pendingSpeech().map { it.id })

        now = 456L
        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { now },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.snapshot().single().speechSuppressed)
        assertNull(reopened.snapshot().single().spokenAtEpochMillis)
        assertEquals(listOf(accepted.id), reopened.pendingContext().map { it.id })
        assertEquals("agent-message-7", reopened.snapshot().single().timelineAnchorId)
        assertTrue(reopened.markContextInjected(setOf(accepted.id)))
        assertEquals(456L, reopened.snapshot().single().contextInjectedAtEpochMillis)
        assertTrue(reopened.pendingContext().isEmpty())
        now = 457L
        assertTrue(reopened.accept(delivery.copy(
            receiptId = "fresh-receipt",
            idempotencyKey = "fresh-key",
            sourceReceivedAtEpochMillis = now,
        )))
        val fresh = reopened.pendingSpeech().single()
        assertTrue(reopened.markSpoken(fresh.id))
        assertEquals(457L, reopened.snapshot().single { it.id == fresh.id }.spokenAtEpochMillis)
        assertTrue(reopened.pendingSpeech().isEmpty())
    }

    @Test
    fun atomicSpeechSuppressionRoundTripsAndLegacyRecordsRemainVisibleButSilent() {
        var now = 100L
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { now },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        now = 101L
        assertTrue(center.accept(delivery("muted-atomic", "Wichtige Meldung.").copy(
            sourceReceivedAtEpochMillis = now,
        )))
        assertEquals(1, center.pendingSpeech().size)
        assertTrue(center.suppressSpeechThrough(now))
        val stateFile = context.noBackupFilesDir.resolve(fileName)
        val document = JSONArray(stateFile.readText())
        assertEquals(101L, document.getJSONObject(0).getLong("sourceReceivedAt"))
        assertTrue(document.getJSONObject(0).getBoolean("speechSuppressed"))
        assertTrue(document.getJSONObject(0).isNull("spokenAt"))

        // Previous canonical schema contains neither receipt provenance nor speech suppression.
        document.getJSONObject(0).remove("sourceReceivedAt")
        document.getJSONObject(0).remove("speechSuppressed")
        stateFile.writeText(document.toString())
        now = 200L
        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { now },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertEquals(1, reopened.pendingContext().size)
        assertEquals("Wichtige Meldung.", reopened.snapshot().single().summary)
        assertNull(reopened.snapshot().single().sourceReceivedAtEpochMillis)
        assertNull(reopened.snapshot().single().spokenAtEpochMillis)
        assertTrue(reopened.snapshot().single().speechSuppressed)
    }

    @Test
    fun unknownListenerAuthorityHidesUiSpeechAndContextUntilDurableRevocationFinishes() {
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val item = delivery(
            idempotencyKey = "authority-item",
            summary = "Nur mit aktuellem Android-Snapshot sichtbar.",
            supersessionKey = "source:" + "a".repeat(64),
        )
        assertTrue(center.accept(item))
        assertEquals(1, center.snapshot().size)

        NotificationValidatedDeliveryAuthority.quarantine()

        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        // The trusted snapshot/outbox path can still prove durable deletion while publication is
        // closed; it does not need to reopen UI/TTS/context to revoke the stale record.
        assertTrue(center.removePendingBySupersessionKey(item.supersessionKey))
        NotificationValidatedDeliveryAuthority.markReady()
        assertTrue(center.snapshot().isEmpty())
    }

    @Test
    fun stagedSuggestionIsPrivateUntilExactQueueActivationAndCanBeRevoked() {
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 10L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val delivery = delivery(
            idempotencyKey = "stage-key",
            summary = "Noch nicht sichtbar.",
            supersessionKey = "source:" + "a".repeat(64),
        )
        val observations = mutableListOf<List<ValidatedNotificationAnnouncement>>()
        center.addObserver { observations += it }

        assertTrue(center.stage(delivery))
        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.pendingContext().isEmpty())
        assertTrue(observations.flatten().isEmpty())

        assertTrue(center.activate(delivery.idempotencyKey))
        assertEquals("Noch nicht sichtbar.", center.snapshot().single().summary)
        assertEquals(1, observations.last().size)

        // This is an independent notification. Using the same supersession key here would
        // intentionally replace the first item before activation and would test supersession,
        // not private staging/rollback.
        val stagedForRollback = delivery(
            idempotencyKey = "rollback-key",
            summary = "Wird widerrufen.",
            supersessionKey = "source:" + "c".repeat(64),
        )
        assertTrue(center.stage(stagedForRollback))
        assertTrue(center.revokePending(stagedForRollback.idempotencyKey))
        assertEquals(1, center.snapshot().size)
        assertEquals(1, center.allRecords().size)

        // A queue-storage failure is retryable. A successfully removed staged receipt must not
        // leave an in-memory privacy tombstone that blocks its later durable retry.
        assertTrue(center.stage(stagedForRollback))
        assertTrue(center.activate(stagedForRollback.idempotencyKey))
        assertEquals(
            listOf("Noch nicht sichtbar.", "Wird widerrufen."),
            center.snapshot().map { it.summary },
        )
    }

    @Test
    fun supersessionRemovesEitherPartiallyCompletedStateButKeepsFullyCompletedHistory() {
        var now = 20L
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { now++ },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val key = "source:" + "a".repeat(64)

        val spokenOnly = delivery("spoken-only", "Alt eins.", key)
        assertTrue(center.accept(spokenOnly))
        assertTrue(center.markSpoken(center.snapshot().single().id))
        assertTrue(center.accept(delivery("replacement-one", "Neu eins.", key)))
        assertEquals(listOf("Neu eins."), center.snapshot().map { it.summary })

        val contextOnly = delivery("context-only", "Alt zwei.", key)
        assertTrue(center.accept(contextOnly))
        val contextOnlyId = center.snapshot().single { it.summary == "Alt zwei." }.id
        assertTrue(center.markContextInjected(setOf(contextOnlyId)))
        assertTrue(center.accept(delivery("replacement-two", "Neu zwei.", key)))
        assertEquals(listOf("Neu zwei."), center.snapshot().map { it.summary })

        val complete = delivery("complete", "Historie.", key)
        assertTrue(center.accept(complete))
        val completeId = center.snapshot().single { it.summary == "Historie." }.id
        assertTrue(center.markSpoken(completeId))
        assertTrue(center.markContextInjected(setOf(completeId)))
        assertTrue(center.accept(delivery("current", "Aktuell.", key)))
        assertEquals(
            listOf("Historie.", "Aktuell."),
            center.snapshot().map { it.summary },
        )
    }

    @Test
    fun durableFenceHidesOldRecordsAcrossReopenWhenOnlyPhysicalClearFails() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val fence = FakeAnnouncementPrivacyFence()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = fence,
        )
        assertTrue(first.accept(delivery("private", "Private Meldung.")))
        assertEquals(1, first.snapshot().size)

        storage.failWrites = true
        assertFalse(first.clearAll())
        assertEquals(1, storage.items.size)
        assertEquals(1L, generation.current())
        assertTrue(fence.isRequired())

        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = fence,
        )
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.pendingContext().isEmpty())
    }

    @Test
    fun durableFenceHidesRecordsAcrossReopenWhenStorageAndGenerationBothFail() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val fence = FakeAnnouncementPrivacyFence()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = fence,
        )
        assertTrue(first.accept(delivery("private-dual-failure", "Private Meldung.")))

        storage.failWrites = true
        generation.failAdvances = true
        assertFalse(first.clearAll())
        assertTrue(fence.isRequired())
        assertEquals(1, storage.items.size)

        val reopenedWhileBroken = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = fence,
        )
        assertTrue(reopenedWhileBroken.snapshot().isEmpty())
        assertTrue(reopenedWhileBroken.pendingSpeech().isEmpty())
        assertTrue(reopenedWhileBroken.pendingContext().isEmpty())
        assertTrue(fence.isRequired())

        storage.failWrites = false
        generation.failAdvances = false
        val recovered = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 40L },
            privacyFence = fence,
        )
        assertFalse(fence.isRequired())
        assertTrue(storage.items.isEmpty())
        assertTrue(recovered.snapshot().isEmpty())
    }

    @Test
    fun sharedDeliveryFenceHidesAllOldRecordsAcrossReopenUntilCombinedPurgeCompletes() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val localFence = FakeAnnouncementPrivacyFence()
        val deliveryFence = FakeDeliveryPrivacyFence()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        val private = delivery("global-purge-private", "Private Meldung.")
        assertTrue(first.accept(private))
        assertEquals(1, first.snapshot().size)

        assertTrue(deliveryFence.markRequired())
        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.pendingContext().isEmpty())
        assertFalse(reopened.stage(delivery("new-while-purging", "Darf nicht erscheinen.")))

        // The global privacy coordinator clears Queue and Center before publishing CLEAN.
        assertTrue(reopened.clearAll())
        assertTrue(deliveryFence.markClean())
        val recovered = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 40L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(recovered.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun coordinatedFreshGenerationRetryClearsOnlyTheFailedRevokeTombstone() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val sharedFence = FakeDeliveryPrivacyFence()
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            deliveryFence = sharedFence,
        )
        val item = delivery("retry-after-failed-revoke", "Wichtige neue Meldung.")

        assertTrue(center.stage(item))
        storage.failWrites = true
        assertFalse(center.revokePending(item.idempotencyKey))
        assertTrue(center.snapshot().isEmpty())

        storage.failWrites = false
        // Recovering a failed local privacy mutation deliberately raises the shared Queue/Center
        // fence. New publication remains blocked until their coordinated purge is complete.
        assertFalse(center.stage(item))
        assertTrue(sharedFence.isRequired())
        assertTrue(center.snapshot().isEmpty())
        assertTrue(center.clearAll())
        assertTrue(sharedFence.markClean())

        assertTrue(center.stage(item))
        assertTrue(center.activate(item.idempotencyKey))
        assertEquals(listOf("Wichtige neue Meldung."), center.snapshot().map { it.summary })
    }

    @Test
    fun failedSpokenCompletionWriteNeverLeavesAlreadyHeardAudioReplayable() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val fence = FakeAnnouncementPrivacyFence()
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = fence,
        )
        val item = delivery("spoken-write-failure", "Diese Meldung wurde bereits gehört.")
        assertTrue(center.accept(item))
        val id = center.snapshot().single().id

        storage.failWrites = true
        assertFalse(center.markSpoken(id))
        assertTrue(fence.isRequired())
        assertTrue(center.pendingSpeech().isEmpty())
        assertTrue(center.snapshot().isEmpty())

        // Process recreation retains the durable fence. Once storage recovers, initialization
        // destroys the stale pre-completion record instead of replaying it.
        storage.failWrites = false
        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = fence,
        )
        assertTrue(reopened.pendingSpeech().isEmpty())
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun unavailableStageStorageRaisesSharedFenceAndCannotActivateUntilCoordinatedPurge() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val localFence = FakeAnnouncementPrivacyFence()
        val deliveryFence = FakeDeliveryPrivacyFence()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        val committedPendingActivation = delivery(
            "committed-pending-corrupt-stage",
            "Darf nach beschädigtem Speicher nie verspätet erscheinen.",
        )
        assertTrue(first.stage(committedPendingActivation))
        assertEquals(1, storage.items.size)

        storage.unavailableReads = true
        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(deliveryFence.isRequired())
        assertTrue(reopened.snapshot().isEmpty())
        assertFalse(reopened.activate(committedPendingActivation.idempotencyKey))

        storage.unavailableReads = false
        assertTrue(reopened.clearAll())
        assertTrue(deliveryFence.markClean())
        val recovered = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 40L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(recovered.snapshot().isEmpty())
        assertTrue(recovered.accept(delivery("after-corruption-repair", "Neue Meldung.")))
        assertEquals(listOf("Neue Meldung."), recovered.snapshot().map { it.summary })
    }

    @Test
    fun corruptGenerationCanOnlyRecoverAfterDestructiveResetAndSharedQueueFence() {
        val storage = FakeAnnouncementStorage()
        val generation = FakePrivacyGenerationStore()
        val localFence = FakeAnnouncementPrivacyFence()
        val deliveryFence = FakeDeliveryPrivacyFence()
        val first = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 20L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(first.accept(delivery("before-generation-corruption", "Private Meldung.")))

        generation.corrupt = true
        val reopened = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 30L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(deliveryFence.isRequired())
        assertTrue(reopened.snapshot().isEmpty())
        assertFalse(generation.corrupt)

        // Only the owner of the combined Queue+Center purge may publish the shared clean marker.
        assertTrue(reopened.clearAll())
        assertTrue(deliveryFence.markClean())
        val recovered = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generation,
            clock = { 40L },
            privacyFence = localFence,
            deliveryFence = deliveryFence,
        )
        assertTrue(recovered.snapshot().isEmpty())
        assertTrue(recovered.accept(delivery("after-generation-repair", "Neue Meldung.")))
    }

    @Test
    fun literalZeroGenerationRequiresSharedDestructiveRepairThenPersistsPositiveActivation() {
        val generationFile = context.noBackupFilesDir.resolve("$fileName.privacy-generation")
        context.noBackupFilesDir.resolve("$fileName.privacy-generation.bak").delete()
        generationFile.writeText("0")
        val generationStore = AtomicNotificationPrivacyGenerationStore(generationFile)
        assertNull(generationStore.current())

        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 50L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        assertTrue(sharedFence.isRequired())
        assertTrue(reopened.snapshot().isEmpty())
        assertFalse(reopened.stage(delivery("blocked-at-zero", "Darf nicht erscheinen.")))
        assertTrue(requireNotNull(generationStore.current()) > 0L)

        val queue = NotificationTriageQueue(
            storage = FakeQueueStorage(),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
            clock = { 50L },
        )
        assertTrue(queue.clearAll())
        assertTrue(reopened.clearAll())
        assertTrue(sharedFence.markClean())

        val recovered = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 60L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val item = delivery("after-zero-repair", "Neue Meldung.")
        assertTrue(recovered.stage(item))
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            recovered.activateResult(item.idempotencyKey),
        )
        assertTrue(
            recovered.finalizeActivation(
                item.idempotencyKey,
                UserFacingNotificationActivationDisposition.ACTIVE,
            ),
        )
        assertTrue(requireNotNull(generationStore.current()) > 0L)
        assertEquals(listOf("Neue Meldung."), recovered.snapshot().map { it.summary })
    }

    @Test
    fun failedZeroGenerationPurgeNeverAdvancesGenerationOrCleansEitherFence() {
        val generationFile = context.noBackupFilesDir.resolve(
            "$fileName.failed-zero-${System.nanoTime()}",
        )
        val generationStore = AtomicNotificationPrivacyGenerationStore(generationFile)
        generationFile.writeText("0")
        val storage = FakeAnnouncementStorage().also {
            it.items = listOf(
                ValidatedNotificationAnnouncement(
                    id = "notification:" + "a".repeat(64),
                    summary = "Private Altlast.",
                    urgency = NotificationUrgency.HIGH,
                    createdAtEpochMillis = 1L,
                    privacyGeneration = 1L,
                ),
            )
            it.failWrites = true
        }
        val localFence = FakeAnnouncementPrivacyFence()
        val sharedFence = FakeDeliveryPrivacyFence()
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = generationStore,
            clock = { 70L },
            privacyFence = localFence,
            deliveryFence = sharedFence,
        )

        assertNull(generationStore.current())
        assertEquals("0", generationFile.readText())
        assertTrue(localFence.isRequired())
        assertTrue(sharedFence.isRequired())
        assertTrue(center.snapshot().isEmpty())

        storage.failWrites = false
        assertTrue(center.clearAll())
        assertTrue(requireNotNull(generationStore.current()) > 0L)
        assertTrue(sharedFence.isRequired())

        generationFile.delete()
        context.noBackupFilesDir.resolve("${generationFile.name}.bak").delete()
        context.noBackupFilesDir.resolve("${generationFile.name}.new").delete()
    }

    @Test
    fun malformedAtomicStageFileRaisesDurableSharedFenceAcrossReopen() {
        val first = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 20L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(first.stage(delivery("atomic-malformed-stage", "Noch privat.")))
        context.noBackupFilesDir.resolve(fileName).writeText("{malformed")

        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 30L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        assertTrue(sharedFence.isRequired())
        assertTrue(reopened.snapshot().isEmpty())
        assertFalse(reopened.activate("atomic-malformed-stage"))

        assertTrue(reopened.clearAll())
        assertTrue(sharedFence.markClean())
        val recovered = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 40L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(recovered.snapshot().isEmpty())
    }

    @Test
    fun nonCanonicalAtomicRecordsAreRejectedInsteadOfCoercedOrSilentlyNormalized() {
        val mutations: List<(JSONArray) -> Unit> = listOf(
            { array -> array.getJSONObject(0).put("summary", "x".repeat(769)) },
            { array -> array.put(JSONObject(array.getJSONObject(0).toString())) },
            { array -> array.getJSONObject(0).put("createdAt", "123") },
            { array -> array.getJSONObject(0).put("sourceReceivedAt", "123") },
            { array -> array.getJSONObject(0).put("sourceReceivedAt", -1L) },
            { array -> array.getJSONObject(0).put("speechSuppressed", "false") },
            { array -> array.getJSONObject(0).put("privacyGeneration", 0) },
            { array -> array.getJSONObject(0).put("activationPending", "false") },
            { array -> array.getJSONObject(0).put("timelineAnchorId", "bad\nanchor") },
            { array -> array.getJSONObject(0).remove("contextReservationId") },
        )
        mutations.forEachIndexed { index, mutate ->
            val caseFile = "validated-announcement-schema-$index-${System.nanoTime()}.json"
            val caseFenceFile = "$caseFile.delivery-fence"
            val sharedFence =
                ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                    context,
                    caseFenceFile,
                )
            val bootstrap = ValidatedNotificationAnnouncementCenter(
                context = context,
                fileName = caseFile,
                deliveryFenceFileName = caseFenceFile,
            )
            assertTrue(bootstrap.clearAll())
            assertTrue(sharedFence.markClean())
            assertTrue(
                bootstrap.accept(
                    delivery("schema-$index", "Kanonische wichtige Meldung."),
                ),
            )
            val stateFile = context.noBackupFilesDir.resolve(caseFile)
            val document = JSONArray(stateFile.readText())
            mutate(document)
            stateFile.writeText(document.toString())

            val reopened = ValidatedNotificationAnnouncementCenter(
                context = context,
                fileName = caseFile,
                deliveryFenceFileName = caseFenceFile,
            )
            assertTrue("case $index must raise shared fence", sharedFence.isRequired())
            assertTrue("case $index must publish nothing", reopened.snapshot().isEmpty())
            assertTrue(reopened.pendingSpeech().isEmpty())
            assertTrue(reopened.pendingContext().isEmpty())

            listOf(
                caseFile,
                "$caseFile.bak",
                "$caseFile.new",
                "$caseFile.privacy-generation",
                "$caseFile.privacy-generation.bak",
                "$caseFile.privacy-generation.new",
                "$caseFile.privacy-fence",
                "$caseFile.privacy-fence.bak",
                "$caseFile.privacy-fence.new",
                caseFenceFile,
                "$caseFenceFile.bak",
                "$caseFenceFile.new",
            ).forEach { context.noBackupFilesDir.resolve(it).delete() }
        }
    }

    @Test
    fun missingLocalFenceQuarantinesCommittedReceiptUntilCombinedPurge() {
        assertCommittedReceiptQuarantinedAfterCenterDamage {
            context.noBackupFilesDir.resolve("$fileName.privacy-fence").delete()
            context.noBackupFilesDir.resolve("$fileName.privacy-fence.bak").delete()
        }
    }

    @Test
    fun missingCenterDocumentQuarantinesCommittedReceiptUntilCombinedPurge() {
        assertCommittedReceiptQuarantinedAfterCenterDamage {
            context.noBackupFilesDir.resolve(fileName).delete()
            context.noBackupFilesDir.resolve("$fileName.bak").delete()
        }
    }

    @Test
    fun missingGenerationDocumentQuarantinesCommittedReceiptUntilCombinedPurge() {
        assertCommittedReceiptQuarantinedAfterCenterDamage {
            context.noBackupFilesDir.resolve("$fileName.privacy-generation").delete()
            context.noBackupFilesDir.resolve("$fileName.privacy-generation.bak").delete()
        }
    }

    @Test
    fun corruptLocalFenceQuarantinesCommittedReceiptUntilCombinedPurge() {
        assertCommittedReceiptQuarantinedAfterCenterDamage {
            context.noBackupFilesDir.resolve("$fileName.privacy-fence.bak").delete()
            context.noBackupFilesDir.resolve("$fileName.privacy-fence").writeText("corrupt")
        }
    }

    @Test
    fun backupOnlyCenterDocumentIsRecoveredBeforeMissingStateIsDeclared() {
        assertBackupOnlyStateRecovers(fileName)
    }

    @Test
    fun backupOnlyGenerationDocumentIsRecoveredBeforeMissingStateIsDeclared() {
        assertBackupOnlyStateRecovers("$fileName.privacy-generation")
    }

    @Test
    fun backupOnlyLocalFenceIsRecoveredBeforeMissingStateIsDeclared() {
        assertBackupOnlyStateRecovers("$fileName.privacy-fence")
    }

    @Test
    fun firstInitializationRequiresListenerCoordinatedQueueAndCenterPurge() {
        deleteCenterStateFiles()
        val queue = NotificationTriageQueue(
            storage = FakeQueueStorage(),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
            clock = { 1_000L },
        )
        assertTrue(
            queue.ingest(notificationEvent()) is
                ai.hans.standard.notifications.NotificationIngressResult.Queued,
        )

        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 20L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        assertTrue(sharedFence.isRequired())
        assertFalse(center.accept(delivery("blocked-before-combined-purge", "Noch gesperrt.")))
        assertEquals(1, queue.receipts().size)

        assertTrue(queue.clearAll())
        assertTrue(center.clearAll())
        assertTrue(sharedFence.markClean())
        assertTrue(queue.receipts().isEmpty())
        assertFalse(sharedFence.isRequired())
        assertTrue(center.accept(delivery("after-first-init-purge", "Jetzt freigegeben.")))
    }

    private fun assertBackupOnlyStateRecovers(relativePath: String) {
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 20L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val committed = commitReceiptPendingActivation(center)
        val base = context.noBackupFilesDir.resolve(relativePath)
        val backup = context.noBackupFilesDir.resolve("$relativePath.bak")
        backup.delete()
        assertTrue(base.renameTo(backup))

        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 30L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        assertFalse(sharedFence.isRequired())
        val completion = committed.queue.activateCommittedSuggestion(
            requireNotNull(committed.queue.nextCommittedUserDelivery()),
            object : UserFacingNotificationSuggestionSink {
                override fun deliver(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingDeliveryDisposition = UserFacingDeliveryDisposition.RETRY

                override fun activate(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingNotificationActivationDisposition =
                    reopened.activateResult(delivery.idempotencyKey)
            },
        ) as UserDeliveryCompletionResult.Accepted
        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER, completion.receipt.state)
        assertEquals(listOf("Wichtige Meldung."), reopened.snapshot().map { it.summary })
    }

    @Test
    fun duplicateActivationIsIdempotentAndPublishesOnlyFirstTransition() {
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 10L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val observations = mutableListOf<List<ValidatedNotificationAnnouncement>>()
        center.addObserver { observations += it }
        val item = delivery("activate-once", "Einmal sichtbar.")
        assertTrue(center.stage(item))

        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        val afterFirst = observations.size
        assertEquals(
            UserFacingNotificationActivationDisposition.ACTIVE,
            center.activateResult(item.idempotencyKey),
        )
        assertEquals(afterFirst, observations.size)
    }

    private fun assertCommittedReceiptQuarantinedAfterCenterDamage(damage: () -> Unit) {
        val center = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 20L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val committed = commitReceiptPendingActivation(center)
        val queue = committed.queue

        damage()
        val reopened = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 30L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        val sharedFence =
            ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence(
                context,
                deliveryFenceFileName,
            )
        assertTrue(sharedFence.isRequired())
        assertFalse(reopened.activate(committed.delivery.idempotencyKey))
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            queue.receipts().single().state,
        )

        assertTrue(queue.clearAll())
        assertTrue(reopened.clearAll())
        assertTrue(sharedFence.markClean())
        assertTrue(queue.receipts().isEmpty())
        val recovered = ValidatedNotificationAnnouncementCenter(
            context = context,
            clock = { 40L },
            fileName = fileName,
            deliveryFenceFileName = deliveryFenceFileName,
        )
        assertTrue(recovered.accept(delivery("after-combined-recovery", "Neue Meldung.")))
    }

    private fun commitReceiptPendingActivation(
        center: ValidatedNotificationAnnouncementCenter,
    ): CommittedReceiptFixture {
        val queue = NotificationTriageQueue(
            storage = FakeQueueStorage(),
            exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
            clock = { 1_000L },
        )
        assertTrue(queue.ingest(notificationEvent()) is ai.hans.standard.notifications.NotificationIngressResult.Queued)
        val triage = requireNotNull(queue.claimNextRestrictedTriage())
        queue.completeRestrictedTriage(
            triage.receipt.id,
            triage.claimToken,
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion(
                    summary = "Wichtige Meldung.",
                    urgency = NotificationUrgency.HIGH,
                ),
            ),
        )
        val lease = requireNotNull(queue.claimNextUserDelivery())
        val sink = object : UserFacingNotificationSuggestionSink {
            override fun deliver(
                delivery: UserFacingNotificationDelivery,
            ): UserFacingDeliveryDisposition = if (center.stage(delivery)) {
                UserFacingDeliveryDisposition.ACCEPTED
            } else {
                UserFacingDeliveryDisposition.RETRY
            }

            override fun activate(
                delivery: UserFacingNotificationDelivery,
            ): UserFacingNotificationActivationDisposition =
                UserFacingNotificationActivationDisposition.RETRY

            override fun revoke(delivery: UserFacingNotificationDelivery) {
                center.revokePending(delivery.idempotencyKey)
            }
        }
        val committed = queue.deliverClaimedSuggestion(lease, sink)
            as UserDeliveryCompletionResult.Accepted
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            committed.receipt.state,
        )
        return CommittedReceiptFixture(queue = queue, delivery = lease.delivery)
    }

    private fun deleteCenterStateFiles() {
        listOf(
            fileName,
            "$fileName.bak",
            "$fileName.new",
            "$fileName.privacy-generation",
            "$fileName.privacy-generation.bak",
            "$fileName.privacy-generation.new",
            "$fileName.privacy-fence",
            "$fileName.privacy-fence.bak",
            "$fileName.privacy-fence.new",
            deliveryFenceFileName,
            "$deliveryFenceFileName.bak",
            "$deliveryFenceFileName.new",
        ).forEach { relativePath -> context.noBackupFilesDir.resolve(relativePath).delete() }
    }

    private data class CommittedReceiptFixture(
        val queue: NotificationTriageQueue,
        val delivery: UserFacingNotificationDelivery,
    )

    private fun notificationEvent() = NotificationInboxEvent(
        sequence = 1L,
        kind = NotificationEventKind.POSTED,
        observedAtEpochMillis = 100L,
        removalReason = null,
        snapshot = NotificationSnapshot(
            packageName = "com.example.chat",
            androidKey = "notification-key",
            postTimeEpochMillis = 100L,
            notificationWhenEpochMillis = 100L,
            title = "Alex",
            text = "Wichtige Nachricht",
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList<NotificationActionMetadata>(),
        ),
    )

    private fun delivery(
        idempotencyKey: String,
        summary: String,
        supersessionKey: String = "source:" + "b".repeat(64),
    ) = UserFacingNotificationDelivery(
        receiptId = "receipt-$idempotencyKey",
        idempotencyKey = idempotencyKey,
        suggestion = UserFacingNotificationSuggestion(
            summary = summary,
            urgency = NotificationUrgency.HIGH,
        ),
        supersessionKey = supersessionKey,
    )

    private class FakeAnnouncementStorage : ValidatedAnnouncementStorage {
        var items: List<ValidatedNotificationAnnouncement> = emptyList()
        var failWrites: Boolean = false
        var unavailableReads: Boolean = false

        override fun read(): List<ValidatedNotificationAnnouncement> = items

        override fun readStatus(): ValidatedAnnouncementStorageRead =
            if (unavailableReads) {
                ValidatedAnnouncementStorageRead.Unavailable
            } else {
                ValidatedAnnouncementStorageRead.Available(items)
            }

        override fun write(items: List<ValidatedNotificationAnnouncement>): Boolean {
            if (failWrites) return false
            this.items = items
            return true
        }
    }

    private class FakeQueueStorage : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())

        override fun read(): NotificationTriageQueueState = state

        override fun write(state: NotificationTriageQueueState) {
            this.state = state
        }
    }

    private class FakePrivacyGenerationStore : NotificationPrivacyGenerationStore {
        private var value = 1L
        var failAdvances = false
        var corrupt = false

        override fun current(): Long? = value.takeUnless { corrupt }

        override fun advance(): Long? {
            if (failAdvances || corrupt) return null
            value += 1
            return value
        }

        override fun resetAfterDestructiveClear(): Long? {
            if (failAdvances) return null
            value = if (corrupt) 1L else value + 1
            corrupt = false
            return value
        }
    }

    private class FakeAnnouncementPrivacyFence : NotificationAnnouncementPrivacyFence {
        private var required = false

        override fun isRequired(): Boolean = required
        override fun markRequired(): Boolean {
            required = true
            return true
        }
        override fun markClean(): Boolean {
            required = false
            return true
        }
    }

    private class FakeDeliveryPrivacyFence : NotificationPrivacyPurgeFence {
        private var required = false
        override fun isRequired(): Boolean = required
        override fun markRequired(): Boolean {
            required = true
            return true
        }
        override fun markClean(): Boolean {
            required = false
            return true
        }
    }
}
