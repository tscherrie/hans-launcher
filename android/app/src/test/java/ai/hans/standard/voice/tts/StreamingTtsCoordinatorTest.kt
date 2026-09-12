package ai.hans.standard.voice.tts

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTtsCoordinatorTest {
    @Test
    fun projectedPartialWebLinkKeepsReadySentencesMovingAcrossMessages() {
        val harness = Harness()
        val projector = CodexTimelineSpeechProjector()
        projector.accept(speechSnapshot(), enabled = true)
        fun accept(vararg items: ClientTimelineItem) {
            projector.accept(speechSnapshot(*items), enabled = true).forEach(harness.coordinator::submit)
            harness.drain()
        }

        accept(speechItem("one", 1, "Erster Satz. [Zweiter Satz", complete = false))
        assertEquals(listOf("Erster Satz."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertTrue(harness.coordinator.state() is TtsPlaybackState.WaitingForText)

        accept(speechItem("one", 2, "Erster Satz. [Zweiter Satz](https://example.org/a_(b)", complete = false))
        assertEquals(listOf("Erster Satz."), harness.provider.requestTexts())
        val finalText = "Erster Satz. [Zweiter Satz](https://example.org/a_(b))."
        accept(speechItem("one", 3, finalText, complete = false))
        assertEquals(listOf("Erster Satz.", "Zweiter Satz."), harness.provider.requestTexts())

        accept(
            speechItem("one", 4, finalText, complete = true),
            speechItem("two", 1, "Dritter Satz.", complete = true, order = 2),
        )
        // No additional model snapshot is needed after playback finishes.
        harness.finishCurrentSentence()
        assertEquals(listOf("Erster Satz.", "Zweiter Satz.", "Dritter Satz."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals(TtsPlaybackState.Idle, harness.coordinator.state())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.RevisionIgnored>().isEmpty())
    }

    @Test
    fun everyCharacterStreamThroughRealProjectorNeverRepeatsOrSpeaksUrlFragments() {
        val harness = Harness()
        val projector = CodexTimelineSpeechProjector()
        projector.accept(speechSnapshot(), enabled = true)
        val raw = "Erster Satz. [Zweiter Satz](https://example.org/a_(b)?next=https://other.test). " +
            "https://tail.test/third. Dritter Satz."
        var finished = 0
        for (end in 1..raw.length) {
            projector.accept(
                speechSnapshot(speechItem("stream", end.toLong(), raw.substring(0, end), complete = false)),
                enabled = true,
            ).forEach(harness.coordinator::submit)
            harness.drain()
            while (finished < harness.provider.calls.size) {
                harness.finishCurrentSentence()
                finished += 1
            }
        }
        projector.accept(
            speechSnapshot(speechItem("stream", raw.length + 1L, raw, complete = true)),
            enabled = true,
        ).forEach(harness.coordinator::submit)
        harness.drain()

        assertEquals(
            listOf("Erster Satz.", "Zweiter Satz.", "tail.test.", "Dritter Satz."),
            harness.provider.requestTexts(),
        )
        assertEquals(TtsPlaybackState.Idle, harness.coordinator.state())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.RevisionIgnored>().isEmpty())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.InputRejected>().isEmpty())
        assertTrue(harness.provider.calls.all { it.handle.cancelCalls == 0 })
    }

    @Test
    fun aFinalUrlOnlyMessageDoesNotBlockTheNextQueuedAnswer() {
        val harness = Harness()
        val projector = CodexTimelineSpeechProjector()
        projector.accept(speechSnapshot(), enabled = true)
        val address = speechItem("address", 1, "https://example.org.", complete = false)
        val next = speechItem("next", 1, "Antwort.", complete = true, order = 2)
        projector.accept(speechSnapshot(address, next), enabled = true).forEach(harness.coordinator::submit)
        harness.drain()
        assertTrue(harness.provider.calls.isEmpty())

        projector.accept(
            speechSnapshot(address.copy(revision = 2, complete = true), next),
            enabled = true,
        ).forEach(harness.coordinator::submit)
        harness.drain()
        assertEquals(listOf("example.org."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals(listOf("example.org.", "Antwort."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals(TtsPlaybackState.Idle, harness.coordinator.state())
        assertEquals("https://example.org.", address.text)
    }

    @Test
    fun emptyFinalProjectionReleasesAWaitingLaterMessageWithoutAnotherSnapshot() {
        val harness = Harness()
        val projector = CodexTimelineSpeechProjector()
        projector.accept(speechSnapshot(), enabled = true)
        val empty = speechItem("empty", 1, "", complete = false)
        val next = speechItem("next", 1, "Nächste Antwort.", complete = true, order = 2)
        projector.accept(speechSnapshot(empty, next), true).forEach(harness.coordinator::submit)
        harness.drain()
        assertTrue(harness.provider.calls.isEmpty())

        projector.accept(speechSnapshot(empty.copy(revision = 2, complete = true), next), true)
            .forEach(harness.coordinator::submit)
        harness.drain()

        assertEquals(listOf("Nächste Antwort."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals(TtsPlaybackState.Idle, harness.coordinator.state())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.RevisionIgnored>().isEmpty())
    }

    @Test
    fun everyCharacterOfFormattedAnswersKeepsSpokenPrefixesAndTheFollowingMessageMoving() {
        val harness = Harness()
        val projector = CodexTimelineSpeechProjector()
        projector.accept(speechSnapshot(), enabled = true)
        val raw = "**Erster Satz.** *Zweiter Satz.* `Dritter Satz.` " +
            "[**Vierter Satz.**](https://example.org/a_(b)?q=private \"Titel\") " +
            "Grüße 👋."
        var finished = 0
        fun drainSpeech() {
            harness.drain()
            while (finished < harness.provider.calls.size) {
                harness.finishCurrentSentence()
                finished += 1
            }
        }
        for (end in 1..raw.length) {
            projector.accept(
                speechSnapshot(speechItem("formatted", end.toLong(), raw.substring(0, end), false)),
                enabled = true,
            ).forEach(harness.coordinator::submit)
            drainSpeech()
        }
        projector.accept(
            speechSnapshot(
                speechItem("formatted", raw.length + 1L, raw, true),
                speechItem("next", 1, "Danach geht es weiter.", true, order = 2),
            ),
            enabled = true,
        ).forEach(harness.coordinator::submit)
        drainSpeech()

        assertEquals(
            listOf("Erster Satz.", "Zweiter Satz.", "Dritter Satz.", "Vierter Satz.",
                "Grüße 👋.", "Danach geht es weiter."),
            harness.provider.requestTexts(),
        )
        assertEquals(TtsPlaybackState.Idle, harness.coordinator.state())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.RevisionIgnored>().isEmpty())
        assertTrue(harness.events.filterIsInstance<TtsPlaybackEvent.InputRejected>().isEmpty())
        assertTrue(harness.provider.calls.all { it.handle.cancelCalls == 0 })
    }

    @Test
    fun playbackCompletionStartsNextSentenceWithoutAnotherRuntimeEvent() {
        val harness = Harness()
        harness.submit("answer", 0, "Erster Satz. Zweiter Satz.", final = false)

        assertEquals(listOf("Erster Satz."), harness.provider.requestTexts())
        harness.finishCurrentSentence()

        assertEquals(
            listOf("Erster Satz.", "Zweiter Satz."),
            harness.provider.requestTexts(),
        )
        harness.finishCurrentSentence()
        assertEquals(
            TtsPlaybackState.WaitingForText(TtsMessageId("answer")),
            harness.coordinator.state(),
        )
        harness.submit("answer", 1, "Erster Satz. Zweiter Satz.", final = true)
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Idle)
        assertTrue(
            harness.events.contains(
                TtsPlaybackEvent.MessageCompleted(TtsMessageId("answer")),
            ),
        )
    }

    @Test
    fun playbackCompletionAlsoAdvancesAcrossMessageBoundary() {
        val harness = Harness()
        harness.submit("one", 0, "Nachricht eins.", final = true)
        harness.submit("two", 0, "Nachricht zwei.", final = true)

        assertEquals(listOf("Nachricht eins."), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals(
            listOf("Nachricht eins.", "Nachricht zwei."),
            harness.provider.requestTexts(),
        )
    }

    @Test
    fun newMessagesNeverInterruptRunningSentenceAndOverflowDropsOldestPendingOnly() {
        val harness = Harness()
        harness.submit("one", 0, "Eins.", final = true)
        val runningHandle = harness.provider.calls.single().handle
        harness.submit("two", 0, "Zwei.", final = true)
        harness.submit("three", 0, "Drei.", final = true)
        harness.submit("four", 0, "Vier.", final = true)
        harness.submit("five", 0, "Fuenf.", final = true)

        assertEquals(0, runningHandle.cancelCalls)
        assertEquals(
            TtsPlaybackEvent.MessageDropped(
                TtsMessageId("two"),
                TtsMessageDropReason.QUEUE_OVERFLOW,
            ),
            harness.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>().single(),
        )

        harness.finishCurrentSentence()
        assertEquals("Drei.", harness.provider.calls.last().request.text)
    }

    @Test
    fun pausedQueueKeepsExactlyThreeNotStartedMessages() {
        val harness = Harness()
        harness.coordinator.pause()
        harness.drain()
        harness.submit("one", 0, "Eins.", final = true)
        harness.submit("two", 0, "Zwei.", final = true)
        harness.submit("three", 0, "Drei.", final = true)
        harness.submit("four", 0, "Vier.", final = true)

        assertTrue(harness.provider.calls.isEmpty())
        val dropped = harness.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>()
        assertEquals(listOf(TtsMessageId("one")), dropped.map { it.messageId })

        harness.coordinator.resume()
        harness.drain()
        assertEquals("Zwei.", harness.provider.calls.single().request.text)
    }

    @Test
    fun finalOnlyFiltersIntermediateMessagesAndDefaultsToFableAtOnePointTwoFive() {
        val harness = Harness(
            initialSettings = TtsPlaybackSettings(
                readAloudMode = TtsReadAloudMode.FINAL_ONLY,
            ),
        )
        harness.submit(
            "progress",
            0,
            "Ich schaue nach.",
            kind = TtsMessageKind.INTERMEDIATE,
            final = true,
        )
        harness.submit(
            "final",
            0,
            "Erledigt.",
            kind = TtsMessageKind.FINAL_OUTPUT,
            final = true,
        )

        assertEquals(1, harness.provider.calls.size)
        val request = harness.provider.calls.single().request
        assertEquals("fable", request.voice)
        assertEquals(1.25, request.speed, 0.0)
        assertTrue(
            harness.events.contains(
                TtsPlaybackEvent.MessageFiltered(TtsMessageId("progress")),
            ),
        )
    }

    @Test
    fun allVisibleModeSpeaksIntermediateMessages() {
        val harness = Harness()
        harness.submit(
            "progress",
            0,
            "Ich schaue nach.",
            kind = TtsMessageKind.INTERMEDIATE,
            final = true,
        )
        assertEquals(listOf("Ich schaue nach."), harness.provider.requestTexts())
    }

    @Test
    fun settingsAreInjectedAndSnapshottedPerMessage() {
        val harness = Harness(
            initialSettings = TtsPlaybackSettings(voice = "nova", speed = 0.9),
        )
        harness.submit("one", 0, "Eins.", final = true)
        harness.settings.current = TtsPlaybackSettings(voice = "fable", speed = 1.75)
        harness.submit("two", 0, "Zwei.", final = true)

        assertEquals("nova", harness.provider.calls.single().request.voice)
        assertEquals(0.9, harness.provider.calls.single().request.speed, 0.0)
        harness.finishCurrentSentence()
        assertEquals("fable", harness.provider.calls.last().request.voice)
        assertEquals(1.75, harness.provider.calls.last().request.speed, 0.0)
    }

    @Test
    fun nonFinalTailWaitsAndFinalRevisionFlushesIt() {
        val harness = Harness()
        harness.submit("answer", 0, "Noch ohne Punkt", final = false)
        assertTrue(harness.provider.calls.isEmpty())
        assertEquals(
            TtsPlaybackState.WaitingForText(TtsMessageId("answer")),
            harness.coordinator.state(),
        )

        harness.submit("answer", 1, "Noch ohne Punkt", final = true)
        assertEquals(listOf("Noch ohne Punkt"), harness.provider.requestTexts())
    }

    @Test
    fun laterMessageCannotOvertakeCurrentStreamingMessage() {
        val harness = Harness()
        harness.submit("streaming", 0, "Noch unvollstaendig", final = false)
        harness.submit("later", 0, "Spaetere Nachricht.", final = true)
        assertTrue(harness.provider.calls.isEmpty())

        harness.submit("streaming", 1, "Noch unvollstaendig", final = true)
        assertEquals(listOf("Noch unvollstaendig"), harness.provider.requestTexts())
        harness.finishCurrentSentence()
        assertEquals("Spaetere Nachricht.", harness.provider.calls.last().request.text)
    }

    @Test
    fun fullSnapshotRevisionsReplaceOnlyUnstartedTailWithoutRepeatingStartedText() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo Welt. Alter Entwurf", final = false)
        assertEquals(listOf("Hallo Welt."), harness.provider.requestTexts())

        harness.submit("answer", 1, "Hallo Welt. Richtiger Satz. Noch", final = false)
        harness.submit("answer", 1, "Hallo Welt. Konflikt", final = false)
        harness.submit("answer", 0, "Hallo Welt. Alt", final = false)
        harness.submit("answer", 2, "Hi Welt. Falsch", final = false)

        val ignored = harness.events.filterIsInstance<TtsPlaybackEvent.RevisionIgnored>()
        assertEquals(
            listOf(
                TtsRevisionIgnoreReason.SAME_REVISION_CONFLICT,
                TtsRevisionIgnoreReason.STALE,
                TtsRevisionIgnoreReason.REWRITES_STARTED_AUDIO,
            ),
            ignored.takeLast(3).map { it.reason },
        )
        assertEquals(0, harness.provider.calls.single().handle.cancelCalls)

        harness.finishCurrentSentence()
        assertEquals("Richtiger Satz.", harness.provider.calls.last().request.text)
        assertEquals(2, harness.provider.calls.size)
    }

    @Test
    fun duplicateAndHigherIdenticalSnapshotsNeverDuplicateSpeech() {
        val harness = Harness()
        harness.submit("answer", 0, "Ein Satz. Rest", final = false)
        harness.submit("answer", 0, "Ein Satz. Rest", final = false)
        harness.submit("answer", 1, "Ein Satz. Rest", final = false)

        assertEquals(1, harness.provider.calls.size)
        assertTrue(
            harness.events.contains(
                TtsPlaybackEvent.RevisionIgnored(
                    TtsMessageId("answer"),
                    0,
                    TtsRevisionIgnoreReason.DUPLICATE,
                ),
            ),
        )
    }

    @Test
    fun terminalMessageRevisionCannotReplayCompletedAudio() {
        val harness = Harness()
        harness.submit("answer", 0, "Fertig.", final = true)
        harness.finishCurrentSentence()
        harness.submit("answer", 1, "Fertig. Noch einmal.", final = true)

        assertEquals(1, harness.provider.calls.size)
        assertTrue(
            harness.events.contains(
                TtsPlaybackEvent.RevisionIgnored(
                    TtsMessageId("answer"),
                    1,
                    TtsRevisionIgnoreReason.TERMINAL_MESSAGE,
                ),
            ),
        )
    }

    @Test
    fun pauseAndAppSwitchKeepCurrentPlaybackAliveUntilExplicitResume() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        val synthesis = harness.provider.calls.single().handle

        harness.coordinator.pause()
        harness.drain()
        assertEquals(1, synthesis.pauseCalls)
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Paused)

        harness.provider.ready()
        harness.drain()
        val playback = harness.player.sessions.single()
        assertEquals(1, playback.pauseCalls)
        harness.coordinator.onAppForegroundChanged("com.example.other")
        harness.drain()
        assertEquals(0, synthesis.cancelCalls)
        assertEquals(0, playback.stopCalls)

        harness.coordinator.resume()
        harness.drain()
        assertEquals(1, synthesis.resumeCalls)
        assertEquals(1, playback.resumeCalls)
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Playing)
    }

    @Test
    fun transientAudioFocusLossPausesAndGainResumesSameSentence() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        harness.provider.ready()
        harness.drain()
        val synthesis = harness.provider.calls.single().handle
        val playback = harness.player.sessions.single()

        harness.focus.send(TtsAudioFocusChange.LOST_TRANSIENT)
        harness.drain()
        assertEquals(1, synthesis.pauseCalls)
        assertEquals(1, playback.pauseCalls)

        harness.focus.send(TtsAudioFocusChange.GAINED)
        harness.drain()
        assertEquals(1, synthesis.resumeCalls)
        assertEquals(1, playback.resumeCalls)
        assertEquals(1, harness.provider.calls.size)
    }

    @Test
    fun explicitStopInterruptsCurrentAndDropsQueueButNewMessageCanStartLater() {
        val harness = Harness()
        harness.submit("one", 0, "Eins.", final = true)
        harness.provider.ready()
        harness.drain()
        harness.submit("two", 0, "Zwei.", final = true)
        val synthesis = harness.provider.calls.single().handle
        val playback = harness.player.sessions.single()

        harness.coordinator.stop()
        harness.drain()
        assertEquals(1, synthesis.cancelCalls)
        assertEquals(1, playback.stopCalls)
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Stopped)
        assertEquals(
            setOf(TtsMessageId("one"), TtsMessageId("two")),
            harness.events.filterIsInstance<TtsPlaybackEvent.MessageDropped>()
                .filter { it.reason == TtsMessageDropReason.EXPLICIT_STOP }
                .map { it.messageId }
                .toSet(),
        )

        harness.submit("three", 0, "Drei.", final = true)
        assertEquals("Drei.", harness.provider.calls.last().request.text)
    }

    @Test
    fun retryableProviderFailureRestartsSameSentenceThenContinuesQueue() {
        val harness = Harness()
        harness.submit("answer", 0, "Erster Satz. Zweiter Satz.", final = true)
        val original = harness.provider.calls.single()
        original.listener.onFailure(TtsProviderFailure("temporary", retryable = true))
        harness.drain()
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Failed)
        assertEquals(1, original.handle.cancelCalls)

        harness.coordinator.retry()
        harness.drain()
        assertEquals(2, harness.provider.calls.size)
        assertEquals(original.request.segmentId, harness.provider.calls.last().request.segmentId)
        assertEquals("Erster Satz.", harness.provider.calls.last().request.text)

        harness.finishCurrentSentence()
        assertEquals("Zweiter Satz.", harness.provider.calls.last().request.text)
    }

    @Test
    fun failedMessageCanBeAbandonedWithoutDroppingTheFollowingQueue() {
        val harness = Harness()
        harness.submit("notification:first", 0, "Erste Meldung.", final = true)
        harness.submit("notification:second", 0, "Zweite Meldung.", final = true)
        val first = harness.provider.calls.single()

        first.listener.onFailure(TtsProviderFailure("temporary", retryable = true))
        harness.drain()

        assertEquals(
            TtsPlaybackEvent.MessageFailed(
                TtsMessageId("notification:first"),
                TtsFailure(TtsFailureKind.PROVIDER, "temporary", retryable = true),
            ),
            harness.events.filterIsInstance<TtsPlaybackEvent.MessageFailed>().single(),
        )
        harness.coordinator.abandonFailedMessage()
        harness.drain()

        assertEquals(listOf("Erste Meldung.", "Zweite Meldung."), harness.provider.requestTexts())
        assertTrue(
            harness.events.contains(
                TtsPlaybackEvent.MessageDropped(
                    TtsMessageId("notification:first"),
                    TtsMessageDropReason.FAILED_MESSAGE_ABANDONED,
                ),
            ),
        )
    }

    @Test
    fun retryablePlayerFailureRestartsWholeSentence() {
        val harness = Harness()
        harness.submit("answer", 0, "Ganzer Satz.", final = true)
        harness.provider.ready()
        harness.provider.chunk(0)
        harness.drain()
        val firstRequest = harness.provider.calls.single().request
        harness.player.sessions.single().fail(TtsPlayerFailure("output_reset", retryable = true))
        harness.drain()

        val failed = harness.coordinator.state() as TtsPlaybackState.Failed
        assertEquals(TtsFailureKind.PLAYER, failed.failure.kind)
        harness.coordinator.retry()
        harness.drain()
        assertEquals(2, harness.provider.calls.size)
        assertEquals(firstRequest.segmentId, harness.provider.calls.last().request.segmentId)
        assertEquals(firstRequest.text, harness.provider.calls.last().request.text)
    }

    @Test
    fun audioFocusDenialIsRetryableWithoutLosingUncommittedSentence() {
        val harness = Harness()
        harness.focus.results += TtsAudioFocusRequestResult.DENIED
        harness.submit("answer", 0, "Bleibt erhalten.", final = true)
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Failed)
        assertTrue(harness.provider.calls.isEmpty())

        harness.coordinator.retry()
        harness.drain()
        assertEquals(listOf("Bleibt erhalten."), harness.provider.requestTexts())
    }

    @Test
    fun nonRetryableProtocolFailureDoesNotRestartOnRetry() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        harness.provider.ready()
        harness.drain()
        harness.provider.chunk(sequence = 1)
        harness.drain()
        val failed = harness.coordinator.state() as TtsPlaybackState.Failed
        assertEquals(TtsFailureKind.PROTOCOL, failed.failure.kind)
        assertFalse(failed.failure.retryable)

        harness.coordinator.retry()
        harness.drain()
        assertEquals(1, harness.provider.calls.size)
    }

    @Test
    fun duplicateAudioChunkIsIgnoredButContiguousChunksReachPlayerOnce() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        harness.provider.ready()
        harness.drain()
        harness.provider.chunk(0)
        harness.provider.chunk(0)
        harness.provider.chunk(1)
        harness.drain()

        assertEquals(listOf(0L, 1L), harness.player.sessions.single().writtenSequences)
    }

    @Test
    fun providerAndPlayerProtocolErrorsAreExplicit() {
        val beforeReady = Harness()
        beforeReady.submit("answer", 0, "Hallo.", final = true)
        beforeReady.provider.completed()
        beforeReady.drain()
        assertEquals(
            TtsFailureKind.PROTOCOL,
            (beforeReady.coordinator.state() as TtsPlaybackState.Failed).failure.kind,
        )

        val earlyPlayback = Harness()
        earlyPlayback.submit("answer", 0, "Hallo.", final = true)
        earlyPlayback.provider.ready()
        earlyPlayback.drain()
        earlyPlayback.player.sessions.single().complete()
        earlyPlayback.drain()
        assertEquals(
            TtsFailureKind.PROTOCOL,
            (earlyPlayback.coordinator.state() as TtsPlaybackState.Failed).failure.kind,
        )
    }

    @Test
    fun permanentFocusLossRetriesCurrentSentenceFromBeginning() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        harness.provider.ready()
        harness.provider.chunk(0)
        harness.drain()
        val originalId = harness.provider.calls.single().request.segmentId

        harness.focus.send(TtsAudioFocusChange.LOST_PERMANENTLY)
        harness.drain()
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Failed)
        harness.coordinator.retry()
        harness.drain()
        assertEquals(originalId, harness.provider.calls.last().request.segmentId)
        assertEquals(2, harness.provider.calls.size)
    }

    @Test
    fun permanentLossAfterTransientPauseDoesNotLeaveRetryStuckPaused() {
        val harness = Harness()
        harness.submit("answer", 0, "Hallo.", final = true)
        harness.provider.ready()
        harness.drain()

        harness.focus.send(TtsAudioFocusChange.LOST_TRANSIENT)
        harness.focus.send(TtsAudioFocusChange.LOST_PERMANENTLY)
        harness.drain()
        assertTrue(harness.coordinator.state() is TtsPlaybackState.Failed)

        harness.coordinator.retry()
        harness.drain()
        assertEquals(2, harness.provider.calls.size)
        assertFalse(harness.coordinator.state() is TtsPlaybackState.Paused)
    }

    @Test
    fun staleCallbacksFromStoppedAttemptCannotAffectNewPlayback() {
        val harness = Harness()
        harness.submit("old", 0, "Alt.", final = true)
        val old = harness.provider.calls.single()
        harness.coordinator.stop()
        harness.drain()
        harness.submit("new", 0, "Neu.", final = true)
        val stateBefore = harness.coordinator.state()

        old.listener.onStreamReady(TtsAudioStreamFormat("audio/pcm"))
        old.listener.onFailure(TtsProviderFailure("late", retryable = true))
        harness.drain()
        assertEquals(stateBefore, harness.coordinator.state())
        assertEquals(listOf("Alt.", "Neu."), harness.provider.requestTexts())
    }

    @Test
    fun priorityStopThatOvertakesQueuedGuardedSubmitCannotStartOlderAudio() {
        val dispatcher = PriorityManualDispatcher()
        val provider = FakeProvider()
        val events = mutableListOf<TtsPlaybackEvent>()
        var admitted = true
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = FakePlayer(),
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) {
                    events += event
                }
            },
        )

        coordinator.submitGuarded(
            TtsMessageRevision(
                messageId = TtsMessageId("notification:old"),
                revision = 1,
                text = "Nicht waehrend des Diktats.",
                kind = TtsMessageKind.FINAL_OUTPUT,
                isFinal = true,
            ),
        ) { admitted }
        admitted = false
        coordinator.stop()

        dispatcher.runControlsFirstThenAll()

        assertTrue(provider.calls.isEmpty())
        assertTrue(
            events.contains(
                TtsPlaybackEvent.MessageDropped(
                    TtsMessageId("notification:old"),
                    TtsMessageDropReason.EXPLICIT_STOP,
                ),
            ),
        )
    }

    @Test
    fun acknowledgedPriorityStopInvalidatesOlderQueuedOrdinarySubmitBeforeItCanStart() {
        val dispatcher = AwaitablePriorityManualDispatcher()
        val provider = FakeProvider()
        val events = mutableListOf<TtsPlaybackEvent>()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = FakePlayer(),
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) {
                    events += event
                }
            },
        )
        coordinator.submit(
            TtsMessageRevision(
                messageId = TtsMessageId("queued-before-dictation"),
                revision = 1,
                text = "Diese Ausgabe darf nicht mehr beginnen.",
                kind = TtsMessageKind.FINAL_OUTPUT,
                isFinal = true,
            ),
        )
        val stopReturned = CountDownLatch(1)
        val stopSucceeded = AtomicBoolean(false)
        val stopping = Thread {
            stopSucceeded.set(coordinator.stopAndAwait(2_000L))
            stopReturned.countDown()
        }
        stopping.start()
        assertTrue(dispatcher.controlEnqueued.await(2, TimeUnit.SECONDS))

        dispatcher.runControlsFirstThenAll()

        assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
        stopping.join(2_000L)
        assertTrue(stopSucceeded.get())
        assertTrue(provider.calls.isEmpty())
        assertTrue(
            events.contains(
                TtsPlaybackEvent.MessageDropped(
                    TtsMessageId("queued-before-dictation"),
                    TtsMessageDropReason.EXPLICIT_STOP,
                ),
            ),
        )

        // A genuinely later request remains legitimate once the caller's interaction gate opens.
        coordinator.submit(
            TtsMessageRevision(
                messageId = TtsMessageId("new-after-stop"),
                revision = 1,
                text = "Neue Ausgabe.",
                kind = TtsMessageKind.FINAL_OUTPUT,
                isFinal = true,
            ),
        )
        dispatcher.runControlsFirstThenAll()
        assertEquals(listOf("Neue Ausgabe."), provider.requestTexts())
    }

    @Test
    fun privacyStopBarrierReturnsOnlyAfterRealDispatcherStopsPhysicalPlayback() {
        val dispatcher = ExecutorTtsTaskDispatcher()
        val provider = FakeProvider()
        val player = FakePlayer()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = player,
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) = Unit
            },
        )
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val stopSucceeded = AtomicBoolean(false)

        try {
            coordinator.submit(
                TtsMessageRevision(
                    messageId = TtsMessageId("notification:privacy-barrier"),
                    revision = 1,
                    text = "Private Nachricht.",
                    kind = TtsMessageKind.FINAL_OUTPUT,
                    isFinal = true,
                ),
            )
            assertTrue(provider.callStarted.await(2, TimeUnit.SECONDS))
            provider.ready()
            provider.chunk(0)
            assertEquals(1, player.sessions.size)

            dispatcher.dispatch {
                blockerEntered.countDown()
                check(releaseBlocker.await(2, TimeUnit.SECONDS))
            }
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            val stopping = Thread {
                stopSucceeded.set(coordinator.stopAndAwait(2_000))
                stopReturned.countDown()
            }
            stopping.start()

            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, player.sessions.single().stopCalls)
            releaseBlocker.countDown()
            assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
            stopping.join(2_000)
            assertTrue(stopSucceeded.get())
            assertEquals(1, player.sessions.single().stopCalls)
            assertEquals(1, provider.calls.single().handle.cancelCalls)
        } finally {
            releaseBlocker.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun privacyStopBarrierFailsClosedWhenPhysicalPlaybackStopThrowsAndStaysFailed() {
        val dispatcher = ExecutorTtsTaskDispatcher()
        val provider = FakeProvider()
        val player = FakePlayer()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = player,
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) = Unit
            },
        )

        try {
            coordinator.submit(
                TtsMessageRevision(
                    messageId = TtsMessageId("throwing-physical-player"),
                    revision = 1,
                    text = "Diese Ausgabe muss physisch stoppen.",
                    kind = TtsMessageKind.FINAL_OUTPUT,
                    isFinal = true,
                ),
            )
            assertTrue(provider.callStarted.await(2, TimeUnit.SECONDS))
            provider.ready()
            provider.chunk(0)
            player.sessions.single().throwOnStop = true

            assertFalse(coordinator.stopAndAwait(2_000L))
            assertEquals(1, player.sessions.single().stopCalls)
            assertEquals(1, provider.calls.single().handle.cancelCalls)
            // An empty later stop may not erase the earlier lack of a physical terminal proof.
            assertFalse(coordinator.stopAndAwait(2_000L))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun privacyStopBarrierFailsClosedWhenProviderCancellationThrows() {
        val dispatcher = ExecutorTtsTaskDispatcher()
        val provider = FakeProvider()
        val player = FakePlayer()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = player,
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) = Unit
            },
        )

        try {
            coordinator.submit(
                TtsMessageRevision(
                    messageId = TtsMessageId("throwing-provider-cancel"),
                    revision = 1,
                    text = "Auch der Provider muss terminal sein.",
                    kind = TtsMessageKind.FINAL_OUTPUT,
                    isFinal = true,
                ),
            )
            assertTrue(provider.callStarted.await(2, TimeUnit.SECONDS))
            provider.ready()
            provider.chunk(0)
            provider.calls.single().handle.throwOnCancel = true

            assertFalse(coordinator.stopAndAwait(2_000L))
            assertEquals(1, provider.calls.single().handle.cancelCalls)
            assertEquals(1, player.sessions.single().stopCalls)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun abandonedPrivacyStopBarrierNeverReportsPhysicalStopSuccess() {
        val dispatcher = ExecutorTtsTaskDispatcher()
        val provider = FakeProvider()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = FakeSettingsSource(TtsPlaybackSettings()),
            provider = provider,
            player = FakePlayer(),
            audioFocus = FakeAudioFocus(),
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) = Unit
                override fun onEvent(event: TtsPlaybackEvent) = Unit
            },
        )
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = AtomicBoolean(false)
        val stopReturned = CountDownLatch(1)
        val stopSucceeded = AtomicBoolean(true)
        dispatcher.dispatch {
            blockerEntered.countDown()
            while (!releaseBlocker.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // close() may interrupt the running ordinary task; keep it alive until the
                    // test explicitly releases it so the queued control is provably abandoned.
                }
            }
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        val stopping = Thread {
            stopSucceeded.set(coordinator.stopAndAwait(2_000))
            stopReturned.countDown()
        }
        stopping.start()
        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))

        dispatcher.close()
        assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
        releaseBlocker.set(true)
        stopping.join(2_000)
        assertFalse(stopSucceeded.get())
    }

    @Test
    fun invalidInputAndSettingsFailureDoNotTouchProvider() {
        val harness = Harness()
        harness.submit(
            "huge",
            0,
            "x".repeat(StreamingTtsCoordinator.MAX_MESSAGE_CHARACTERS + 1),
            final = true,
        )
        harness.settings.throwOnSnapshot = true
        harness.submit("settings", 0, "Hallo.", final = true)

        assertTrue(harness.provider.calls.isEmpty())
        assertEquals(
            listOf(
                TtsInputRejectionReason.MESSAGE_TOO_LARGE,
                TtsInputRejectionReason.SETTINGS_UNAVAILABLE,
            ),
            harness.events.filterIsInstance<TtsPlaybackEvent.InputRejected>().map { it.reason },
        )
    }

    private fun speechSnapshot(vararg items: ClientTimelineItem) = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.READY,
        generation = 1,
        session = CodexSessionReducer().snapshot(),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = items.toList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
        problem = null,
    )

    private fun speechItem(
        id: String,
        revision: Long,
        text: String,
        complete: Boolean,
        order: Long = 1,
    ) = ClientTimelineItem(
        id = id,
        role = ClientTimelineRole.HANS,
        text = text,
        order = order,
        revision = revision,
        complete = complete,
        status = if (complete) ClientTimelineStatus.COMPLETE else ClientTimelineStatus.STREAMING,
        agentPhase = AgentMessagePhase.FINAL_ANSWER,
    )

    private class Harness(
        initialSettings: TtsPlaybackSettings = TtsPlaybackSettings(),
    ) {
        val dispatcher = ManualDispatcher()
        val settings = FakeSettingsSource(initialSettings)
        val provider = FakeProvider()
        val player = FakePlayer()
        val focus = FakeAudioFocus()
        val states = mutableListOf<TtsPlaybackState>()
        val events = mutableListOf<TtsPlaybackEvent>()
        val coordinator = StreamingTtsCoordinator(
            settingsSource = settings,
            provider = provider,
            player = player,
            audioFocus = focus,
            dispatcher = dispatcher,
            listener = object : TtsPlaybackListener {
                override fun onStateChanged(state: TtsPlaybackState) {
                    states += state
                }

                override fun onEvent(event: TtsPlaybackEvent) {
                    events += event
                }
            },
        )

        fun submit(
            id: String,
            revision: Long,
            text: String,
            kind: TtsMessageKind = TtsMessageKind.FINAL_OUTPUT,
            final: Boolean,
        ) {
            coordinator.submit(
                TtsMessageRevision(
                    messageId = TtsMessageId(id),
                    revision = revision,
                    text = text,
                    kind = kind,
                    isFinal = final,
                ),
            )
            drain()
        }

        fun finishCurrentSentence() {
            provider.ready()
            provider.chunk(0)
            provider.completed()
            drain()
            player.sessions.last().complete()
            drain()
        }

        fun drain() = dispatcher.runAll()
    }

    private class ManualDispatcher : TtsTaskDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun dispatch(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runAll() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(++count < 10_000) { "event loop did not become idle" }
                tasks.removeFirst().invoke()
            }
        }
    }

    private class PriorityManualDispatcher : TtsTaskDispatcher {
        private val ordinary = ArrayDeque<() -> Unit>()
        private val controls = ArrayDeque<() -> Unit>()

        override fun dispatch(task: () -> Unit) {
            ordinary.addLast(task)
        }

        override fun dispatchControl(task: () -> Unit) {
            controls.addLast(task)
        }

        fun runControlsFirstThenAll() {
            while (controls.isNotEmpty()) controls.removeFirst().invoke()
            while (ordinary.isNotEmpty()) ordinary.removeFirst().invoke()
        }
    }

    private class AwaitablePriorityManualDispatcher : TtsTaskDispatcher {
        private val ordinary = ArrayDeque<() -> Unit>()
        private val controls = ArrayDeque<() -> Unit>()
        val controlEnqueued = CountDownLatch(1)

        override fun dispatch(task: () -> Unit) {
            synchronized(this) { ordinary.addLast(task) }
        }

        override fun dispatchControlAndAwait(timeoutMillis: Long, task: () -> Unit): Boolean {
            val completed = CountDownLatch(1)
            synchronized(this) {
                controls.addLast {
                    task()
                    completed.countDown()
                }
            }
            controlEnqueued.countDown()
            return completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        fun runControlsFirstThenAll() {
            while (true) {
                val next = synchronized(this) {
                    if (controls.isEmpty()) null else controls.removeFirst()
                } ?: break
                next()
            }
            while (true) {
                val next = synchronized(this) {
                    if (ordinary.isEmpty()) null else ordinary.removeFirst()
                } ?: break
                next()
            }
        }
    }

    private class FakeSettingsSource(initial: TtsPlaybackSettings) : TtsSettingsSource {
        var current = initial
        var throwOnSnapshot = false

        override fun snapshot(): TtsPlaybackSettings {
            if (throwOnSnapshot) error("settings unavailable")
            return current
        }
    }

    private class FakeProvider : StreamingTtsProvider {
        val calls = mutableListOf<Call>()
        val callStarted = CountDownLatch(1)

        override fun start(
            request: TtsSynthesisRequest,
            listener: StreamingTtsProvider.Listener,
        ): StreamingTtsSynthesis {
            return FakeSynthesis().also {
                calls += Call(request, listener, it)
                callStarted.countDown()
            }
        }

        fun ready() {
            calls.last().listener.onStreamReady(TtsAudioStreamFormat("audio/pcm"))
        }

        fun chunk(sequence: Long) {
            calls.last().listener.onAudioChunk(
                TtsAudioChunk.create(sequence, byteArrayOf(sequence.toByte())),
            )
        }

        fun completed() {
            calls.last().listener.onCompleted()
        }

        fun requestTexts(): List<String> = calls.map { it.request.text }

        data class Call(
            val request: TtsSynthesisRequest,
            val listener: StreamingTtsProvider.Listener,
            val handle: FakeSynthesis,
        )
    }

    private class FakeSynthesis : StreamingTtsSynthesis {
        var pauseCalls = 0
        var resumeCalls = 0
        var cancelCalls = 0
        var throwOnCancel = false

        override fun pause() {
            pauseCalls += 1
        }

        override fun resume() {
            resumeCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
            if (throwOnCancel) throw IllegalStateException("cancel detail")
        }
    }

    private class FakePlayer : StreamingTtsPlayer {
        val sessions = mutableListOf<FakePlayback>()

        override fun open(
            segmentId: TtsSegmentId,
            format: TtsAudioStreamFormat,
            listener: StreamingTtsPlayer.Listener,
        ): StreamingTtsPlayback {
            return FakePlayback(segmentId, listener).also(sessions::add)
        }
    }

    private class FakePlayback(
        val segmentId: TtsSegmentId,
        private val listener: StreamingTtsPlayer.Listener,
    ) : StreamingTtsPlayback {
        val writtenSequences = mutableListOf<Long>()
        var finishCalls = 0
        var pauseCalls = 0
        var resumeCalls = 0
        var stopCalls = 0
        var throwOnStop = false

        override fun write(chunk: TtsAudioChunk) {
            writtenSequences += chunk.sequence
        }

        override fun finishInput() {
            finishCalls += 1
        }

        override fun pause() {
            pauseCalls += 1
        }

        override fun resume() {
            resumeCalls += 1
        }

        override fun stop() {
            stopCalls += 1
            if (throwOnStop) throw IllegalStateException("stop detail")
        }

        fun complete() {
            listener.onCompleted()
        }

        fun fail(failure: TtsPlayerFailure) {
            listener.onFailure(failure)
        }
    }

    private class FakeAudioFocus : TtsAudioFocusCoordinator {
        val results = ArrayDeque<TtsAudioFocusRequestResult>()
        var requestCalls = 0
        var abandonCalls = 0
        private var listener: ((TtsAudioFocusChange) -> Unit)? = null

        override fun request(
            onChange: (TtsAudioFocusChange) -> Unit,
        ): TtsAudioFocusRequestResult {
            requestCalls += 1
            listener = onChange
            return if (results.isEmpty()) {
                TtsAudioFocusRequestResult.GRANTED
            } else {
                results.removeFirst()
            }
        }

        override fun abandon() {
            abandonCalls += 1
        }

        fun send(change: TtsAudioFocusChange) {
            checkNotNull(listener).invoke(change)
        }
    }
}
