package ai.hans.standard.voice.feedback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integration seams around Android services, paired with behavioral provider/store/UI tests. */
class SpeechFailureWiringTest {
    @Test
    fun batchDictationFailureIsOwnedAndPublishedBeforeFinalTranscriptCallbackDelivery() {
        val service = source("voice/android/HansDictationService.kt")
        val block = service.substringAfter("override fun onFailure(recordingId: RecordingId, code: String)")
            .substringBefore("sttProvider = AutoCloseable")
        assertTrue(block.contains("serviceOwners.runIfOwner(this@HansDictationService)"))
        assertTrue(block.contains("HansSpeechFailureRuntime.report(code)"))
        val provider = source("voice/stt/CodexBatchTranscriptionProvider.kt")
        val failure = provider.substringAfter("if (wav == null)").substringBefore("BatchTranscriptionDiagnostics.event(recordingId, BatchTranscriptionDiagnostics.Event.REQUEST_STARTED)")
        assertTrue(failure.indexOf("observer.onFailure(recordingId, code)") >= 0)
        assertTrue(failure.indexOf("observer.onFailure(recordingId, code)") < failure.indexOf("callback(Result.failure"))
    }

    @Test
    fun liveAndTtsPublishWithoutAnActivityAndLauncherObservesDismissibleReplay() {
        val live = source("voice/realtime/HansLiveVoiceForegroundService.kt")
        val serviceFailure = live.substringAfter("internal fun reportServiceFailure(failure: LiveVoiceFailure)")
            .substringBefore("internal fun clearForTest")
        assertTrue(serviceFailure.contains("HansSpeechFailureRuntime.report(failure.code)"))
        val sessionFailure = live.substringAfter("// A retryable error may still be inside this call")
        assertTrue(sessionFailure.indexOf("HansSpeechFailureRuntime.report(failure.code)") <
            sessionFailure.indexOf("observerHub.onFailure(failure)"))
        assertTrue(source("integration/AndroidCodexSessionHost.kt").contains("HansSpeechFailureRuntime.report(state.failure.code)"))
        val launcher = source("LauncherActivity.kt")
        assertTrue(launcher.contains("localUi.copy(speechFailure = failure"))
        assertTrue(launcher.contains("failure.revision >= localUi.speechFailure.revision"))
        assertTrue(launcher.contains("onDismissSpeechFailure = { revision -> HansSpeechFailureRuntime.dismiss(revision) }"))
        assertTrue(launcher.contains("onOpenSpeechFailureHelp = { target -> openFixedOpenAiPage(target.url) }"))
        val dictationObserver = launcher.substringAfter("val voiceObserver = DictationRuntimeObserver")
            .substringBefore("val speechFailureObserver")
        assertTrue(dictationObserver.contains("RecordingFailure.OPENAI_PROJECT_SPENDING_LIMIT_REACHED -> Unit"))
        assertTrue(!dictationObserver.contains("HansSpeechFailureRuntime.report("))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/ai/hans/standard", path),
        File("android/app/src/main/java/ai/hans/standard", path),
    ).first(File::isFile).readText()
}
