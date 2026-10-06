package ai.hans.standard.integration

import ai.hans.standard.voice.realtime.CodexLiveSessionProvider
import ai.hans.standard.voice.realtime.CodexLiveVoiceSession
import ai.hans.standard.voice.realtime.LiveSessionAnswer
import ai.hans.standard.voice.realtime.LiveSessionProvider
import ai.hans.standard.voice.realtime.LiveSessionSetup
import ai.hans.standard.voice.realtime.LiveVoiceFailure
import ai.hans.standard.voice.realtime.LiveVoiceSessionConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual provider/coordinator callbacks with Android JSON; no audio, account or network. */
class RealtimeCloseReuseAndroidTest {
    @Test fun repeatedVoiceStartsRequireRequestedCloseAndIgnoreOldUnrequestedReceipt() =
        checkRecovery(expireDrain = false)

    @Test fun lateCloseAfterTimeoutRecoversTheSameLeaseWithoutReplayOrRestart() =
        checkRecovery(expireDrain = true)

    private fun checkRecovery(expireDrain: Boolean) {
        for (reason in listOf("transport_closed", "error", null)) {
            val h = Harness()
            val events = Events()
            val first = h.open(events)
            h.ready()
            h.event("closed", JSONObject().put("reason", reason ?: JSONObject.NULL))
            assertEquals(1, events.closes)
            assertEquals(1, h.requests("thread/realtime/stop").size)
            assertFalse(first.closeAndAwait(0))
            h.ackStop()
            assertFalse(first.closeAndAwait(0))
            if (expireDrain) h.expireDrain()
            val rejected = Answer()
            h.open(answer = rejected)
            assertEquals(if (expireDrain) "codex_live_recovery_required" else "codex_live_already_active",
                rejected.failures.single().code)
            assertEquals(1, h.requests("thread/realtime/start").size)
            h.event("closed", JSONObject().put("reason", "requested"))
            assertTrue(first.closeAndAwait(0))
            assertEquals(1, events.closes)

            val nextEvents = Events()
            val next = h.open(nextEvents)
            h.ready()
            h.event("closed", JSONObject().put("reason", "requested"))
            first.close()
            h.ackStop() // Late old stop ACK has no authority over this new provider.
            assertEquals(1, nextEvents.starts)
            assertEquals(0, nextEvents.closes)
            assertEquals(CodexRealtimeState.ACTIVE, h.coordinator.diagnostics().state)
            assertEquals(1, h.requests("thread/realtime/stop").size)
            next.close()
            h.event("closed", JSONObject().put("reason", "requested"))
            assertTrue(next.closeAndAwait(0))
            assertEquals(2, h.requests("thread/realtime/start").size)
            assertTrue(h.sent.all { it.getString("method") in setOf("thread/realtime/start", "thread/realtime/stop") })
        }
    }

    private class Harness : CodexRealtimeGateway {
        val sent = mutableListOf<JSONObject>()
        private val drainTasks = mutableListOf<() -> Unit>()
        private var sequence = 0
        val coordinator = CodexRealtimeCoordinator(
            send = { _, text -> sent += JSONObject(text); true },
            newId = { "android-close-${++sequence}" },
            scheduleDrainDeadline = { task -> drainTasks += task; SetupDispatchDeadline {} },
        )
        override fun start(offerSdp: String, prompt: String, voice: String?,
            callbacks: CodexRealtimeCallbacks): CodexRealtimeCall? {
            val lease = coordinator.start(1, "thread", offerSdp, prompt, voice, callbacks) ?: return null
            return CodexRealtimeCall { coordinator.stop(lease) }
        }
        override fun interruptCurrentTurn(): Boolean = error("No work interruption is allowed")
        fun requests(method: String) = sent.filter { it.getString("method") == method }
        fun expireDrain() = drainTasks.last().invoke()
        fun ackStop() {
            coordinator.onFrame(1, JSONObject().put("id", requests("thread/realtime/stop").last().getString("id"))
                .put("result", JSONObject()).toString())
        }
        fun event(method: String, params: JSONObject) {
            assertTrue(coordinator.onFrame(1, JSONObject().put("method", "thread/realtime/$method")
                .put("params", params.put("threadId", "thread")).toString()))
        }
        fun ready() {
            event("started", JSONObject().put("version", "v3").put("realtimeSessionId",
                requests("thread/realtime/start").last().getJSONObject("params").getString("realtimeSessionId")))
            event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        }
        fun open(events: Events = Events(), answer: Answer = Answer()) = CodexLiveSessionProvider(this, events).also {
            it.create(LiveSessionSetup(LiveVoiceSessionConfig(CodexLiveVoiceSession.MODEL, "cove"),
                "Native instructions"), "v=0\r\noffer", answer)
        }
    }

    private class Events : CodexLiveSessionProvider.Events {
        var starts = 0
        var closes = 0
        override fun onStarted() { starts++ }
        override fun onTranscript(role: String, text: String, isFinal: Boolean) = Unit
        override fun onClosed() { closes++ }
    }

    private class Answer : LiveSessionProvider.Callback {
        val failures = mutableListOf<LiveVoiceFailure>()
        override fun onCreated(answer: LiveSessionAnswer) = Unit
        override fun onFailure(failure: LiveVoiceFailure) { failures += failure }
    }
}
