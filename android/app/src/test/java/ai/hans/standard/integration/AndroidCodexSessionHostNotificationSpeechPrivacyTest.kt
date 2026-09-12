package ai.hans.standard.integration

import ai.hans.standard.notifications.NotificationUrgency
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationSuggestion
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCodexSessionHostNotificationSpeechPrivacyTest {
    @Test
    fun silentPeriodAcrossInteractionAndDelayedTriageNeverReplaysButFreshNoticeSpeaksOnce() {
        var now = 100L
        val storage = object : ValidatedAnnouncementStorage {
            var items = emptyList<ValidatedNotificationAnnouncement>()
            override fun read() = items
            override fun write(items: List<ValidatedNotificationAnnouncement>): Boolean {
                this.items = items
                return true
            }
        }
        val center = ValidatedNotificationAnnouncementCenter(
            storage = storage,
            privacyGenerationStore = object : NotificationPrivacyGenerationStore {
                override fun current() = 1L
                override fun advance() = 2L
            },
            clock = { now },
        )
        fun arrive(key: String, receivedAt: Long) = center.accept(
            UserFacingNotificationDelivery(
                receiptId = key,
                idempotencyKey = key,
                suggestion = UserFacingNotificationSuggestion(key, NotificationUrgency.HIGH),
                sourceReceivedAtEpochMillis = receivedAt,
            ),
        )
        val policy = NotificationSpeechAudibilitySuppressionState(initiallyAudible = true)
        val playback = NotificationSpeechHostState()
        fun reconcile(audible: Boolean) {
            policy.observe(audible, now)
            if (!policy.beginReconciliation()) return
            playback.pauseForInaudiblePolicy()
            while (true) {
                val cutoff = policy.nextCutoffOrFinish() ?: break
                assertTrue(center.suppressSpeechThrough(cutoff))
            }
        }

        assertTrue(arrive("awaiting-interaction", 90))
        // Speech is blocked by Live/dictation. Audio policy still runs independently of that gate.
        now = 200
        reconcile(false)
        now = 300
        reconcile(true)
        assertTrue(arrive("triage-finished-after-unmute", 250))
        playback.resumeDeferred() // Ordinary interaction completion cannot undo Center suppression.
        assertTrue(center.pendingSpeech().isEmpty())
        assertEquals(2, center.pendingContext().size)
        assertTrue(center.snapshot().all { it.spokenAtEpochMillis == null })

        now = 301
        assertTrue(arrive("new-important-notice", 301))
        val fresh = center.pendingSpeech().single()
        assertEquals("new-important-notice", fresh.summary)
        assertTrue(center.markSpoken(fresh.id))
        assertTrue(center.pendingSpeech().isEmpty())
        playback.resumeDeferred()
        assertTrue(center.pendingSpeech().isEmpty())
    }

    @Test
    fun concurrentMuteAndUnmuteDuringSuppressionCannotReopenBeforeTheirCutoff() {
        val policy = NotificationSpeechAudibilitySuppressionState(initiallyAudible = true)
        policy.observe(false, 100)
        assertTrue(policy.beginReconciliation())
        assertEquals(100L, policy.nextCutoffOrFinish())

        // These callbacks occur while the first cutoff is being persisted. The second silent
        // period includes an arrival at 250, after the first write's cutoff.
        policy.observe(true, 150)
        policy.observe(false, 200)
        policy.observe(true, 300)
        assertFalse(policy.beginReconciliation())
        assertTrue(policy.blocksSpeech)
        assertEquals(300L, policy.nextCutoffOrFinish())
        assertTrue(policy.blocksSpeech)
        assertNull(policy.nextCutoffOrFinish())
        assertFalse(policy.blocksSpeech)
        policy.observe(true, 400)
        assertFalse(policy.beginReconciliation())
    }

    @Test
    fun suppressionExceptionKeepsAdmissionClosedUntilCutoffIsRetried() {
        val policy = NotificationSpeechAudibilitySuppressionState(initiallyAudible = false)
        policy.observe(true, 100)
        assertTrue(policy.beginReconciliation())
        assertEquals(100L, policy.nextCutoffOrFinish())
        policy.retryAfterFailure(100)
        assertTrue(policy.blocksSpeech)
        policy.observe(true, 200)
        assertTrue(policy.beginReconciliation())
        assertEquals(100L, policy.nextCutoffOrFinish())
        assertNull(policy.nextCutoffOrFinish())
        assertFalse(policy.blocksSpeech)
    }

    @Test
    fun providerFailureClearsCorrelationRetriesFreshAndThenAdvancesExactlyOnce() {
        val state = NotificationSpeechHostState()
        val first = NotificationSpeechAttempt(
            announcementId = "notification:first",
            playbackId = "notification:first:attempt:1",
            gateEpoch = state.gateEpoch,
        )
        state.inFlight = first

        val disposition = state.registerPlaybackFailure(
            playbackId = first.playbackId,
            retryable = true,
            maximumAutomaticRetries = 2,
        ) as NotificationSpeechFailureDisposition.Retry

        assertNull(state.inFlight)
        assertTrue(first.announcementId in state.deferredAnnouncementIds)
        assertTrue(state.releaseFailureRetry(disposition.ticket))
        assertFalse(first.announcementId in state.deferredAnnouncementIds)

        val retry = NotificationSpeechAttempt(
            announcementId = first.announcementId,
            playbackId = "notification:first:attempt:2",
            gateEpoch = state.gateEpoch,
        )
        state.inFlight = retry
        assertTrue(state.completePlayback(retry, completionPersisted = true))
        assertFalse("duplicate completion cannot persist again", state.completePlayback(retry, true))

        val following = NotificationSpeechAttempt(
            announcementId = "notification:second",
            playbackId = "notification:second:attempt:3",
            gateEpoch = state.gateEpoch,
        )
        state.inFlight = following
        assertTrue(state.completePlayback(following, completionPersisted = true))
        assertNull(state.inFlight)
        assertTrue(state.deferredAnnouncementIds.isEmpty())
    }

    @Test
    fun staleFailureRetryCannotCrossAChangedInteractionGate() {
        val state = NotificationSpeechHostState()
        val attempt = NotificationSpeechAttempt(
            announcementId = "notification:private",
            playbackId = "notification:private:attempt:1",
            gateEpoch = state.gateEpoch,
        )
        state.inFlight = attempt
        val disposition = state.registerPlaybackFailure(
            playbackId = attempt.playbackId,
            retryable = true,
            maximumAutomaticRetries = 1,
        ) as NotificationSpeechFailureDisposition.Retry

        state.advanceGate()
        assertFalse(state.releaseFailureRetry(disposition.ticket))
        assertTrue(attempt.announcementId in state.deferredAnnouncementIds)
    }

    @Test
    fun updateRevocationStopsPlaybackBeforeBlockingCenterIoAndPropagatesFailure() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:stale-update",
                playbackId = "notification:stale-update:attempt:1",
                gateEpoch = gateEpoch,
            )
        }
        val centerEntered = CountDownLatch(1)
        val releaseCenter = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var result = true
        var physicalStops = 0

        val thread = Thread {
            result = revokeValidatedNotificationAfterStopping(
                cancelAndStopPlayback = {
                    assertTrue(state.cancelAllForPrivacyPurge())
                    physicalStops += 1
                    true
                },
                mutateValidatedCenter = {
                    centerEntered.countDown()
                    check(releaseCenter.await(2, TimeUnit.SECONDS))
                    false
                },
            )
            finished.countDown()
        }
        thread.start()
        assertTrue(centerEntered.await(2, TimeUnit.SECONDS))
        assertNull(state.inFlight)
        assertEquals(1, physicalStops)
        assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        releaseCenter.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        thread.join(2_000)
        assertFalse(result)
    }

    @Test
    fun blockingCenterIoCannotDelayCorrelationRevocationOrPhysicalStop() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:blocking-clear",
                playbackId = "notification:blocking-clear:attempt:1",
                gateEpoch = gateEpoch,
            )
        }
        val centerEntered = CountDownLatch(1)
        val releaseCenter = CountDownLatch(1)
        val clearFinished = CountDownLatch(1)
        var stopCalls = 0
        val boundary = NotificationSpeechPrivacyClearBoundary(
            clearValidatedCenter = {
                centerEntered.countDown()
                check(releaseCenter.await(2, TimeUnit.SECONDS))
                true
            },
            cancelHostSpeech = state::cancelAllForPrivacyPurge,
            stopPlaybackAndAwait = {
                stopCalls += 1
                true
            },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
        )

        val clearing = Thread {
            boundary.clear()
            clearFinished.countDown()
        }
        clearing.start()
        assertTrue(centerEntered.await(2, TimeUnit.SECONDS))

        assertEquals(1, stopCalls)
        assertNull(state.inFlight)
        assertEquals(1L, state.gateEpoch)
        assertFalse(clearFinished.await(100, TimeUnit.MILLISECONDS))
        releaseCenter.countDown()
        assertTrue(clearFinished.await(2, TimeUnit.SECONDS))
        clearing.join(2_000)
        assertFalse(clearing.isAlive)
    }

    @Test
    fun sharedFenceClearStopsStartedSpeechWithoutDependingOnCenterVisibility() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:private",
                playbackId = "notification:private:attempt:1",
                gateEpoch = gateEpoch,
            )
            deferredAnnouncementIds += "notification:older"
        }
        var sharedFenceRequired = true
        var centerClearCalls = 0
        var stopCalls = 0
        val boundary = NotificationSpeechPrivacyClearBoundary(
            clearValidatedCenter = {
                assertNull(state.inFlight)
                assertEquals(1, stopCalls)
                centerClearCalls += 1
                true
            },
            cancelHostSpeech = state::cancelAllForPrivacyPurge,
            stopPlaybackAndAwait = {
                stopCalls += 1
                true
            },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
        )

        assertTrue(boundary.clear())
        assertEquals(1, centerClearCalls)
        assertEquals(1, stopCalls)
        assertEquals(1L, state.gateEpoch)
        assertNull(state.inFlight)
        assertTrue(state.deferredAnnouncementIds.isEmpty())

        // A completion emitted synchronously or later by stop() has no live correlation and can
        // therefore neither mark the deleted item spoken nor put it back into a retry set.
        assertNull(state.currentForPlayback("notification:private:attempt:1"))
        sharedFenceRequired = false
        assertTrue(boundary.clear())
        assertEquals(1, stopCalls)
    }

    @Test
    fun centerIoFailureStillRevokesAndStopsIrreversiblePlayback() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:io-failure",
                playbackId = "notification:io-failure:attempt:4",
                gateEpoch = gateEpoch,
            )
        }
        var stopCalls = 0
        val boundary = NotificationSpeechPrivacyClearBoundary(
            clearValidatedCenter = { false },
            cancelHostSpeech = state::cancelAllForPrivacyPurge,
            stopPlaybackAndAwait = {
                stopCalls += 1
                true
            },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
        )

        assertFalse(boundary.clear())
        assertEquals(1, stopCalls)
        assertNull(state.currentForPlayback("notification:io-failure:attempt:4"))
    }

    @Test
    fun unacknowledgedPhysicalStopCannotReportPrivacyClearSuccess() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:stop-timeout",
                playbackId = "notification:stop-timeout:attempt:2",
                gateEpoch = gateEpoch,
            )
        }
        var centerCleared = false
        var stopAcknowledged = false
        var stopCalls = 0
        val boundary = NotificationSpeechPrivacyClearBoundary(
            clearValidatedCenter = {
                centerCleared = true
                true
            },
            cancelHostSpeech = state::cancelAllForPrivacyPurge,
            stopPlaybackAndAwait = {
                stopCalls += 1
                stopAcknowledged
            },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
        )

        assertFalse(boundary.clear())
        assertTrue(centerCleared)
        assertNull(state.inFlight)
        assertTrue(state.requiresPhysicalStop())

        centerCleared = false
        assertFalse(boundary.clear())
        assertTrue(centerCleared)
        assertEquals(2, stopCalls)
        assertTrue(state.requiresPhysicalStop())

        stopAcknowledged = true
        assertTrue(boundary.clear())
        assertEquals(3, stopCalls)
        assertFalse(state.requiresPhysicalStop())
        assertTrue(boundary.clear())
        assertEquals(3, stopCalls)
    }

    @Test
    fun mutePauseKeepsPhysicalStopStickyUntilClearRetriesItSuccessfully() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:mute-race",
                playbackId = "notification:mute-race:attempt:1",
                gateEpoch = gateEpoch,
            )
        }
        assertTrue(state.pauseForInaudiblePolicy())
        assertNull(state.inFlight)
        assertTrue(state.deferredAnnouncementIds.isEmpty())
        assertTrue(state.requiresPhysicalStop())

        var stopSucceeds = false
        val boundary = NotificationSpeechPrivacyClearBoundary(
            clearValidatedCenter = { true },
            cancelHostSpeech = state::cancelAllForPrivacyPurge,
            stopPlaybackAndAwait = { stopSucceeds },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
        )
        assertFalse(boundary.clear())
        assertTrue(state.requiresPhysicalStop())

        stopSucceeds = true
        assertTrue(boundary.clear())
        assertFalse(state.requiresPhysicalStop())
        assertTrue(state.deferredAnnouncementIds.isEmpty())
    }

    @Test
    fun broadSettingsCallbacksCloseSilentPeriodOnlyOnActualUnmute() {
        val tracker = NotificationSpeechAudibilityTransitionTracker()

        assertFalse(tracker.becameAudible(true))
        assertFalse(tracker.becameAudible(true))
        assertFalse(tracker.becameAudible(false))
        assertFalse(tracker.becameAudible(false))
        assertTrue(tracker.becameAudible(true))
        assertFalse(tracker.becameAudible(true))
    }

    @Test
    fun initiallyMutedStateClosesItsSilentPeriodOnSingleUnmuteEvent() {
        val tracker = NotificationSpeechAudibilityTransitionTracker()
        assertFalse(tracker.becameAudible(false))

        assertTrue(tracker.becameAudible(true))
        assertFalse(tracker.becameAudible(true))
    }

    @Test
    fun muteInvalidatesDelayedFailureRetryAndLatePlaybackCallbacks() {
        val state = NotificationSpeechHostState()
        val failed = NotificationSpeechAttempt("notification:failed", "failed:attempt:1", state.gateEpoch)
        state.inFlight = failed
        val retry = state.registerPlaybackFailure(failed.playbackId, true, 2)
            as NotificationSpeechFailureDisposition.Retry
        val playing = NotificationSpeechAttempt("notification:playing", "playing:attempt:2", state.gateEpoch)
        state.inFlight = playing

        assertTrue(state.pauseForInaudiblePolicy())
        assertTrue(state.deferredAnnouncementIds.isEmpty())
        assertFalse(state.releaseFailureRetry(retry.ticket))
        assertNull(state.currentForPlayback(playing.playbackId))
        assertFalse(state.completePlayback(playing, completionPersisted = true))
        assertNull(state.registerPlaybackFailure(playing.playbackId, true, 2))
        state.acknowledgePhysicalStop()
        state.resumeDeferred()
        assertTrue(state.deferredAnnouncementIds.isEmpty())
    }

    @Test
    fun oneUnmuteEventRecoversTimedOutMuteStopAndResumesExactlyOnce() {
        val state = NotificationSpeechHostState().apply {
            inFlight = NotificationSpeechAttempt(
                announcementId = "notification:mute-retry",
                playbackId = "notification:mute-retry:attempt:1",
                gateEpoch = gateEpoch,
            )
        }
        assertTrue(state.pauseForInaudiblePolicy())
        val scheduled = ArrayDeque<() -> Unit>()
        var audible = false
        var stopCalls = 0
        var resumeCalls = 0
        val controller = NotificationPhysicalStopRetryController(
            requiresPhysicalStop = state::requiresPhysicalStop,
            stopPlaybackAndAwait = {
                stopCalls += 1
                stopCalls >= 2
            },
            acknowledgePlaybackStopped = state::acknowledgePhysicalStop,
            onRecovered = {
                if (audible) resumeCalls += 1
            },
            scheduleRetry = { _, task ->
                scheduled.addLast(task)
                true
            },
            retryDelayMillis = 1L,
            maximumAttempts = 4,
        )

        // Mute handling makes the first physical stop attempt; it times out and posts one retry.
        controller.request()
        assertEquals(1, stopCalls)
        assertTrue(state.requiresPhysicalStop())
        assertEquals(1, scheduled.size)

        // Android emits exactly one unmute callback. request() coalesces with the existing retry;
        // advancing the fake Handler then acknowledges the stop and resumes once.
        audible = true
        controller.request()
        scheduled.removeFirst().invoke()
        assertEquals(2, stopCalls)
        assertFalse(state.requiresPhysicalStop())
        assertEquals(1, resumeCalls)
        assertTrue(scheduled.isEmpty())

        controller.request()
        assertEquals(2, stopCalls)
        assertEquals(1, resumeCalls)
    }

    @Test
    fun closingPhysicalStopRecoveryInvalidatesAlreadyScheduledRetry() {
        var stopCalls = 0
        var resumeCalls = 0
        val scheduled = ArrayDeque<() -> Unit>()
        val controller = NotificationPhysicalStopRetryController(
            requiresPhysicalStop = { true },
            stopPlaybackAndAwait = {
                stopCalls += 1
                false
            },
            acknowledgePlaybackStopped = { error("must not acknowledge") },
            onRecovered = { resumeCalls += 1 },
            scheduleRetry = { _, task ->
                scheduled.addLast(task)
                true
            },
            retryDelayMillis = 1L,
            maximumAttempts = 4,
        )

        controller.request()
        assertEquals(1, stopCalls)
        assertEquals(1, scheduled.size)
        controller.close()
        scheduled.removeFirst().invoke()

        assertEquals(1, stopCalls)
        assertEquals(0, resumeCalls)
        controller.request()
        assertEquals(1, stopCalls)
    }
}
