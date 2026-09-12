package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationActionMetadata
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import ai.hans.standard.phone.notifications.facts.NotificationArchiveCaptureToken
import ai.hans.standard.phone.notifications.facts.NotificationFactArchiveHealth
import ai.hans.standard.phone.notifications.facts.NotificationFactBatch
import ai.hans.standard.phone.notifications.facts.NotificationFactCommitResult
import ai.hans.standard.phone.notifications.facts.NotificationFactCorrection
import ai.hans.standard.phone.notifications.facts.NotificationFactCorrectionResult
import ai.hans.standard.phone.notifications.facts.NotificationFactIds
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyBeginResult
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyIntent
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyRequest
import ai.hans.standard.phone.notifications.facts.NotificationFactQuery
import ai.hans.standard.phone.notifications.facts.NotificationFactQueryResult
import ai.hans.standard.phone.notifications.facts.NotificationFactRepository
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end policy regression for the Inbox -> restricted analysis -> visible/TTS boundary. */
class NotificationSpeechAdmissionRegressionTest {
    @Test
    fun restrictedPromptsMakeTextOnlyIntakeAndSoleSpeechBoundaryExplicit() {
        assertTrue(
            RestrictedNotificationClassificationPrompt.DEVELOPER_INSTRUCTIONS.contains(
                "Notification intake is always text-only and never authorizes speech",
            ),
        )
        assertTrue(
            RestrictedNotificationSynthesisPrompt.DEVELOPER_INSTRUCTIONS
                .replace(Regex("\\s+"), " ")
                .contains(
                    "This is the sole path that may authorize notification speech",
                ),
        )
    }

    @Test
    fun rawIngestCreatesNeitherTimelineOutputNorTtsBeforeAnalysis() {
        val storage = MemoryStorage()
        val boundary = RecordingUserBoundary()
        val queue = queue(storage)

        assertTrue(queue.ingest(event(1)) is NotificationIngressResult.Queued)

        assertEquals(NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE, queue.receipts().single().state)
        assertEquals(0, boundary.stagedOutputs)
        assertEquals(0, boundary.visibleOutputs)
        assertEquals(0, boundary.ttsDispatches)
    }

    @Test
    fun routineNotificationMayBecomeMemoryButNeverCrossesVisibleOrSpeechBoundary() {
        val storage = MemoryStorage()
        val archive = RecordingFactArchive()
        val boundary = RecordingUserBoundary()
        val queue = queue(storage, archive)
        queue.ingest(event(2))
        val processor = NotificationTriageProcessor(
            queue = queue,
            restrictedExecutor = RestrictedNotificationTriageExecutor {
                RestrictedTriageDecision.NotRelevant(
                    reason = NotificationDismissalReason.NOT_ACTIONABLE,
                    memoryCandidates = listOf(memoryCandidate()),
                )
            },
            suggestionSink = boundary,
        )

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(NotificationDeliveryState.DISMISSED_BY_TRIAGE, queue.receipts().single().state)
        assertEquals(0, boundary.stagedOutputs)
        assertEquals(0, boundary.visibleOutputs)
        assertEquals(0, boundary.ttsDispatches)

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(1, archive.committed.size)
        assertEquals(TEXT, archive.committed.single().validatedCandidates.single().quote)
        assertEquals(0, boundary.visibleOutputs)
        assertEquals(0, boundary.ttsDispatches)
    }

    @Test
    fun urgentAnalysisProducesOneContextualVisibleOutputAndOneTtsEvenAfterDuplicateIngest() {
        val storage = MemoryStorage()
        val boundary = RecordingUserBoundary()
        val queue = queue(storage)
        val original = event(3)
        assertTrue(queue.ingest(original) is NotificationIngressResult.Queued)
        assertTrue(queue.ingest(original) is NotificationIngressResult.Duplicate)
        val processor = urgentProcessor(queue, boundary)

        // Classification/synthesis is a durable transition, not an early output stage.
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(NotificationDeliveryState.SUGGESTED_TO_USER, queue.receipts().single().state)
        assertEquals(0, boundary.visibleOutputs)
        assertEquals(0, boundary.ttsDispatches)

        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER, queue.receipts().single().state)
        assertEquals(1, boundary.stagedOutputs)
        assertEquals(1, boundary.visibleOutputs)
        assertEquals(1, boundary.ttsDispatches)
        assertEquals(CONTEXTUAL_SUMMARY, boundary.visibleText.single())

