package ai.hans.standard.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol checks on Android's real JSON implementation; no mic/account/network. */
class CodexRealtimeAndroidTest {
    @Test fun markerlessHangupBindingIsLocalAndFirstOwnerSurvivesCallReplacementOnAndroid() {
        for (handoffFirst in listOf(false, true)) {
            val sent = mutableListOf<JSONObject>()
            val errors = mutableListOf<CodexRealtimeIssue>()
            var counter = 0
            var localTurn: String? = null
            var localId = "00000000-0000-4000-8000-000000000001"
            val coordinator = CodexRealtimeCoordinator(send = { _, wire -> sent += JSONObject(wire); true },
                newId = { "voice-${++counter}" }, localHandoffTurn = { _, _ -> localTurn })
            fun event(name: String, params: JSONObject) = coordinator.onFrame(1,
                JSONObject().put("method", "thread/realtime/$name")
                    .put("params", params.put("threadId", "main")).toString())
            fun start(): Long {
                val lease = checkNotNull(coordinator.start(1, "main", "v=0", "No marker", null,
                    object : CodexRealtimeCallbacks {
                        override val voiceControlSessionId: String get() = localId
                        override fun onError(issue: CodexRealtimeIssue) { errors += issue }
                    }))
                val wire = sent.last()
                assertFalse(wire.toString().contains(localId))
                event("started", JSONObject().put("version", "v3")
                    .put("realtimeSessionId", wire.getJSONObject("params").getString("realtimeSessionId")))
                return lease
            }
            fun handoff(id: String) = event("itemAdded", JSONObject().put("item", JSONObject()
                .put("type", "handoff_request").put("handoff_id", id).put("item_id", "input-$id")))
            val a = start()
            if (handoffFirst) handoff("A")
            assertTrue(coordinator.claimTurnOrigin(1, "main", "turn"))
            if (!handoffFirst) {
                assertNull(coordinator.voiceControlSessionIdFor(1, "main", "turn"))
                localTurn = "turn"; handoff("A")
            }
            localTurn = "turn"
            assertEquals(localId, coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            coordinator.stop(a)
            assertNull(coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            event("closed", JSONObject().put("reason", "requested"))
            localId = "00000000-0000-4000-8000-000000000002"
            start(); handoff("B-steer")
            assertNull(coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            coordinator.completeVoiceTurn(1, "main", "turn")
            localTurn = null; handoff("B-new")
            assertTrue(coordinator.claimTurnOrigin(1, "main", "new-turn"))
            assertEquals(localId, coordinator.voiceControlSessionIdFor(1, "main", "new-turn"))
            assertTrue(errors.isEmpty())
        }
    }

    @Test fun bufferedAudioAndCanonicalItemReceiptsAreSessionFencedOnAndroid() {
        val sent = mutableListOf<JSONObject>()
        var counter = 0
        val items = mutableListOf<String>()
        val results = mutableListOf<Result<Unit>>()
        var deadlineCancelled = false
        val coordinator = CodexRealtimeCoordinator(send = { _, wire -> sent += JSONObject(wire); true },
            newId = { "audio-${++counter}" }, scheduleAudioDeadline = { SetupDispatchDeadline { deadlineCancelled = true } })
        val callbacks = object : CodexRealtimeCallbacks {
            override fun onItemCompleted(itemId: String, role: String, text: String) { items += "$itemId:$role:$text" }
            override fun onError(issue: CodexRealtimeIssue) { fail(issue.name) }
        }
        val lease = checkNotNull(coordinator.start(1, "main", "v=0", "prompt", null, callbacks,
            CodexRealtimeOptions(delegationAckFiller = false)))
        val sessionId = sent.first().getJSONObject("params").getString("realtimeSessionId")
        fun event(name: String, params: JSONObject) = coordinator.onFrame(1,
            JSONObject().put("method", "thread/realtime/$name").put("params", params.put("threadId", "main")).toString())
        assertFalse(sent.first().getJSONObject("params").getBoolean("delegationAckFiller"))
        assertFalse(coordinator.appendAudio(lease, 1, "main", "AAAAAA==", 24_000, results::add))
        event("started", JSONObject().put("version", "v3").put("realtimeSessionId", sessionId))
        assertTrue(coordinator.appendAudio(lease, 1, "main", "AAAAAA==", 24_000, results::add))
        assertFalse(coordinator.appendAudio(lease, 1, "main", "AAAAAA==", 24_000, results::add))
        val audio = sent.last()
        coordinator.onFrame(1, JSONObject().put("id", audio.getString("id")).put("result", JSONObject()).toString())
        assertTrue(results.single().isSuccess)
        assertTrue(deadlineCancelled)
        for (id in listOf("stale", sessionId, sessionId)) {
            event("item/completed", JSONObject().put("item", JSONObject().put("id", "item")
                .put("realtimeSessionId", id).put("type", "transcriptSegment").put("role", "user").put("text", "hello")))
        }
        assertEquals(listOf("item:user:hello"), items)
        coordinator.stop(lease)
        assertFalse(coordinator.appendAudio(lease, 1, "main", "AAAAAA==", 24_000, results::add))
    }

    @Test fun startedSdpCancellationAndErrorAreBoundedOnAndroid() {
        val sent = mutableListOf<JSONObject>()
        var counter = 0
        var ready = 0
        var sdpCount = 0
        val errors = mutableListOf<CodexRealtimeIssue>()
        val coordinator = CodexRealtimeCoordinator(send = { _, wire -> sent += JSONObject(wire); true },
            newId = { "android-${++counter}" })
        val callbacks = object : CodexRealtimeCallbacks {
            override fun onStarted() { ready++ }
            override fun onRemoteSdp(sdp: String) { sdpCount++ }
            override fun onError(issue: CodexRealtimeIssue) { errors += issue }
        }
        val lease = checkNotNull(coordinator.start(1, "main", "v=0\r\noffer", "prompt", "arbor", callbacks))
        fun event(name: String, params: JSONObject) = coordinator.onFrame(1,
            JSONObject().put("method", "thread/realtime/$name").put("params", params.put("threadId", "main")).toString())
        coordinator.onFrame(1, JSONObject().put("id", sent.first().getString("id")).put("result", JSONObject()).toString())
        assertEquals(0, ready)
        event("started", JSONObject().put("version", "v3").put("realtimeSessionId",
            sent.first().getJSONObject("params").getString("realtimeSessionId")))
        event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        assertEquals(1, ready); assertEquals(1, sdpCount)
        coordinator.stop(lease)
        event("sdp", JSONObject().put("sdp", "v=0\r\nlate"))
        assertEquals(1, sdpCount)
        assertEquals("thread/realtime/stop", sent.last().getString("method"))
        assertTrue(errors.isEmpty())
    }
}
