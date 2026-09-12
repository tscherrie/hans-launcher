package ai.hans.standard.voice

import ai.hans.standard.voice.android.DictationServiceOwnerGate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No network, real microphone, or audio output; run on every supported Android API. */
@RunWith(AndroidJUnit4::class)
class DictationTeardownContractTest {
    @Test
    fun delayedOldServiceCallbacksCannotReleaseTheReplacementRecording() {
        val owners = DictationServiceOwnerGate()
        val oldService = Any()
        val newService = Any()
        var active = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(owners.acquire(oldService))
            owners.runIfOwner(oldService) { active = true }
            assertFalse(owners.acquire(newService))
            owners.release(oldService) { active = false }
            assertTrue(owners.acquire(newService))
            owners.runIfOwner(newService) { active = true }
        }
        assertFalse(owners.runIfOwner(oldService) { active = false })
        assertFalse(owners.release(oldService) { active = false })
        assertTrue(active)
        owners.release(newService) { active = false }
    }

    @Test
    fun stopFromAndroidMainThreadRevokesBlockedStartupAndRetainsQueuedCleanup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val dispatcher = ExecutorRecordingTaskDispatcher()
        val fixture = Fixture(dispatcher, DictationCaptureStartBarrier {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        })
        try {
            fixture.core.startRecording()
            assertTrue(entered.await(3, TimeUnit.SECONDS))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.core.onProcessStopping { stopped.countDown() }
                // Closing the executor must drain the already accepted cleanup.
                dispatcher.close()
                assertEquals(1L, stopped.count)
            }
            release.countDown()

            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(1, fixture.providerOpens.get())
            assertEquals(1, fixture.transcriptionCancels.get())
            assertEquals(0, fixture.captureStarts.get())
            assertEquals(RecordingFailure.PROCESS_STOPPED, (fixture.core.state() as RecordingState.Failed).failure)
            val lateCallbackRan = AtomicBoolean(false)
            dispatcher.dispatch { lateCallbackRan.set(true) }
            assertFalse(lateCallbackRan.get())
        } finally {
            release.countDown()
            fixture.core.onProcessStopping()
            dispatcher.close()
        }
    }

    @Test
    fun userStopFromAndroidMainThreadCancelsWarmConnectionWithoutOpeningMicrophone() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val dispatcher = ExecutorRecordingTaskDispatcher()
        val fixture = Fixture(
            dispatcher,
            DictationCaptureStartBarrier {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            },
            completed = completed,
        )
        try {
            val id = fixture.core.startRecording()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(1, fixture.providerOpens.get())
            assertEquals(0, fixture.captureStarts.get())

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.core.stopRecording(id)
                assertEquals(1L, completed.count)
            }
            release.countDown()

            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(0, fixture.captureStarts.get())
            assertEquals(1, fixture.transcriptionCancels.get())
            assertEquals(RecordingState.Completed(id, RecordingStopReason.USER), fixture.core.state())
        } finally {
            release.countDown()
            fixture.core.onProcessStopping()
            dispatcher.close()
        }
    }

    @Test
    fun physicalCaptureIsClosedBeforeForegroundAndActivityAreReleased() {
        val dispatcher = ExecutorRecordingTaskDispatcher()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val fixture = Fixture(dispatcher, DictationCaptureStartBarrier { true }, started)
        try {
            fixture.core.startRecording()
            assertTrue(started.await(3, TimeUnit.SECONDS))

            fixture.core.onProcessStopping { stopped.countDown() }

            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(1, fixture.captureCloses.get())
            assertEquals(1, fixture.closeCountAtForegroundRelease.get())
            assertEquals(1, fixture.closeCountAtTerminalPublication.get())
            assertEquals(1, fixture.transcriptionCancels.get())
        } finally {
            fixture.core.onProcessStopping()
            dispatcher.close()
        }
    }

    private class Fixture(
        dispatcher: RecordingTaskDispatcher,
        barrier: DictationCaptureStartBarrier,
        started: CountDownLatch = CountDownLatch(1),
        completed: CountDownLatch = CountDownLatch(1),
    ) {
        val providerOpens = AtomicInteger()
        val captureStarts = AtomicInteger()
        val captureCloses = AtomicInteger()
        val transcriptionCancels = AtomicInteger()
        val closeCountAtForegroundRelease = AtomicInteger(-1)
        val closeCountAtTerminalPublication = AtomicInteger(-1)
        val core = DictationForegroundServiceCore(
            config = DictationRecordingConfig(),
            clock = MonotonicClock { 1L },
            permissionChecker = RecordAudioPermissionChecker { true },
            captureStartBarrier = barrier,
            audioFocus = object : RecordingAudioFocusCoordinator {
                override fun request(onChange: (RecordingAudioFocusChange) -> Unit) = AudioFocusRequestResult.GRANTED
                override fun abandon() = Unit
            },
            captureFactory = PcmAudioCaptureFactory { _, _, _ ->
                object : PcmAudioCapture {
                    override fun start(listener: PcmAudioCapture.Listener) {
                        captureStarts.incrementAndGet()
                        started.countDown()
                    }
                    override fun requestStop() = Unit
                    override fun close() { captureCloses.incrementAndGet() }
                }
            },
            sttProvider = object : IncrementalSttProvider {
                override fun openSession(
                    recordingId: RecordingId,
                    format: PcmAudioFormat,
                    progressListener: RecordingProgressListener,
                ): IncrementalSttSession {
                    providerOpens.incrementAndGet()
                    return object : IncrementalSttSession {
                        override fun submitChunk(chunk: PcmAudioChunk, callback: (Result<Unit>) -> Unit) = Unit
                        override fun finish(callback: (Result<String>) -> Unit) = Unit
                        override fun cancel() { transcriptionCancels.incrementAndGet() }
                    }
                }
            },
            deadlineScheduler = RecordingDeadlineScheduler { _, _ -> ScheduledRecordingDeadline {} },
            dispatcher = dispatcher,
            foregroundHost = object : RecordingForegroundHost {
                override fun showRecordingForeground(state: RecordingState) = Unit
                override fun leaveRecordingForeground() {
                    closeCountAtForegroundRelease.set(captureCloses.get())
                }
            },
            downstreamListener = object : DictationRecordingListener {
                override fun onRecordingStateChanged(state: RecordingState) {
                    if (state is RecordingState.Failed) closeCountAtTerminalPublication.set(captureCloses.get())
                    if (state is RecordingState.Completed) completed.countDown()
                }
                override fun onUserMessageReady(recordingId: RecordingId, transcript: String) = Unit
                override fun onStartRejected(activeRecordingId: RecordingId) = Unit
            },
        )
    }
}