        assertEquals(NotificationTriageStepResult.IDLE, processor.processOne())
        assertEquals(1, boundary.visibleOutputs)
        assertEquals(1, boundary.ttsDispatches)
    }

    @Test
    fun restartBetweenStageAndActivationResumesOnceAndLaterReplayStaysSilent() {
        val storage = MemoryStorage()
        val queue = queue(storage)
        queue.ingest(event(4))
        val unavailableBoundary = RecordingUserBoundary(
            activationDisposition = UserFacingNotificationActivationDisposition.RETRY,
        )
        val beforeRestart = urgentProcessor(queue, unavailableBoundary)

        assertEquals(NotificationTriageStepResult.PROGRESSED, beforeRestart.processOne())
        assertEquals(NotificationTriageStepResult.RETRY_LATER, beforeRestart.processOne())
        assertEquals(
            NotificationDeliveryState.DELIVERY_COMMITTED_PENDING_ACTIVATION,
            queue.receipts().single().state,
        )
        assertEquals(1, unavailableBoundary.stagedOutputs)
        assertEquals(0, unavailableBoundary.visibleOutputs)
        assertEquals(0, unavailableBoundary.ttsDispatches)

        val afterRestartBoundary = RecordingUserBoundary()
        val recreatedQueue = queue(storage)
        val afterRestart = NotificationTriageProcessor(
            queue = recreatedQueue,
            restrictedExecutor = RestrictedNotificationTriageExecutor {
                error("committed delivery must not be reclassified")
            },
            suggestionSink = afterRestartBoundary,
        )
        assertEquals(NotificationTriageStepResult.PROGRESSED, afterRestart.processOne())
        assertEquals(NotificationDeliveryState.DELIVERED_TO_USER, recreatedQueue.receipts().single().state)
        assertEquals(0, afterRestartBoundary.stagedOutputs)
        assertEquals(1, afterRestartBoundary.visibleOutputs)
        assertEquals(1, afterRestartBoundary.ttsDispatches)

        val laterReplayBoundary = RecordingUserBoundary()
        val laterReplay = NotificationTriageProcessor(
            queue = queue(storage),
            restrictedExecutor = RestrictedNotificationTriageExecutor {
                error("delivered receipt must not be reclassified")
            },
            suggestionSink = laterReplayBoundary,
        )
        assertEquals(NotificationTriageStepResult.IDLE, laterReplay.processOne())
        assertEquals(0, laterReplayBoundary.stagedOutputs)
        assertEquals(0, laterReplayBoundary.visibleOutputs)
        assertEquals(0, laterReplayBoundary.ttsDispatches)
    }

    @Test
    fun analysisFailureRetriesDurablyThenFailsWithoutAnyVisibleOrSpokenOutput() {
        val storage = MemoryStorage()
        val boundary = RecordingUserBoundary()
        val queue = queue(storage)
        queue.ingest(event(5))
        val processor = NotificationTriageProcessor(
            queue = queue,
            restrictedExecutor = RestrictedNotificationTriageExecutor {
                error("synthetic restricted analysis failure")
            },
            suggestionSink = boundary,
        )

        repeat(NotificationTriageBounds.MAX_TRIAGE_ATTEMPTS - 1) {
            assertEquals(NotificationTriageStepResult.RETRY_LATER, processor.processOne())
            assertEquals(NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE, queue.receipts().single().state)
        }
        assertEquals(NotificationTriageStepResult.PROGRESSED, processor.processOne())
        assertEquals(NotificationDeliveryState.TRIAGE_FAILED, queue.receipts().single().state)
        assertEquals(0, boundary.stagedOutputs)
        assertEquals(0, boundary.visibleOutputs)
        assertEquals(0, boundary.ttsDispatches)
    }

    private fun urgentProcessor(
        queue: NotificationTriageQueue,
        boundary: RecordingUserBoundary,
    ) = NotificationTriageProcessor(
        queue = queue,
        restrictedExecutor = RestrictedNotificationTriageExecutor {
            RestrictedTriageDecision.SuggestUser(
                UserFacingNotificationSuggestion(CONTEXTUAL_SUMMARY, NotificationUrgency.HIGH),
            )
        },
        suggestionSink = boundary,
    )

    private fun queue(
        storage: NotificationTriageStorage,
        archive: NotificationFactRepository? = null,
    ): NotificationTriageQueue {
        // Intake deliberately debounces rapid Android updates. Advance one deterministic second
        // per queue transition so these policy tests exercise eligible work without wall-clock
        // sleeps; persisted restart cases retain their own durable state.
        val clock = AtomicLong(NOW)
        return NotificationTriageQueue(
            storage = storage,
            exclusionPolicy = HansNotificationExclusionPolicy(setOf("ai.hans.standard")),
            clock = { clock.getAndAdd(1_000L) },
            factArchive = archive,
        )
    }

    private class RecordingUserBoundary(
        private val activationDisposition: UserFacingNotificationActivationDisposition =
            UserFacingNotificationActivationDisposition.ACTIVE,
    ) : UserFacingNotificationSuggestionSink {
        var stagedOutputs = 0
        var visibleOutputs = 0
        var ttsDispatches = 0
        val visibleText = mutableListOf<String>()

        override fun deliver(delivery: UserFacingNotificationDelivery): UserFacingDeliveryDisposition {
            stagedOutputs += 1
            return UserFacingDeliveryDisposition.ACCEPTED
        }

        override fun activate(
            delivery: UserFacingNotificationDelivery,
        ): UserFacingNotificationActivationDisposition {
            if (activationDisposition == UserFacingNotificationActivationDisposition.ACTIVE) {
                visibleOutputs += 1
                ttsDispatches += 1
                visibleText += delivery.suggestion.summary
            }
            return activationDisposition
        }
    }

    private class MemoryStorage : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())
        override fun read(): NotificationTriageQueueState = state
        override fun write(state: NotificationTriageQueueState) {
            this.state = state.normalize()
        }
    }

    private class RecordingFactArchive : NotificationFactRepository {
        private val token = NotificationArchiveCaptureToken(
            storeEpoch = "00000000-0000-4000-8000-000000000001",
            allGeneration = 0,
            packageGeneration = 0,
        )
        val committed = mutableListOf<NotificationFactBatch>()

        override fun captureToken(packageName: String): NotificationArchiveCaptureToken = token
        override fun canStage(batch: NotificationFactBatch): Boolean = batch.token == token
        override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult {
            committed += batch
            return NotificationFactCommitResult.Stored(
                batch.validatedCandidates.map {
                    NotificationFactIds.forCandidate(batch.packageName, batch.sourceRef, it)
                },
            )
        }

        override fun query(query: NotificationFactQuery): NotificationFactQueryResult = error("unused")
        override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult =
            error("unused")
        override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult =
            error("unused")
        override fun pendingIntents(): List<NotificationFactPrivacyIntent> = emptyList()
        override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean = false
        override fun health(): NotificationFactArchiveHealth = error("unused")
        override fun close() = Unit
    }

    private companion object {
        const val NOW = 1_000L
        const val TEXT = "Treffen heute um 18 Uhr am Bahnhof."
        const val CONTEXTUAL_SUMMARY =
            "Alex erinnert dich: Das wichtige Treffen beginnt heute um 18 Uhr."

        fun event(sequence: Long) = NotificationInboxEvent(
            sequence = sequence,
            kind = NotificationEventKind.POSTED,
            observedAtEpochMillis = 900L,
            removalReason = null,
            snapshot = NotificationSnapshot(
                packageName = "com.example.chat",
                androidKey = "message-$sequence",
                postTimeEpochMillis = 900L,
                notificationWhenEpochMillis = 900L,
                title = "Alex",
                text = TEXT,
                subtext = "",
                category = "message",
                channelId = "messages",
                ongoing = false,
                clearable = true,
                actions = emptyList<NotificationActionMetadata>(),
            ),
        )

        fun memoryCandidate() = NotificationMemoryCandidate(
            kind = NotificationMemoryKind.EVENT_DETAIL,
            sourceField = NotificationMemorySourceField.TEXT,
            quote = TEXT,
            startUtf16 = 0,
            endUtf16 = TEXT.length,
            sourceSha256 = MessageDigest.getInstance("SHA-256")
                .digest(TEXT.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
    }
}
