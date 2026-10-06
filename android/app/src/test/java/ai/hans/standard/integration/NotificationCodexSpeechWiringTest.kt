package ai.hans.standard.integration

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring complements the behavioral guarded-delivery and positive audio-release tests. */
class NotificationCodexSpeechWiringTest {
    private val source = File("src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt").readText()

    @Test fun notificationSpeechUsesChatGptAccessAndNeverRequiresAnApiCredential() {
        val drain = source.substringAfter("private fun drainPendingNotificationSpeech()")
            .substringBefore("private fun notificationSpeechGateOpenLocked()")
        assertTrue(drain.contains("hasCodexSpeechAccess()"))
        assertFalse(drain.contains("speechCredentialStatus()"))
        assertTrue(drain.contains("notificationReadAloud.submitGuarded("))
        assertFalse(drain.contains("ttsCoordinator.submitGuarded("))
    }

    @Test fun notificationDeliveryCorrelatesReceiptsAndUsesIndependentAttemptEpochs() {
        assertTrue(source.contains("snapshotDeliveryExecutor.execute { acceptTtsPlaybackEvent(event) }"))
        assertTrue(source.contains("deliveryEpoch = attemptSequence"))
        assertTrue(source.contains("it.deliveryEpoch == speechEpoch"))
        assertTrue(source.contains("notificationReadAloud.beginTurn(reservation.attempt.deliveryEpoch)"))
    }

    @Test fun privacyAndCaptureWaitForBothNotificationOutputPaths() {
        val stop = source.substringAfter("private fun stopNotificationSpeechAndAwait(")
            .substringBefore("/**")
        assertTrue(stop.contains("notificationReadAloud.stopAndAwait(timeoutMillis)"))
        assertTrue(stop.contains("ttsCoordinator.stopAndAwait(remaining)"))
        assertTrue(source.contains("stopPlaybackAndAwait = {\n                stopNotificationSpeechAndAwait("))
    }

    @Test fun nativeStartAndSpeechAppendRevalidateNotificationAdmission() {
        val reader = source.substringAfter("private fun createCodexReader(")
            .substringBefore("private fun readAloudPlaybackState(")
        assertTrue(reader.contains("if (!admissionCurrent() || currentClient() !== owner)"))
        assertTrue(reader.contains("admissionCurrent() && currentClient() === owner"))
        assertTrue(reader.contains("mediaMode = LiveVoiceMediaMode.OUTPUT_ONLY"))
        assertTrue(reader.contains("reader.prepareGuarded"))
        assertTrue(reader.contains("reader.speakGuarded"))
    }

    @Test fun positiveReleaseObserversDoNotInstallContractsWhileStopWaitersHoldDispatchLock() {
        val readers = source.substringAfter("private val codexReadAloud:")
            .substringBefore("private fun createCodexReader(")
        assertFalse(readers.contains("dynamicToolCoordinator.onActivityChanged()"))
        assertTrue(readers.contains("onPhoneToolWorkQuiescent()"))
        assertTrue(readers.contains("remoteControlMain.post { drainPendingNotificationSpeech() }"))
    }
}
