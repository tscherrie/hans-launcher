package ai.hans.standard.voice.realtime

import ai.hans.standard.integration.CodexRealtimeCall
import ai.hans.standard.integration.CodexRealtimeCallbacks
import ai.hans.standard.integration.CodexRealtimeCoordinator
import ai.hans.standard.integration.CodexRealtimeGateway
import ai.hans.standard.codex.*
import ai.hans.standard.integration.*
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.ui.*
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real wire parser, provider, voice session and observer replay; no microphone, account or network. */
class CodexVoiceTranscriptSingleOwnerAndroidTest {
    @Test fun confirmedShortTaskScopeUsesOneVisibleFinalOwnerInEitherUiArrivalOrderAndReplays() {
        for (entryPoint in LiveVoiceEntryPoint.entries) for (voiceFirst in listOf(true, false))
            for (userPartialFirst in listOf(true, false)) Fixture(entryPoint).use { h ->
            h.activate()
            h.wire.flat("assistant", "Einen Moment, ich schau kurz nach.", true); h.drain()
            val interim = h.live.revisions.single()
            if (userPartialFirst) { h.wire.flat("user", "Hallo", false); h.drain() }
            h.wire.handoffAndBind("voice-turn"); h.drain()
            h.wire.flat("user", "Hallo Hans.", true); h.drain() // Late final, including without any delta.
            h.wire.flat("assistant", "Guten Morgen, Jeremias! Bin bereit für den Tag.", true); h.drain()
            val spoken = h.live.revisions.last()
            assertEquals(if (entryPoint == LiveVoiceEntryPoint.DICTATION) CodexVoiceWorkScope("synthetic-thread", "voice-turn") else null,
                spoken.dictationWorkScope)
            assertNull(interim.dictationWorkScope)
            val native = ClientTimelineItem("native-final", ClientTimelineRole.HANS,
                "Guten Morgen, Jeremias! Ich bin bereit für den Tag.", 1, 0, true,
                ClientTimelineStatus.COMPLETE, AgentMessagePhase.FINAL_ANSWER, "voice-turn")
            val speechRows = listOf(interim, spoken).map(::speechRow)
            if (voiceFirst) assertEquals(2, project(emptyList(), speechRows).size)
            else assertEquals(listOf(native.id), project(listOf(native), emptyList()).map { it.id })
            val merged = project(listOf(native), speechRows)
            assertEquals(if (entryPoint == LiveVoiceEntryPoint.DICTATION) 2 else 3, merged.size)
            assertTrue(merged.any { it.id == interim.displayId })
            assertEquals(entryPoint == LiveVoiceEntryPoint.PHONE, merged.any { it.id == spoken.displayId })
            assertEquals(merged, project(listOf(native), h.replay().revisions.filter { it.author == LiveVoiceTranscriptAuthor.HANS }.map(::speechRow)))
            assertTrue(project(listOf(native.copy(text = "")), speechRows).any { it.id == spoken.displayId })
            assertTrue(project(listOf(native.copy(turnId = "independent")), speechRows).any { it.id == spoken.displayId })
        }
    }

    @Test fun blankFinalRetiresItsTypedPartialCardWithoutRemovingAnotherUtterance() = Fixture(LiveVoiceEntryPoint.DICTATION).use { h ->
        h.activate()
        h.wire.flat("assistant", "Previous reply.", true)
        h.wire.flat("assistant", "Discarded partial", false); h.drain()
        val rows = h.live.revisions.map(::speechRow)
        h.wire.flat("assistant", "", true); h.drain()
        assertEquals(rows.last().id, h.live.revisions.last().displayId)
        assertEquals(listOf(rows.first()), LiveVoiceTranscriptUiUpdates.discardIncomplete(rows,
            h.live.revisions.last().displayId, ChatMessageAuthor.HANS))
        assertEquals(listOf("Previous reply."), h.replay().revisions.map { it.text })
    }

    private fun speechRow(event: LiveVoiceTranscriptRevision) = ChatMessageUiModel(event.displayId,
        if (event.author == LiveVoiceTranscriptAuthor.HANS) ChatMessageAuthor.HANS else ChatMessageAuthor.USER,
        event.text, complete = event.isFinal, localArrivalOrder = 1, liveVoiceTranscript = event)

