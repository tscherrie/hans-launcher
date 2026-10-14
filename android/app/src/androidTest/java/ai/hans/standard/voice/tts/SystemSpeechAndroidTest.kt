package ai.hans.standard.voice.tts

import ai.hans.standard.voice.tts.android.AndroidSystemSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemSpeechAndroidTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = AndroidSystemSpeech.AndroidEngine(instrumentation.targetContext,
            instrumentation.context.packageName) {}
        var options = SystemSpeechOptions()
        val phases = LinkedBlockingQueue<CodexReadAloudDeliveryPhase>()
        val runtime = SystemSpeechRuntime({ engine }, { options }, { options = it }, { Locale.GERMANY },
            object : TtsAudioFocusCoordinator {
                override fun request(onChange: (TtsAudioFocusChange) -> Unit) = TtsAudioFocusRequestResult.GRANTED
                override fun abandon() = Unit
            }, { true })
        fun session(): CodexReadAloudSessionPort {
            val port = runtime.session({ phases.offer(it) })
            assertTrue(port.prepareGuarded { true })
            await(CodexReadAloudDeliveryPhase.READY)
            // Prove the exact fixture was bound, not a fallback or installed vendor engine.
            assertEquals("hans-test-de", runtime.snapshot.selectedVoice?.id)
            return port
        }
        fun await(expected: CodexReadAloudDeliveryPhase) {
            repeat(8) {
                val phase = phases.poll(10, TimeUnit.SECONDS)
                if (phase == expected) return
                if (phase == null || phase == CodexReadAloudDeliveryPhase.FAILED) {
                    val logs = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand(
                            "logcat -d -t 250 -s AndroidRuntime:E TextToSpeech:E TextToSpeechService:E"))
                        .bufferedReader().use { it.readText().takeLast(8000) }
                    fail("Engine failed before $expected: ${runtime.snapshot}\n$logs")
                }
            }
            fail("Missing $expected")
        }
        override fun close() = runtime.close()
    }

    @Test fun publicEngineDiscoversOfflineVoiceAndCompletesTwoUtterances() = Fixture().use { f ->
        repeat(2) {
            val port = f.session()
            assertTrue(f.runtime.snapshot.selectedVoice!!.offline)
            assertEquals(2, f.runtime.snapshot.voices.size)
            assertTrue(port.speak("Hallo"))
            f.await(CodexReadAloudDeliveryPhase.SPEAKING)
            f.await(CodexReadAloudDeliveryPhase.STOPPED)
            assertTrue(port.completedNaturally)
            assertTrue(port.stopAndAwait(1_000))
        }
    }
    @Test fun publicEngineStopCallbackReleasesWithoutNaturalCompletion() = Fixture().use { f ->
        val port = f.session(); assertTrue(port.speak("long"))
        f.await(CodexReadAloudDeliveryPhase.SPEAKING)
        assertTrue(port.stopAndAwait(5_000))
        assertFalse(port.completedNaturally)
    }
    @Test fun publicEngineErrorDoesNotInventPlaybackCompletion() = Fixture().use { f ->
        val port = f.session(); assertTrue(port.speak("fail"))
        f.await(CodexReadAloudDeliveryPhase.FAILED)
        assertFalse(port.completedNaturally)
        assertTrue(port.stopAndAwait(1_000))
    }
    @Test fun missingLanguageAndNetworkVoiceAreReportedFromEffectiveCatalog() = Fixture().use { f ->
        val port = f.session()
        assertNull(selectSystemSpeechVoice(f.runtime.snapshot.voices, null, Locale.JAPAN))
        assertFalse(selectSystemSpeechVoice(f.runtime.snapshot.voices, "hans-test-en", Locale.GERMANY)!!.offline)
        assertTrue(port.stopAndAwait(1_000))
    }
}
