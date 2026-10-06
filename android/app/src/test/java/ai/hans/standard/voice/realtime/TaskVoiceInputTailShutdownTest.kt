package ai.hans.standard.voice.realtime

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class TaskVoiceInputTailShutdownTest {
    @Test fun nativeStopTimeoutKeepsImmediateInputCutoffAndStillExecutesQueuedCleanup() {
        val control = LiveVoiceTransportControl()
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val nativeStopped = AtomicBoolean(false)
        val input = StartupPcmBuffer()
        render(input, 7)
        try {
            assertTrue(control.execute { workerBlocked.countDown(); check(releaseWorker.await(2, TimeUnit.SECONDS)) })
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            input.close() // The cutoff occurs before the bounded native acknowledgement.
            assertFalse(control.callBounded(25L) { nativeStopped.set(true); true })
            assertFalse(nativeStopped.get())
            assertFalse(input.setCaptureEnabled(true))
            assertTrue(render(input, 88).all { it == 0.toByte() })
            releaseWorker.countDown()
            assertTrue(control.call { nativeStopped.get() })
            assertFalse(input.setCaptureEnabled(true))
        } finally { releaseWorker.countDown(); input.close(); control.close() }
    }

    @Test fun boundedNativeReceiptFromItsOwnWorkerNeverWaitsForItselfOrCancelsStop() {
        val control = LiveVoiceTransportControl()
        val stopped = AtomicBoolean(false)
        try {
            assertFalse(control.call { control.callBounded(500L) { stopped.set(true); true } })
            assertTrue(control.call { stopped.get() })
        } finally { control.close() }
    }

    @Test fun unsupportedTransportNeverClaimsItsMicrophoneWasPermanentlyClosed() {
        val transport = object : LiveVoiceTransport {
            override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) = Unit
            override fun sendUtf8(event: String) = false
            override fun setInputAudioEnabled(enabled: Boolean) = true
            override fun clearOutputAudio() = false
            override fun close() = Unit
        }
        assertFalse(transport.finishInputForOutputTail())
    }

    @Test fun outputTailStopsTrackAndBothRecorderOwnersButKeepsPlayout() {
        val events = mutableListOf<String>()
        assertTrue(LiveVoiceOutputTailInputShutdown.apply(
            disableLocalTrack = { events += "track:false"; true },
            disableNativeRecording = { events += "native:false" },
            disablePhysicalRecording = { events += "physical:false" },
            stopExplicitRecorder = { events += "explicit:stop" },
            preservePlayout = { events += "playout:preserve" },
        ))
        assertEquals(listOf("track:false", "native:false", "physical:false", "explicit:stop", "playout:preserve"), events)
    }

    @Test fun failedTrackOrRecorderStopCannotSkipOtherPrivacyStopsOrReportSuccess() {
        for (failure in 0..4) {
            val attempted = mutableListOf<Int>()
            fun step(index: Int) { attempted += index; if (failure == index) throw IllegalStateException("synthetic") }
            assertFalse(LiveVoiceOutputTailInputShutdown.apply(
                disableLocalTrack = { step(0); true },
                disableNativeRecording = { step(1) },
                disablePhysicalRecording = { step(2) },
                stopExplicitRecorder = { step(3) },
                preservePlayout = { step(4) },
            ))
            assertEquals(listOf(0, 1, 2, 3, 4), attempted)
        }
        var stopped = false
        assertFalse(LiveVoiceOutputTailInputShutdown.apply({ false }, {}, {}, { stopped = true }, {}))
        assertTrue(stopped)
    }

    @Test fun finishedInputDiscardsSavedSpeechAndZeroesEveryLateFramePermanently() {
        val input = StartupPcmBuffer()
        repeat(5) { render(input, it + 1) }
        assertTrue(input.queuedMillis > 0)
        input.close() // Same synchronous irreversible FIFO cutoff used by the transport.
        assertFalse(input.isOpen)
        assertFalse(input.hasPendingDrain)
        assertEquals(0L, input.queuedMillis)
        assertFalse(input.setCaptureEnabled(true))
        assertFalse(input.setTransmissionEnabled(true))
        repeat(5) { assertTrue(render(input, 99).all { it == 0.toByte() }) }
        assertEquals(5L to 0L, input.counts())
    }

    @Test fun ordinaryMuteStillKeepsItsPrefixAndCanResumeUnlikeTaskCompletion() {
        val input = StartupPcmBuffer()
        render(input, 7)
        assertTrue(input.setCaptureEnabled(false))
        assertEquals(10L, input.queuedMillis)
        assertTrue(input.isOpen)
        assertTrue(input.setCaptureEnabled(true))
        assertTrue(input.setTransmissionEnabled(true))
        repeat(StartupPcmBuffer.ACTIVATION_FRAMES) { render(input, 8) }
        assertTrue(render(input, 9).all { it == 7.toByte() })
        input.close()
    }

    @Test fun transportWiresPermanentCutoffBeforeNativeWaitAndRechecksAllAdmissionPaths() {
        val source = sequenceOf(File("src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
            File("android/app/src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"))
            .first(File::isFile).readText()
        val finish = source.substringAfter("override fun finishInputForOutputTail(): Boolean {")
            .substringBefore("override fun confirmSessionStarted()")
        assertTrue(finish.indexOf("inputFinishedForOutputTail.set(true)") < finish.indexOf("startupPcm?.close()"))
        assertTrue(finish.indexOf("startupPcm?.close()") < finish.indexOf("return acknowledgeFinishedInputForOutputTail()"))
        assertTrue(finish.contains("control.callBounded(500L)"))
        assertTrue(finish.contains("mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX"))
        val state = source.substringAfter("private fun applyEarlyCaptureState(): Boolean {")
            .substringBefore("private fun applyFinishedInputForOutputTail()")
        assertTrue(state.indexOf("inputFinishedForOutputTail.get()") < state.indexOf("if (!input.isOpen)"))
        assertTrue(state.contains("return applyFinishedInputForOutputTail()"))
        val mute = source.substringAfter("override fun setUserInputMuted(muted: Boolean): Boolean {")
            .substringBefore("override fun finishInputForOutputTail()")
        assertTrue(mute.contains("return muted && acknowledgeFinishedInputForOutputTail()"))
        assertTrue(mute.contains("return@controlResult muted && applyFinishedInputForOutputTail()"))
        val context = source.substringAfter("override fun setInputAudioEnabled(enabled: Boolean)")
            .substringBefore("override fun setUserInputMuted")
        assertTrue(context.contains("if (enabled) false else applyFinishedInputForOutputTail()"))
        val confirm = source.substringAfter("override fun confirmSessionStarted()")
            .substringBefore("override fun clearOutputAudio()")
        assertTrue(confirm.contains("applyEarlyCaptureState()"))
        val native = source.substringAfter("private fun applyFinishedInputForOutputTail(): Boolean {")
            .substringBefore("private fun applyReceiveOnlyState()")
        listOf("peer?.setAudioRecording(false)", "module?.setAudioRecordEnabled(false)",
            "module?.requestStopRecording()", "LiveVoiceLocalTrackState.apply(false",
            "peer?.setAudioPlayout(mediaMode.permitsOutput").forEach { assertTrue(it, native.contains(it)) }
        assertFalse(native.contains("peer?.setAudioPlayout(false)"))
        assertFalse(source.contains("inputFinishedForOutputTail.set(false)"))
    }

    private fun render(input: StartupPcmBuffer, value: Int): ByteArray {
        val frame = ByteArray(StartupPcmBuffer.FRAME_BYTES) { value.toByte() }
        input.render(ByteBuffer.wrap(frame), StartupPcmBuffer.PCM_16_BIT, 1,
            StartupPcmBuffer.SAMPLE_RATE_HZ, frame.size, value.toLong())
        return frame
    }
}
