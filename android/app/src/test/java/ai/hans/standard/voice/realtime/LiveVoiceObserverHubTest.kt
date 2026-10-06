package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveVoiceObserverHubTest {
    @Test fun typedTranscriptReplayPreservesNativeOriginAndDoesNotCollapseRepeatedSpeech() {
        val hub = LiveVoiceObserverHub()
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        val source = CodexVoiceWorkScope("main", "turn")
        val first = LiveVoiceTranscriptRevision("session", 1, "native-1", LiveVoiceTranscriptAuthor.HANS,
            "Equal wording.", true, source)
        val second = first.copy(utteranceId = "native-2", dictationWorkScope = null)
        hub.onTranscriptRevision(first.copy(text = "Equal ", isFinal = false))
        hub.onTranscriptRevision(first)
        hub.onTranscriptRevision(second)
        hub.onTranscriptRevision(first) // Identified redelivery, not text equality.
        val received = mutableListOf<LiveVoiceTranscriptRevision>()
        hub.add(object : LiveVoiceObserver {
            override fun onTranscriptRevision(event: LiveVoiceTranscriptRevision) { received += event }
        }).cancel()
        assertEquals(listOf(first, second), received)
        assertEquals(2, received.map { it.displayId }.distinct().size)
        assertEquals(source, received.first().dictationWorkScope)
    }

    @Test fun typedTranscriptStillReachesLegacyObserversOnceAndSourceFenceRejectsStaleMetadata() {
        val hub = LiveVoiceObserverHub()
        val received = mutableListOf<String>()
        hub.add(object : LiveVoiceObserver {
            override fun onHansTranscript(text: String, isFinal: Boolean) { received += "$text:$isFinal" }
        })
        val event = LiveVoiceTranscriptRevision("session", 1, "native-1", LiveVoiceTranscriptAuthor.HANS,
            "Visible.", true)
        hub.ifCurrentSource({ false }) { hub.onTranscriptRevision(event) }
        assertTrue(received.isEmpty())
        hub.ifCurrentSource({ true }) { hub.onTranscriptRevision(event) }
        assertEquals(listOf("Visible.:true"), received)
    }
    @Test
    fun responseReadyReachesProcessObserverWithoutActivityAndIsNeverReplayedOnSubscribe() {
        val hub = LiveVoiceObserverHub()
        val first = LiveVoiceResponseReady("session-one", 1, "response-one")
        val received = mutableListOf<LiveVoiceResponseReady>()
        hub.onHansResponseReady(first) // no history is stored by this process event hub
        hub.add(object : LiveVoiceObserver {
            override fun onHansResponseReady(event: LiveVoiceResponseReady) = error("observer failure")
        })
        val subscription = hub.add(object : LiveVoiceObserver {
            override fun onHansResponseReady(event: LiveVoiceResponseReady) { received += event }
        })
        assertEquals(emptyList<LiveVoiceResponseReady>(), received)
        val second = first.copy(responseId = "response-two")
        hub.onHansResponseReady(second)
        assertEquals(listOf(second), received)
        subscription.cancel()
        hub.onHansResponseReady(first.copy(responseId = "response-three"))
        assertEquals(listOf(second), received)
    }

    @Test
    fun observerFailuresAreIsolatedAndCancellationIsIdempotent() {
        val hub = LiveVoiceObserverHub()
        var delivered = 0
        hub.add(
            object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    error("observer_failure")
                }
            },
        )
        val subscription = hub.add(
            object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    delivered += 1
                }
            },
        )

        // Observer registration immediately restores the latest snapshot.
        assertEquals(1, delivered)
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        assertEquals(2, delivered)
        subscription.cancel()
        subscription.cancel()
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.STOPPED))
        assertEquals(2, delivered)
    }

    @Test
    fun activityRecreationImmediatelyRestoresActiveMutedCallAndTranscriptState() {
        val hub = LiveVoiceObserverHub()
        val active = LiveVoiceSnapshot(
            phase = LiveVoicePhase.HANS_SPEAKING,
            generation = 4,
            inputMuted = true,
        )
        hub.onSnapshot(active)
        hub.onUserTranscript("Wo ist mein Termin?", true)
        hub.onHansTranscript("Ich schaue", false)

        val events = mutableListOf<String>()
        hub.add(
            object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    events += "snapshot:${snapshot.phase}:${snapshot.inputMuted}"
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    events += "user:$text:$isFinal"
                }

                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    events += "hans:$text:$isFinal"
                }
            },
        )

        assertEquals(
            listOf(
                "snapshot:HANS_SPEAKING:true",
                "user:Wo ist mein Termin?:true",
                "hans:Ich schaue:false",
            ),
            events,
        )
    }

    @Test
    fun snapshotReplayAndConcurrentDeliveryCannotOvertakeEachOther() {
        val hub = LiveVoiceObserverHub()
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        val replayEntered = CountDownLatch(1)
        val allowReplayToFinish = CountDownLatch(1)
        val publishStarted = CountDownLatch(1)
        val publishFinished = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<LiveVoicePhase>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val registration = executor.submit {
                hub.add(
                    object : LiveVoiceObserver {
                        override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                            events += snapshot.phase
                            if (snapshot.phase == LiveVoicePhase.LISTENING) {
                                replayEntered.countDown()
                                assertTrue(allowReplayToFinish.await(5, TimeUnit.SECONDS))
                            }
                        }
                    },
                )
            }
            assertTrue(replayEntered.await(5, TimeUnit.SECONDS))
            executor.submit {
                publishStarted.countDown()
                hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.STOPPED))
                publishFinished.countDown()
            }
            assertTrue(publishStarted.await(5, TimeUnit.SECONDS))
            assertFalse(publishFinished.await(100, TimeUnit.MILLISECONDS))
            allowReplayToFinish.countDown()
            registration.get(5, TimeUnit.SECONDS)
            assertTrue(publishFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(LiveVoicePhase.LISTENING, LiveVoicePhase.STOPPED), events)
        } finally {
            allowReplayToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aNewCallDoesNotReplayTranscriptsFromThePreviousCall() {
        val hub = LiveVoiceObserverHub()
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.LISTENING))
        hub.onHansTranscript("Alte Antwort", true)
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.STOPPED))
        hub.onSnapshot(LiveVoiceSnapshot(phase = LiveVoicePhase.CONNECTING))
        val transcripts = mutableListOf<String>()

        hub.add(
            object : LiveVoiceObserver {
                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    transcripts += text
                }
            },
        )

        assertEquals(emptyList<String>(), transcripts)
    }
}