    private fun project(native: List<ClientTimelineItem>, speech: List<ChatMessageUiModel>): List<ChatMessageUiModel> {
        val client = CodexClientSnapshot(ClientRuntimePhase.READY, ClientSessionPhase.READY, 1,
            SessionUiSnapshot(AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
                "synthetic-thread", emptyList(), DeliveryUiSnapshot(1, 1, null, false)), emptyList(), null,
            emptyList(), native, null, DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort), null)
        return HansClientUiProjector.project(client, HansLocalUiState(liveVoiceMessages = speech), HansSettings(),
            AndroidHansTextResolver(InstrumentationRegistry.getInstrumentation().targetContext)).chat.messages
    }
    @Test fun canonicalAndFlatWireFinalsRenderAndReplayOnceRegardlessOfOrder() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (canonicalFirst in listOf(true, false)) Fixture(entryPoint).use { h ->
                h.activate()
                for (role in listOf("user", "assistant")) {
                    h.wire.item("started", role, role, "")
                    h.wire.flat(role, "Draft wording", false)
                    if (canonicalFirst) h.wire.item("completed", role, role, "Draft wording")
                    h.wire.flat(role, "Corrected wording.", true)
                    if (!canonicalFirst) h.wire.item("completed", role, role, "Draft wording")
                }
                h.drain()
                assertEquals(listOf("user:Draft wording:false", "user:Corrected wording.:true",
                    "assistant:Draft wording:false", "assistant:Corrected wording.:true"), h.live.transcripts)
                assertEquals(listOf("user:Corrected wording.:true", "assistant:Corrected wording.:true"),
                    h.replay().transcripts)
                assertEquals(1, h.live.responses.size)
                assertEquals(listOf("thread/realtime/start"), h.wire.methods())
            }
        }
    }

    @Test fun delayedCanonicalLifecycleCannotClearANewerPartialOrReplayAnOlderMessage() {
        for (entryPoint in LiveVoiceEntryPoint.entries) {
            for (role in listOf("user", "assistant")) Fixture(entryPoint).use { h ->
                h.activate()
                h.wire.item("started", "previous", role, "")
                h.wire.flat(role, "First.", true)
                h.wire.flat(role, "Second ", false)
                h.wire.item("completed", "previous", role, "First.")
                h.wire.item("started", "current", role, "")
                h.wire.flat(role, "message", false)
                h.drain()
                assertEquals(listOf("$role:First.:true", "$role:Second :false", "$role:Second message:false"),
                    h.live.transcripts)
                assertEquals(listOf("$role:First.:true", "$role:Second message:false"), h.replay().transcripts)
                h.wire.flat(role, "Second message.", true)
                h.wire.item("completed", "current", role, "Second message")
                h.drain()
                assertEquals(listOf("$role:First.:true", "$role:Second message.:true"), h.replay().transcripts)
                if (role == "assistant") assertEquals(2, h.live.responses.size)
            }
        }
    }

    @Test fun genuineRepeatedSpeechIsRetainedAndCanonicalHistorySplitsAreNotExtraReplies() {
        for (entryPoint in LiveVoiceEntryPoint.entries) Fixture(entryPoint).use { h ->
            h.activate()
            repeat(2) { index ->
                h.wire.item("started", "first-$index", "assistant", "")
                h.wire.flat("assistant", "Same ", false)
                h.wire.item("completed", "first-$index", "assistant", "Same ")
                h.wire.item("started", "second-$index", "assistant", "")
                h.wire.flat("assistant", "reply", false)
                h.wire.item("completed", "second-$index", "assistant", "reply")
                h.wire.flat("assistant", "Same reply.", true)
            }
            h.drain()
            assertEquals(listOf("assistant:Same :false", "assistant:Same reply:false", "assistant:Same reply.:true",
                "assistant:Same :false", "assistant:Same reply:false", "assistant:Same reply.:true"), h.live.transcripts)
            assertEquals(listOf("assistant:Same reply.:true", "assistant:Same reply.:true"), h.replay().transcripts)
            assertEquals(2, h.live.responses.size)
            assertEquals(2, h.live.responses.map { it.responseId }.distinct().size)
            assertTrue(h.replay().responses.isEmpty())
        }
    }

    private class Fixture(entryPoint: LiveVoiceEntryPoint) : AutoCloseable {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val wire = Wire()
        val hub = LiveVoiceObserverHub()
        val live = Observer()
        private val subscription = hub.add(live)
        lateinit var media: Media
        val session = CodexLiveVoiceSession(wire, { Media(it).also { media = it } },
            LiveVoiceInstructionsProvider { "Synthetic native voice context" }, observer = hub,
            scheduler = scheduler, entryPoint = entryPoint)

        fun activate() {
            session.start(); drain()
            wire.ready(); drain()
            media.listener.onOpen()
            media.listener.onInputCaptureStarted()
            drain()
            assertEquals(LiveVoicePhase.LISTENING, session.snapshot.phase)
        }

        // Start and SDP callbacks may enqueue another callback on the same single owner.
        fun drain() { repeat(3) { scheduler.submit {}.get(3, TimeUnit.SECONDS) } }

        fun replay(): Observer = Observer().also { hub.add(it).cancel() }

        override fun close() {
            try {
                session.close(); drain()
                assertEquals(listOf("thread/realtime/start", "thread/realtime/stop"), wire.methods())
            } finally {
                subscription.cancel()
                scheduler.shutdownNow()
            }
        }
    }

    private class Wire : CodexRealtimeGateway {
        private var sequence = 0
        private val sent = mutableListOf<JSONObject>()
        private val coordinator = CodexRealtimeCoordinator(
            send = { _, wire -> sent += JSONObject(wire); true },
            newId = { "transcript-test-${++sequence}" },
        )

        @Synchronized override fun start(offerSdp: String, prompt: String, voice: String?,
            callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? {
            val lease = coordinator.start(1, "synthetic-thread", offerSdp, prompt, voice, callbacks) ?: return null
            return CodexRealtimeCall {
                synchronized(this) {
                    coordinator.stop(lease)
                    event("closed", JSONObject().put("reason", "requested"))
                }
            }
        }

        override fun interruptCurrentTurn(): Boolean = error("No work interruption")

        @Synchronized fun methods(): List<String> = sent.map { it.getString("method") }

        @Synchronized fun ready() {
            event("started", JSONObject().put("version", "v3").put("realtimeSessionId", sessionId()))
            event("sdp", JSONObject().put("sdp", "v=0\r\nsynthetic-answer"))
        }

        @Synchronized fun flat(role: String, text: String, final: Boolean) {
            event(if (final) "transcript/done" else "transcript/delta", JSONObject().put("role", role)
                .put(if (final) "text" else "delta", text))
        }

        @Synchronized fun item(stage: String, id: String, role: String, text: String) {
            event("item/$stage", JSONObject().put("item", JSONObject().put("type", "transcriptSegment")
                .put("id", id).put("realtimeSessionId", sessionId()).put("role", role).put("text", text)))
        }

        @Synchronized fun handoffAndBind(turnId: String) {
            event("itemAdded", JSONObject().put("item", JSONObject().put("type", "handoff_request")
                .put("handoff_id", "actual-handoff").put("item_id", "user-input")))
            assertTrue(coordinator.claimTurnOrigin(1, "synthetic-thread", turnId))
        }

        private fun sessionId(): String = sent.first { it.getString("method") == "thread/realtime/start" }
            .getJSONObject("params").getString("realtimeSessionId")

        private fun event(method: String, params: JSONObject) {
            assertTrue(coordinator.onFrame(1, JSONObject().put("method", "thread/realtime/$method")
                .put("params", params.put("threadId", "synthetic-thread")).toString()))
        }
    }

    private class Media(private val provider: LiveSessionProvider) : LiveVoiceTransport {
        lateinit var listener: LiveVoiceTransport.Listener
        override fun connect(setup: LiveSessionSetup, listener: LiveVoiceTransport.Listener) {
            this.listener = listener
            provider.create(setup, "v=0\r\nsynthetic-offer", object : LiveSessionProvider.Callback {
                override fun onCreated(answer: LiveSessionAnswer) = Unit
                override fun onFailure(failure: LiveVoiceFailure) { listener.onClosed(failure) }
            })
        }
        override fun connect(credential: RealtimeEphemeralCredential, listener: LiveVoiceTransport.Listener) =
            error("No credential or network access")
        override fun confirmSessionStarted(): Boolean = true
        override fun sendUtf8(event: String): Boolean = error("No extra submission")
        override fun setInputAudioEnabled(enabled: Boolean): Boolean = true
        override fun setUserInputMuted(muted: Boolean): Boolean = true
        override fun clearOutputAudio(): Boolean = false
        override fun close() = Unit
        override fun closeAndAwait(timeoutMillis: Long): Boolean = true
    }

    private class Observer : LiveVoiceObserver {
        val transcripts = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<LiveVoiceResponseReady>()
        val revisions = CopyOnWriteArrayList<LiveVoiceTranscriptRevision>()
        override fun onTranscriptRevision(event: LiveVoiceTranscriptRevision) {
            revisions += event
            super<LiveVoiceObserver>.onTranscriptRevision(event)
        }
        override fun onUserTranscript(text: String, isFinal: Boolean) { transcripts += "user:$text:$isFinal" }
        override fun onHansTranscript(text: String, isFinal: Boolean) { transcripts += "assistant:$text:$isFinal" }
        override fun onHansResponseReady(event: LiveVoiceResponseReady) { responses += event }
    }
}
