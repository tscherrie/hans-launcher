package ai.hans.standard

import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import ai.hans.standard.voice.stt.android.OpenAiRealtimeTranscriptionConfig
import ai.hans.standard.voice.stt.android.OpenAiRealtimeTranscriptionProvider
import ai.hans.standard.voice.stt.android.RealtimeTranscriptionObserver
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in network/auth gate; ordinary deterministic test runs remain offline. */
@RunWith(AndroidJUnit4::class)
class OpenAiRealtimeTranscriptionConnectionTest {
    @Test
    fun storedCredentialCanOpenOfficialRealtimeTranscriptionSession() {
        verifyAcceptedSession(SttTranscriptionDelay.LOW)
    }

    @Test
    fun storedCredentialCanOpenMinimalDelayRealtimeTranscriptionSession() {
        verifyAcceptedSession(SttTranscriptionDelay.MINIMAL)
    }

    private fun verifyAcceptedSession(requestedDelay: SttTranscriptionDelay) {
        if (
            InstrumentationRegistry.getArguments().getString(ARGUMENT_NAME) != "true"
        ) {
            assertTrue(true)
            return
        }
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val sessionReady = AtomicReference(false)
        val recordingId = RecordingId(9_001)
        val provider = OpenAiRealtimeTranscriptionProvider(
            tokenSource = AndroidKeystoreSpeechCredentialStore(
                ApplicationProvider.getApplicationContext(),
            ),
            config = OpenAiRealtimeTranscriptionConfig(transcriptionDelay = requestedDelay),
            observer = object : RealtimeTranscriptionObserver {
                override fun onSessionReady(recordingId: RecordingId) {
                    sessionReady.set(true)
                    ready.countDown()
                }

                override fun onFailure(recordingId: RecordingId, code: String) {
                    failure.compareAndSet(null, code)
                    ready.countDown()
                }
            },
        )
        val session = provider.openSession(recordingId, PcmAudioFormat(sampleRateHz = 24_000))
        try {
            assertTrue("Realtime transcription session did not become ready", ready.await(25, TimeUnit.SECONDS))
            assertNull("Realtime transcription failed: ${failure.get()}", failure.get())
            // A successful update need not echo the optional delay setting.
            assertTrue("Server did not accept the transcription session update", sessionReady.get())
        } finally {
            session.cancel()
            provider.close()
        }
    }

    companion object {
        const val ARGUMENT_NAME = "runLiveSpeechSmoke"
    }
}
