package ai.hans.standard.integration

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring complements the behavioral guarded-delivery and positive audio-release tests. */
class NotificationCodexSpeechWiringTest {
    private val source = File("src/main/java/ai/hans/standard/integration/AndroidCodexSessionHost.kt").readText()

    @Test fun notificationSpeechUsesSystemVoiceAndNeverRequiresAnApiCredential() {
        val drain = source.substringAfter("private fun drainPendingNotificationSpeech()")
            .substringBefore("private fun notificationSpeechGateOpenLocked()")
        assertTrue(drain.contains("hasReadAloudAccess()"))
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

    @Test fun systemStartAndSpeechRevalidateNotificationAdmissionWithoutLiveOrAccount() {
        val reader = source.substringAfter("private fun createCodexReader(")
            .substringBefore("private fun readAloudPlaybackState(")
        assertTrue(reader.contains("check(admissionCurrent())"))
        assertTrue(reader.contains("admissionCurrent() && admissionGuard()"))
        assertTrue(reader.contains("AndroidSystemSpeech.get(appContext)"))
        assertFalse(reader.contains("startRealtime("))
        assertFalse(reader.contains("AndroidWebRtcRealtimeTransport"))
        assertTrue(reader.contains("reader.prepareGuarded"))
        assertTrue(reader.contains("reader.speakGuarded"))
    }

    @Test fun disablingReadAloudDoesNotDisableOrBypassDictationAuthentication() {
        val dictation = source.substringAfter("fun hasCodexSpeechAccess(): Boolean =")
            .substringBefore("fun hasReadAloudAccess()")
        assertTrue(dictation.contains("AccountPhase.SIGNED_IN"))
        assertTrue(dictation.contains("AccountIdentity.ChatGpt"))
        assertFalse(dictation.contains("AndroidSystemSpeech.get"))
        val readAloud = source.substringAfter("fun hasReadAloudAccess(): Boolean =").substringBefore("/**")
        assertTrue(readAloud.contains("AndroidSystemSpeech.get(appContext).enabled"))
        assertFalse(readAloud.contains("SIGNED_IN"))
    }

    @Test fun positiveReleaseObserversDoNotInstallContractsWhileStopWaitersHoldDispatchLock() {
        val readers = source.substringAfter("private val codexReadAloud:")
            .substringBefore("private fun createCodexReader(")
        assertFalse(readers.contains("dynamicToolCoordinator.onActivityChanged()"))
        assertTrue(readers.contains("onPhoneToolWorkQuiescent()"))
        assertTrue(readers.contains("remoteControlMain.post { drainPendingNotificationSpeech() }"))
    }
}
