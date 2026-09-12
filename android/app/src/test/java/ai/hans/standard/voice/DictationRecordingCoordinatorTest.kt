package ai.hans.standard.voice

import ai.hans.standard.voice.tts.throwingPhysicalPlayerCaptureBarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationRecordingCoordinatorTest {
    @Test
    fun transcriptionConnectionWarmsDuringBarrierButMicrophoneWaitsForAcknowledgement() {
        var barrierCalls = 0
        lateinit var fixture: Fixture
        fixture = Fixture(
            captureStartBarrier = DictationCaptureStartBarrier {
                barrierCalls += 1
                assertEquals(1, fixture.sttProvider.sessions.size)
                assertTrue(fixture.sttProvider.sessions.single().chunks.isEmpty())
                assertTrue(fixture.captureFactory.captures.isEmpty())
                true
            },
        )

        fixture.coordinator.startRecording()

        assertEquals(1, barrierCalls)
        assertEquals(1, fixture.sttProvider.sessions.size)
        assertEquals(1, fixture.captureFactory.captures.size)
        assertTrue(fixture.coordinator.state() is RecordingState.Recording)
    }

    @Test
    fun unacknowledgedCaptureBarrierCancelsWarmConnectionWithoutOpeningMicrophone() {
        val fixture = Fixture(
            captureStartBarrier = DictationCaptureStartBarrier { false },
        )

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.focus.abandonCalls)
        assertEquals(
            RecordingFailure.AUDIO_CAPTURE_FAILED,
            (fixture.coordinator.state() as RecordingState.Failed).failure,
        )
    }

    @Test
    fun captureBarrierExceptionCancelsWarmConnectionWithoutOpeningMicrophone() {
        val fixture = Fixture(
            captureStartBarrier = DictationCaptureStartBarrier {
                error("stop acknowledgement unavailable")
            },
        )

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.focus.abandonCalls)
        assertTrue(fixture.coordinator.state() is RecordingState.Failed)
    }

    @Test
    fun throwingPhysicalTtsPlayerPreventsDictationMicrophoneCapture() {
        val fixture = Fixture(
            captureStartBarrier = DictationCaptureStartBarrier(
                throwingPhysicalPlayerCaptureBarrier(),
            ),
        )

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.focus.abandonCalls)
        assertTrue(fixture.coordinator.state() is RecordingState.Failed)
    }

    @Test
    fun completedDictationReleasesPhysicalCaptureAndBusyGateBeforeSubmittingExactlyOnce() {
        val fixture = Fixture()
        val recording = fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        val stt = fixture.sttProvider.sessions.single()
        var gateReleasedBeforeText = false
        fixture.listener.beforeStatePublished = { state ->
            if (state is RecordingState.Completed) {
                assertEquals(1, capture.closeCalls)
                assertTrue(fixture.listener.messages.isEmpty())
                gateReleasedBeforeText = true
            }
        }
        fixture.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.RuntimeActivityChanged(active = true))
        fixture.coordinator.stopRecording(recording)
        capture.completeStop(byteArrayOf(4, 5, 6))
        assertTrue(fixture.listener.messages.isEmpty())
        stt.completeFinal("Finaler Steuertext")
        assertTrue(gateReleasedBeforeText)
        assertEquals(listOf(recording to "Finaler Steuertext"), fixture.listener.messages)
        stt.completeFinal("Finaler Steuertext")
        assertEquals(1, fixture.listener.messages.size)
    }

    @Test
    fun fixedChunksAreIncrementalButOnlyFinalAggregateBecomesUserMessage() {
        val fixture = Fixture()
        val firstId = fixture.coordinator.startRecording()
        val firstCapture = fixture.captureFactory.captures.single()
        val firstStt = fixture.sttProvider.sessions.single()

        firstCapture.emit(ByteArray(fixture.config.chunkBytes) { 1 })
        firstCapture.emit(ByteArray(fixture.config.chunkBytes) { 2 })
        assertEquals(2, firstStt.chunks.size)
        assertTrue(fixture.listener.messages.isEmpty())

        fixture.coordinator.stopRecording(firstId)
        // A read already in flight at stop is still valid and must not be lost.
        firstCapture.emit(ByteArray(fixture.config.chunkBytes) { 3 })
        firstCapture.completeStop(byteArrayOf(4, 5, 6))
        assertEquals(4, firstStt.chunks.size)
        assertTrue(firstStt.chunks.last().isFinal)
        assertEquals(1, firstStt.finishCalls)
        assertTrue(fixture.listener.messages.isEmpty())

        firstStt.completeFinal("one complete message")
        assertEquals(listOf("one complete message"), fixture.listener.messages.map { it.second })

        // Codex may still be working on the first message; recording owns no
        // Codex busy/turn state, so the next dictation starts immediately.
        fixture.coordinator.onEnvironmentEvent(
            VoiceEnvironmentEvent.RuntimeActivityChanged(active = true),
        )
        val secondId = fixture.coordinator.startRecording()
        assertNotEquals(firstId, secondId)
        assertEquals(2, fixture.captureFactory.captures.size)
        assertTrue(fixture.coordinator.state() is RecordingState.Recording)
    }

    @Test
    fun hardOneHourDeadlineStopsCaptureButStillFinalizesCleanly() {
        val fixture = Fixture(
            config = DictationRecordingConfig(
                progressInactivityTimeoutMillis =
                    DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS,
            ),
        )
        fixture.clock.now = 25L
        val id = fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        assertEquals(
            DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS,
            fixture.scheduler.pendingDelays().max(),
        )

        fixture.clock.now = 25L + DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS - 1L
        fixture.scheduler.runWithDelay(DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS)
        assertEquals(0, capture.stopRequests)
        assertTrue(1L in fixture.scheduler.pendingDelays())

        fixture.clock.now += 1L
        fixture.scheduler.runWithDelay(1L)
        assertEquals(1, capture.stopRequests)
        assertEquals(
            RecordingStopReason.MAXIMUM_DURATION,
            (fixture.coordinator.state() as RecordingState.Stopping).reason,
        )

        capture.completeStop(ByteArray(0))
        fixture.sttProvider.sessions.single().completeFinal("hour complete")
        assertEquals(listOf("hour complete"), fixture.listener.messages.map { it.second })
        assertEquals(id, fixture.listener.messages.single().first)
    }

    @Test
    fun duplicateStopLateAudioAndLateFinalCallbacksAreIdempotent() {
        val fixture = Fixture()
        val id = fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        val stt = fixture.sttProvider.sessions.single()

        fixture.coordinator.stopRecording(id)
        fixture.coordinator.stopRecording(id)
        assertEquals(1, capture.stopRequests)

        capture.emit(ByteArray(fixture.config.chunkBytes))
        capture.completeStop(byteArrayOf(7))
        val submittedAtStop = stt.chunks.size
        capture.emit(ByteArray(fixture.config.chunkBytes))
        capture.completeStop(byteArrayOf(8))
        assertEquals(submittedAtStop, stt.chunks.size)
        assertEquals(1, stt.chunks.count { it.isFinal })
        assertEquals(1, stt.finishCalls)

        stt.completeFinal("only once")
        stt.completeFinal("duplicate")
        assertEquals(listOf("only once"), fixture.listener.messages.map { it.second })
    }

    @Test
    fun appSwitchTtsAndRuntimeEventsDoNotTouchActiveAudio() {
        val fixture = Fixture()
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()

        fixture.coordinator.onEnvironmentEvent(
            VoiceEnvironmentEvent.AppForegroundChanged("com.whatsapp"),
        )
        fixture.coordinator.onEnvironmentEvent(
            VoiceEnvironmentEvent.TextToSpeechActivityChanged(active = true),
        )
        fixture.coordinator.onEnvironmentEvent(
            VoiceEnvironmentEvent.RuntimeActivityChanged(active = true),
        )
        fixture.focus.emit(RecordingAudioFocusChange.LOST_TRANSIENT)

        assertEquals(0, capture.stopRequests)
        assertTrue(fixture.coordinator.state() is RecordingState.Recording)
    }

    @Test
    fun sixtySecondsWithoutTranscriptOrLocalSpeechAbortsImmediately() {
        val fixture = Fixture()
        fixture.clock.now = 100L
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        val stt = fixture.sttProvider.sessions.single()

        fixture.clock.now += DictationRecordingConfig.DEFAULT_PROGRESS_INACTIVITY_TIMEOUT_MILLIS
        fixture.scheduler.runWithDelay(
            DictationRecordingConfig.DEFAULT_PROGRESS_INACTIVITY_TIMEOUT_MILLIS,
        )

        assertEquals(1, capture.stopRequests)
        assertEquals(1, stt.cancelCalls)
        assertEquals(
            RecordingFailure.TRANSCRIPT_PROGRESS_TIMEOUT,
            (fixture.coordinator.state() as RecordingState.Failed).failure,
        )
        assertTrue(fixture.listener.messages.isEmpty())
    }

    @Test
    fun everyTranscriptOrSpeechProgressMovesInactivityDeadlineWithoutShorteningHourLimit() {
        val fixture = Fixture()
        fixture.clock.now = 1_000L
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        val stt = fixture.sttProvider.sessions.single()
        val timeout = DictationRecordingConfig.DEFAULT_PROGRESS_INACTIVITY_TIMEOUT_MILLIS

        fixture.clock.now += 30_000L
        stt.progress(RecordingProgress.TRANSCRIPT_DELTA)
        fixture.clock.now += 30_000L
        fixture.scheduler.runWithDelay(timeout)
        assertEquals(0, capture.stopRequests)
        assertTrue(30_000L in fixture.scheduler.pendingDelays())

        fixture.clock.now += 20_000L
        fixture.speechActivityDetector.speechDetected = true
        capture.emit(ByteArray(fixture.config.chunkBytes))
        fixture.speechActivityDetector.speechDetected = false
        fixture.clock.now += 10_000L
        fixture.scheduler.runWithDelay(30_000L)
        assertEquals(0, capture.stopRequests)
        assertTrue(50_000L in fixture.scheduler.pendingDelays())
        assertTrue(
            DictationRecordingConfig.HARD_MAXIMUM_DURATION_MILLIS in
                fixture.scheduler.pendingDelays(),
        )

        fixture.clock.now += 50_000L
        fixture.scheduler.runWithDelay(50_000L)
        assertEquals(1, capture.stopRequests)
        assertEquals(
            RecordingFailure.TRANSCRIPT_PROGRESS_TIMEOUT,
            (fixture.coordinator.state() as RecordingState.Failed).failure,
        )
    }

    @Test
    fun processStopCancelsCaptureAndTranscriptionWithoutPublishingPartialText() {
        val fixture = Fixture()
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        val stt = fixture.sttProvider.sessions.single()
        capture.emit(ByteArray(fixture.config.chunkBytes))

        fixture.coordinator.onProcessStopping()

        assertEquals(1, capture.stopRequests)
        assertEquals(1, stt.cancelCalls)
        assertEquals(1, fixture.focus.abandonCalls)
        assertTrue(fixture.listener.messages.isEmpty())
        assertEquals(
            RecordingFailure.PROCESS_STOPPED,
            (fixture.coordinator.state() as RecordingState.Failed).failure,
        )
    }

    @Test
    fun processStopClosesMicrophoneBeforePublishingTerminalState() {
        val fixture = Fixture()
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        var closeCallsWhenTerminalPublished = -1
        fixture.listener.beforeStatePublished = { state ->
            if (state is RecordingState.Failed) closeCallsWhenTerminalPublished = capture.closeCalls
        }

        fixture.coordinator.onProcessStopping()

        assertEquals(1, closeCallsWhenTerminalPublished)
        assertEquals(1, capture.closeCalls)
    }

    @Test
    fun processStopDuringCaptureBarrierCancelsWarmConnectionWithoutOpeningMicrophone() {
        lateinit var fixture: Fixture
        fixture = Fixture(captureStartBarrier = DictationCaptureStartBarrier {
            fixture.coordinator.onProcessStopping()
            true
        })

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(RecordingFailure.PROCESS_STOPPED, (fixture.coordinator.state() as RecordingState.Failed).failure)
    }

    @Test
    fun processStopDuringProviderOpenCancelsTheReturnedSessionWithoutCreatingMicrophone() {
        val fixture = Fixture()
        fixture.sttProvider.onOpen = { fixture.coordinator.onProcessStopping() }

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
    }

    @Test
    fun processStopDuringCaptureCreationClosesTheReturnedCaptureWithoutStartingIt() {
        val fixture = Fixture()
        fixture.captureFactory.onCreate = { fixture.coordinator.onProcessStopping() }

        fixture.coordinator.startRecording()

        assertEquals(0, fixture.captureFactory.captures.single().startCalls)
        assertEquals(1, fixture.captureFactory.captures.single().closeCalls)
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
    }

    @Test
    fun teardownAcknowledgementRunsAfterCaptureCloseAndCannotReopenResources() {
        val fixture = Fixture()
        fixture.coordinator.startRecording()
        val capture = fixture.captureFactory.captures.single()
        var acknowledged = 0

        fixture.coordinator.onProcessStopping {
            assertEquals(1, capture.closeCalls)
            acknowledged += 1
        }
        fixture.coordinator.onProcessStopping { acknowledged += 1 }
        fixture.coordinator.startRecording()

        assertEquals(2, acknowledged)
        assertEquals(1, capture.closeCalls)
        assertEquals(1, fixture.captureFactory.captures.size)
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
    }

    @Test
    fun aBrokenDeadlineCancellationDoesNotPreventMicrophoneRelease() {
        val fixture = Fixture()
        fixture.coordinator.startRecording()
        fixture.scheduler.throwOnCancel = true

        fixture.coordinator.onProcessStopping()

        assertEquals(1, fixture.captureFactory.captures.single().closeCalls)
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertEquals(RecordingFailure.PROCESS_STOPPED, (fixture.coordinator.state() as RecordingState.Failed).failure)
    }

    @Test
    fun processStopDuringSynchronousFocusGrantReleasesTheUnrecordedLease() {
        val fixture = Fixture()
        fixture.focus.beforeReturn = { fixture.coordinator.onProcessStopping() }

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.focus.abandonCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertTrue(fixture.sttProvider.sessions.isEmpty())
    }

    @Test
    fun queuedUserStopDuringBarrierRevokesAdmissionWithoutWaitingForTheQueuedStop() {
        lateinit var fixture: Fixture
        fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                val id = (fixture.coordinator.state() as RecordingState.Recording).recordingId
                fixture.coordinator.stopRecording(id)
                fixture.coordinator.stopRecording(id)
                // Both stop events are still queued behind this startup/barrier operation.
                assertTrue(fixture.coordinator.state() is RecordingState.Recording)
                assertTrue(fixture.captureFactory.captures.isEmpty())
                true
            },
        )

        fixture.coordinator.startRecording()

        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertEquals(0, fixture.sttProvider.sessions.single().finishCalls)
        assertEquals(1, fixture.focus.abandonCalls)
        assertEquals(RecordingStopReason.USER, (fixture.coordinator.state() as RecordingState.Completed).reason)
        assertTrue(fixture.listener.messages.isEmpty())
    }

    @Test
    fun queuedProcessStopDuringBarrierClosesWarmConnectionBeforeTeardownAcknowledgement() {
        var acknowledged = false
        lateinit var fixture: Fixture
        fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                fixture.coordinator.onProcessStopping {
                    assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
                    assertTrue(fixture.captureFactory.captures.isEmpty())
                    acknowledged = true
                }
                assertTrue(!acknowledged)
                true
            },
        )

        fixture.coordinator.startRecording()

        assertTrue(acknowledged)
        assertEquals(RecordingFailure.PROCESS_STOPPED, (fixture.coordinator.state() as RecordingState.Failed).failure)
        assertTrue(fixture.listener.messages.isEmpty())
    }

    @Test
    fun queuedStopDuringProviderOpenCancelsReturnedConnectionWithoutEnteringBarrier() {
        var barrierCalls = 0
        val fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                barrierCalls += 1
                true
            },
        )
        fixture.sttProvider.onOpen = {
            val id = (fixture.coordinator.state() as RecordingState.Recording).recordingId
            fixture.coordinator.stopRecording(id)
        }

        fixture.coordinator.startRecording()

        assertEquals(0, barrierCalls)
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertTrue(fixture.coordinator.state() is RecordingState.Completed)
    }

    @Test
    fun queuedStopDuringCaptureCreationClosesUnstartedCaptureAndWarmConnection() {
        val fixture = Fixture(dispatcher = QueuedWhileDispatchingDispatcher())
        fixture.captureFactory.onCreate = {
            val id = (fixture.coordinator.state() as RecordingState.Recording).recordingId
            fixture.coordinator.stopRecording(id)
        }

        fixture.coordinator.startRecording()

        assertEquals(0, fixture.captureFactory.captures.single().startCalls)
        assertEquals(1, fixture.captureFactory.captures.single().closeCalls)
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.coordinator.state() is RecordingState.Completed)
    }

    @Test
    fun permissionRevokedDuringBarrierCancelsWarmConnectionBeforeMicrophoneCreation() {
        var permissionGranted = true
        val fixture = Fixture(
            permissionChecker = RecordAudioPermissionChecker { permissionGranted },
            captureStartBarrier = DictationCaptureStartBarrier {
                permissionGranted = false
                true
            },
        )

        fixture.coordinator.startRecording()

        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertEquals(RecordingFailure.PERMISSION_DENIED, (fixture.coordinator.state() as RecordingState.Failed).failure)
    }

    @Test
    fun queuedPermanentFocusLossDuringBarrierRevokesMicrophoneAdmission() {
        lateinit var fixture: Fixture
        fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                fixture.focus.emit(RecordingAudioFocusChange.LOST_PERMANENTLY)
                true
            },
        )

        fixture.coordinator.startRecording()

        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.sttProvider.sessions.single().cancelCalls)
        assertEquals(
            RecordingStopReason.PERMANENT_AUDIO_FOCUS_LOSS,
            (fixture.coordinator.state() as RecordingState.Completed).reason,
        )
    }

    @Test
    fun queuedAdvisoryEnvironmentAndTransientFocusChangesDoNotDiscardWarmConnection() {
        lateinit var fixture: Fixture
        fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                fixture.coordinator.onEnvironmentEvent(
                    VoiceEnvironmentEvent.AppForegroundChanged("com.example.app"),
                )
                fixture.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.RuntimeActivityChanged(true))
                fixture.coordinator.onEnvironmentEvent(VoiceEnvironmentEvent.TextToSpeechActivityChanged(true))
                fixture.focus.emit(RecordingAudioFocusChange.LOST_TRANSIENT)
                fixture.focus.emit(RecordingAudioFocusChange.LOST_TRANSIENT_CAN_DUCK)
                assertTrue(fixture.captureFactory.captures.isEmpty())
                true
            },
        )

        fixture.coordinator.startRecording()

        assertEquals(1, fixture.captureFactory.captures.single().startCalls)
        assertEquals(0, fixture.sttProvider.sessions.single().cancelCalls)
        assertTrue(fixture.coordinator.state() is RecordingState.Recording)
    }

    @Test
    fun initialPermissionOrFocusDenialDoesNotPrepareNetworkOrEnterCaptureBarrier() {
        var barrierCalls = 0
        val barrier = DictationCaptureStartBarrier {
            barrierCalls += 1
            true
        }
        val permissionDenied = Fixture(
            permissionChecker = RecordAudioPermissionChecker { false },
            captureStartBarrier = barrier,
        )
        val focusDenied = Fixture(captureStartBarrier = barrier)
        focusDenied.focus.result = AudioFocusRequestResult.DENIED

        permissionDenied.coordinator.startRecording()
        focusDenied.coordinator.startRecording()

        assertEquals(0, barrierCalls)
        assertTrue(permissionDenied.sttProvider.sessions.isEmpty())
        assertTrue(focusDenied.sttProvider.sessions.isEmpty())
        assertTrue(permissionDenied.captureFactory.captures.isEmpty())
        assertTrue(focusDenied.captureFactory.captures.isEmpty())
    }

    @Test
    fun synchronousProviderFailureNeverEntersBarrierOrCreatesMicrophone() {
        var barrierCalls = 0
        val fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                barrierCalls += 1
                true
            },
        )
        fixture.sttProvider.failOnOpen = true

        fixture.coordinator.startRecording()

        assertEquals(0, barrierCalls)
        assertTrue(fixture.sttProvider.sessions.isEmpty())
        assertTrue(fixture.captureFactory.captures.isEmpty())
        assertEquals(1, fixture.focus.abandonCalls)
        assertEquals(RecordingFailure.TRANSCRIPTION_FAILED, (fixture.coordinator.state() as RecordingState.Failed).failure)
    }

    @Test
    fun cancelledWarmupDoesNotKeepAnIdleConnectionOrBlockNextDictation() {
        var stopFirstStartup = true
        lateinit var fixture: Fixture
        fixture = Fixture(
            dispatcher = QueuedWhileDispatchingDispatcher(),
            captureStartBarrier = DictationCaptureStartBarrier {
                if (stopFirstStartup) {
                    stopFirstStartup = false
                    val id = (fixture.coordinator.state() as RecordingState.Recording).recordingId
                    fixture.coordinator.stopRecording(id)
                }
                true
            },
        )

        val firstId = fixture.coordinator.startRecording()
        val secondId = fixture.coordinator.startRecording()

        assertNotEquals(firstId, secondId)
        assertEquals(2, fixture.sttProvider.sessions.size)
        assertEquals(1, fixture.sttProvider.sessions.first().cancelCalls)
        assertEquals(0, fixture.sttProvider.sessions.last().cancelCalls)
        assertEquals(1, fixture.captureFactory.captures.single().startCalls)
        assertEquals(secondId, (fixture.coordinator.state() as RecordingState.Recording).recordingId)
    }

    private class Fixture(
        val config: DictationRecordingConfig = DictationRecordingConfig(),
        captureStartBarrier: DictationCaptureStartBarrier =
            DictationCaptureStartBarrier { true },
        permissionChecker: RecordAudioPermissionChecker = RecordAudioPermissionChecker { true },
        dispatcher: RecordingTaskDispatcher = RecordingTaskDispatcher { it() },
    ) {
        val clock = FakeClock()
        val scheduler = FakeDeadlineScheduler()
        val focus = FakeAudioFocus()
        val captureFactory = FakeCaptureFactory()
        val sttProvider = FakeSttProvider()
        val listener = FakeListener()
        val speechActivityDetector = FakeSpeechActivityDetector()
        val coordinator = DictationRecordingCoordinator(
            config = config,
            clock = clock,
            permissionChecker = permissionChecker,
            captureStartBarrier = captureStartBarrier,
            audioFocus = focus,
            captureFactory = captureFactory,
            sttProvider = sttProvider,
            deadlineScheduler = scheduler,
            dispatcher = dispatcher,
            listener = listener,
            speechActivityDetector = speechActivityDetector,
        )
    }

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }

    private class FakeDeadlineScheduler : RecordingDeadlineScheduler {
        private val entries = ArrayDeque<Entry>()
        var throwOnCancel = false

        override fun schedule(
            delayMillis: Long,
            task: () -> Unit,
        ): ScheduledRecordingDeadline {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledRecordingDeadline {
                if (throwOnCancel) error("deadline already disposed")
                entry.cancelled = true
            }
        }

        fun runWithDelay(delayMillis: Long) {
            while (entries.isNotEmpty()) {
                val index = entries.indexOfFirst { !it.cancelled && it.delayMillis == delayMillis }
                if (index < 0) break
                val entry = entries.removeAt(index)
                entry.task()
                return
            }
            error("no scheduled deadline with delay $delayMillis; pending=${pendingDelays()}")
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.cancelled }.map { it.delayMillis }

        private data class Entry(
            val delayMillis: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class FakeAudioFocus : RecordingAudioFocusCoordinator {
        private var listener: ((RecordingAudioFocusChange) -> Unit)? = null
        var abandonCalls = 0
        var beforeReturn: () -> Unit = {}
        var result: AudioFocusRequestResult = AudioFocusRequestResult.GRANTED

        override fun request(
            onChange: (RecordingAudioFocusChange) -> Unit,
        ): AudioFocusRequestResult {
            listener = onChange
            beforeReturn()
            return result
        }

        override fun abandon() {
            abandonCalls += 1
        }

        fun emit(change: RecordingAudioFocusChange) {
            listener?.invoke(change)
        }
    }

    /** Reentrant events are queued exactly as on the production single-thread worker. */
    private class QueuedWhileDispatchingDispatcher : RecordingTaskDispatcher {
        private val pending = ArrayDeque<() -> Unit>()
        private var dispatching = false

        override fun dispatch(task: () -> Unit) {
            pending.addLast(task)
            if (dispatching) return
            dispatching = true
            try {
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            } finally {
                dispatching = false
            }
        }
    }

    private class FakeCaptureFactory : PcmAudioCaptureFactory {
        val captures = mutableListOf<FakeCapture>()
        var onCreate: () -> Unit = {}

        override fun create(
            recordingId: RecordingId,
            format: PcmAudioFormat,
            fixedChunkBytes: Int,
        ): PcmAudioCapture = FakeCapture().also {
            captures.add(it)
            onCreate()
        }
    }

    private class FakeCapture : PcmAudioCapture {
        private lateinit var listener: PcmAudioCapture.Listener
        var stopRequests = 0
        var closeCalls = 0
        var startCalls = 0

        override fun start(listener: PcmAudioCapture.Listener) {
            startCalls += 1
            this.listener = listener
        }

        override fun requestStop() {
            stopRequests += 1
        }

        override fun close() {
            closeCalls += 1
        }

        fun emit(bytes: ByteArray) {
            listener.onAudioChunk(bytes, 1L)
        }

        fun completeStop(finalBytes: ByteArray) {
            listener.onCaptureStopped(finalBytes, 2L)
        }
    }

    private class FakeSttProvider : IncrementalSttProvider {
        val sessions = mutableListOf<FakeSttSession>()
        var onOpen: () -> Unit = {}
        var failOnOpen = false

        override fun openSession(
            recordingId: RecordingId,
            format: PcmAudioFormat,
            progressListener: RecordingProgressListener,
        ): IncrementalSttSession {
            if (failOnOpen) error("transcription unavailable")
            return FakeSttSession(progressListener).also {
                sessions.add(it)
                onOpen()
            }
        }
    }

    private class FakeSttSession(
        private val progressListener: RecordingProgressListener,
    ) : IncrementalSttSession {
        val chunks = mutableListOf<PcmAudioChunk>()
        private var finalCallback: ((Result<String>) -> Unit)? = null
        var finishCalls = 0
        var cancelCalls = 0

        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) {
            chunks += chunk
            callback(Result.success(Unit))
        }

        override fun finish(callback: (Result<String>) -> Unit) {
            finishCalls += 1
            finalCallback = callback
        }

        override fun cancel() {
            cancelCalls += 1
        }

        fun completeFinal(text: String) {
            finalCallback?.invoke(Result.success(text))
        }

        fun progress(progress: RecordingProgress) {
            progressListener.onProgress(progress)
        }
    }

    private class FakeSpeechActivityDetector : PcmSpeechActivityDetector {
        var speechDetected = false

        override fun containsSpeechLikeActivity(
            pcmBytes: ByteArray,
            format: PcmAudioFormat,
        ): Boolean = speechDetected
    }

    private class FakeListener : DictationRecordingListener {
        val states = mutableListOf<RecordingState>()
        val messages = mutableListOf<Pair<RecordingId, String>>()
        val rejected = mutableListOf<RecordingId>()
        var beforeStatePublished: (RecordingState) -> Unit = {}

        override fun onRecordingStateChanged(state: RecordingState) {
            beforeStatePublished(state)
            states += state
        }

        override fun onUserMessageReady(recordingId: RecordingId, transcript: String) {
            messages += recordingId to transcript
        }

        override fun onStartRejected(activeRecordingId: RecordingId) {
            rejected += activeRecordingId
        }
    }
}
