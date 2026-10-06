package ai.hans.standard.voice.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Root-free wiring regressions; never records, reads credentials, or invokes the backend. */
class CodexBatchDictationRoutingContractTest {
    @Test fun actionKeyAndComposerUseLocalBatchInsteadOfOpeningLiveVoiceOrPaidAudio() {
        val service = source("voice/android/HansDictationService.kt")
        assertTrue(service.contains("CodexBatchTranscriptionProvider("))
        assertTrue(service.contains("AndroidCodexBatchTranscriptionGateway(this) { admissionStillValid() }"))
        assertFalse(service.contains("AndroidLiveVoiceRuntime.start("))
        assertFalse(service.contains("CodexShortLiveDictationProvider("))
        assertFalse(service.contains("OpenAiRealtimeTranscriptionProvider("))
        assertFalse(service.contains("speechCredentialStore"))
        assertFalse(service.contains("speechCredentialStatus()"))
        assertFalse(service.contains("completeNativeSession("))
    }

    @Test fun coldStartProbesHelperWithoutRequiringTheStillLazyRecordingCore() {
        val service = source("voice/android/HansDictationService.kt")
        val request = service.substringAfter("private fun requestRecording(startId: Int)")
            .substringBefore("override fun onStartRejected")
        assertTrue(request.contains("batchGateway?.isAvailable() ?: AndroidCodexBatchTranscriptionGateway(this)"))
        assertTrue(request.contains(".use { it.isAvailable() }"))
        assertTrue(request.contains("val lease = newAdmissionLease()"))
        assertTrue(request.contains("commandRetirement.started(recordingId, startId, lease)"))
        assertTrue(request.indexOf("!helperAvailable") < request.indexOf("startRecording()"))
    }

    @Test fun deliveryHasOneDurableStartOrSteerSubmissionAndRejectsStaleAccountOrContext() {
        val service = source("voice/android/HansDictationService.kt")
        val delivery = service.substringAfter("override fun onUserMessageReady(")
            .substringBefore("private fun requestRecording(startId: Int)")
        assertTrue(delivery.contains("commandRetirement.admission(recordingId) ?: return"))
        assertTrue(delivery.contains("if (finishCurrentRecording) retireRecording(recordingId)"))
        assertTrue(delivery.contains("commandRetirement.claimDelivery(recordingId)"))
        assertTrue(delivery.contains("recordingAdmission()"))
        assertTrue(delivery.contains("admissionStillValid = recordingAdmission"))
        assertTrue(delivery.indexOf("commandRetirement.claimDelivery(recordingId)") < delivery.indexOf(".submitDictationTranscript("))
        assertFalse(service.contains("stopSelf()"))
        assertTrue(service.contains("retirementHandler.post {"))
        assertTrue(service.contains("commandRetirement.takeRetirementStartId(recordingId)?.let { startId -> stopSelf(startId) }"))
        assertTrue(service.contains("current.generation == admitted.generation"))
        assertTrue(service.contains("current.session.currentThreadId == admitted.session.currentThreadId"))
        assertTrue(service.contains("current.session.account.identity == admitted.session.account.identity"))
        assertTrue(service.contains("host.hasCodexSpeechAccess()"))
    }

    @Test fun backgroundSecondTapStopsAndFinalizationCannotStartOrMuteAnything() {
        val coordinator = source("phone/keys/RootFreeDictationLaunch.kt")
        val toggle = coordinator.substringAfter("fun requestToggleFromBackground(")
            .substringBefore("fun requestStartFromBackground(")
        assertTrue(toggle.contains("phase == DictationUiPhase.FINALIZING) return true"))
        assertTrue(toggle.contains("return requestStop(context)"))
        assertFalse(toggle.contains("toggleDictationInputMuted"))
        val service = source("voice/android/HansDictationService.kt")
        assertTrue(service.contains("if (phase == DictationUiPhase.FINALIZING) return"))
        assertTrue(service.contains("current is RecordingState.Stopping || current is RecordingState.Finalizing"))
        assertTrue(service.contains("commandRetirement.activeRecordingId()"))
        assertTrue(service.contains("commandRetirement.expectStartupCancellation(activeRecordingId)"))
        assertTrue(service.contains("commandRetirement.takeIdleStartupCancellation()"))
        assertTrue(service.contains("cancelledStartup?.let(::retireRecording)"))
        assertTrue(service.contains("commandRetirement.completedWithoutTranscript(state.recordingId)"))
    }

    @Test fun phoneCaptureIncludesPendingAndClosingOwnershipAndIsGatedBeforeDictationStart() {
        val service = source("voice/android/HansDictationService.kt")
        val ownership = service.substringAfter("internal fun phoneOwnsCapture(): Boolean =")
            .substringBefore("private fun hasRecordAudioPermission")
        assertTrue(ownership.contains("entryPoint() == LiveVoiceEntryPoint.PHONE"))
        assertTrue(ownership.contains("VoiceInputTransitionPolicy.liveOwnsVoice("))
        assertTrue(ownership.contains("AndroidLiveVoiceRuntime.isCaptureRequestedOrActive()"))
        val start = service.substringAfter("fun start(context: Context)").substringBefore("fun stop(context: Context)")
        assertTrue(start.indexOf("phoneOwnsCapture()") < start.indexOf("ContextCompat.startForegroundService("))
        assertTrue(service.substringAfter("private fun requestRecording(startId: Int)").substringBefore("override fun onStartRejected")
            .contains("phoneOwnsCapture()"))
        assertTrue(service.contains("!phoneOwnsCapture() && host.awaitDictationCaptureReady() && !phoneOwnsCapture()"))
    }

    @Test fun recorderOverflowUsesABoundedWrapperAndStopsCaptureWithoutLosingThePrefix() {
        val service = source("voice/android/HansDictationService.kt")
        assertTrue(service.contains("BoundedPcmInputProvider("))
        assertTrue(service.contains("maximumPcmBytes = CodexBatchTranscriptionProvider.MAX_PCM_BYTES"))
        assertTrue(service.contains("maximumChunkBytes = CodexDictationIntegration.recordingConfig.chunkBytes"))
        assertTrue(service.contains("sttProvider = boundedInputProvider"))
        assertTrue(service.contains("newSttProvider.onNetworkUnavailable()"))
        assertTrue(service.contains("try { newSttProvider.close() } finally { gateway.close() }"))
    }

    private fun source(relative: String): String = listOf(
        File("src/main/java/ai/hans/standard/$relative"),
        File("android/app/src/main/java/ai/hans/standard/$relative"),
    ).first(File::isFile).readText()
}
