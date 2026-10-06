package ai.hans.standard.voice.realtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceMediaModeTest {
    @Test fun bufferedDictationNeverOwnsOrEnablesDeviceAudioForAnyMuteOrLifecycleState() {
        listOf(LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT,
            LiveVoiceMediaMode.BUFFERED_DICTATION_PRIMARY_SILENT).forEach { mode ->
            assertFalse(mode.physicalCapture)
            assertFalse(mode.takesAudioFocus)
            assertFalse(mode.playsOutput)
            assertFalse(mode.playsConnectionTones)
            listOf(false, true).forEach { started ->
                listOf(false, true).forEach { muted ->
                    listOf(false, true).forEach { ended ->
                        assertFalse(mode.permitsCapture(started, muted, ended))
                    }
                }
            }
        }
    }

    @Test fun primaryBufferedModeUsesPublicExternalPcmInputAndCannotOwnPhysicalMicrophone() {
        val source = source()
        val primary = source.substringAfter("else if (mediaMode.usesBufferedPrimaryAudio)")
            .substringBefore("else JavaAudioDeviceModule.builder")
        assertTrue(primary.contains("setAudioBufferCallback"))
        assertTrue(primary.contains("setAudioRecordEnabled(false)"))
        assertTrue(primary.contains("setInputSampleRate(PacedPcmInput.SAMPLE_RATE_HZ)"))
        assertTrue(primary.contains("setAudioFormat(PacedPcmInput.PCM_16_BIT)"))
        assertTrue(primary.contains("setUseStereoInput(false)"))
        assertTrue(primary.contains("setSpeakerMute(true)"))
        assertFalse(primary.contains("setAudioRecordEnabled(true)"))
        assertTrue(LiveVoiceMediaMode.BUFFERED_DICTATION_PRIMARY_SILENT.usesBufferedPrimaryAudio)
        assertFalse(LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT.usesBufferedPrimaryAudio)
        assertFalse(LiveVoiceMediaMode.DUPLEX.usesBufferedPrimaryAudio)
    }

    @Test fun ordinaryLiveKeepsItsExistingStartupMuteAndTerminalCaptureGates() {
        val mode = LiveVoiceMediaMode.DUPLEX
        assertTrue(mode.physicalCapture)
        assertTrue(mode.takesAudioFocus)
        assertTrue(mode.playsOutput)
        assertTrue(mode.playsConnectionTones)
        assertTrue(mode.permitsCapture(started = true, muted = false, ended = false))
        assertFalse(mode.permitsCapture(started = false, muted = false, ended = false))
        assertFalse(mode.permitsCapture(started = true, muted = true, ended = false))
        assertFalse(mode.permitsCapture(started = true, muted = false, ended = true))
    }

    @Test fun earlyCaptureDuplexOwnsOneCommunicationEndpointButNoConnectionTone() {
        val mode = LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX
        assertTrue(mode.physicalCapture)
        assertTrue(mode.takesAudioFocus)
        assertTrue(mode.playsOutput)
        assertFalse(mode.playsConnectionTones)
        assertFalse(mode.receivesOnly)
        assertFalse(mode.usesBufferedPrimaryAudio)
        assertTrue(mode.permitsCapture(started = false, muted = false, ended = false))
        assertFalse(mode.permitsCapture(started = false, muted = true, ended = false))
        assertFalse(mode.permitsCapture(started = true, muted = false, ended = true))
        assertFalse(mode.permitsOutput(started = false, ended = false))
        assertTrue(mode.permitsOutput(started = true, ended = false))
        assertFalse(mode.permitsOutput(started = true, ended = true))
    }

    @Test fun earlyCaptureUsesTheSameCommunicationAdmAndBothOwnershipsAreReleased() {
        val source = source()
        val captureAdm = source.substringAfter("val audioModule = if (mediaMode.physicalCapture)")
            .substringBefore("else if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY)")
        assertTrue(captureAdm.contains("MediaRecorder.AudioSource.VOICE_COMMUNICATION"))
        assertTrue(captureAdm.contains("setUseHardwareAcousticEchoCanceler"))
        assertTrue(captureAdm.contains("setUseHardwareNoiseSuppressor"))
        assertTrue(captureAdm.contains("setAudioRecordStateCallback"))
        assertTrue(captureAdm.contains("setAudioRecordErrorCallback"))
        assertTrue(captureAdm.contains("setInputSampleRate(StartupPcmBuffer.SAMPLE_RATE_HZ)"))
        assertFalse(captureAdm.contains("setAudioRecordEnabled(false)"))
        val earlyStart = source.substringAfter("initializationStage = \"early_microphone\"")
            .substringBefore("initializationStage = \"peer_factory\"")
        assertTrue(earlyStart.contains("requestStartRecording(AudioProcessingOptions.communication())"))
        assertFalse(earlyStart.contains("AudioRecord.Builder"))
        val cleanup = source.substringAfter("private fun disposeResources()")
            .substringBefore("private inline fun ignoreRuntimeFailure")
        assertTrue(cleanup.indexOf("peerConnection?.setAudioRecording(false)") <
            cleanup.indexOf("earlyCaptureModule?.requestStopRecording()"))
        assertTrue(cleanup.indexOf("earlyCaptureModule?.requestStopRecording()") <
            cleanup.indexOf("peerFactory?.dispose()"))
        assertTrue(cleanup.contains("earlyRecorderStopped.get()"))
        assertTrue(cleanup.contains("earlyCaptureModule?.release()"))
    }

    @Test fun earlyCaptureActivityIsRawAndFirstFrameNotificationIsNotOptimistic() {
        val source = source()
        val input = source.substringAfter("setAudioBufferCallback { buffer, format, channels, rate, bytesRead, timestamp ->")
            .substringBefore(".setSamplesReadyCallback")
        assertTrue(input.indexOf("observeEarlyPhysicalInput(") < input.indexOf("input.render("))
        assertTrue(input.contains("!inputFinishedForOutputTail.get() && input.hasCapturedFrame &&"))
        assertTrue(input.contains("earlyCaptureNotified.compareAndSet(false, true)"))
        assertTrue(input.contains("dispatchControl { listener?.onInputCaptureStarted() }"))
        val samples = source.substringAfter(".setSamplesReadyCallback { samples ->")
            .substringBefore(".setPlaybackSamplesReadyCallback")
        assertTrue(samples.contains("mediaMode != LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX"))
        val confirm = source.substringAfter("override fun confirmSessionStarted()")
            .substringBefore("// Live handles")
        assertTrue(confirm.contains("val input = startupPcm ?: return@controlResult false"))
        assertTrue(confirm.contains("input.acceptsPhysicalFrames &&"))
        assertTrue(confirm.contains("!input.hasCapturedFrame"))
        assertTrue(confirm.contains("!earlyRecorderStarted.get()"))
        assertTrue(confirm.contains("dataChannel?.state() != DataChannel.State.OPEN"))
        val sending = source.substringAfter("private fun applyEarlyCaptureState()")
            .substringBefore("private fun applyReceiveOnlyState()")
        assertTrue(sending.contains("EarlyCapturePipelinePolicy.resolve("))
        assertTrue(sending.contains("muted = !input.acceptsPhysicalFrames, pendingPrefix = input.hasPendingDrain"))
        assertTrue(sending.contains("ready = startupInputEnabled.get(), contextEnabled = contextInputEnabled.get(), ended = ended"))
        assertTrue(sending.contains("val send = pipeline.sendNativeInput"))
        assertTrue(sending.contains("peer.setAudioPlayout(pipeline.playOutput)"))
        assertTrue(sending.indexOf("peer.setAudioRecording(send)") <
            sending.indexOf("input.setTransmissionEnabled(send)"))
    }

    @Test fun nativeSilentWiringCreatesReceiveOnlyMediaWithoutCaptureToneOrRoutingOwnership() {
        val source = source()
        assertTrue(source.contains("mediaMode: LiveVoiceMediaMode = LiveVoiceMediaMode.DUPLEX"))
        assertTrue(source.contains("private val connectionAudio = if (mediaMode.playsConnectionTones)"))
        val connection = source.substringAfter("this.listener = listener")
            .substringBefore("ensureWebRtcInitialized")
        assertTrue(connection.contains("if (mediaMode.takesAudioFocus)"))
        assertTrue(connection.contains("connectionAudio?.connecting()"))
        val receiveOnly = source.substringAfter("initializationStage = \"receive_only_audio\"")
            .substringBefore("} else {")
        assertTrue(receiveOnly.contains("MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO"))
        assertTrue(receiveOnly.contains("RtpTransceiver.RtpTransceiverDirection.RECV_ONLY"))
        assertTrue(receiveOnly.contains("receiver.sender.track() == null"))
        assertFalse(receiveOnly.contains("createAudioSource"))
        assertFalse(receiveOnly.contains("createAudioTrack"))
        assertTrue(source.contains("peer.setAudioPlayout(false)"))
        assertTrue(source.contains("if (mediaMode.takesAudioFocus) disposeStep { check(restoreCommunicationAudio())"))
        assertFalse(source.contains("setMicrophoneMute(false)"))
    }

    @Test fun outputOnlyHasIndependentOutputCapabilitiesAndNeverPermitsCapture() {
        val mode = LiveVoiceMediaMode.OUTPUT_ONLY
        assertFalse(mode.physicalCapture)
        assertFalse(mode.usesBufferedPrimaryAudio)
        assertFalse(mode.playsConnectionTones)
        assertTrue(mode.receivesOnly)
        assertTrue(mode.takesAudioFocus)
        assertTrue(mode.playsOutput)
        listOf(false, true).forEach { started ->
            listOf(false, true).forEach { muted ->
                listOf(false, true).forEach { ended ->
                    assertFalse(mode.permitsCapture(started, muted, ended))
                    assertEquals(started && !ended, mode.permitsOutput(started, ended))
                }
            }
        }
    }

    @Test fun receiveOnlyNativeBoundaryAlwaysDisablesRecordingBeforeConditionalPlayout() {
        listOf(LiveVoiceMediaMode.OUTPUT_ONLY, LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT).forEach { mode ->
            listOf(false, true).forEach { started ->
                listOf(false, true).forEach { ended ->
                    val commands = mutableListOf<String>()
                    assertTrue(LiveVoiceReceiveOnlyMediaState.apply(mode, started, ended, true,
                        setRecording = { commands += "recording:$it" },
                        setPlayout = { commands += "playout:$it" }))
                    assertEquals(listOf("recording:false",
                        "playout:${mode == LiveVoiceMediaMode.OUTPUT_ONLY && started && !ended}"), commands)
                }
            }
        }
    }

    @Test fun unexpectedLocalInputOrRecordingFailureCannotEnableOutput() {
        val commands = mutableListOf<String>()
        assertFalse(LiveVoiceReceiveOnlyMediaState.apply(LiveVoiceMediaMode.OUTPUT_ONLY,
            started = true, ended = false, localInputAbsent = false,
            setRecording = { commands += "recording:$it" },
            setPlayout = { commands += "playout:$it" }))
        assertEquals(listOf("recording:false", "playout:false"), commands)

        commands.clear()
        assertFalse(LiveVoiceReceiveOnlyMediaState.apply(LiveVoiceMediaMode.OUTPUT_ONLY,
            started = true, ended = false, localInputAbsent = true,
            setRecording = { throw IllegalStateException("synthetic rejection") },
            setPlayout = { commands += "playout:$it" }))
        assertEquals(listOf("playout:false"), commands)
    }

    @Test fun receiveOnlyBoundaryCannotBeUsedToReconfigureDuplexOrPrimaryInput() {
        listOf(LiveVoiceMediaMode.DUPLEX, LiveVoiceMediaMode.EARLY_CAPTURE_DUPLEX,
            LiveVoiceMediaMode.BUFFERED_DICTATION_PRIMARY_SILENT).forEach { mode ->
            assertFalse(LiveVoiceReceiveOnlyMediaState.apply(mode, true, false, false,
                setRecording = { error("must not change recording") },
                setPlayout = { error("must not change playout") }))
        }
    }

    @Test fun outputOnlyWiringPermanentlyDisablesAdmInputBeforeFactoryAndUsesNoInputTrack() {
        val source = source()
        val outputAdm = source.substringAfter("else if (mediaMode == LiveVoiceMediaMode.OUTPUT_ONLY) JavaAudioDeviceModule.builder")
            .substringBefore("else if (mediaMode.usesBufferedPrimaryAudio)")
        assertTrue(outputAdm.contains("setAudioRecordEnabled(false)"))
        assertTrue(outputAdm.contains("setMicrophoneMute(true)"))
        assertTrue(outputAdm.contains("setSpeakerMute(false)"))
        assertTrue(outputAdm.contains("setPlaybackSamplesReadyCallback"))
        assertFalse(outputAdm.contains("setSamplesReadyCallback"))
        assertFalse(outputAdm.contains("setAudioBufferCallback"))
        assertFalse(outputAdm.contains("setAudioRecordEnabled(true)"))
        assertTrue(source.indexOf("setAudioRecordEnabled(false)") < source.indexOf(".setAudioDeviceModule(audioModule)"))
        assertTrue(source.indexOf("peer.setAudioRecording(false)") < source.indexOf("peer.addTransceiver("))
        val receiveOnly = source.substringAfter("if (mediaMode.receivesOnly) {").substringBefore("} else {")
        assertTrue(receiveOnly.contains("RECV_ONLY"))
        assertTrue(receiveOnly.contains("receiver.sender.track() == null"))
        assertFalse(receiveOnly.contains("createAudioSource"))
        assertFalse(receiveOnly.contains("createAudioTrack"))
    }

    @Test fun outputOnlyRequiresReadyConfirmationBeforePlayoutAndRetainsOutputMonitoringAndCleanup() {
        val source = source()
        val confirm = source.substringAfter("override fun confirmSessionStarted()").substringBefore("// Live handles")
        assertTrue(confirm.contains("!opened.get()"))
        assertTrue(confirm.contains("dataChannel?.state() != DataChannel.State.OPEN"))
        assertTrue(confirm.contains("startupInputEnabled.set(true)"))
        assertTrue(confirm.contains("applyOutputOnlyState()"))
        val monitoring = source.substringAfter("override fun setAudioActivityMonitoringEnabled").substringBefore("override fun close()")
        assertTrue(monitoring.contains("!mediaMode.playsOutput"))
        val activity = source.substringAfter("private fun observeAudioActivity(").substringBefore("private fun applyLocalInputState")
        assertTrue(activity.contains("!mediaMode.permitsCapture"))
        assertTrue(activity.contains("!mediaMode.permitsOutput"))
        val cleanup = source.substringAfter("private fun disposeResources()").substringBefore("private inline fun ignoreRuntimeFailure")
        assertTrue(cleanup.contains("peerConnection?.setAudioRecording(false)"))
        assertTrue(cleanup.contains("peerConnection?.setAudioPlayout(false)"))
        assertTrue(cleanup.indexOf("outputOnlyPlayback.close()") < cleanup.indexOf("peerConnection?.dispose()"))
        assertTrue(cleanup.indexOf("peerConnection?.setAudioPlayout(false)") < cleanup.indexOf("peerConnection?.dispose()"))
        assertTrue(cleanup.contains("outputOnlyPlayback.close()"))
        assertTrue(source.contains("live_output_focus_lost"))
    }

    private fun source(): String = sequenceOf(
        File("src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
        File("android/app/src/main/java/ai/hans/standard/voice/realtime/AndroidWebRtcRealtimeTransport.kt"),
    ).first(File::isFile).readText()
}
