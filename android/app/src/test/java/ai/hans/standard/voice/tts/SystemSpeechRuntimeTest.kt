package ai.hans.standard.voice.tts

import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SystemSpeechRuntimeTest {
    private class Engine : SystemSpeechEngine {
        lateinit var events: SystemSpeechEngine.Events
        var initSuccess = true
        var available = listOf(SystemSpeechVoice("de", "de-DE", "Deutsch", true))
        val utterances = LinkedBlockingQueue<Pair<String, String>>()
        val stops = LinkedBlockingQueue<Boolean>()
        var configured: Pair<String, Float>? = null
        override val maxInputLength = 20
        override fun initialize(events: SystemSpeechEngine.Events) { this.events = events; events.initialized(initSuccess) }
        override fun voices() = available
        override fun configure(voiceId: String, speed: Float): Boolean { configured = voiceId to speed; return true }
        override fun speak(text: String, id: String): Boolean { utterances.offer(text to id); return true }
        override fun stop(): Boolean { stops.offer(true); return true }
        override fun close() = Unit
        fun spoken(): Pair<String, String> = checkNotNull(utterances.poll(3, TimeUnit.SECONDS))
    }
    private class Fixture : AutoCloseable {
        val engine = Engine()
        var options = SystemSpeechOptions()
        var audible = true
        var allowed = true
        var granted = true
        lateinit var focusChanged: (TtsAudioFocusChange) -> Unit
        var creates = 0
        val phases = LinkedBlockingQueue<CodexReadAloudDeliveryPhase>()
        val failures = LinkedBlockingQueue<String>()
        val runtime = SystemSpeechRuntime({ creates++; engine }, { options }, { options = it },
            { Locale.GERMANY }, object : TtsAudioFocusCoordinator {
                override fun request(onChange: (TtsAudioFocusChange) -> Unit): TtsAudioFocusRequestResult {
                    focusChanged = onChange
                    return if (granted) TtsAudioFocusRequestResult.GRANTED else TtsAudioFocusRequestResult.DENIED
                }
                override fun abandon() = Unit
            }, { audible }, { failures.offer(it) })
        fun session(): CodexReadAloudSessionPort {
            val port = runtime.session({ phases.offer(it) }, { allowed })
            assertTrue(port.prepareGuarded { allowed })
            await(CodexReadAloudDeliveryPhase.READY)
            return port
        }
        fun await(expected: CodexReadAloudDeliveryPhase) {
            repeat(16) { if (phases.poll(3, TimeUnit.SECONDS) == expected) return }
            fail("Missing phase $expected")
        }
        override fun close() = runtime.close()
    }

    @Test fun readyAloneNeverProvesPlaybackAndNaturalCompletionReusesEngine() = Fixture().use { f ->
        repeat(2) {
            val port = f.session()
            assertFalse(port.completedNaturally)
            assertTrue(port.speak("Hallo"))
            val (_, id) = f.engine.spoken()
            f.engine.events.started(id); f.await(CodexReadAloudDeliveryPhase.SPEAKING)
            f.engine.events.done(id); f.await(CodexReadAloudDeliveryPhase.STOPPED)
            assertTrue(port.completedNaturally)
            assertTrue(port.stopAndAwait(50))
        }
        assertEquals(1, f.creates)
    }
    @Test fun longTextUsesBoundedSequentialChunksAndIgnoresDuplicateCallbacks() = Fixture().use { f ->
        val port = f.session()
        val text = "Hallo Welt. ".repeat(7)
        assertTrue(port.speak(text))
        val received = StringBuilder()
        val chunks = splitSystemSpeech(text, 20)
        chunks.forEach { chunk ->
            val (spoken, id) = f.engine.spoken()
            assertEquals(chunk, spoken); received.append(spoken)
            f.engine.events.started(id); f.engine.events.done(id); f.engine.events.done(id)
        }
        f.await(CodexReadAloudDeliveryPhase.STOPPED)
        assertEquals(text, received.toString())
        assertTrue(port.completedNaturally)
        assertTrue(f.engine.utterances.isEmpty())
    }
    @Test fun stopNeedsCorrelatedReceiptAndNeverMarksCancelledOutputDelivered() = Fixture().use { f ->
        val port = f.session(); port.speak("Hallo")
        val (_, id) = f.engine.spoken()
        assertFalse(port.stopAndAwait(30))
        assertNotNull(f.engine.stops.poll(2, TimeUnit.SECONDS))
        f.engine.events.stopped("wrong-id")
        assertFalse(port.stopAndAwait(30))
        f.engine.events.stopped(id)
        assertTrue(port.stopAndAwait(2_000)); assertFalse(port.completedNaturally)
        f.engine.events.started(id); f.engine.events.done(id)
        assertFalse(port.completedNaturally)
    }
    @Test fun focusLossStopsBeforeAnotherChunk() = Fixture().use { f ->
        val port = f.session(); port.speak("Hallo Welt. ".repeat(5))
        val (_, id) = f.engine.spoken()
        f.focusChanged(TtsAudioFocusChange.LOST_TRANSIENT)
        assertNotNull(f.engine.stops.poll(2, TimeUnit.SECONDS))
        f.engine.events.done(id)
        assertTrue(port.stopAndAwait(2_000)); assertFalse(port.completedNaturally)
        assertTrue(f.engine.utterances.isEmpty())
    }
    @Test fun privateOutputLossRequiresStopReceiptAndDoesNotContinueOnSpeaker() = Fixture().use { f ->
        val port = f.session(); port.speak("Hallo Welt. ".repeat(5))
        val (_, id) = f.engine.spoken()
        f.engine.events.interrupted(id)
        assertNotNull(f.engine.stops.poll(2, TimeUnit.SECONDS))
        assertFalse(port.stopAndAwait(30))
        f.engine.events.stopped(id)
        assertTrue(port.stopAndAwait(2_000)); assertFalse(port.completedNaturally)
        assertTrue(f.engine.utterances.isEmpty())
    }
    @Test fun mutedVolumeDropsWithoutDelayedBacklog() = Fixture().use { f ->
        val port = f.session(); f.audible = false; port.speak("Hallo")
        f.await(CodexReadAloudDeliveryPhase.STOPPED)
        f.audible = true
        assertTrue(f.engine.utterances.isEmpty()); assertFalse(port.completedNaturally)
    }
    @Test fun contextRevocationRejectsSpeech() = Fixture().use { f ->
        val port = f.session(); f.allowed = false
        assertFalse(port.speak("private")); assertTrue(port.stopAndAwait(2_000))
        assertTrue(f.engine.utterances.isEmpty())
    }
    @Test fun engineInitializationFailureIsExplicit() = Fixture().use { f ->
        f.engine.initSuccess = false
        f.runtime.session({ f.phases.offer(it) }).prepare()
        f.await(CodexReadAloudDeliveryPhase.FAILED)
        assertEquals("system_speech_engine_missing", f.failures.poll(2, TimeUnit.SECONDS))
    }
    @Test fun missingLanguageCannotSilentlyUseAnotherLanguage() = Fixture().use { f ->
        f.engine.available = listOf(SystemSpeechVoice("en", "en-US", "English", true))
        f.runtime.session({ f.phases.offer(it) }).prepare()
        f.await(CodexReadAloudDeliveryPhase.FAILED)
        assertNull(f.runtime.snapshot.selectedVoice)
    }
    @Test fun doneWithoutStartIsNotSuccessfulPlayback() = Fixture().use { f ->
        val port = f.session(); port.speak("Hallo")
        f.engine.events.done(f.engine.spoken().second)
        f.await(CodexReadAloudDeliveryPhase.FAILED)
        assertFalse(port.completedNaturally)
    }
    @Test fun focusDenialFailsWithoutSubmittingSpeech() = Fixture().use { f ->
        val port = f.session(); f.granted = false; port.speak("Hallo")
        f.await(CodexReadAloudDeliveryPhase.FAILED)
        assertEquals("system_speech_focus_denied", f.failures.poll(2, TimeUnit.SECONDS))
        assertTrue(f.engine.utterances.isEmpty())
    }
    @Test fun voiceSelectionPrefersOfflineMatchingLanguageButHonorsExplicitVoice() {
        val online = SystemSpeechVoice("online", "de-DE", "Online", false)
        val offline = SystemSpeechVoice("offline", "de-AT", "Offline", true)
        assertEquals(offline, selectSystemSpeechVoice(listOf(online, offline), null, Locale.GERMANY))
        assertEquals(online, selectSystemSpeechVoice(listOf(online, offline), online.id, Locale.GERMANY))
        assertEquals(online, selectSystemSpeechVoice(listOf(online), null, Locale.GERMANY))
        assertNull(selectSystemSpeechVoice(listOf(online), null, Locale.JAPAN))
    }
    @Test fun chunkingPreservesSurrogatesAndText() {
        val text = "🙂 Hallo!\n".repeat(500)
        val chunks = splitSystemSpeech(text, 30)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 30 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
