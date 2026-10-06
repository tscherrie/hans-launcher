package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeFailure
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.integration.CodexRealtimeIssue
import ai.hans.standard.integration.CodexRealtimeOptions
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import java.io.Closeable
import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic lifecycle checks; media drain is not a remote consumption/EOS receipt. */
class CodexShortLiveDictationProviderTest {
    @Test fun networkStartsBeforeAnyPcmWithNativeModelAndNoGreetingOrFiller() = Fixture().use { f ->
        assertEquals(1, f.gateway.starts.get())
        assertEquals(CodexLiveVoiceSession.MODEL, f.media.setup.config.model)
        assertEquals("cove", f.gateway.voice)
        assertFalse(f.gateway.options.delegationAckFiller)
        assertTrue(f.gateway.prompt.contains("There is no opening greeting"))
        assertTrue(f.gateway.prompt.contains("Hand every meaningful dictated request"))
        assertTrue(f.media.setup.history.isEmpty())
        assertTrue(f.gateway.call.appends.isEmpty())
        assertTrue(f.media.mutes.all { !it })
        assertEquals(0, f.readyCount.get())
    }

    @Test fun nativeAndMediaReadinessAreBothRequiredBeforeSendingPrimaryPcm() {
        listOf(true, false).forEach { nativeFirst -> Fixture().use { f ->
            val accepted = f.submit(final = true)
            if (nativeFirst) f.events.onStarted() else f.media.listener.onOpen()
            f.drain()
            assertEquals(0, f.readyCount.get())
            assertTrue(f.gateway.call.appends.isEmpty())
            if (nativeFirst) f.media.listener.onOpen() else f.events.onStarted()
            assertTrue(accepted.await().isSuccess)
            f.drain()
            assertEquals(1, f.readyCount.get())
            assertEquals(1, f.media.confirms.get())
            assertTrue(f.media.mutes.all { !it })
            assertEquals(0, f.media.inputEnables.get())
        } }
    }

    @Test fun quickReleaseBeforeConnectionBuffersOnceThenAddsBoundedSilentTail() = Fixture().use { f ->
        val input = f.submit(final = true, bytes = ByteArray(200) { 7 })
        val result = f.finish()
        f.drain()
        assertFalse(input.isDone)
        assertFalse(result.isDone)
        assertTrue(f.gateway.call.appends.isEmpty())
        f.ready()
        assertTrue(input.await().isSuccess)
        f.tailDrained()
        assertEquals(5, f.gateway.call.appends.size)
        assertTrue(f.gateway.call.appends.first().bytes.contentEquals(ByteArray(200) { 7 }))
        assertTrue(f.gateway.call.appends.drop(1).all { a ->
            a.bytes.size == CodexShortLiveDictationProvider.CHUNK_BYTES && a.bytes.all { it == 0.toByte() }
        })
        assertTrue(f.gateway.call.appends.all { it.rate == 24_000 })
        assertFalse(result.isDone)
        f.final("u1", "user", "Open settings")
        f.final("a1", "assistant", "I passed that request to Codex.")
        assertEquals("Open settings", result.await().getOrThrow())
        assertEquals(1, f.completedCount.get())
        assertEquals(1, f.gateway.call.stops.get())
        assertEquals(1, f.media.closes.get())
        assertEquals(0, f.gateway.interrupts.get())
    }

    @Test fun onlyOneAppendOutstandingAndDuplicateAckNeverReplaysPcm() = Fixture().use { f ->
        f.gateway.call.autoAck = false
        f.ready()
        val first = f.submit(final = false)
        val second = f.submit(final = true)
        f.drain()
        assertEquals(1, f.gateway.call.appends.size)
        assertFalse(first.isDone)
        assertFalse(second.isDone)
        val firstAppend = f.gateway.call.appends[0]
        firstAppend.callback(Result.success(Unit))
        assertTrue(first.await().isSuccess)
        f.drain()
        assertEquals(2, f.gateway.call.appends.size)
        firstAppend.callback(Result.success(Unit))
        f.drain()
        assertFalse(second.isDone)
        assertEquals(2, f.gateway.call.appends.size)
        f.gateway.call.appends[1].callback(Result.success(Unit))
        assertTrue(second.await().isSuccess)
    }

    @Test fun greetingAndEarlierReplyCannotCompleteNewSemanticInput() = Fixture(replyTimeout = 160).use { f ->
        f.ready()
        f.final("greeting", "assistant", "Hello")
        assertTrue(f.submit(final = false).await().isSuccess)
        f.final("u-before-release", "user", "First part")
        f.final("a-before-release", "assistant", "I will do that.")
        f.drain()
        assertTrue(f.submit(final = true).await().isSuccess)
        f.events.onItemStarted("new-user-utterance", "user")
        f.events.onTranscript("user", "And also", false)
        val result = f.finish()
        f.tailDrained(expectedAppends = 6)
        assertFailure(result.await(), "codex_live_dictation_transcript_timeout")
        assertEquals(0, f.completedCount.get())
        assertEquals(0, f.gateway.interrupts.get())
        assertEquals(1, f.gateway.starts.get())
    }

