package ai.hans.standard.voice.stt.android

import ai.hans.standard.voice.PcmAudioChunk
import ai.hans.standard.voice.PcmAudioFormat
import ai.hans.standard.voice.IncrementalSttSession
import ai.hans.standard.voice.RecordingId
import ai.hans.standard.voice.RecordingProgress
import ai.hans.standard.voice.RecordingProgressListener
import ai.hans.standard.voice.stt.SttTranscriptionContext
import ai.hans.standard.voice.stt.SttTranscriptionContextSource
import ai.hans.standard.voice.stt.SttTranscriptionDelay
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRealtimeTranscriptionProtocolTest {
    @Test
    fun bothDelayChoicesUseDocumentedWireValuesAndPreservePrompt() {
        for (delay in SttTranscriptionDelay.entries) {
            val transcription = JSONObject(
                OpenAiRealtimeTranscriptionProtocol.sessionUpdate("Hans dictation", delay),
            ).getJSONObject("session").getJSONObject("audio").getJSONObject("input")
                .getJSONObject("transcription")
            assertEquals(delay.wireValue, transcription.getString("delay"))
            assertEquals("gpt-live-transcribe", transcription.getString("model"))
            assertEquals("Hans dictation", transcription.getString("prompt"))
        }
        assertEquals(SttTranscriptionDelay.LOW, OpenAiRealtimeTranscriptionConfig().transcriptionDelay)
        assertEquals(null, SttTranscriptionDelay.fromWireValue("medium"))
    }

    @Test
    fun delayIsSnapshottedPerRecordingAndAcknowledgedBeforeReady() {
        val sockets = FakeSocketFactory()
        var selected = SttTranscriptionDelay.LOW
        var reads = 0
        val observed = mutableListOf<String>()
        val provider = provider(
            sockets,
            delaySource = { reads += 1; selected },
            observer = object : RealtimeTranscriptionObserver {
                override fun onTranscriptionDelayConfirmed(recordingId: RecordingId, delay: SttTranscriptionDelay) {
                    observed += "confirmed:${delay.wireValue}"
                }

                override fun onSessionReady(recordingId: RecordingId) {
                    observed += "ready"
                }
            },
        )
        val first = provider.openSession(RecordingId(100), PCM_24K)
        selected = SttTranscriptionDelay.MINIMAL
        sockets.open()
        assertEquals("low", requestedDelay(sockets.sent.single()))
        assertTrue(observed.isEmpty())
        sockets.ready(SttTranscriptionDelay.LOW)
        assertEquals(listOf("confirmed:low", "ready"), observed)
        first.cancel()

        provider.openSession(RecordingId(101), PCM_24K)
        sockets.open()
        assertEquals("minimal", requestedDelay(sockets.sent.last()))
        sockets.ready(SttTranscriptionDelay.MINIMAL)
        assertEquals(listOf("confirmed:low", "ready", "confirmed:minimal", "ready"), observed)
        assertEquals(2, reads)
        provider.close()
    }

    @Test
    fun acceptedSessionWithoutKnownDelayEchoStillDictatesWithoutClaimingConfirmation() {
        for (ack in listOf(
            """{"type":"session.updated"}""",
            """{"type":"session.updated","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"transcription":{"model":"gpt-live-transcribe","language":"de","prompt":"Hans"},"turn_detection":null}}}}""",
            """{"type":"transcription_session.updated","session":{"input_audio_transcription":{"model":"gpt-live-transcribe"}}}""",
            """{"type":"session.updated","session":{"audio":{"input":{"transcription":{"delay":"medium"}}}}}""",
        )) {
            val sockets = FakeSocketFactory()
            val failures = mutableListOf<String>()
            val confirmed = mutableListOf<SttTranscriptionDelay>()
            var ready = false
            val provider = provider(
                sockets,
                observer = object : RealtimeTranscriptionObserver {
                    override fun onSessionReady(recordingId: RecordingId) { ready = true }
                    override fun onFailure(recordingId: RecordingId, code: String) { failures += code }
                    override fun onTranscriptionDelayConfirmed(recordingId: RecordingId, delay: SttTranscriptionDelay) {
                        confirmed += delay
                    }
                },
            )
            val session = provider.openSession(RecordingId(102), PCM_24K)
            var chunkAccepted: Result<Unit>? = null
            session.submitChunk(chunk(102, 0, byteArrayOf(1, 2))) { chunkAccepted = it }
            sockets.open()
            sockets.server(ack)
            assertTrue(failures.isEmpty())
            assertTrue(ready)
            assertTrue(confirmed.isEmpty())
            assertTrue(checkNotNull(chunkAccepted).isSuccess)
            assertEquals(listOf("session.update", "input_audio_buffer.append"), sockets.sent.map(::type))
            var final: Result<String>? = null
            session.finish { final = it }
            sockets.server(delta("Vorläufig"))
            assertEquals(null, final)
            sockets.server(completed("Abgeschlossene Sprachnachricht"))
            assertEquals("Abgeschlossene Sprachnachricht", checkNotNull(final).getOrThrow())
            provider.close()
        }
    }

    @Test
    fun differentKnownDelayEchoReportsActualSettingWithoutBlockingAudio() {
        val sockets = FakeSocketFactory()
        val observed = mutableListOf<String>()
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onTranscriptionDelayConfirmed(recordingId: RecordingId, delay: SttTranscriptionDelay) {
                observed += "confirmed:${delay.wireValue}"
            }
            override fun onSessionReady(recordingId: RecordingId) { observed += "ready" }
            override fun onFailure(recordingId: RecordingId, code: String) { observed += "failure:$code" }
        })
        val session = provider.openSession(RecordingId(105), PCM_24K)
        var chunkAccepted: Result<Unit>? = null
        session.submitChunk(chunk(105, 0, byteArrayOf(1, 2))) { chunkAccepted = it }
        sockets.open()
        assertEquals("low", requestedDelay(sockets.sent.single()))
        sockets.ready(SttTranscriptionDelay.MINIMAL)
        assertEquals(listOf("confirmed:minimal", "ready"), observed)
        assertTrue(checkNotNull(chunkAccepted).isSuccess)
        assertEquals(listOf("session.update", "input_audio_buffer.append"), sockets.sent.map(::type))
        session.cancel()
        provider.close()
    }

    @Test
    fun createdOrPrematureUpdateDoesNotBypassAcceptedUpdateAndApiErrorsStillFail() {
        val sockets = FakeSocketFactory()
        val failures = mutableListOf<String>()
        var ready = false
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onSessionReady(recordingId: RecordingId) { ready = true }
            override fun onFailure(recordingId: RecordingId, code: String) { failures += code }
        })
        val session = provider.openSession(RecordingId(106), PCM_24K)
        var accepted: Result<Unit>? = null
        session.submitChunk(chunk(106, 0, byteArrayOf(1, 2))) { accepted = it }
        sockets.server("""{"type":"session.updated"}""")
        assertFalse(ready)
        sockets.open()
        sockets.server("""{"type":"session.created"}""")
        assertFalse(ready)
        assertEquals(null, accepted)
        assertEquals(listOf("session.update"), sockets.sent.map(::type))
        sockets.server("""{"type":"error","error":{"code":"invalid_request_error"}}""")
        sockets.server("""{"type":"session.updated"}""")
        assertFalse(ready)
        assertTrue(checkNotNull(accepted).isFailure)
        assertEquals(listOf("realtime_stt_provider_invalid_request_error"), failures)
        provider.close()
    }

    @Test
    fun parserUnderstandsGaAndLegacyDelayAcknowledgments() {
        for (delay in SttTranscriptionDelay.entries) {
            for (type in listOf("session.updated", "transcription_session.updated")) {
                assertEquals(
                    RealtimeTranscriptionEvent.SessionReady(delay),
                    OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                        """{"type":"$type","session":{"input_audio_transcription":{"delay":"${delay.wireValue}"}}}""",
                    ),
                )
            }
            assertEquals(
                RealtimeTranscriptionEvent.SessionReady(delay),
                OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                    """{"type":"session.updated","session":{"audio":{"input":{"transcription":{"delay":"${delay.wireValue}"}}}}}""",
                ),
            )
        }
    }

    @Test
    fun provisionalTextIsCumulativeDeduplicatedAndNeverCompletesBeforeCommit() {
        val sockets = FakeSocketFactory()
        val partials = mutableListOf<String>()
        var completions = 0
        val provider = provider(
            sockets,
            observer = object : RealtimeTranscriptionObserver {
                override fun onPartialTranscript(recordingId: RecordingId, transcript: String) {
                    assertEquals(RecordingId(103), recordingId)
                    partials += transcript
                }
                override fun onCompleted(recordingId: RecordingId) { completions += 1 }
            },
        )
        val session = provider.openSession(RecordingId(103), PCM_24K)
        sockets.server(delta("before ready"))
        assertTrue(partials.isEmpty())
        sockets.open()
        sockets.ready()
        sockets.server(delta("Hans", eventId = "one"))
        sockets.server(delta("Hans", eventId = "one"))
        sockets.server(delta(" ", eventId = "space"))
        sockets.server(delta("Hans", eventId = "two"))
        assertEquals(listOf("Hans", "Hans Hans"), partials)
        sockets.server(completed("premature final"))
        assertEquals(0, completions)
        var final: Result<String>? = null
        session.finish { final = it }
        assertEquals(null, final)
        sockets.server(completed("  Hannes, Hans.  "))
        assertEquals("Hannes, Hans.", checkNotNull(final).getOrThrow())
        assertEquals(1, completions)
        sockets.server(delta("late text"))
        assertEquals(listOf("Hans", "Hans Hans"), partials)
        provider.close()
    }

    @Test
    fun partialTailIsBoundedWhileCompletedServerTextRemainsAuthoritative() {
        val sockets = FakeSocketFactory()
        val partials = mutableListOf<String>()
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onPartialTranscript(recordingId: RecordingId, transcript: String) {
                partials += transcript
            }
        })
        val session = provider.openSession(RecordingId(104), PCM_24K)
        sockets.open()
        sockets.ready()
        sockets.server(delta("a".repeat(4_000)))
        sockets.server(delta("b"))
        assertEquals(4_000, partials.first().length)
        assertEquals("a".repeat(3_999) + "b", partials.last())
        sockets.server(delta("\uD83D\uDE00" + "c".repeat(3_999)))
        assertEquals("c".repeat(3_999), partials.last())
        var final: Result<String>? = null
        session.finish { final = it }
        val fullFinal = "corrected ".repeat(1_000).trim()
        sockets.server(completed(fullFinal))
        assertEquals(fullFinal, checkNotNull(final).getOrThrow())
        provider.close()
    }

    @Test
    fun cancelledSessionIgnoresLaterPartialAndFinalEvents() {
        val sockets = FakeSocketFactory()
        val partials = mutableListOf<String>()
        var completedCount = 0
        var recovered = false
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onPartialTranscript(recordingId: RecordingId, transcript: String) { partials += transcript }
            override fun onCompleted(recordingId: RecordingId) { completedCount += 1 }
            override fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) { recovered = true }
        })
        val session = provider.openSession(RecordingId(105), PCM_24K)
        sockets.open()
        sockets.ready()
        sockets.server(delta("draft"))
        var final: Result<String>? = null
        session.finish { final = it }
        session.cancel()
        sockets.server(delta("late"))
        sockets.server(completed("late completed"))
        assertEquals(listOf("draft"), partials)
        assertTrue(checkNotNull(final).isFailure)
        assertEquals(0, completedCount)
        assertFalse(recovered)
        provider.close()
    }

    @Test
    fun finalAndPartialEventsFromOtherItemsCannotOverrideTheRecording() {
        val sockets = FakeSocketFactory()
        val partials = mutableListOf<String>()
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onPartialTranscript(recordingId: RecordingId, transcript: String) { partials += transcript }
        })
        val session = provider.openSession(RecordingId(106), PCM_24K)
        sockets.open()
        sockets.ready()
        sockets.server(delta("right", itemId = "item_one"))
        sockets.server(delta("wrong", itemId = "item_other"))
        var final: Result<String>? = null
        session.finish { final = it }
        sockets.server(completed("wrong", "item_other"))
        assertEquals(null, final)
        sockets.server(completed("right final", "item_one"))
        assertEquals("right final", checkNotNull(final).getOrThrow())
        assertEquals(listOf("right"), partials)
        provider.close()
    }

    @Test
    fun emptyFinalNeverPromotesPartialTextIntoConversation() {
        val sockets = FakeSocketFactory()
        val recovered = mutableListOf<String>()
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) { recovered += transcript }
        })
        val session = provider.openSession(RecordingId(107), PCM_24K)
        sockets.open()
        sockets.ready()
        sockets.server(delta("uncertain provisional words"))
        var final: Result<String>? = null
        session.finish { final = it }
        sockets.server(completed(" "))
        assertTrue(checkNotNull(final).isFailure)
        assertEquals("realtime_stt_empty_transcript", final?.exceptionOrNull()?.message)
        assertEquals(listOf("uncertain provisional words"), recovered)
        provider.close()
    }

    @Test
    fun cancellationInsidePartialObserverDoesNotEmitLaterProgressOrFinal() {
        val sockets = FakeSocketFactory()
        val progress = mutableListOf<RecordingProgress>()
        lateinit var session: IncrementalSttSession
        var callbacks = 0
        val provider = provider(sockets, observer = object : RealtimeTranscriptionObserver {
            override fun onPartialTranscript(recordingId: RecordingId, transcript: String) {
                callbacks += 1
                session.cancel()
            }
        })
        session = provider.openSession(RecordingId(108), PCM_24K, RecordingProgressListener { progress += it })
        sockets.open()
        sockets.ready()
        sockets.server(delta("cancel here"))
        sockets.server(delta("must not escape"))
        assertEquals(1, callbacks)
        assertEquals(listOf(RecordingProgress.TRANSCRIPTION_SESSION_READY), progress)
        provider.close()
    }

    @Test
    fun deltaParserCapsTextAndIdentifierRetentionAndRejectsOversizedEvents() {
        val event = OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
            delta("a".repeat(40_000), eventId = "id".repeat(200)),
        ) as RealtimeTranscriptionEvent.Delta
        assertEquals(32 * 1_024, event.text.length)
        assertEquals(null, event.eventId)
        assertEquals(
            RealtimeTranscriptionEvent.Ignored,
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(delta("a".repeat(256 * 1_024))),
        )
    }

    @Test
    fun networkLossPreservesRecognizedPartialTextOnceButNeverCompletesOrSendsIt() {
        val sockets = FakeSocketFactory()
        val recovered = mutableListOf<String>()
        val provider = provider(
            sockets,
            observer = object : RealtimeTranscriptionObserver {
                override fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) {
                    recovered += transcript
                }
            },
        )
        val session = provider.openSession(RecordingId(8), PCM_24K)
        sockets.open()
        sockets.ready()
        sockets.server("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Morgen um"}""")
        var completion: Result<String>? = null
        session.finish { completion = it }
        provider.onNetworkUnavailable()
        sockets.fail()
        assertEquals(listOf("Morgen um"), recovered)
        assertTrue(checkNotNull(completion).isFailure)
        assertEquals(true, sockets.cancelled)
        provider.close()
    }

    @Test
    fun failedConnectionBeforeAnyWordsNeverInventsRecoverableTranscript() {
        val sockets = FakeSocketFactory()
        val recovered = mutableListOf<String>()
        val provider = provider(
            sockets,
            observer = object : RealtimeTranscriptionObserver {
                override fun onInterruptedTranscript(recordingId: RecordingId, transcript: String) {
                    recovered += transcript
                }
            },
        )
        provider.openSession(RecordingId(9), PCM_24K)
        provider.onNetworkUnavailable()
        assertTrue(recovered.isEmpty())
        provider.close()
    }

    @Test
    fun endpointSelectsTranscriptionIntentWithoutConversationModelQuery() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?intent=transcription",
            OpenAiRealtimeTranscriptionProtocol.ENDPOINT,
        )
        assertFalse(OpenAiRealtimeTranscriptionProtocol.ENDPOINT.contains("model="))
    }

    @Test
    fun sessionUpdateMatchesOfficialRealtimeTranscriptionShape() {
        val root = JSONObject(OpenAiRealtimeTranscriptionProtocol.sessionUpdate())
        assertEquals("session.update", root.getString("type"))
        val session = root.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        assertEquals(
            "gpt-live-transcribe",
            input.getJSONObject("transcription").getString("model"),
        )
        assertFalse(input.getJSONObject("transcription").has("prompt"))
        assertEquals("low", input.getJSONObject("transcription").getString("delay"))
        assertFalse(input.getJSONObject("transcription").has("language"))
        assertTrue(input.isNull("turn_detection"))
    }

    @Test
    fun contextIsSnapshottedAtRecordingBoundaryAndNeverForcesOneLanguage() {
        val sockets = FakeSocketFactory()
        var snapshot = SttTranscriptionContext(
            confirmedProfileSummary = "Spricht Deutsch and English.",
            confirmedGlossaryTerms = listOf("Grenzebach"),
        )
        var reads = 0
        val provider = provider(
            sockets,
            contextSource = SttTranscriptionContextSource {
                reads += 1
                snapshot
            },
        )

        val first = provider.openSession(RecordingId(20), PCM_24K)
        snapshot = SttTranscriptionContext(confirmedGlossaryTerms = listOf("Gamsbart"))
        sockets.open()
        val firstTranscription = JSONObject(sockets.sent[0])
            .getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")
        assertTrue(firstTranscription.getString("prompt").contains("Grenzebach"))
        assertFalse(firstTranscription.getString("prompt").contains("Gamsbart"))
        assertFalse(firstTranscription.has("language"))
        first.cancel()

        provider.openSession(RecordingId(21), PCM_24K)
        sockets.open()
        val secondTranscription = JSONObject(sockets.sent[1])
            .getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")
        assertTrue(secondTranscription.getString("prompt").contains("Gamsbart"))
        assertFalse(secondTranscription.getString("prompt").contains("Grenzebach"))
        assertEquals(2, reads)
        provider.close()
    }

    @Test
    fun appendEncodesPcmWithoutLoggingOrMutation() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val root = JSONObject(OpenAiRealtimeTranscriptionProtocol.audioAppend(pcm))
        assertEquals("input_audio_buffer.append", root.getString("type"))
        assertEquals(
            pcm.toList(),
            Base64.getDecoder().decode(root.getString("audio")).toList(),
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), pcm.toList())
    }

    @Test
    fun eventParserBoundsAndClassifiesOnlyKnownEvents() {
        assertEquals(
            RealtimeTranscriptionEvent.SessionReady(null),
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                """{"type":"session.updated"}""",
            ),
        )
        assertEquals(
            RealtimeTranscriptionEvent.Delta("Hal"),
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","delta":"Hal"}""",
            ),
        )
        assertEquals(
            RealtimeTranscriptionEvent.Completed("Hallo"),
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hallo"}""",
            ),
        )
        assertEquals(
            RealtimeTranscriptionEvent.Error("invalid_audio"),
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                """{"type":"error","error":{"code":"invalid_audio"}}""",
            ),
        )
        assertEquals(
            RealtimeTranscriptionEvent.Error("audio_unintelligible", "transcription_error"),
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.failed","error":{"type":"transcription_error","code":"audio_unintelligible","message":"must not escape"}}""",
            ),
        )
        assertEquals(
            RealtimeTranscriptionEvent.Ignored,
            OpenAiRealtimeTranscriptionProtocol.parseServerEvent("not-json"),
        )
    }

    @Test
    fun providerStreamsAudioButReleasesOnlyCompletedTranscript() {
        val socketFactory = FakeSocketFactory()
        val provider = provider(socketFactory)
        val progress = mutableListOf<RecordingProgress>()
        val session = provider.openSession(
            RecordingId(1),
            PCM_24K,
            RecordingProgressListener { progress += it },
        )
        socketFactory.open()

        assertEquals("session.update", type(socketFactory.sent.single()))
        socketFactory.ready()
        assertEquals(listOf(RecordingProgress.TRANSCRIPTION_SESSION_READY), progress)

        var firstAccepted: Result<Unit>? = null
        var finalMarkerAccepted: Result<Unit>? = null
        session.submitChunk(chunk(recordingId = 1, index = 0, bytes = byteArrayOf(1, 2))) {
            firstAccepted = it
        }
        session.submitChunk(
            chunk(recordingId = 1, index = 1, bytes = byteArrayOf(), final = true),
        ) {
            finalMarkerAccepted = it
        }
        assertTrue(checkNotNull(firstAccepted).isSuccess)
        assertTrue(checkNotNull(finalMarkerAccepted).isSuccess)
        assertEquals("input_audio_buffer.append", type(socketFactory.sent[1]))

        var completed: Result<String>? = null
        session.finish { completed = it }
        assertEquals("input_audio_buffer.commit", type(socketFactory.sent[2]))
        socketFactory.server(
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"Hal"}""",
        )
        assertEquals(
            listOf(
                RecordingProgress.TRANSCRIPTION_SESSION_READY,
                RecordingProgress.TRANSCRIPT_DELTA,
            ),
            progress,
        )
        assertEquals(null, completed)
        socketFactory.server(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hallo Welt"}""",
        )
        assertEquals("Hallo Welt", checkNotNull(completed).getOrThrow())
        assertEquals(1_000, socketFactory.closedCode)
        provider.close()
    }

    @Test
    fun audioWaitsForConfirmedSessionAndPendingBytesAreBounded() {
        val socketFactory = FakeSocketFactory()
        val provider = provider(
            socketFactory = socketFactory,
            maximumPendingAudioBytes = 48_000,
        )
        val session = provider.openSession(RecordingId(2), PCM_24K)
        var accepted: Result<Unit>? = null
        session.submitChunk(chunk(2, 0, ByteArray(30_000))) { accepted = it }
        assertEquals(null, accepted)

        var rejected: Result<Unit>? = null
        session.submitChunk(chunk(2, 1, ByteArray(20_000))) { rejected = it }
        assertTrue(checkNotNull(rejected).isFailure)

        socketFactory.open()
        socketFactory.ready()
        assertTrue(checkNotNull(accepted).isSuccess)
        provider.close()
    }

    @Test
    fun serverFailureFailsPendingChunkAndFinishOnce() {
        val socketFactory = FakeSocketFactory()
        val provider = provider(socketFactory)
        val session = provider.openSession(RecordingId(3), PCM_24K)
        var accepted: Result<Unit>? = null
        session.submitChunk(chunk(3, 0, byteArrayOf(1, 2))) { accepted = it }
        var completion: Result<String>? = null
        session.finish { completion = it }

        socketFactory.fail()
        assertTrue(checkNotNull(accepted).isFailure)
        assertTrue(checkNotNull(completion).isFailure)
        assertNotNull(socketFactory.cancelled)
        provider.close()
    }

    @Test
    fun transcriptionFailedEventTerminatesImmediatelyWithStableProviderCode() {
        val socketFactory = FakeSocketFactory()
        val failures = mutableListOf<String>()
        val provider = provider(
            socketFactory,
            observer = object : RealtimeTranscriptionObserver {
                override fun onFailure(recordingId: RecordingId, code: String) {
                    failures += code
                }
            },
        )
        val session = provider.openSession(RecordingId(31), PCM_24K)
        socketFactory.open()
        socketFactory.ready()
        var completion: Result<String>? = null
        session.finish { completion = it }

        socketFactory.server(
            """{"type":"conversation.item.input_audio_transcription.failed","error":{"type":"transcription_error","code":"audio_unintelligible","message":"must not escape"}}""",
        )

        assertTrue(checkNotNull(completion).isFailure)
        assertEquals(listOf("realtime_stt_provider_audio_unintelligible"), failures)
        assertTrue(socketFactory.cancelled == true)
        provider.close()
    }

    @Test
    fun incompatibleAudioFormatFailsBeforeOpeningSocket() {
        val socketFactory = FakeSocketFactory()
        val provider = provider(socketFactory)
        val result = runCatching {
            provider.openSession(RecordingId(4), PcmAudioFormat(sampleRateHz = 16_000))
        }
        assertTrue(result.isFailure)
        assertFalse(socketFactory.opened)
        provider.close()
    }

    private fun provider(
        socketFactory: FakeSocketFactory,
        maximumPendingAudioBytes: Int = 8 * 1_024 * 1_024,
        observer: RealtimeTranscriptionObserver = RealtimeTranscriptionObserver.NONE,
        contextSource: SttTranscriptionContextSource = SttTranscriptionContextSource.EMPTY,
        delaySource: () -> SttTranscriptionDelay = { SttTranscriptionDelay.LOW },
    ): OpenAiRealtimeTranscriptionProvider = OpenAiRealtimeTranscriptionProvider(
        tokenSource = { "sk-test-not-a-real-credential" },
        socketFactory = socketFactory,
        config = OpenAiRealtimeTranscriptionConfig(
            maximumPendingAudioBytes = maximumPendingAudioBytes,
        ),
        deadlineScheduler = SttDeadlineScheduler { _, _ -> SttDeadline {} },
        observer = observer,
        contextSource = contextSource,
        delaySource = delaySource,
    )

    private fun chunk(
        recordingId: Long,
        index: Long,
        bytes: ByteArray,
        final: Boolean = false,
    ): PcmAudioChunk = PcmAudioChunk.create(
        recordingId = RecordingId(recordingId),
        index = index,
        pcmBytes = bytes,
        isFinal = final,
        capturedAtMillis = index,
    )

    private fun type(payload: String): String = JSONObject(payload).getString("type")

    private fun requestedDelay(payload: String): String = JSONObject(payload)
        .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        .getJSONObject("transcription").getString("delay")

    private fun delta(text: String, eventId: String? = null, itemId: String? = null): String = JSONObject()
        .put("type", "conversation.item.input_audio_transcription.delta")
        .put("delta", text)
        .apply { eventId?.let { put("event_id", it) }; itemId?.let { put("item_id", it) } }
        .toString()

    private fun completed(text: String, itemId: String? = null): String = JSONObject()
        .put("type", "conversation.item.input_audio_transcription.completed")
        .put("transcript", text)
        .apply { itemId?.let { put("item_id", it) } }
        .toString()

    private class FakeSocketFactory : RealtimeTranscriptionSocketFactory {
        lateinit var listener: RealtimeTranscriptionSocketListener
        val sent = mutableListOf<String>()
        var opened = false
        var closedCode: Int? = null
        var cancelled: Boolean? = null

        override fun open(
            bearerToken: String,
            listener: RealtimeTranscriptionSocketListener,
        ): RealtimeTranscriptionSocket {
            assertFalse(bearerToken.contains("Bearer"))
            this.listener = listener
            return object : RealtimeTranscriptionSocket {
                override fun send(text: String): Boolean = sent.add(text).let { true }

                override fun queuedBytes(): Long = 0L

                override fun close(code: Int, reason: String): Boolean {
                    closedCode = code
                    return true
                }

                override fun cancel() {
                    cancelled = true
                }
            }
        }

        fun open() {
            opened = true
            listener.onOpen()
        }

        fun server(payload: String) = listener.onText(payload)

        fun ready(delay: SttTranscriptionDelay = SttTranscriptionDelay.LOW) = server(
            """{"type":"session.updated","session":{"audio":{"input":{"transcription":{"model":"gpt-live-transcribe","delay":"${delay.wireValue}"}}}}}""",
        )

        fun fail() = listener.onFailure()
    }

    private companion object {
        val PCM_24K = PcmAudioFormat(sampleRateHz = 24_000)
    }
}
