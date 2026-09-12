package ai.hans.standard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    private val config = DictationRecordingConfig(maximumDurationMillis = 60_000L)

    @Test
    fun finalTextIsReleasedOnceAndOnlyAfterFinalChunkWasAccepted() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(1)
        machine.start(id, nowMillis = 100L, permissionGranted = true)
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, nowMillis = 110L)
        val regular = machine.onAudioChunk(id, ByteArray(config.chunkBytes), 1_000L)
            .filterIsInstance<RecordingEffect.SubmitAudioChunk>()
            .single()
            .chunk
        machine.requestStop(id)
        val final = machine.onCaptureStopped(id, byteArrayOf(1, 2), 1_100L)
            .filterIsInstance<RecordingEffect.SubmitAudioChunk>()
            .single()
            .chunk

        assertTrue(machine.onFinalTranscript(id, "too early").isEmpty())
        assertTrue(machine.onChunkAccepted(id, final.index).none {
            it is RecordingEffect.FinishTranscription
        })
        val regularAccepted = machine.onChunkAccepted(id, regular.index)
        assertEquals(1, regularAccepted.count { it is RecordingEffect.FinishTranscription })
        val completed = machine.onFinalTranscript(id, "  complete dictation  ")

        assertEquals(1, completed.count { it is RecordingEffect.UserMessageReady })
        assertEquals(
            "complete dictation",
            completed.filterIsInstance<RecordingEffect.UserMessageReady>().single().transcript,
        )
        assertTrue(machine.onFinalTranscript(id, "late duplicate").isEmpty())
    }

    @Test
    fun transientFocusAndEnvironmentEventsNeverStopCapture() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(2)
        machine.start(id, 0L, permissionGranted = true)
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)

        assertTrue(
            machine.onAudioFocusChanged(id, RecordingAudioFocusChange.LOST_TRANSIENT).isEmpty(),
        )
        assertTrue(
            machine.onAudioFocusChanged(
                id,
                RecordingAudioFocusChange.LOST_TRANSIENT_CAN_DUCK,
            ).isEmpty(),
        )
        assertTrue(
            machine.onEnvironmentEvent(
                VoiceEnvironmentEvent.AppForegroundChanged("com.example.app"),
            ).isEmpty(),
        )
        assertTrue(
            machine.onEnvironmentEvent(
                VoiceEnvironmentEvent.RuntimeActivityChanged(active = true),
            ).isEmpty(),
        )
        assertTrue(
            machine.onEnvironmentEvent(
                VoiceEnvironmentEvent.TextToSpeechActivityChanged(active = true),
            ).isEmpty(),
        )
        assertTrue(machine.state() is RecordingState.Recording)

        val permanent = machine.onAudioFocusChanged(
            id,
            RecordingAudioFocusChange.LOST_PERMANENTLY,
        )
        assertEquals(1, permanent.count { it is RecordingEffect.StopCapture })
        assertEquals(
            RecordingStopReason.PERMANENT_AUDIO_FOCUS_LOSS,
            (machine.state() as RecordingState.Stopping).reason,
        )
    }

    @Test
    fun permissionAndProcessStopHaveExplicitFailureStates() {
        val permissionMachine = RecordingStateMachine(config)
        permissionMachine.start(RecordingId(3), 0L, permissionGranted = false)
        assertEquals(
            RecordingFailure.PERMISSION_DENIED,
            (permissionMachine.state() as RecordingState.Failed).failure,
        )

        val processMachine = RecordingStateMachine(config)
        val id = RecordingId(4)
        processMachine.start(id, 0L, permissionGranted = true)
        processMachine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)
        val effects = processMachine.onProcessStopping()
        assertEquals(1, effects.count { it is RecordingEffect.StopCapture })
        assertEquals(1, effects.count { it is RecordingEffect.CancelTranscription })
        assertEquals(1, effects.count { it is RecordingEffect.ReleaseAudioFocus })
        assertEquals(
            RecordingFailure.PROCESS_STOPPED,
            (processMachine.state() as RecordingState.Failed).failure,
        )
    }

    @Test
    fun transcriptProgressTimeoutOnlyAbortsActiveCapture() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(5)
        machine.start(id, 0L, permissionGranted = true)

        assertTrue(machine.onTranscriptProgressTimeout(id).isEmpty())
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)
        val effects = machine.onTranscriptProgressTimeout(id)

        assertEquals(1, effects.count { it is RecordingEffect.StopCapture })
        assertEquals(1, effects.count { it is RecordingEffect.CancelTranscription })
        assertEquals(1, effects.count { it is RecordingEffect.ReleaseAudioFocus })
        assertEquals(
            RecordingFailure.TRANSCRIPT_PROGRESS_TIMEOUT,
            (machine.state() as RecordingState.Failed).failure,
        )
        assertTrue(machine.onTranscriptProgressTimeout(id).isEmpty())
    }

    @Test
    fun cancellationBeforePhysicalCaptureCompletesWithoutAudioOrTranscriptFinalization() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(6)
        machine.start(id, 0L, permissionGranted = true)
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)

        val effects = machine.cancelBeforeCapture(id, RecordingStopReason.USER)

        assertEquals(1, effects.count { it is RecordingEffect.CancelTranscription })
        assertEquals(1, effects.count { it is RecordingEffect.ReleaseAudioFocus })
        assertEquals(0, effects.count { it is RecordingEffect.StopCapture })
        assertEquals(0, effects.count { it is RecordingEffect.SubmitAudioChunk })
        assertEquals(0, effects.count { it is RecordingEffect.FinishTranscription })
        assertEquals(0, effects.count { it is RecordingEffect.UserMessageReady })
        assertEquals(RecordingState.Completed(id, RecordingStopReason.USER), machine.state())
        assertTrue(machine.cancelBeforeCapture(id, RecordingStopReason.USER).isEmpty())
        assertTrue(machine.requestStop(id).isEmpty())
    }

    @Test
    fun cancellationBeforePhysicalCaptureAlsoResolvesAnAlreadyQueuedStop() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(7)
        machine.start(id, 0L, permissionGranted = true)
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)
        machine.requestStop(id)

        machine.cancelBeforeCapture(id, RecordingStopReason.USER)

        assertEquals(RecordingState.Completed(id, RecordingStopReason.USER), machine.state())
    }

    @Test
    fun startupCancellationDoesNotDiscardPreviouslyAcceptedAudioOrAnotherRecording() {
        val machine = RecordingStateMachine(config)
        val id = RecordingId(8)
        machine.start(id, 0L, permissionGranted = true)
        machine.onAudioFocusRequestResult(id, AudioFocusRequestResult.GRANTED, 1L)
        assertTrue(machine.cancelBeforeCapture(RecordingId(9), RecordingStopReason.USER).isEmpty())
        machine.onAudioChunk(id, byteArrayOf(1, 2), 2L)

        assertTrue(machine.cancelBeforeCapture(id, RecordingStopReason.USER).isEmpty())
        assertTrue(machine.state() is RecordingState.Recording)
    }
}