    @Test fun replyDuringSilentTailIsPreservedButCannotCloseBeforeMediaDrain() = Fixture().use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        f.gateway.call.autoAck = false
        val result = f.finish()
        f.drain()
        f.final("u1", "user", "Use all of this recording")
        f.final("a1", "assistant", "I have passed your request on.")
        f.drain()
        assertFalse(result.isDone)
        repeat(4) { tailIndex ->
            assertEquals(tailIndex + 2, f.gateway.call.appends.size)
            assertFalse(result.isDone)
            f.gateway.call.appends[tailIndex + 1].callback(Result.success(Unit))
            f.drain()
        }
        assertEquals("Use all of this recording", result.await().getOrThrow())
        assertEquals(1, f.completedCount.get())
    }

    @Test fun observedVoiceExchangeSurvivesNonemptySilentRecorderTail() = Fixture().use { f ->
        f.ready()
        assertTrue(f.submit(final = false).await().isSuccess)
        f.final("u1", "user", "Open my requested app")
        f.final("a1", "assistant", "Codex is opening it.")
        f.drain()
        assertEquals(0, f.completedCount.get())
        assertTrue(f.submit(final = true, bytes = ByteArray(480)).await().isSuccess)
        val result = f.finish()
        assertEquals("Open my requested app", result.await().getOrThrow())
        assertEquals(1, f.completedCount.get())
    }

    @Test fun duplicateUserFinalDoesNotEraseAlreadyObservedReply() = Fixture().use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        f.final("u1", "user", "Open settings")
        f.final("a1", "assistant", "I passed it on.")
        f.events.onTranscript("user", "Open settings", true)
        val result = f.finish()
        assertEquals("Open settings", result.await().getOrThrow())
    }

    @Test fun earlierGreetingDoesNotPreventConfirmedUserTextFromReachingCodex() = Fixture(replyTimeout = 170).use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.tailDrained()
        f.events.onItemStarted("old-greeting", "assistant")
        f.final("u1", "user", "Do the requested work")
        f.events.onItemCompleted("old-greeting", "assistant", "Hello")
        f.events.onTranscript("assistant", "Hello", true)
        assertEquals("Do the requested work", result.await().getOrThrow())
        assertEquals(listOf("Do the requested work"), f.gateway.call.delivered)
    }

    @Test fun assistantPromotionAndMissingAssistantFlatFinalCannotBlockDictation() = Fixture(replyTimeout = 170).use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.tailDrained()
        f.final("u1", "user", "Open settings")
        f.events.onItemStarted("a1", "assistant")
        f.events.onItemCompleted("a1", "assistant", "I am doing that.")
        // Native history can seal a segment without emitting the actual flat final event.
        assertEquals("Open settings", result.await().getOrThrow())
        assertEquals(1, f.completedCount.get())
    }

    @Test fun uncorrelatedFlatFinalsFailClosedInsteadOfUsingFirstAssistantText() = Fixture(replyTimeout = 170).use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.tailDrained()
        f.events.onTranscript("user", "Open settings", true)
        f.events.onTranscript("assistant", "Done", true)
        assertFailure(result.await(), "codex_live_dictation_transcript_timeout")
    }

    @Test fun recognizedTextWithoutAnyVoiceReplyWaitsForCodexDispatchAck() = Fixture().use { f ->
        f.ready()
        f.gateway.call.autoDispatchAck = false
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.final("u1", "user", "Hallo, hörst du mich?")
        assertTrue(f.gateway.call.dispatchRequested.await(2, TimeUnit.SECONDS))
        f.drain()
        assertFalse(result.isDone)
        assertEquals(1, f.gateway.call.stops.get())
        assertEquals(listOf("Hallo, hörst du mich?"), f.gateway.call.delivered)
        f.gateway.call.dispatchCompletion!!.invoke(Result.success(Unit))
        assertEquals("Hallo, hörst du mich?", result.await().getOrThrow())
        f.events.onCloseConfirmed()
        f.drain()
        assertEquals(1, f.gateway.call.delivered.size)
        assertEquals(0, f.gateway.interrupts.get())
    }

    @Test fun duplexAssistantCanFinishBeforeUserFinalWithoutCausingReplyTimeout() = Fixture().use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.events.onItemStarted("u1", "user")
        f.events.onTranscript("user", "Open settings", false)
        f.final("a1", "assistant", "I am passing that on.")
        f.events.onItemCompleted("u1", "user", "Open settings")
        f.events.onTranscript("user", "Open settings", true)
        assertEquals("Open settings", result.await().getOrThrow())
        assertEquals(listOf("Open settings"), f.gateway.call.delivered)
    }

    @Test fun handoffDrainedAfterStopPreventsAnyFallbackDispatch() = Fixture().use { f ->
        f.ready()
        f.gateway.call.autoClose = false
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.final("u1", "user", "Do this once")
        assertTrue(f.gateway.call.stopRequested.await(2, TimeUnit.SECONDS))
        f.events.onHandoff()
        f.events.onCloseConfirmed()
        f.events.onClosed()
        assertEquals("Do this once", result.await().getOrThrow())
        assertTrue(f.gateway.call.delivered.isEmpty())
        assertEquals(1, f.gateway.call.stops.get())
    }

    @Test fun stopWithoutAuthoritativeCloseNeverDispatchesOrReportsSuccess() = Fixture(closeTimeout = 100).use { f ->
        f.ready()
        f.gateway.call.autoClose = false
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.final("u1", "user", "Do not replay uncertain input")
        assertFailure(result.await(), "codex_live_dictation_close_timeout")
        assertTrue(f.gateway.call.delivered.isEmpty())
        assertEquals(0, f.completedCount.get())
    }

    @Test fun failedOrRejectedFallbackNeverRetriesOrMarksCompletion() {
        listOf(false, true).forEach { rejected -> Fixture().use { f ->
            f.ready()
            f.gateway.call.rejectDispatch = rejected
            f.gateway.call.dispatchResult = Result.failure(IllegalStateException("ambiguous"))
            assertTrue(f.submit(final = true).await().isSuccess)
            val result = f.finish()
            f.final("u1", "user", "Exactly once")
            assertFailure(result.await(), if (rejected) "codex_live_dictation_dispatch_unconfirmed" else "codex_live_dictation_dispatch_failed")
            assertEquals(1, f.gateway.call.delivered.size)
            assertEquals(0, f.completedCount.get())
            assertEquals(0, f.gateway.interrupts.get())
        } }
    }

    @Test fun lateUnfinishedUserInputAtCloseCannotDispatchIncompleteText() = Fixture().use { f ->
        f.ready()
        f.gateway.call.autoClose = false
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.final("u1", "user", "Initial phrase")
        assertTrue(f.gateway.call.stopRequested.await(2, TimeUnit.SECONDS))
        f.events.onItemStarted("u2", "user")
        f.events.onTranscript("user", "But", false)
        f.events.onCloseConfirmed()
        assertFailure(result.await(), "codex_live_dictation_transcript_incomplete")
        assertTrue(f.gateway.call.delivered.isEmpty())
    }

    @Test fun userCorrectionDuringQuietGraceInvalidatesOldReply() = Fixture(grace = 200).use { f ->
        f.ready()
        assertTrue(f.submit(final = true).await().isSuccess)
        val result = f.finish()
        f.tailDrained()
        f.final("u1", "user", "First request")
        f.final("a1", "assistant", "I passed that on.")
        f.events.onItemStarted("u2", "user")
        f.events.onTranscript("user", "Correction", false)
        f.drain()
        assertFalse(result.isDone)
        f.events.onItemCompleted("u2", "user", "Correction to the request")
        f.events.onTranscript("user", "Correction to the request", true)
        f.final("a2", "assistant", "The correction is passed on.")
        assertEquals("First request Correction to the request", result.await().getOrThrow())
        assertEquals(1, f.completedCount.get())
    }

    @Test fun cumulativeDisplayKeepsRepeatedUserSegmentsButNeverAssistantText() = Fixture().use { f ->
        f.ready()
        f.events.onItemStarted("u1", "user")
        f.events.onTranscript("user", "Again", false)
        f.events.onItemCompleted("u1", "user", "Again")
        f.events.onTranscript("user", "Again", true)
        f.events.onTranscript("user", "Again", true) // Same canonical identity: duplicate.
        f.events.onItemStarted("u2", "user")
        f.events.onTranscript("user", "Again", false)
        f.events.onItemCompleted("u2", "user", "Again")
        f.events.onTranscript("user", "Again", true)
        f.final("assistant", "assistant", "This must never become the user's message.")
        f.drain()
        assertEquals("Again Again", f.partials.last())
        assertTrue(f.partials.none { it.contains("must never") })
    }

    @Test fun cancelInFlightSettlesOnceAndLateNativeEventsCannotRestartOrDispatch() = Fixture().use { f ->
        f.gateway.call.autoAck = false
        f.ready()
        val accepted = f.submit(final = true)
        f.drain()
        val append = f.gateway.call.appends.single()
        f.session.cancel()
        f.session.cancel()
        assertTrue(accepted.await().isFailure)
        append.callback(Result.success(Unit))
        f.events.onStarted()
        f.events.onRemoteSdp("late-sdp")
        f.final("late-user", "user", "Do not replay")
        f.final("late-assistant", "assistant", "Done")
        f.drain()
        assertEquals(1, f.gateway.call.appends.size)
        assertEquals(1, f.gateway.call.stops.get())
        assertEquals(1, f.media.closes.get())
        assertEquals(0, f.completedCount.get())
        assertEquals(0, f.gateway.interrupts.get())
        assertTrue(f.partials.isEmpty())
    }

    @Test fun cancelBeforeReadySendsNoAudioAndCannotBeReopenedByLateReadiness() = Fixture().use { f ->
        val accepted = f.submit(final = true)
        f.session.cancel()
        assertTrue(accepted.await().isFailure)
        f.events.onStarted()
        f.media.listener.onOpen()
        f.drain()
        assertTrue(f.gateway.call.appends.isEmpty())
        assertEquals(0, f.readyCount.get())
        assertEquals(1, f.gateway.call.stops.get())
    }

    @Test fun nativeUsageFailureIsVisibleWithoutApiFallbackOrReplay() = Fixture().use { f ->
        f.events.onError(CodexRealtimeIssue.USAGE_LIMIT)
        f.drain()
        assertEquals(listOf("codex_live_usage_limit"), f.failures)
        val result = f.finish().await()
        assertFailure(result, "codex_live_usage_limit")
        assertEquals(1, f.gateway.starts.get())
        assertEquals(1, f.gateway.call.stops.get())
        assertEquals(0, f.gateway.interrupts.get())
    }

    @Test fun appendTypedErrorAndRejectionAreBoundedAndNeverRetried() {
        listOf(false, true).forEach { rejected -> Fixture().use { f ->
            f.gateway.call.reject = rejected
            f.gateway.call.autoAck = false
            f.ready()
            val accepted = f.submit(final = true)
            f.drain()
            if (!rejected) f.gateway.call.appends.single().callback(
                Result.failure(CodexRealtimeFailure(CodexRealtimeIssue.SESSION_CHANGED)))
            assertTrue(accepted.await().isFailure)
            f.drain()
            assertEquals(listOf(if (rejected) "codex_live_dictation_audio_rejected" else "codex_live_session_changed"), f.failures)
            assertEquals(1, f.gateway.call.appends.size)
            assertEquals(1, f.gateway.call.stops.get())
        } }
    }

    @Test fun missingAppendAckTimesOutAndDoesNotReplayOrInterruptAcceptedWork() = Fixture(ackTimeout = 100).use { f ->
        f.gateway.call.autoAck = false
        f.ready()
        val accepted = f.submit(final = true)
        assertFailure(accepted.await(), "codex_live_dictation_audio_ack_timeout")
        f.drain()
        assertEquals(1, f.gateway.call.appends.size)
        assertEquals(0, f.gateway.interrupts.get())
        assertEquals(listOf("codex_live_dictation_audio_ack_timeout"), f.failures)
    }

    @Test fun emptyInputAndMissingFinalMarkerHaveHonestFailureNotEmptySuccess() {
        Fixture().use { f ->
            f.ready()
            assertTrue(f.submit(final = true, bytes = ByteArray(0)).await().isSuccess)
            assertFailure(f.finish().await(), "codex_live_dictation_no_audio")
            assertTrue(f.gateway.call.appends.isEmpty())
        }
        Fixture().use { f ->
            f.ready()
            assertTrue(f.submit(final = false).await().isSuccess)
            assertFailure(f.finish().await(), "codex_live_dictation_finish_order_invalid")
        }
    }

    @Test fun malformedOrderFormatAndRecordingIdentityNeverReachNativeInput() {
        val cases = listOf(
            Triple(RecordingId(2), 0L, ByteArray(200)),
            Triple(RecordingId(1), 1L, ByteArray(200)),
            Triple(RecordingId(1), 0L, ByteArray(3)),
            Triple(RecordingId(1), 0L, ByteArray(24_002)),
        )
        cases.forEach { (id, index, bytes) -> Fixture().use { f ->
            f.ready()
            val result = CompletableFuture<Result<Unit>>()
            f.session.submitChunk(PcmAudioChunk.create(id, index, bytes, true, 0), result::complete)
            assertTrue(result.await().isFailure)
            assertTrue(f.gateway.call.appends.isEmpty())
        } }
    }

    @Test fun maximumRecordingIs244NativeAppendsIncludingTailAndOverflowIsRejected() {
        Fixture().use { f ->
            f.ready()
            repeat(240) { index -> assertTrue(f.submit(final = index == 239).await().isSuccess) }
            val result = f.finish()
            f.tailDrained(expectedAppends = 244)
            assertEquals(244, f.gateway.call.appends.size)
            assertFalse(result.isDone)
            f.session.cancel()
            assertTrue(result.await().isFailure)
        }
        Fixture().use { f ->
            f.ready()
            repeat(240) { assertTrue(f.submit(final = false).await().isSuccess) }
            assertFailure(f.submit(final = true).await(), "codex_live_dictation_input_limit")
            assertEquals(240, f.gateway.call.appends.size)
        }
    }

    @Test fun longPacedRecordingWithOnlyUserDeltasWaitsFull45SecondsThenReleasesWithoutReplay() =
        PacedFixture().use { f ->
            f.recordAndDrainRealAudio()
            val finishAt = f.finishAtMillis
            assertEquals(30_165L, finishAt)
            assertEquals(52, f.realAcks)
            assertEquals(0, f.completedCount)
            assertFalse(f.result.isDone)

            f.time.advanceTo(finishAt + 2_000)
            assertEquals(56, f.audioAckTimes.size)
            assertEquals(1, f.maximumOutstandingAudio)
            assertTrue(f.gateway.call.appends.drop(52).all { append -> append.bytes.all { it == 0.toByte() } })
            assertTrue(f.partials.isNotEmpty())
            assertEquals(52, f.partials.size)
            assertEquals(1, f.finishCalls)

            // Local PCM/tail drain and ongoing recognition are not a native input final.
            f.time.advanceTo(finishAt + 44_999)
            assertFalse(f.result.isDone)
            assertTrue(f.failures.isEmpty())
            assertEquals(0, f.gateway.call.stops.get())
            assertTrue(f.gateway.call.delivered.isEmpty())
            assertEquals(0, f.media.closes.get())

            f.time.advanceTo(finishAt + 45_000)
            assertFailure(f.result.get(), "codex_live_dictation_transcript_timeout")
            assertEquals(listOf("codex_live_dictation_transcript_timeout"), f.failures)
            assertEquals(0, f.completedCount)
            assertEquals(1, f.gateway.call.stops.get())
            assertEquals(1, f.media.closes.get())
            assertTrue(f.gateway.call.delivered.isEmpty())

            // A late final/close and expired startup/input deadlines cannot retry the request.
            f.finalUser()
            f.events.onCloseConfirmed()
            f.events.onClosed()
            f.time.advanceTo(180_000)
            assertEquals(1, f.gateway.starts.get())
            assertEquals(56, f.gateway.call.appends.size)
            assertEquals(1, f.gateway.call.stops.get())
            assertEquals(1, f.media.closes.get())
            assertEquals(1, f.failures.size)
            assertEquals(0, f.gateway.interrupts.get())
            assertTrue(f.gateway.call.delivered.isEmpty())
        }

    @Test fun sameLongPacedRecordingWithFinalAndConfirmedCloseDeliversExactlyOnceAfterDispatchAck() =
        PacedFixture().use { f ->
            f.gateway.call.autoClose = false
            f.gateway.call.autoDispatchAck = false
            f.recordAndDrainRealAudio()
            val finishAt = f.finishAtMillis
            // The user final may arrive while the separately paced silent tail is still draining.
            f.time.advanceTo(finishAt + 1_000)
            f.finalUser()
            f.time.runCurrent()
            assertFalse(f.result.isDone)
            assertEquals(0, f.gateway.call.stops.get())
            f.time.advanceTo(finishAt + 2_000)
            assertEquals(56, f.audioAckTimes.size)
            f.time.advanceTo(finishAt + 4_999)
            assertEquals(0, f.gateway.call.stops.get())
            f.time.advanceTo(finishAt + 5_000)
            assertEquals(1, f.gateway.call.stops.get())
            assertFalse(f.result.isDone)
            assertTrue(f.gateway.call.delivered.isEmpty())

            f.events.onCloseConfirmed()
            f.events.onClosed()
            f.time.runCurrent()
            assertEquals(listOf(PacedFixture.TRANSCRIPT), f.gateway.call.delivered)
            assertFalse(f.result.isDone)
            assertEquals(0, f.completedCount)
            f.gateway.call.dispatchCompletion!!.invoke(Result.success(Unit))
            f.time.runCurrent()
            assertEquals(PacedFixture.TRANSCRIPT, f.result.get().getOrThrow())
            assertEquals(1, f.completedCount)
            assertEquals(1, f.media.closes.get())

            f.events.onCloseConfirmed()
            f.gateway.call.dispatchCompletion!!.invoke(Result.success(Unit))
            f.finalUser()
            f.time.advanceTo(180_000)
            assertEquals(listOf(PacedFixture.TRANSCRIPT), f.gateway.call.delivered)
            assertEquals(1, f.gateway.starts.get())
            assertEquals(1, f.gateway.call.stops.get())
            assertEquals(1, f.completedCount)
            assertEquals(1, f.media.closes.get())
            assertEquals(0, f.gateway.interrupts.get())
            assertTrue(f.failures.isEmpty())
        }

    @Test fun virtualDeadlineMayCancelItselfDuringTerminalCleanupWithoutAbortingLaterCallbacks() {
        val time = VirtualScheduler()
        val completed = mutableListOf<String>()
        lateinit var deadline: ScheduledFuture<*>
        deadline = time.schedule({
            // Production end() cancels every deadline, including the currently executing one.
            deadline.cancel(false)
            completed += "terminal cleanup"
            time.execute { completed += "closed callback" }
        }, 45_000, TimeUnit.MILLISECONDS)
        time.advanceTo(45_000)
        assertTrue(deadline.isCancelled)
        assertEquals(listOf("terminal cleanup", "closed callback"), completed)
        time.shutdownNow()
    }

    @Test fun unexpectedNativeCloseAndNetworkLossCannotBecomeSuccess() {
        listOf(false, true).forEach { networkLoss -> Fixture().use { f ->
            f.ready()
            assertTrue(f.submit(final = true).await().isSuccess)
            val result = f.finish()
            f.tailDrained()
            if (networkLoss) f.provider.onNetworkUnavailable() else f.events.onClosed()
            assertFailure(result.await(), if (networkLoss) "codex_live_dictation_network_unavailable" else "codex_live_dictation_native_closed")
            assertEquals(0, f.completedCount.get())
            assertEquals(0, f.gateway.interrupts.get())
        } }
    }

    @Test fun exactRecorderFormatAndProviderClosedStateAreEnforced() = Fixture().use { f ->
        assertTrue(runCatching { f.provider.openSession(RecordingId(2), PcmAudioFormat()) }.isFailure)
        assertEquals(24_000, CodexShortLiveDictationProvider.AUDIO_FORMAT.sampleRateHz)
        assertEquals(500L, CodexShortLiveDictationProvider.CHUNK_DURATION_MILLIS)
        assertEquals(120_000L, CodexShortLiveDictationProvider.MAXIMUM_DURATION_MILLIS)
        f.provider.close()
        assertTrue(runCatching {
            f.provider.openSession(RecordingId(2), CodexShortLiveDictationProvider.AUDIO_FORMAT)
        }.isFailure)
    }

    @Test fun closeRacingWithTerminalSessionRemovalNeverThrowsOrClosesTwice() {
        // submitChunk invokes the rejected-chunk callback immediately before terminal cleanup.
        // Closing from the test thread while that cleanup removes the last active session
        // exercises the ConcurrentHashMap size-one snapshot race found by the limit test.
        repeat(100) {
            Fixture().use { f ->
                val rejected = CompletableFuture<Result<Unit>>()
                f.session.submitChunk(
                    PcmAudioChunk.create(RecordingId(2), 0, ByteArray(200), true, 0),
                    rejected::complete,
                )
                assertTrue(rejected.await().isFailure)
                f.provider.onNetworkUnavailable()
                f.provider.close()
                f.drain()
                assertEquals(1, f.gateway.call.stops.get())
                assertEquals(1, f.media.closes.get())
                assertTrue(f.gateway.call.appends.isEmpty())
                assertEquals(0, f.gateway.interrupts.get())
            }
        }
    }

    /** Reproduces local timing only: no microphone, RTP, server VAD or server receipt is simulated. */
    private class PacedFixture : Closeable {
        val time = VirtualScheduler()
        val gateway = Gateway()
        lateinit var media: Media
        val partials = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val audioAckTimes = mutableListOf<Long>()
        val result = CompletableFuture<Result<String>>()
        var realAcks = 0
        var finishCalls = 0
        var finishAtMillis = -1L
        var completedCount = 0
        var maximumOutstandingAudio = 0
        private var outstandingAudio = 0
        private var recordingReleased = false
        val events get() = gateway.callbacks
        private val provider = CodexShortLiveDictationProvider(
            gateway = gateway,
            transportFactory = { Media(it, gateway.call).also { created -> media = created } },
            instructionsProvider = LiveVoiceInstructionsProvider { "Synthetic timing reproduction." },
            observer = object : CodexShortLiveDictationObserver {
                override fun onPartialTranscript(recordingId: RecordingId, transcript: String) { partials += transcript }
                override fun onCompleted(recordingId: RecordingId) { completedCount++ }
                override fun onFailure(recordingId: RecordingId, code: String) { failures += code }
            },
            config = CodexShortLiveDictationConfig(), // Actual 20s/45s/10s/3s production deadlines.
            scheduler = time,
            nanoTime = { time.nowNanos },
        )
        private val session = provider.openSession(RecordingId(1), CodexShortLiveDictationProvider.AUDIO_FORMAT)

        init {
            gateway.call.onPrimaryAppend = { append ->
                outstandingAudio++
                maximumOutstandingAudio = maxOf(maximumOutstandingAudio, outstandingAudio)
                val appendNumber = gateway.call.appends.size
                // Serial copying consumes the actual PCM duration: 500ms chunks, then 365ms.
                // This ACK deliberately proves only a local copy, never network/model consumption.
                time.schedule({
                    outstandingAudio--
                    audioAckTimes += time.nowMillis
                    append.callback(Result.success(Unit))
                    if (appendNumber <= REAL_CHUNKS) {
                        if (appendNumber == 1) events.onItemStarted("long-user", "user")
                        events.onTranscript("user", "part$appendNumber ", false)
                    }
                }, append.bytes.size / BYTES_PER_MILLISECOND, TimeUnit.MILLISECONDS)
            }
            time.runCurrent()
            assertEquals(1, gateway.starts.get())
        }

        fun recordAndDrainRealAudio() {
            repeat(REAL_CHUNKS) { index ->
                val final = index == REAL_CHUNKS - 1
                val capturedAt = if (final) RECORDING_MILLIS else (index + 1) * 500L
                val duration = if (final) 365 else 500
                time.schedule({
                    if (final) recordingReleased = true
                    session.submitChunk(PcmAudioChunk.create(
                        RecordingId(1), index.toLong(), ByteArray(duration * BYTES_PER_MILLISECOND.toInt()) { 11 },
                        final, capturedAt,
                    )) { accepted ->
                        assertTrue(accepted.isSuccess)
                        realAcks++
                        // Mirrors the recorder's pending-chunk barrier, not an early finish call.
                        if (recordingReleased && realAcks == REAL_CHUNKS) {
                            finishCalls++
                            finishAtMillis = time.nowMillis
                            session.finish(result::complete)
                        }
                    }
                }, capturedAt, TimeUnit.MILLISECONDS)
            }
            time.advanceTo(4_299)
            assertTrue(gateway.call.appends.isEmpty())
            assertEquals(0, realAcks)
            time.advanceTo(4_300)
            events.onRemoteSdp("v=answer")
            events.onStarted()
            media.listener.onOpen()
            time.runCurrent()
            assertEquals(1, gateway.call.appends.size)
            time.advanceTo(4_799)
            assertEquals(0, realAcks)
            time.advanceTo(4_800)
            assertEquals(1, realAcks)
            assertTrue(partials.isNotEmpty())
            time.advanceTo(RECORDING_MILLIS)
            assertTrue(recordingReleased)
            assertEquals(0, finishCalls)
            assertFalse(result.isDone)
            assertTrue(realAcks < REAL_CHUNKS)
            time.advanceTo(30_164)
            assertEquals(51, realAcks)
            assertEquals(0, finishCalls)
            time.advanceTo(30_165)
            assertEquals(52, realAcks)
            assertEquals(1, finishCalls)
            assertTrue(gateway.call.delivered.isEmpty())
            assertTrue(failures.isEmpty())
        }

        fun finalUser() {
            events.onItemCompleted("long-user", "user", TRANSCRIPT)
            events.onTranscript("user", TRANSCRIPT, true)
        }

        override fun close() {
            provider.close()
            time.runCurrent()
            time.shutdownNow()
        }

        companion object {
            const val RECORDING_MILLIS = 25_865L
            const val REAL_CHUNKS = 52
            const val BYTES_PER_MILLISECOND = 48L
            const val TRANSCRIPT = "Synthetic complete long dictated request."
        }
    }

    /** All callbacks and deadlines use one deterministic clock; no wall-clock sleeps or workers. */
    private class VirtualScheduler : AbstractExecutorService(), ScheduledExecutorService {
        var nowNanos = 0L
            private set
        val nowMillis get() = TimeUnit.NANOSECONDS.toMillis(nowNanos)
        private var nextOrder = 0L
        private var stopped = false
        private val queue = PriorityQueue<Task<*>>(compareBy({ it.dueNanos }, { it.order }))

        fun runCurrent() = advanceTo(nowMillis)

        fun advanceTo(millis: Long) {
            val target = TimeUnit.MILLISECONDS.toNanos(millis)
            require(target >= nowNanos)
            var executed = 0
            while (queue.peek()?.let { it.dueNanos <= target } == true) {
                check(executed++ < 100_000) { "Virtual scheduler did not settle" }
                val task = queue.remove()
                nowNanos = task.dueNanos
                if (!task.isCancelled) {
                    task.run()
                    // A running timeout may cancel its own Future during terminal cleanup.
                    // Only uncancelled futures can expose callback failures through get().
                    if (!task.isCancelled) task.get()
                }
            }
            nowNanos = target
        }

        override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.NANOSECONDS) }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run(); Unit }, delay, unit)

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (stopped) throw RejectedExecutionException("Virtual scheduler closed")
            return Task(nowNanos + unit.toNanos(delay.coerceAtLeast(0)), nextOrder++, callable).also(queue::add)
        }

        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("No polling is needed by this provider")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("No polling is needed by this provider")
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            queue.forEach { it.cancel(false) }
            queue.clear()
            return mutableListOf()
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated

        private inner class Task<V>(val dueNanos: Long, val order: Long, callable: Callable<V>) :
            FutureTask<V>(callable), ScheduledFuture<V> {
            override fun getDelay(unit: TimeUnit) = unit.convert(dueNanos - nowNanos, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
    }

    private class Fixture(
        replyTimeout: Long = 2_000,
        ackTimeout: Long = 1_000,
        grace: Long = 50,
        closeTimeout: Long = 1_000,
    ) : Closeable {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val gateway = Gateway()
        lateinit var media: Media
        val partials = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val readyCount = AtomicInteger()
        val completedCount = AtomicInteger()
        val provider = CodexShortLiveDictationProvider(
            gateway,
            transportFactory = { Media(it, gateway.call).also { made -> media = made } },
            instructionsProvider = LiveVoiceInstructionsProvider { "Reply in English. Glossary: []" },
            observer = object : CodexShortLiveDictationObserver {
                override fun onSessionReady(recordingId: RecordingId) { readyCount.incrementAndGet() }
                override fun onPartialTranscript(recordingId: RecordingId, transcript: String) { partials += transcript }
                override fun onCompleted(recordingId: RecordingId) { completedCount.incrementAndGet() }
                override fun onFailure(recordingId: RecordingId, code: String) { failures += code }
            },
            config = CodexShortLiveDictationConfig(
                sessionReadyTimeoutMillis = 3_000, finalTranscriptTimeoutMillis = replyTimeout,
                audioAckTimeoutMillis = ackTimeout, minimumSendIntervalMillis = 0,
                transcriptQuietGraceMillis = grace,
                closeTimeoutMillis = closeTimeout,
            ),
            scheduler = scheduler,
        )
        val session: IncrementalSttSession = provider.openSession(RecordingId(1), CodexShortLiveDictationProvider.AUDIO_FORMAT)
        private var nextIndex = 0L
        val events get() = gateway.callbacks

        init { assertTrue("native exchange was not started", gateway.started.await(2, TimeUnit.SECONDS)); drain() }

        fun ready() {
            events.onRemoteSdp("v=answer")
            events.onStarted()
            media.listener.onOpen()
            drain()
        }

        fun submit(final: Boolean, bytes: ByteArray = ByteArray(24_000) { 11 }): CompletableFuture<Result<Unit>> {
            val answer = CompletableFuture<Result<Unit>>()
            session.submitChunk(PcmAudioChunk.create(RecordingId(1), nextIndex++, bytes, final, 0), answer::complete)
            return answer
        }

        fun finish(): CompletableFuture<Result<String>> = CompletableFuture<Result<String>>().also { result ->
            session.finish(result::complete)
        }

        fun final(id: String, role: String, text: String) {
            events.onItemStarted(id, role)
            events.onItemCompleted(id, role, text)
            events.onTranscript(role, text, true)
        }

        fun tailDrained(expectedAppends: Int = 5) {
            // Each callback queues one next send; several barriers flush the bounded four-chunk tail.
            drain()
            assertEquals(expectedAppends, gateway.call.appends.size)
        }

        fun drain() { repeat(12) { scheduler.submit {}.get(2, TimeUnit.SECONDS) } }

        override fun close() {
            provider.close()
            drain()
            scheduler.shutdownNow()
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class Gateway : CodexRealtimeGateway {
        val started = CountDownLatch(1)
        val starts = AtomicInteger()
        val interrupts = AtomicInteger()
        val call = Call()
        lateinit var callbacks: CodexRealtimeCallbacks
        lateinit var options: CodexRealtimeOptions
        var prompt = ""
        var voice: String? = null
        override fun start(offerSdp: String, prompt: String, voice: String?, callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? =
            error("Short dictation must explicitly disable delegation filler")

        override fun start(offerSdp: String, prompt: String, voice: String?, options: CodexRealtimeOptions,
                           callbacks: CodexRealtimeCallbacks): CodexRealtimeCall {
            this.callbacks = callbacks
            call.events = callbacks
            this.options = options
            this.prompt = prompt
            this.voice = voice
            starts.incrementAndGet()
            started.countDown()
            return call
        }

        override fun interruptCurrentTurn(): Boolean { interrupts.incrementAndGet(); return false }
    }

    private class Call : CodexRealtimeCall {
        val appends = CopyOnWriteArrayList<Append>()
        val stops = AtomicInteger()
        val stopRequested = CountDownLatch(1)
        val dispatchRequested = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<String>()
        lateinit var events: CodexRealtimeCallbacks
        @Volatile var autoClose = true
        @Volatile var autoDispatchAck = true
        @Volatile var rejectDispatch = false
        @Volatile var dispatchResult = Result.success(Unit)
        @Volatile var dispatchCompletion: ((Result<Unit>) -> Unit)? = null
        @Volatile var autoAck = true
        @Volatile var reject = false
        var onPrimaryAppend: ((Append) -> Unit)? = null
        override fun stop() {
            stops.incrementAndGet()
            stopRequested.countDown()
            if (autoClose) { events.onCloseConfirmed(); events.onClosed() }
        }
        override fun finishUnroutedDictation(text: String, callback: (Result<Unit>) -> Unit): Boolean {
            delivered += text
            dispatchCompletion = callback
            dispatchRequested.countDown()
            if (rejectDispatch) return false
            if (autoDispatchAck) callback(dispatchResult)
            return true
        }
        override fun appendAudio(base64: String, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean =
            error("Dictation must send audio through primary media, never the native sideband")

        // Shared synthetic recorder for assertions; this is invoked only by Media below.
        fun primaryAudio(pcm: ByteArray, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean {
            val append = Append(pcm.copyOf(), sampleRateHz, callback)
            appends += append
            if (reject) return false
            val scheduled = onPrimaryAppend
            if (scheduled != null) scheduled(append) else if (autoAck) callback(Result.success(Unit))
            return true
        }
    }

    private data class Append(val bytes: ByteArray, val rate: Int, val callback: (Result<Unit>) -> Unit)

    private class Media(private val provider: LiveSessionProvider, private val audio: Call) : LiveVoiceTransport {
        lateinit var setup: LiveSessionSetup
        lateinit var listener: LiveVoiceTransport.Listener
        val mutes = CopyOnWriteArrayList<Boolean>()
        val confirms = AtomicInteger()
        val inputEnables = AtomicInteger()
        val closes = AtomicInteger()
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.setup = setup
            this.listener = listener
            provider.create(setup, "v=offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) = Unit
                override fun onFailure(failure: LiveVoiceFailure) = Unit
            })
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            error("No API credential path is allowed")
        override fun confirmSessionStarted(): Boolean { confirms.incrementAndGet(); return true }
        override fun appendInputAudio(pcm: ByteArray, sampleRateHz: Int, callback: (Result<Unit>) -> Unit): Boolean =
            audio.primaryAudio(pcm, sampleRateHz, callback)
        override fun setUserInputMuted(muted: Boolean): Boolean { mutes += muted; return true }
        override fun setInputAudioEnabled(enabled: Boolean): Boolean { inputEnables.incrementAndGet(); return false }
        override fun sendUtf8(event: String): Boolean = error("No peer input/replay path")
        override fun clearOutputAudio(): Boolean = false
        override fun close() { closes.incrementAndGet() }
    }

    private fun <T> CompletableFuture<T>.await(): T = get(3, TimeUnit.SECONDS)
    private fun assertFailure(result: Result<*>, expected: String) {
        assertTrue(result.isFailure)
        assertEquals(expected, result.exceptionOrNull()?.message)
    }
}
