package ai.hans.standard.voice.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechAudioTrackRouteRegistrationTest {
    @Test fun speakerDoesNotPrerollOrWait() {
        val route = SpeechAudioTrackRouteRegistration(AutoCloseable {}, { false }, { error("must not wait") }, {})
        route.prepareBeforeWrite { error("must not preroll") }
    }
    @Test fun pendingPrivateRouteWritesOnlySilenceBeforeAcknowledgement() {
        val signal = CountDownLatch(1)
        val silent = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val route = SpeechAudioTrackRouteRegistration(AutoCloseable {}, { true },
            { check(signal.await(1, TimeUnit.SECONDS)) }, {})
        val worker = Thread { route.prepareBeforeWrite { silent.countDown() }; completed.countDown() }
        worker.start()
        assertTrue(silent.await(1, TimeUnit.SECONDS))
        assertEquals(1L, completed.count)
        signal.countDown()
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        worker.join(1000)
    }
    @Test fun rejectedAcknowledgementNeverAuthorizesSpeech() {
        var silence = 0
        val route = SpeechAudioTrackRouteRegistration(AutoCloseable {}, { true }, {}, { error("wrong route") })
        assertThrows(IllegalStateException::class.java) { route.prepareBeforeWrite { silence++ } }
        assertEquals(1, silence)
    }
    @Test fun timeoutDoesNotCallVerificationAsSuccess() {
        val route = SpeechAudioTrackRouteRegistration(AutoCloseable {}, { true },
            { error("bounded timeout") }, { error("must not reach verification") })
        val failure = assertThrows(IllegalStateException::class.java) { route.prepareBeforeWrite {} }
        assertEquals("bounded timeout", failure.message)
    }

    @Test fun closeWakesPendingAcknowledgementAndDoesNotAuthorizeSpeech() {
        val signal = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val route = SpeechAudioTrackRouteRegistration(AutoCloseable {}, { true },
            { entered.countDown(); check(signal.await(1, TimeUnit.SECONDS)) },
            { check(!cancelled.get()) { "cancelled" } },
            { cancelled.set(true); signal.countDown() })
        val worker = Thread {
            try { route.prepareBeforeWrite {} } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        route.close()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals("cancelled", failure.get()?.message)
        worker.join(1000)
    }
}
