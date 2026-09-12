package ai.hans.standard.voice.realtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceSessionTest {
    @Test
    fun callMuteChangesOnlyLocalTrackAndPublishesObservableState() {
        val transport = FakeTransport()
        val snapshots = Collections.synchronizedList(mutableListOf<LiveVoiceSnapshot>())
        val session = readySession(transport, FakeTaskExecutor(), object : LiveVoiceObserver {
            override fun onSnapshot(snapshot: LiveVoiceSnapshot) { snapshots += snapshot }
        })
        try {
            assertTrue(session.setInputMuted(true))
            await { session.snapshot.inputMuted }
            assertTrue(transport.userInputMuted)
            assertTrue(transport.inputAudioEnabled)
            assertTrue(snapshots.any { it.inputMuted })

            assertTrue(session.setInputMuted(false))
            await { !session.snapshot.inputMuted }
            assertFalse(transport.userInputMuted)
        } finally {
            session.close()
        }
    }

    @Test
    fun explicitFarewellToolWaitsForResponseAndPhysicalAudioDrainBeforeStopping() {
        val transport = FakeTransport()
        val session = readySession(transport, FakeTaskExecutor())
        try {
            transport.emit(userSpeechStarted("farewell-user"))
            transport.emit(finalUserTranscript("farewell-user", "Alles klar, bis zum nächsten Mal."))
            transport.emit(responseCreated("farewell-response"))
            transport.emit(outputAudioStarted("farewell-response"))
            transport.emit(endCall("farewell-call", "farewell-response"))
            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))

            transport.emit(responseDone("farewell-response"))
            Thread.sleep(50)
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)

            transport.emit(outputAudioStopped("farewell-response"))
            await { session.snapshot.phase == LiveVoicePhase.STOPPED }
        } finally {
            session.close()
        }
    }

    @Test
    fun toolBeforeFinalTranscriptAuthorizesOnlyThatTurnAndPreservesBufferedDrainEvents() {
        val transport = FakeTransport()
        val session = readySession(transport, FakeTaskExecutor())
        try {
            transport.emit(userSpeechStarted("late-transcript-user"))
            transport.emit(responseCreated("late-transcript-response"))
            transport.emit(outputAudioStarted("late-transcript-response"))
            transport.emit(endCall("late-transcript-call", "late-transcript-response"))
            Thread.sleep(100)
            assertEquals(1L, transport.functionOutputSent.count)

            transport.emit(responseDone("late-transcript-response"))
            transport.emit(outputAudioStopped("late-transcript-response"))
            transport.emit(finalUserTranscript("late-transcript-user", "Bis zum nächsten Mal."))

            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))
            assertEquals("completed", transport.latestFunctionOutputStatus())
            await { session.snapshot.phase == LiveVoicePhase.STOPPED }
        } finally {
            session.close()
        }
    }

    @Test
    fun delayedToolCannotReuseOlderFarewellWhenCurrentTranscriptDoesNotMatch() {
        val transport = FakeTransport()
        val session = readySession(transport, FakeTaskExecutor())
        try {
            transport.emit(userSpeechStarted("old-farewell-user"))
            transport.emit(finalUserTranscript("old-farewell-user", "Tschüss."))

            transport.emit(userSpeechStarted("current-user"))
            transport.emit(responseCreated("mismatch-response"))
            transport.emit(endCall("mismatch-call", "mismatch-response"))
            Thread.sleep(100)
            assertEquals(1L, transport.functionOutputSent.count)

            transport.emit(finalUserTranscript("current-user", "Nein, mach bitte weiter."))
            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))
            assertEquals("failed", transport.latestFunctionOutputStatus())
            assertFalse(session.snapshot.phase == LiveVoicePhase.STOPPED)
        } finally {
            session.close()
        }
    }

    @Test
    fun missingFinalTranscriptRejectsDeferredHangupAfterBoundedTimeout() {
        val transport = FakeTransport()
        val session = readySession(
            transport,
            FakeTaskExecutor(),
            config = LiveVoiceSessionConfig(
                progressAnnouncementDelayMillis = 0,
                progressAnnouncementIntervalMillis = 1_000,
                modelHangupTranscriptWaitMillis = 500,
            ),
        )
        try {
            transport.emit(userSpeechStarted("timeout-user"))
            transport.emit(responseCreated("timeout-response"))
            transport.emit(endCall("timeout-call", "timeout-response"))

            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))
            assertEquals("failed", transport.latestFunctionOutputStatus())
            assertFalse(session.snapshot.phase == LiveVoicePhase.STOPPED)
        } finally {
            session.close()
        }
    }

    @Test
    fun lostPhysicalAudioDrainEventUsesBoundedConservativeHangupFallback() {
        val transport = FakeTransport()
        val session = readySession(
            transport,
            FakeTaskExecutor(),
            config = LiveVoiceSessionConfig(
                progressAnnouncementDelayMillis = 0,
                progressAnnouncementIntervalMillis = 1_000,
                modelHangupAudioDrainTimeoutMillis = 1_000,
            ),
        )
        try {
            transport.emit(userSpeechStarted("lost-drain-user"))
            transport.emit(finalUserTranscript("lost-drain-user", "Tschüss."))
            transport.emit(responseCreated("lost-drain-response"))
            transport.emit(outputAudioStarted("lost-drain-response"))
            transport.emit(endCall("lost-drain-call", "lost-drain-response"))
            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))

            transport.emit(responseDone("lost-drain-response"))
            Thread.sleep(200)
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            await { session.snapshot.phase == LiveVoicePhase.STOPPED }
            await { transport.closeCount == 1 }
        } finally {
            session.close()
        }
    }

    @Test
    fun voiceSettingIsResolvedFreshForEveryNewCallButNeverMutatedMidCall() {
        val firstTransport = FakeTransport()
        val secondTransport = FakeTransport()
        val transports = listOf(firstTransport, secondTransport)
        val transportIndex = AtomicInteger(0)
        val requestedVoice = AtomicReference("fable")
        val credentialConfigs = Collections.synchronizedList(
            mutableListOf<LiveVoiceSessionConfig>(),
        )
        val credentialProvider = object : LiveVoiceCredentialProvider {
            override fun request(
                session: LiveVoiceSessionConfig,
                callback: LiveVoiceCredentialProvider.Callback,
            ): LiveVoiceCancellation {
                credentialConfigs += session
                callback.onCredential(RealtimeEphemeralCredential.of("ek_test_1234567890"))
                return LiveVoiceCancellation.NONE
            }
        }
        val session = LiveVoiceSession(
            credentialProvider = credentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = { "Du bist Hans." },
            voiceSelectionProvider = LiveVoiceVoiceSelectionProvider {
                LiveVoiceRealtimeVoiceMapper.resolve(requestedVoice.get())
            },
        )
        try {
            session.start()
            assertTrue(firstTransport.configured.await(3, TimeUnit.SECONDS))
            assertEquals("ballad", credentialConfigs.single().voice)
            assertEquals("ballad", firstTransport.latestSessionVoice())
            assertEquals(
                LiveVoiceVoiceSelection(
                    requestedTtsVoice = "fable",
                    effectiveRealtimeVoice = "ballad",
                    resolution = LiveVoiceVoiceResolution.APPROXIMATE,
                ),
                session.snapshot.voiceSelection,
            )

            requestedVoice.set("onyx")
            session.refreshContext()
            Thread.sleep(50)
            assertEquals("ballad", firstTransport.latestSessionVoice())

            session.stop()
            await { session.snapshot.phase == LiveVoicePhase.STOPPED }
            session.start()
            assertTrue(secondTransport.configured.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("ballad", "cedar"), credentialConfigs.map { it.voice })
            assertEquals("cedar", secondTransport.latestSessionVoice())
            assertEquals("onyx", session.snapshot.voiceSelection?.requestedTtsVoice)
            assertEquals(
                LiveVoiceVoiceResolution.APPROXIMATE,
                session.snapshot.voiceSelection?.resolution,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun modelCannotHangUpForQuotedFarewellOrThanksAlone() {
        listOf("Sag ihm bitte tschüss.", "Danke!").forEachIndexed { index, utterance ->
            val transport = FakeTransport()
            val session = readySession(transport, FakeTaskExecutor())
            try {
                val responseId = "rejected-response-$index"
                transport.emit(userSpeechStarted("rejected-user-$index"))
                transport.emit(finalUserTranscript("rejected-user-$index", utterance))
                transport.emit(responseCreated(responseId))
                transport.emit(outputAudioStarted(responseId))
                transport.emit(endCall("rejected-call-$index", responseId))
                assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))
                assertEquals("failed", transport.latestFunctionOutputStatus())
                transport.emit(responseDone(responseId))
                transport.emit(outputAudioStopped(responseId))
                await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun firstCorrelatedAssistantTextEmitsReadyOnceButResponseCreatedDoesNot() {
        val transport = FakeTransport()
        val readyEvents = Collections.synchronizedList(mutableListOf<LiveVoiceResponseReady>())
        val transcripts = AtomicInteger()
        val session = readySession(transport, FakeTaskExecutor(), object : LiveVoiceObserver {
            override fun onHansResponseReady(event: LiveVoiceResponseReady) { readyEvents += event }
            override fun onHansTranscript(text: String, isFinal: Boolean) { transcripts.incrementAndGet() }
        })
        try {
            transport.emit(responseCreated("response-one"))
            await { session.snapshot.phase == LiveVoicePhase.HANS_SPEAKING }
            assertTrue(readyEvents.isEmpty())
            transport.emit(hansTranscript("Uncorrelated"))
            transport.emit(hansTranscript("Other response", responseId = "response-other"))
            await { transcripts.get() == 2 }
            assertTrue(readyEvents.isEmpty())
            transport.emit(hansTranscript("Hallo", responseId = "response-one"))
            transport.emit(hansTranscript("noch ein Satz", responseId = "response-one"))
            transport.emit(hansTranscript("Hallo, noch ein Satz.", responseId = "response-one", isFinal = true))
            await { transcripts.get() == 5 }
            assertEquals(1, readyEvents.size)
            assertEquals("response-one", readyEvents.single().responseId)
            assertEquals(session.snapshot.generation, readyEvents.single().generation)
            transport.emit(responseDone("response-one"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            transport.emit(responseCreated("response-two"))
            transport.emit(hansTranscript("Nur die Endantwort.", responseId = "response-two", isFinal = true))
            await { readyEvents.size == 2 }
            assertEquals(readyEvents.first().sessionInstanceId, readyEvents.last().sessionInstanceId)
        } finally {
            session.close()
        }
    }

    @Test
    fun explicitLiveInterruptionBeforeFirstTextSuppressesLateFeedbackButNotTranscriptDelivery() {
        val transport = FakeTransport()
        val readyEvents = Collections.synchronizedList(mutableListOf<LiveVoiceResponseReady>())
        val lateTranscript = CountDownLatch(1)
        val session = readySession(transport, FakeTaskExecutor(), object : LiveVoiceObserver {
            override fun onHansResponseReady(event: LiveVoiceResponseReady) { readyEvents += event }
            override fun onHansTranscript(text: String, isFinal: Boolean) { lateTranscript.countDown() }
        })
        try {
            transport.emit(responseCreated("cancelled"))
            await { session.snapshot.phase == LiveVoicePhase.HANS_SPEAKING }
            session.interruptHans()
            assertTrue(transport.explicitClear.await(3, TimeUnit.SECONDS))
            transport.emit(hansTranscript("Spät eingetroffen.", responseId = "cancelled"))
            assertTrue(lateTranscript.await(3, TimeUnit.SECONDS))
            assertTrue(readyEvents.isEmpty())
            transport.emit(responseDone("cancelled", "cancelled"))
            transport.emit(responseCreated("new"))
            transport.emit(hansTranscript("Neue Antwort.", responseId = "new"))
            await { readyEvents.size == 1 }
            assertEquals("new", readyEvents.single().responseId)
        } finally {
            session.close()
        }
    }

    @Test
    fun responseReadyObserverFailureCannotBreakLiveTranscriptOrMediaState() {
        val transport = FakeTransport()
        val transcript = CountDownLatch(1)
        val session = readySession(transport, FakeTaskExecutor(), object : LiveVoiceObserver {
            override fun onHansResponseReady(event: LiveVoiceResponseReady) = error("optional observer failed")
            override fun onHansTranscript(text: String, isFinal: Boolean) { transcript.countDown() }
        })
        try {
            transport.emit(responseCreated("one"))
            transport.emit(hansTranscript("Hallo.", responseId = "one"))
            assertTrue(transcript.await(3, TimeUnit.SECONDS))
            assertEquals(LiveVoicePhase.HANS_SPEAKING, session.snapshot.phase)
        } finally {
            session.close()
        }
    }

    @Test
    fun dynamicContextRefreshDeduplicatesButNotAcrossCodexThreadSwitches() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext(
            instructions = "Du bist Hans. Gleicher sichtbarer Auszug.",
            contextIdentity = "thread-one",
        )
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            assertEquals(1, transport.sessionUpdateCount())

            session.refreshContext()
            Thread.sleep(50)
            assertEquals(1, transport.sessionUpdateCount())

            context = context.copy(contextIdentity = "thread-two")
            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
        } finally {
            session.close()
        }
    }

    @Test
    fun activeSetupRouteIsRequiredOnceThenResetUntilNextUserTurn() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        var context = LiveVoiceSessionContext(
            instructions = "Du bist Hans.",
            taskRouting = LiveVoiceTaskRouting.AUTO,
            contextIdentity = "thread-one|setup-0",
        )
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = executor,
            instructionsProvider = provider,
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            context = context.copy(
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
                contextIdentity = "thread-one|setup-28",
            )

            session.refreshContext()
            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }
            assertEquals("required", transport.latestSessionToolChoice())
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)

            transport.emit(userSpeechStarted("setup-user-1"))
            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            assertEquals(2, transport.sessionUpdateCount())
            transport.emit(functionCall("setup-call-1", "Ja, lass uns anfangen."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(2, transport.sessionUpdateCount())
            assertEquals("required", transport.latestSessionToolChoice())
            assertEquals(1, executor.executeCount.get())

            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())
            transport.emit(userSpeechStarted("setup-user-2"))
            Thread.sleep(50)
            assertEquals(2, transport.sessionUpdateCount())
            assertEquals("required", transport.latestSessionToolChoice())
        } finally {
            session.close()
        }
    }

    @Test
    fun requiredSetupRefreshKeepsInputLiveWhileServerAcknowledgesRouting() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext(
            instructions = "Du bist Hans.",
            taskRouting = LiveVoiceTaskRouting.AUTO,
            contextIdentity = "thread-one|setup-0",
        )
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            context = context.copy(
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
                contextIdentity = "thread-one|setup-1",
            )

            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }
            assertTrue(transport.inputAudioEnabled)
            assertTrue(transport.inputAudioEnabledChanges.isEmpty())

            transport.ackSessionUpdate()
            assertTrue(transport.inputAudioEnabled)
            assertTrue(transport.inputAudioEnabledChanges.isEmpty())

            transport.emit(userSpeechStarted("after-routing-ack"))
            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            assertEquals("required", transport.latestSessionToolChoice())
        } finally {
            session.close()
        }
    }

    @Test
    fun sameRouteContextRefreshNeverCutsMicrophoneInput() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            context = LiveVoiceSessionContext("Updated profile context.", contextIdentity = "one")

            session.refreshContext()

            await { transport.sessionUpdateCount() == 2 }
            assertTrue(transport.inputAudioEnabled)
            assertTrue(transport.inputAudioEnabledChanges.isEmpty())
            assertEquals("auto", transport.latestSessionToolChoice())
            transport.ackSessionUpdate()
            assertTrue(transport.inputAudioEnabled)
        } finally {
            session.close()
        }
    }

    @Test
    fun lateVadDuringContextRefreshKeepsUtteranceAndCreatesResponseOnlyAfterAck() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext(
            instructions = "Du bist Hans.",
            taskRouting = LiveVoiceTaskRouting.AUTO,
            contextIdentity = "thread-one|setup-0",
        )
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = LiveVoiceSessionConfig(
                reconnectBaseDelayMillis = 60_000,
                reconnectMaximumDelayMillis = 60_000,
            ),
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            context = context.copy(
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
                contextIdentity = "thread-one|setup-1",
            )
            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }

            // The user's audio can cross the WebRTC boundary before the session.updated ACK.
            // It must stay live and be answered under the acknowledged REQUIRED context.
            transport.emit(userSpeechStarted("late-vad-during-refresh"))
            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())

            Thread.sleep(50)
            assertEquals(0, transport.responseCreateCount())
            assertTrue(transport.inputAudioEnabled)
            assertTrue(transport.inputAudioEnabledChanges.isEmpty())
            assertEquals(0, transport.closeCount)

            transport.ackSessionUpdate()

            await { transport.responseCreateCount() == 1 }
            assertEquals("required", transport.latestSessionToolChoice())
            assertFalse(transport.responseCreates().single().has("response"))
            assertEquals(0, transport.closeCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun ordinarySpeechStopCreatesExactlyOneClientInitiatedUserResponse() {
        val transport = FakeTransport()
        val session = readySession(transport, FakeTaskExecutor())
        try {
            transport.emit(userSpeechStarted("ordinary-user-turn"))
            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())

            await { transport.responseCreateCount() == 1 }
            val response = transport.responseCreates().single()
            assertFalse(response.has("response"))

            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())
            Thread.sleep(50)
            assertEquals(1, transport.responseCreateCount())
        } finally {
            session.close()
        }
    }

    @Test
    fun contextChangesDuringSpeechAreCoalescedUntilSpeechStopsWithoutReconnect() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = LiveVoiceSessionConfig(
                reconnectBaseDelayMillis = 60_000,
                reconnectMaximumDelayMillis = 60_000,
            ),
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            transport.emit(userSpeechStarted("already-speaking"))
            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            context = LiveVoiceSessionContext("Intermediate profile context.", contextIdentity = "one")
            session.refreshContext()
            context = LiveVoiceSessionContext("Latest setup context.", contextIdentity = "two")

            session.refreshContext()

            Thread.sleep(50)
            assertEquals(LiveVoicePhase.USER_SPEAKING, session.snapshot.phase)
            assertEquals(1, transport.sessionUpdateCount())
            assertTrue(transport.inputAudioEnabled)
            assertEquals(0, transport.closeCount)

            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())

            await { transport.sessionUpdateCount() == 2 }
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertTrue(transport.inputAudioEnabled)
            assertTrue(transport.latestSessionInstructions().contains("Latest setup context."))
            assertFalse(transport.latestSessionInstructions().contains("Intermediate profile context."))
            assertEquals(0, transport.closeCount)
            assertEquals(0, transport.responseCreateCount())
            transport.ackSessionUpdate()
            await { transport.responseCreateCount() == 1 }
            assertFalse(transport.responseCreates().single().has("response"))
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertEquals(0, transport.closeCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun activeSpeechReconnectClearsVadStateBeforeNextIdleContextRefresh() {
        val initial = FakeTransport()
        val replacement = FakeTransport()
        val transports = listOf(initial, replacement)
        val transportIndex = AtomicInteger(0)
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = reconnectingConfig(maximumReconnectAttempts = 2),
        )
        try {
            session.start()
            assertTrue(initial.configured.await(3, TimeUnit.SECONDS))
            initial.emit(userSpeechStarted("speech-on-old-transport"))
            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            initial.emitClosed(LiveVoiceFailure("network_lost", true))

            assertTrue(replacement.configured.await(3, TimeUnit.SECONDS))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertEquals(0, initial.closeCount)
            replacement.autoAcknowledgeSessionUpdates = false
            context = LiveVoiceSessionContext("Context two.", contextIdentity = "two")

            session.refreshContext()

            await { replacement.sessionUpdateCount() == 2 }
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertTrue(replacement.inputAudioEnabled)
            assertEquals(0, replacement.closeCount)
            replacement.ackSessionUpdate()
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertEquals(0, replacement.closeCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun contextChangedDuringInitialConfigurationRefreshesImmediatelyAfterInitialAck() {
        val transport = FakeTransport().apply {
            autoAcknowledgeSessionUpdates = false
        }
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
        )
        try {
            session.start()
            await { transport.sessionUpdateCount() == 1 }
            assertEquals(LiveVoicePhase.CONFIGURING, session.snapshot.phase)
            context = LiveVoiceSessionContext(
                instructions = "Setup started while configuring.",
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
                contextIdentity = "setup-one",
            )

            session.refreshContext()
            Thread.sleep(50)
            assertEquals(1, transport.sessionUpdateCount())
            transport.ackSessionUpdate()

            await { transport.sessionUpdateCount() == 2 }
            assertTrue(transport.latestSessionInstructions().contains("Setup started while configuring."))
            assertEquals("required", transport.latestSessionToolChoice())
            assertTrue(transport.inputAudioEnabled)
            transport.ackSessionUpdate()
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
        } finally {
            session.close()
        }
    }

    @Test
    fun refreshRequestedDuringReconnectIsReevaluatedAfterNewSessionConfiguration() {
        val initial = FakeTransport()
        val reconnected = FakeTransport()
        val transports = listOf(initial, reconnected)
        val transportIndex = AtomicInteger(0)
        val contextBuilds = AtomicInteger(0)
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext {
                contextBuilds.incrementAndGet()
                return context
            }
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = reconnectingConfig(maximumReconnectAttempts = 2),
        )
        try {
            session.start()
            assertTrue(initial.configured.await(3, TimeUnit.SECONDS))
            initial.emitClosed(LiveVoiceFailure("network_lost", true))
            await { session.snapshot.phase == LiveVoicePhase.RECONNECTING }
            context = LiveVoiceSessionContext("Context during reconnect.", contextIdentity = "one")

            session.refreshContext()

            assertTrue(reconnected.configured.await(3, TimeUnit.SECONDS))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertTrue(reconnected.latestSessionInstructions().contains("Context during reconnect."))
            // The reconnect configuration itself may already observe the refreshed context.
            // Requiring a redundant third provider read races the asynchronous refresh request;
            // the functional assertion above is the contract that matters.
            assertTrue(contextBuilds.get() >= 2)
        } finally {
            session.close()
        }
    }

    @Test
    fun routingChangeDiscoveredAtSpeechStartWaitsForSpeechStop() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext(
            instructions = "Du bist Hans.",
            taskRouting = LiveVoiceTaskRouting.AUTO,
            contextIdentity = "thread-one|setup-0",
        )
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = LiveVoiceSessionConfig(
                reconnectBaseDelayMillis = 60_000,
                reconnectMaximumDelayMillis = 60_000,
            ),
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            context = context.copy(
                taskRouting = LiveVoiceTaskRouting.REQUIRED,
                contextIdentity = "thread-one|setup-1",
            )

            // Even if the proactive setup refresh was missed, speech-start detects the changed
            // route but lets the already-started utterance finish under the current context.
            transport.emit(userSpeechStarted("in-flight-before-routing-ack"))

            await { session.snapshot.phase == LiveVoicePhase.USER_SPEAKING }
            assertEquals(1, transport.sessionUpdateCount())
            assertTrue(transport.inputAudioEnabled)
            assertEquals(0, transport.closeCount)

            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_stopped").toString())

            await { transport.sessionUpdateCount() == 2 }
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertTrue(transport.inputAudioEnabled)
            assertEquals("required", transport.latestSessionToolChoice())
            assertEquals(0, transport.closeCount)
            assertEquals(0, transport.responseCreateCount())
            transport.ackSessionUpdate()
            await { transport.responseCreateCount() == 1 }
            assertFalse(transport.responseCreates().single().has("response"))
            assertEquals(2, transport.sessionUpdateCount())
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
            assertEquals(0, transport.closeCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun contextRefreshIsSingleFlightAndCoalescesToLatestDesiredContext() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false

            context = LiveVoiceSessionContext("Context one.", contextIdentity = "one")
            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }
            context = LiveVoiceSessionContext("Context two.", contextIdentity = "two")
            session.refreshContext()
            context = LiveVoiceSessionContext("Context three.", contextIdentity = "three")
            session.refreshContext()
            Thread.sleep(50)
            assertEquals(2, transport.sessionUpdateCount())

            transport.ackSessionUpdate()
            await { transport.sessionUpdateCount() == 3 }
            assertTrue(transport.latestSessionInstructions().contains("Context three."))
            assertFalse(transport.latestSessionInstructions().contains("Context two."))
            transport.ackSessionUpdate()
            Thread.sleep(50)
            assertEquals(3, transport.sessionUpdateCount())
        } finally {
            session.close()
        }
    }

    @Test
    fun missingContextRefreshAcknowledgementReconnectsWithinBoundedTimeout() {
        val transport = FakeTransport()
        var context = LiveVoiceSessionContext("Context zero.", contextIdentity = "zero")
        val provider = object : LiveVoiceInstructionsProvider {
            override fun buildInstructions(): String = context.instructions

            override fun buildSessionContext(): LiveVoiceSessionContext = context
        }
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = provider,
            config = LiveVoiceSessionConfig(
                configureTimeoutMillis = 1_000,
                reconnectBaseDelayMillis = 60_000,
                reconnectMaximumDelayMillis = 60_000,
            ),
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.autoAcknowledgeSessionUpdates = false
            context = LiveVoiceSessionContext("Context one.", contextIdentity = "one")
            session.refreshContext()
            await { transport.sessionUpdateCount() == 2 }

            await { session.snapshot.phase == LiveVoicePhase.RECONNECTING }
            assertEquals("realtime_context_refresh_ack_timeout", session.snapshot.lastFailureCode)
            assertEquals(1, transport.closeCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun taskProgressAndCompletionReturnThroughSameHansSession() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        val ready = CountDownLatch(1)
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans. Sage niemals, dass du an ein anderes System delegierst." },
            observer = object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    if (snapshot.phase == LiveVoicePhase.LISTENING) ready.countDown()
                }
            },
            config = LiveVoiceSessionConfig(
                progressAnnouncementDelayMillis = 0,
                progressAnnouncementIntervalMillis = 1_000,
            ),
        )
        try {
            session.start()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            transport.emit(functionCall("call_1", "Prüfe den Akkustand."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))

            executor.listener!!.onProgress(LiveVoiceTaskProgress("Ich prüfe den Akkustand."))
            assertTrue(transport.progressSent.await(3, TimeUnit.SECONDS))
            executor.listener!!.onCompleted(LiveVoiceTaskResult("Der Akku steht bei 73 Prozent."))
            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))

            val all = transport.sentSnapshot().joinToString("\n")
            assertTrue(all.contains("Der Akku steht bei 73 Prozent."))
            assertFalse(all.contains("delegate", ignoreCase = true))
            assertFalse(all.contains("Codex", ignoreCase = true))
        } finally {
            session.close()
        }
    }

    @Test
    fun serverVadSpeechDoesNotClearPlaybackButExplicitInterruptDoes() {
        val transport = FakeTransport()
        val firstSpeaking = CountDownLatch(1)
        val secondSpeaking = CountDownLatch(1)
        val userSpeaking = CountDownLatch(1)
        val speakingCount = AtomicInteger(0)
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = FakeTaskExecutor(),
            instructionsProvider = { "Du bist Hans." },
            observer = object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    when (snapshot.phase) {
                        LiveVoicePhase.HANS_SPEAKING -> {
                            if (speakingCount.incrementAndGet() == 1) {
                                firstSpeaking.countDown()
                            } else {
                                secondSpeaking.countDown()
                            }
                        }
                        LiveVoicePhase.USER_SPEAKING -> userSpeaking.countDown()
                        else -> Unit
                    }
                }
            },
        )
        try {
            session.start()
            assertTrue(transport.configured.await(3, TimeUnit.SECONDS))
            transport.emit(responseCreated("response_1"))
            assertTrue(firstSpeaking.await(3, TimeUnit.SECONDS))
            transport.emit(JSONObject().put("type", "input_audio_buffer.speech_started").toString())
            assertTrue(userSpeaking.await(3, TimeUnit.SECONDS))
            assertEquals(0, transport.clearCount)
            assertFalse(transport.sentSnapshot().any { it.contains("response.cancel") })

            transport.emit(responseCreated("response_2"))
            assertTrue(secondSpeaking.await(3, TimeUnit.SECONDS))
            session.interruptHans()
            assertTrue(transport.explicitClear.await(3, TimeUnit.SECONDS))
            assertEquals(1, transport.clearCount)
            assertTrue(transport.sentSnapshot().any { it.contains("response.cancel") })
        } finally {
            session.close()
        }
    }

    @Test
    fun taskResultWaitsForActiveProgressResponseThenCreatesExactlyOnce() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        val session = readySession(transport, executor)
        try {
            transport.emit(finalUserTranscript("user-1", "Prüfe Maps."))
            transport.emit(functionCall("call-1", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))

            executor.listener!!.onProgress(LiveVoiceTaskProgress("Ich prüfe Maps."))
            await { transport.responseCreateCount() == 1 }
            val progressCreate = transport.responseCreates().single().getJSONObject("response")
            assertEquals("none", progressCreate.getString("tool_choice"))
            transport.emit(responseCreated("progress-response"))

            executor.listener!!.onCompleted(LiveVoiceTaskResult("Maps ist installiert."))
            assertTrue(transport.functionOutputSent.await(3, TimeUnit.SECONDS))
            await { session.snapshot.pendingTaskCount == 0 }
            assertEquals(LiveVoicePhase.HANS_SPEAKING, session.snapshot.phase)
            assertEquals(1, transport.responseCreateCount())

            transport.emit(responseDone("progress-response"))
            await { transport.responseCreateCount() == 2 }
            transport.emit(responseDone("progress-response"))
            transport.emit(responseCreated("task-result-response"))
            transport.emit(responseDone("task-result-response"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertEquals(2, transport.responseCreateCount())
        } finally {
            session.close()
        }
    }

    @Test
    fun activeResponseSchedulingConflictIsRecoveredWithoutFailingSession() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        val failures = AtomicInteger(0)
        val session = readySession(
            transport,
            executor,
            observer = object : LiveVoiceObserver {
                override fun onFailure(failure: LiveVoiceFailure) {
                    failures.incrementAndGet()
                }
            },
        )
        try {
            transport.emit(finalUserTranscript("user-1", "Prüfe Maps."))
            transport.emit(functionCall("call-1", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))
            executor.listener!!.onCompleted(LiveVoiceTaskResult("Maps ist installiert."))
            await { transport.responseCreateCount() == 1 }

            val eventId = transport.responseCreates().single().getString("event_id")
            transport.emit(activeResponseConflict(eventId))
            await { session.snapshot.phase != LiveVoicePhase.FAILED }
            assertEquals(0, failures.get())
            transport.emit(responseCreated("server-vad-response"))
            transport.emit(responseDone("server-vad-response"))

            await { transport.responseCreateCount() == 2 }
            assertEquals(0, failures.get())
            assertFalse(session.snapshot.phase == LiveVoicePhase.FAILED)
        } finally {
            session.close()
        }
    }

    @Test
    fun responseCreatedBeforeSchedulingConflictStillDrainsTaskResult() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        val session = readySession(transport, executor)
        try {
            transport.emit(finalUserTranscript("user-1", "Prüfe Maps."))
            transport.emit(functionCall("call-1", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))
            executor.listener!!.onCompleted(LiveVoiceTaskResult("Maps ist installiert."))
            await { transport.responseCreateCount() == 1 }
            val eventId = transport.responseCreates().single().getString("event_id")

            transport.emit(responseCreated("server-vad-response"))
            transport.emit(activeResponseConflict(eventId))
            transport.emit(responseDone("server-vad-response"))

            await { transport.responseCreateCount() == 2 }
            assertEquals("hans-response-2", transport.responseCreates()[1].getString("event_id"))
        } finally {
            session.close()
        }
    }

    @Test
    fun staleDoneCannotEndNewerActiveResponseOrCancelItsPhase() {
        val transport = FakeTransport()
        val barrier = CountDownLatch(1)
        val session = readySession(
            transport,
            FakeTaskExecutor(),
            observer = object : LiveVoiceObserver {
                override fun onHansTranscript(text: String, isFinal: Boolean) {
                    if (text == "barrier") barrier.countDown()
                }
            },
        )
        try {
            transport.emit(responseCreated("response-one"))
            await { session.snapshot.phase == LiveVoicePhase.HANS_SPEAKING }
            transport.emit(responseDone("response-one"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            transport.emit(responseCreated("response-two"))
            await { session.snapshot.phase == LiveVoicePhase.HANS_SPEAKING }

            transport.emit(responseDone("response-one"))
            transport.emit(hansTranscript("barrier"))
            assertTrue(barrier.await(3, TimeUnit.SECONDS))
            assertEquals(LiveVoicePhase.HANS_SPEAKING, session.snapshot.phase)

            transport.emit(responseDone("response-two"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
        } finally {
            session.close()
        }
    }

    @Test
    fun secondToolCallNeedsANewFinalUserTurnBeforeItCanStart() {
        val transport = FakeTransport()
        val executor = FakeTaskExecutor()
        val session = readySession(transport, executor)
        try {
            // Realtime can emit the function call before transcription.completed. VAD start is
            // therefore the authoritative local boundary for this spoken user turn.
            transport.emit(userSpeechStarted("user-1"))
            transport.emit(functionCall("call-1", "Öffne Maps."))
            await { executor.executeCount.get() == 1 }
            transport.emit(finalUserTranscript("user-1", "Öffne Maps."))
            transport.emit(responseCreated("original-tool-response"))

            transport.emit(functionCall("call-2", "Öffne Maps noch einmal."))
            await { transport.functionOutputCallIds().contains("call-2") }
            assertEquals(1, executor.executeCount.get())
            assertTrue(
                transport.sentSnapshot().any {
                    it.contains("already running") && it.contains("call-2")
                },
            )
            assertEquals(0, transport.responseCreateCount())

            transport.emit(responseDone("original-tool-response"))
            await { transport.responseCreateCount() == 1 }
            assertEquals(
                "none",
                transport.responseCreates().single()
                    .getJSONObject("response")
                    .getString("tool_choice"),
            )
            transport.emit(responseCreated("duplicate-continuation"))

            executor.listenerFor("call-1")!!.onCompleted(LiveVoiceTaskResult("Maps ist offen."))
            await { transport.functionOutputCallIds().contains("call-1") }
            assertEquals(1, transport.responseCreateCount())
            transport.emit(responseDone("duplicate-continuation"))
            await { transport.responseCreateCount() == 2 }

            transport.emit(userSpeechStarted("user-2"))
            transport.emit(functionCall("call-3", "Suche den Flughafen in Maps."))
            await { executor.executeCount.get() == 2 }
            assertTrue(executor.listenerFor("call-3") != null)
            transport.emit(finalUserTranscript("user-2", "Suche jetzt den Flughafen."))
        } finally {
            session.close()
        }
    }

    @Test
    fun droppedFunctionOutputIsRecoveredAfterReconnectWithoutExposingCorrelation() {
        val firstTransport = FakeTransport(functionOutputFailures = 1)
        val recoveredTransport = FakeTransport()
        val executor = FakeTaskExecutor()
        val transportCount = AtomicInteger(0)
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                if (transportCount.getAndIncrement() == 0) {
                    firstTransport
                } else {
                    recoveredTransport
                }
            },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans." },
            config = LiveVoiceSessionConfig(
                reconnectBaseDelayMillis = 100,
                reconnectMaximumDelayMillis = 100,
                maximumReconnectAttempts = 2,
                progressAnnouncementDelayMillis = 0,
                progressAnnouncementIntervalMillis = 1_000,
            ),
        )
        try {
            session.start()
            assertTrue(firstTransport.configured.await(3, TimeUnit.SECONDS))
            firstTransport.emit(userSpeechStarted("user-recovery"))
            firstTransport.emit(functionCall("call-recovery-secret", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))

            executor.listenerFor("call-recovery-secret")!!.onCompleted(
                LiveVoiceTaskResult("Maps ist installiert."),
            )
            assertTrue(firstTransport.functionOutputAttempted.await(3, TimeUnit.SECONDS))
            assertTrue(recoveredTransport.configured.await(3, TimeUnit.SECONDS))
            await {
                recoveredTransport.contextTexts().any { text ->
                    text.contains("<task-result>") && text.contains("Maps ist installiert.")
                }
            }
            await { recoveredTransport.responseCreateCount() == 1 }

            val recoveryEvents = recoveredTransport.sentSnapshot().joinToString("\n")
            assertFalse(recoveryEvents.contains("call-recovery-secret"))
            assertEquals(
                "none",
                recoveredTransport.responseCreates().single()
                    .getJSONObject("response")
                    .getString("tool_choice"),
            )
            assertEquals(2, transportCount.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun recoveryBatchSurvivesCreateDropAndDisconnectUntilMatchingDone() {
        val initial = FakeTransport(functionOutputFailures = 1)
        val createDrop = FakeTransport(responseCreateFailures = 1)
        val disconnectedBeforeDone = FakeTransport()
        val recoveredAfterDisconnect = FakeTransport()
        val afterCommit = FakeTransport()
        val transports = listOf(
            initial,
            createDrop,
            disconnectedBeforeDone,
            recoveredAfterDisconnect,
            afterCommit,
        )
        val transportIndex = AtomicInteger(0)
        val executor = FakeTaskExecutor()
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans." },
            config = reconnectingConfig(maximumReconnectAttempts = 4),
        )
        try {
            session.start()
            assertTrue(initial.configured.await(3, TimeUnit.SECONDS))
            initial.emit(userSpeechStarted("user-create-drop"))
            initial.emit(functionCall("call-create-drop", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))
            executor.listenerFor("call-create-drop")!!.onCompleted(
                LiveVoiceTaskResult("Maps ist verfügbar."),
            )

            assertTrue(createDrop.configured.await(3, TimeUnit.SECONDS))
            assertTrue(createDrop.responseCreateAttempted.await(3, TimeUnit.SECONDS))
            assertTrue(disconnectedBeforeDone.configured.await(3, TimeUnit.SECONDS))
            await {
                disconnectedBeforeDone.contextTexts().any { it.contains("Maps ist verfügbar.") }
            }
            await { disconnectedBeforeDone.responseCreateCount() == 1 }
            assertEquals(
                "none",
                disconnectedBeforeDone.responseCreates().single()
                    .getJSONObject("response")
                    .getString("tool_choice"),
            )
            disconnectedBeforeDone.emitClosed(
                LiveVoiceFailure("test_disconnect_before_done", retryable = true),
            )

            assertTrue(recoveredAfterDisconnect.configured.await(3, TimeUnit.SECONDS))
            await {
                recoveredAfterDisconnect.contextTexts().any { it.contains("Maps ist verfügbar.") }
            }
            await { recoveredAfterDisconnect.responseCreateCount() == 1 }

            recoveredAfterDisconnect.emit(responseCreated("recovery-response"))
            recoveredAfterDisconnect.emit(responseDone("recovery-response"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            recoveredAfterDisconnect.emitClosed(
                LiveVoiceFailure("test_disconnect_after_done", retryable = true),
            )

            assertTrue(afterCommit.configured.await(3, TimeUnit.SECONDS))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
            assertTrue(afterCommit.contextTexts().isEmpty())
            assertEquals(0, afterCommit.responseCreateCount())
        } finally {
            session.close()
        }
    }

    @Test
    fun partialRecoveryBatchIsRetriedInFullOnFreshSession() {
        val initial = FakeTransport(functionOutputFailures = 1)
        val partial = FakeTransport(recoveredContextFailureAttempt = 2)
        val recovered = FakeTransport()
        val transports = listOf(initial, partial, recovered)
        val transportIndex = AtomicInteger(0)
        val executor = FakeTaskExecutor()
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans." },
            config = reconnectingConfig(maximumReconnectAttempts = 4),
        )
        try {
            session.start()
            assertTrue(initial.configured.await(3, TimeUnit.SECONDS))
            initial.emit(userSpeechStarted("user-a"))
            initial.emit(functionCall("call-a", "Prüfe Maps."))
            initial.emit(userSpeechStarted("user-b"))
            initial.emit(functionCall("call-b", "Prüfe Gmail."))
            await { executor.executeCount.get() == 2 }

            executor.listenerFor("call-a")!!.onCompleted(LiveVoiceTaskResult("Ergebnis A."))
            executor.listenerFor("call-b")!!.onCompleted(LiveVoiceTaskResult("Ergebnis B."))

            assertTrue(partial.configured.await(3, TimeUnit.SECONDS))
            assertTrue(recovered.configured.await(3, TimeUnit.SECONDS))
            await { recovered.contextTexts().size == 2 }
            await { recovered.responseCreateCount() == 1 }

            assertEquals(1, partial.contextTexts().size)
            assertTrue(partial.contextTexts().single().contains("Ergebnis A."))
            assertEquals(
                listOf("Ergebnis A.", "Ergebnis B."),
                recovered.contextTexts().map { text ->
                    when {
                        text.contains("Ergebnis A.") -> "Ergebnis A."
                        text.contains("Ergebnis B.") -> "Ergebnis B."
                        else -> "unexpected"
                    }
                },
            )
            assertEquals(
                "none",
                recovered.responseCreates().single()
                    .getJSONObject("response")
                    .getString("tool_choice"),
            )
            recovered.emit(responseCreated("batch-recovery-response"))
            recovered.emit(responseDone("batch-recovery-response"))
            await { session.snapshot.phase == LiveVoicePhase.LISTENING }
        } finally {
            session.close()
        }
    }

    @Test
    fun failedRecoveryDoneRetainsAndRetriesBatch() {
        assertRecoveryTerminalStatusIsRetried("failed")
    }

    @Test
    fun incompleteRecoveryDoneRetainsAndRetriesBatch() {
        assertRecoveryTerminalStatusIsRetried("incomplete")
    }

    @Test
    fun intentionalCancelledRecoveryStopsAudioButRetainsAndRetriesBatch() {
        assertRecoveryTerminalStatusIsRetried("cancelled", intentionallyCancel = true)
    }

    private fun assertRecoveryTerminalStatusIsRetried(
        status: String,
        intentionallyCancel: Boolean = false,
    ) {
        val initial = FakeTransport(functionOutputFailures = 1)
        val terminal = FakeTransport()
        val retried = FakeTransport()
        val transports = listOf(initial, terminal, retried)
        val transportIndex = AtomicInteger(0)
        val executor = FakeTaskExecutor()
        val session = LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory {
                transports[transportIndex.getAndIncrement()]
            },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans." },
            config = reconnectingConfig(maximumReconnectAttempts = 4),
        )
        try {
            session.start()
            assertTrue(initial.configured.await(3, TimeUnit.SECONDS))
            initial.emit(userSpeechStarted("user-status-$status"))
            initial.emit(functionCall("call-status-$status", "Prüfe Maps."))
            assertTrue(executor.started.await(3, TimeUnit.SECONDS))
            executor.listenerFor("call-status-$status")!!.onCompleted(
                LiveVoiceTaskResult("Recovery $status."),
            )

            assertTrue(terminal.configured.await(3, TimeUnit.SECONDS))
            await { terminal.responseCreateCount() == 1 }
            terminal.emit(responseCreated("response-$status"))
            if (intentionallyCancel) {
                session.interruptHans()
                await { terminal.sentSnapshot().any { it.contains("response.cancel") } }
            }
            terminal.emit(responseDone("response-$status", status))

            assertTrue(retried.configured.await(3, TimeUnit.SECONDS))
            await {
                retried.contextTexts().any { text -> text.contains("Recovery $status.") }
            }
            await { retried.responseCreateCount() == 1 }
            assertEquals(
                "none",
                retried.responseCreates().single()
                    .getJSONObject("response")
                    .getString("tool_choice"),
            )
        } finally {
            session.close()
        }
    }

    private fun reconnectingConfig(maximumReconnectAttempts: Int) = LiveVoiceSessionConfig(
        reconnectBaseDelayMillis = 100,
        reconnectMaximumDelayMillis = 100,
        maximumReconnectAttempts = maximumReconnectAttempts,
        progressAnnouncementDelayMillis = 0,
        progressAnnouncementIntervalMillis = 1_000,
    )

    private fun readySession(
        transport: FakeTransport,
        executor: FakeTaskExecutor,
        observer: LiveVoiceObserver = object : LiveVoiceObserver {},
        config: LiveVoiceSessionConfig = LiveVoiceSessionConfig(
            progressAnnouncementDelayMillis = 0,
            progressAnnouncementIntervalMillis = 1_000,
        ),
    ): LiveVoiceSession {
        val ready = CountDownLatch(1)
        val combinedObserver = object : LiveVoiceObserver {
            override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                observer.onSnapshot(snapshot)
                if (snapshot.phase == LiveVoicePhase.LISTENING) ready.countDown()
            }

            override fun onFailure(failure: LiveVoiceFailure) = observer.onFailure(failure)

            override fun onHansTranscript(text: String, isFinal: Boolean) =
                observer.onHansTranscript(text, isFinal)

            override fun onHansResponseReady(event: LiveVoiceResponseReady) =
                observer.onHansResponseReady(event)
        }
        return LiveVoiceSession(
            credentialProvider = ImmediateCredentialProvider,
            transportFactory = LiveVoiceTransportFactory { transport },
            taskExecutor = executor,
            instructionsProvider = { "Du bist Hans." },
            observer = combinedObserver,
            config = config,
        ).also {
            it.start()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
        }
    }

    private fun functionCall(callId: String, request: String): String = JSONObject()
        .put("type", "response.function_call_arguments.done")
        .put("call_id", callId)
        .put("name", "use_hans")
        .put("arguments", JSONObject().put("request", request).toString())
        .toString()

    private fun endCall(callId: String, responseId: String): String = JSONObject()
        .put("type", "response.function_call_arguments.done")
        .put("response_id", responseId)
        .put("call_id", callId)
        .put("name", "end_live_call")
        .put("arguments", "{}")
        .toString()

    private fun outputAudioStarted(responseId: String): String = JSONObject()
        .put("type", "output_audio_buffer.started")
        .put("response_id", responseId)
        .toString()

    private fun outputAudioStopped(responseId: String): String = JSONObject()
        .put("type", "output_audio_buffer.stopped")
        .put("response_id", responseId)
        .toString()

    private fun responseCreated(id: String): String = JSONObject()
        .put("type", "response.created")
        .put("response", JSONObject().put("id", id))
        .toString()

    private fun responseDone(id: String, status: String = "completed"): String = JSONObject()
        .put("type", "response.done")
        .put(
            "response",
                JSONObject()
                .put("id", id)
                .put("status", status),
        )
        .toString()

    private fun finalUserTranscript(itemId: String, text: String): String = JSONObject()
        .put("type", "conversation.item.input_audio_transcription.completed")
        .put("item_id", itemId)
        .put("transcript", text)
        .toString()

    private fun userSpeechStarted(itemId: String): String = JSONObject()
        .put("type", "input_audio_buffer.speech_started")
        .put("item_id", itemId)
        .toString()

    private fun activeResponseConflict(eventId: String): String = JSONObject()
        .put("type", "error")
        .put(
            "error",
            JSONObject()
                .put("code", "conversation_already_has_active_response")
                .put("event_id", eventId),
        )
        .put("event_id", "unrelated-server-event")
        .toString()

    private fun hansTranscript(text: String, responseId: String? = null, isFinal: Boolean = false): String = JSONObject()
        .put("type", if (isFinal) "response.output_audio_transcript.done" else "response.output_audio_transcript.delta")
        .put(if (isFinal) "transcript" else "delta", text)
        .put("response_id", responseId)
        .toString()

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var observed = predicate()
        while (!observed && System.nanoTime() < deadline) {
            Thread.sleep(5)
            observed = predicate()
        }
        // "Eventually observed" must not re-read a concurrent transient state after success.
        assertTrue(observed)
    }

    private object ImmediateCredentialProvider : LiveVoiceCredentialProvider {
        override fun request(
            session: LiveVoiceSessionConfig,
            callback: LiveVoiceCredentialProvider.Callback,
        ): LiveVoiceCancellation {
            callback.onCredential(RealtimeEphemeralCredential.of("ek_test_1234567890"))
            return LiveVoiceCancellation.NONE
        }
    }

    private class FakeTaskExecutor : LiveVoiceTaskExecutor {
        val started = CountDownLatch(1)
        val executeCount = AtomicInteger(0)
        private val listeners = Collections.synchronizedMap(
            linkedMapOf<String, LiveVoiceTaskExecutor.Listener>(),
        )
        @Volatile var listener: LiveVoiceTaskExecutor.Listener? = null

        override fun execute(
            request: LiveVoiceTaskRequest,
            listener: LiveVoiceTaskExecutor.Listener,
        ): LiveVoiceTaskHandle {
            this.listener = listener
            listeners[request.callId] = listener
            executeCount.incrementAndGet()
            started.countDown()
            return LiveVoiceTaskHandle.NONE
        }

        fun listenerFor(callId: String): LiveVoiceTaskExecutor.Listener? = listeners[callId]
    }

    private class FakeTransport(
        functionOutputFailures: Int = 0,
        responseCreateFailures: Int = 0,
        private val recoveredContextFailureAttempt: Int? = null,
    ) : LiveVoiceTransport {
        private val sent = Collections.synchronizedList(mutableListOf<String>())
        val configured = CountDownLatch(1)
        val progressSent = CountDownLatch(1)
        val functionOutputSent = CountDownLatch(1)
        val functionOutputAttempted = CountDownLatch(if (functionOutputFailures > 0) 1 else 0)
        val responseCreateAttempted = CountDownLatch(if (responseCreateFailures > 0) 1 else 0)
        val explicitClear = CountDownLatch(1)
        private val remainingFunctionOutputFailures = AtomicInteger(functionOutputFailures)
        private val remainingResponseCreateFailures = AtomicInteger(responseCreateFailures)
        private val recoveredContextAttempts = AtomicInteger(0)
        @Volatile var clearCount = 0
        @Volatile var inputAudioEnabled = true
        @Volatile var userInputMuted = false
        @Volatile var autoAcknowledgeSessionUpdates = true
        @Volatile var closeCount = 0
        val inputAudioEnabledChanges = Collections.synchronizedList(mutableListOf<Boolean>())
        @Volatile private var listener: LiveVoiceTransport.Listener? = null

        override fun connect(
            credential: RealtimeEphemeralCredential,
            listener: LiveVoiceTransport.Listener,
        ) {
            this.listener = listener
            listener.onOpen()
        }

        override fun sendUtf8(event: String): Boolean {
            val root = JSONObject(event)
            val type = root.getString("type")
            val isFunctionOutput = type == "conversation.item.create" &&
                root.optJSONObject("item")?.optString("type") == "function_call_output"
            val contextText = root.optJSONObject("item")
                ?.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
            val isRecoveredContext = contextText?.contains("<task-result>") == true
            if (
                isFunctionOutput &&
                remainingFunctionOutputFailures.getAndUpdate { remaining ->
                    (remaining - 1).coerceAtLeast(0)
                } > 0
            ) {
                functionOutputAttempted.countDown()
                return false
            }
            if (
                isRecoveredContext &&
                recoveredContextAttempts.incrementAndGet() == recoveredContextFailureAttempt
            ) {
                return false
            }
            if (
                type == "response.create" &&
                remainingResponseCreateFailures.getAndUpdate { remaining ->
                    (remaining - 1).coerceAtLeast(0)
                } > 0
            ) {
                responseCreateAttempted.countDown()
                return false
            }
            sent += event
            if (type == "session.update") {
                if (autoAcknowledgeSessionUpdates) ackSessionUpdate()
                configured.countDown()
            }
            if (event.contains("<task-progress>")) {
                progressSent.countDown()
            }
            if (isFunctionOutput) {
                functionOutputAttempted.countDown()
                functionOutputSent.countDown()
            }
            if (type == "response.create") responseCreateAttempted.countDown()
            return true
        }

        override fun setInputAudioEnabled(enabled: Boolean): Boolean {
            inputAudioEnabled = enabled
            inputAudioEnabledChanges += enabled
            return true
        }

        override fun setUserInputMuted(muted: Boolean): Boolean {
            userInputMuted = muted
            return true
        }

        override fun clearOutputAudio(): Boolean {
            clearCount += 1
            explicitClear.countDown()
            return true
        }

        override fun close() {
            closeCount += 1
        }

        fun emit(event: String) {
            listener?.onEvent(event)
        }

        fun ackSessionUpdate() {
            listener?.onEvent(JSONObject().put("type", "session.updated").toString())
        }

        fun emitClosed(failure: LiveVoiceFailure) {
            listener?.onClosed(failure)
        }

        fun sentSnapshot(): List<String> = synchronized(sent) { sent.toList() }

        fun sessionUpdateCount(): Int = sentSnapshot()
            .map(::JSONObject)
            .count { it.optString("type") == "session.update" }

        fun latestSessionVoice(): String = sentSnapshot()
            .map(::JSONObject)
            .last {
                it.optString("type") == "session.update" &&
                    it.optJSONObject("session")?.optJSONObject("audio") != null
            }
            .getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("output")
            .getString("voice")

        fun latestSessionToolChoice(): String = sentSnapshot()
            .map(::JSONObject)
            .last { it.optString("type") == "session.update" }
            .getJSONObject("session")
            .getString("tool_choice")

        fun latestSessionInstructions(): String = sentSnapshot()
            .map(::JSONObject)
            .last { it.optString("type") == "session.update" }
            .getJSONObject("session")
            .getString("instructions")

        fun responseCreates(): List<JSONObject> = sentSnapshot()
            .map(::JSONObject)
            .filter { it.optString("type") == "response.create" }

        fun responseCreateCount(): Int = responseCreates().size

        fun latestFunctionOutputStatus(): String = sentSnapshot()
            .map(::JSONObject)
            .last {
                it.optString("type") == "conversation.item.create" &&
                    it.optJSONObject("item")?.optString("type") == "function_call_output"
            }
            .getJSONObject("item")
            .getString("output")
            .let(::JSONObject)
            .getString("status")

        fun contextTexts(): List<String> = sentSnapshot()
            .map(::JSONObject)
            .filter { it.optString("type") == "conversation.item.create" }
            .mapNotNull { root ->
                root.optJSONObject("item")
                    ?.takeIf { it.optString("type") == "message" }
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf(String::isNotBlank)
            }

        fun functionOutputCallIds(): Set<String> = sentSnapshot()
            .map(::JSONObject)
            .filter { it.optString("type") == "conversation.item.create" }
            .mapNotNull { root ->
                root.optJSONObject("item")
                    ?.takeIf { it.optString("type") == "function_call_output" }
                    ?.optString("call_id")
                    ?.takeIf(String::isNotBlank)
            }
            .toSet()
    }
}
