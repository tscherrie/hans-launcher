package ai.hans.standard.voice.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTtsBackpressureTest {
    @Test
    fun fastProviderCannotQueueAudioAheadOfImmediateStopOnSlowSink() {
        Harness().use { harness ->
            harness.start()
            assertTrue(harness.player.firstWriteStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            harness.coordinator.stop()
            harness.player.allowFirstWrite.countDown()

            assertTrue(harness.provider.handle.cancelled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(harness.player.stopped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, harness.player.writeCount.get())
            assertTrue(harness.provider.emittedChunks.get() <= 2)
        }
    }

    @Test
    fun fastProviderCannotQueueAudioAheadOfPauseOnSlowSink() {
        Harness().use { harness ->
            harness.start()
            assertTrue(harness.player.firstWriteStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            harness.coordinator.pause()
            harness.player.allowFirstWrite.countDown()

            assertTrue(harness.provider.handle.paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(harness.player.paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(
                harness.provider.handle.producerBlockedByPause.await(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ),
            )
            val writesAtPause = harness.player.writeCount.get()
            // One callback may already have crossed the acknowledged handoff
            // at the exact pause boundary, but the stream can never build an
            // unbounded queue behind it.
            assertTrue(writesAtPause in 1..2)
            assertTrue(harness.provider.emittedChunks.get() <= 2)
            assertTrue(harness.provider.handle.isPaused.get())

            harness.coordinator.stop()
            assertTrue(harness.provider.handle.cancelled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(harness.provider.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(writesAtPause, harness.player.writeCount.get())
        }
    }

    private class Harness : AutoCloseable {
        val dispatcher = ExecutorTtsTaskDispatcher()
        val provider = FastProvider()
        val player = SlowPlayer()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = TtsSettingsSource { TtsPlaybackSettings() },
            provider = provider,
            player = player,
            audioFocus = ImmediateAudioFocus,
            dispatcher = dispatcher,
            listener = NoOpListener,
        )

        fun start() {
            coordinator.submit(
                TtsMessageRevision(
                    messageId = TtsMessageId("backpressure"),
                    revision = 1,
                    text = "Hallo.",
                    kind = TtsMessageKind.FINAL_OUTPUT,
                    isFinal = true,
                ),
            )
            assertTrue(provider.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        override fun close() {
            player.allowFirstWrite.countDown()
            runCatching { coordinator.stop() }
            provider.close()
            dispatcher.close()
        }
    }

    private class FastProvider : StreamingTtsProvider, AutoCloseable {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val emittedChunks = AtomicInteger(0)
        val handle = Handle()
        private var worker: Thread? = null

        override fun start(
            request: TtsSynthesisRequest,
            listener: StreamingTtsProvider.Listener,
        ): StreamingTtsSynthesis {
            worker = Thread({
                try {
                    started.countDown()
                    listener.onStreamReady(TtsAudioStreamFormat("audio/pcm", 24_000, 1))
                    repeat(1_000) { index ->
                        handle.awaitRunnable()
                        if (handle.isCancelled.get()) return@Thread
                        emittedChunks.incrementAndGet()
                        listener.onAudioChunk(
                            TtsAudioChunk.create(index.toLong(), byteArrayOf(0, 0)),
                        )
                    }
                    listener.onCompleted()
                } finally {
                    finished.countDown()
                }
            }, "fast-test-tts-provider").apply {
                isDaemon = true
                start()
            }
            return handle
        }

        override fun close() {
            handle.cancel()
            worker?.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }

        class Handle : StreamingTtsSynthesis {
            val cancelled = CountDownLatch(1)
            val paused = CountDownLatch(1)
            val producerBlockedByPause = CountDownLatch(1)
            val isPaused = AtomicBoolean(false)
            val isCancelled = AtomicBoolean(false)
            private val monitor = Object()

            override fun pause() {
                isPaused.set(true)
                paused.countDown()
            }

            override fun resume() {
                synchronized(monitor) {
                    isPaused.set(false)
                    monitor.notifyAll()
                }
            }

            override fun cancel() {
                synchronized(monitor) {
                    if (!isCancelled.compareAndSet(false, true)) return
                    isPaused.set(false)
                    cancelled.countDown()
                    monitor.notifyAll()
                }
            }

            fun awaitRunnable() {
                synchronized(monitor) {
                    while (isPaused.get() && !isCancelled.get()) {
                        producerBlockedByPause.countDown()
                        monitor.wait()
                    }
                }
            }
        }
    }

    private class SlowPlayer : StreamingTtsPlayer {
        val firstWriteStarted = CountDownLatch(1)
        val allowFirstWrite = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val writeCount = AtomicInteger(0)

        override fun open(
            segmentId: TtsSegmentId,
            format: TtsAudioStreamFormat,
            listener: StreamingTtsPlayer.Listener,
        ): StreamingTtsPlayback = object : StreamingTtsPlayback {
            override fun write(chunk: TtsAudioChunk) {
                if (writeCount.incrementAndGet() == 1) {
                    firstWriteStarted.countDown()
                    check(allowFirstWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            }

            override fun finishInput() = Unit

            override fun pause() {
                paused.countDown()
            }

            override fun resume() = Unit

            override fun stop() {
                stopped.countDown()
            }
        }
    }

    private object ImmediateAudioFocus : TtsAudioFocusCoordinator {
        override fun request(
            onChange: (TtsAudioFocusChange) -> Unit,
        ): TtsAudioFocusRequestResult = TtsAudioFocusRequestResult.GRANTED

        override fun abandon() = Unit
    }

    private object NoOpListener : TtsPlaybackListener {
        override fun onStateChanged(state: TtsPlaybackState) = Unit
        override fun onEvent(event: TtsPlaybackEvent) = Unit
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
