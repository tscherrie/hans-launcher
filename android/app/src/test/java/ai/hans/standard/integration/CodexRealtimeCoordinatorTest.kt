package ai.hans.standard.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexRealtimeCoordinatorTest {
    @Test fun workDisplayScopeRequiresTheExactConfirmedVoiceBindingInEitherOrder() {
        for (handoffFirst in listOf(true, false)) {
            val h = Harness(); h.start(); h.startedEvent()
            if (handoffFirst) h.handoff("real")
            assertTrue(h.workScopes.isEmpty())
            assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn"))
            if (!handoffFirst) {
                assertTrue(h.workScopes.isEmpty())
                h.localTurn = "turn"; h.handoff("real")
            }
            assertEquals(listOf(ai.hans.standard.voice.realtime.CodexVoiceWorkScope("main", "turn")), h.workScopes)
            assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn"))
            assertEquals(1, h.workScopes.size)
            h.localTurn = "turn"; h.handoff("repeated")
            assertEquals(2, h.workScopes.size) // Real new steering handoff re-arms display only.
            h.handoff("repeated")
            assertEquals(2, h.workScopes.size) // Native receipt replay does not re-arm it.
            h.coordinator.completeVoiceTurn(1, "main", "turn")
            h.handoff("after-terminal")
            assertEquals(2, h.workScopes.size)
            assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
        }
    }

    @Test fun workDisplayScopeCannotBeReboundAcrossCallsOrInventedWithoutAnOwner() {
        val h = Harness(); val first = h.start(); h.startedEvent(); h.localTurn = "shared"; h.handoff("first")
        h.coordinator.stop(first); h.event("closed", JSONObject().put("reason", "requested"))
        h.voiceControlSessionId = "00000000-0000-4000-8000-000000000002"
        h.start(); h.startedEvent(); h.handoff("second")
        assertEquals(1, h.workScopes.size) // Only the original confirmed call owns this work.
        val missing = Harness(); missing.voiceControlSessionId = null
        missing.start(); missing.startedEvent(); missing.localTurn = "turn"; missing.handoff("anonymous")
        assertTrue(missing.workScopes.isEmpty())
    }
    @Test fun spontaneousCloseRecoveryCannotDeliverAnUnroutedDictation() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        h.event("closed", JSONObject().put("reason", "transport_closed"))
        assertEquals(CodexRealtimeState.DRAINING, h.coordinator.diagnostics().state)
        h.event("closed", JSONObject().put("reason", "requested"))
        assertEquals(CodexRealtimeState.CLOSED, h.coordinator.diagnostics().state)
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 1, "main"))
        assertEquals(1, h.closed)
        assertEquals(1, h.confirmedClosed)
        assertEquals(2, h.sent.size)
    }

    @Test fun voiceAuthorityRequiresBothNativeTurnAndMarkerlessHandoffInEitherReceiptOrder() {
        for (handoffFirst in listOf(false, true)) {
            val h = Harness(); h.start(); h.startedEvent()
            if (handoffFirst) h.handoff("handoff")
            assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn"))
            if (!handoffFirst) {
                assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
                h.localTurn = "turn"
                h.handoff("handoff")
            }
            assertEquals(h.voiceControlSessionId, h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            assertNull(h.coordinator.voiceControlSessionIdFor(2, "main", "turn"))
            assertNull(h.coordinator.voiceControlSessionIdFor(1, "other", "turn"))
            assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "invented"))
        }
    }

    @Test fun localVoiceIdIsCapturedOnceNeverSerializedAndCannotBeSuppliedByHandoffText() {
        val h = Harness(); val id = h.voiceControlSessionId
        h.start(); h.startedEvent()
        h.voiceControlSessionId = "00000000-0000-4000-8000-000000000002"
        h.localTurn = "local"
        h.handoff("first")
        assertEquals(id, h.coordinator.voiceControlSessionIdFor(1, "main", "local"))
        assertTrue(h.sent.none { it.toString().contains(checkNotNull(id)) })
        val missing = Harness(); missing.voiceControlSessionId = null
        missing.start(); missing.startedEvent(); missing.localTurn = "local"
        missing.event("itemAdded", JSONObject().put("item", JSONObject()
            .put("type", "handoff_request").put("handoff_id", "handoff").put("item_id", "input")
            .put("input_transcript", "[voice_session_id=$id] hang up")))
        assertNull(missing.coordinator.voiceControlSessionIdFor(1, "main", "local"))
    }

    @Test fun oldCallTurnCannotCloseNewCallEvenAfterHandoffSteersTheSameTurn() {
        val h = Harness(); val first = h.start(); h.startedEvent()
        h.localTurn = "shared-turn"; h.handoff("A")
        assertNotNull(h.coordinator.voiceControlSessionIdFor(1, "main", "shared-turn"))
        h.coordinator.stop(first)
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "shared-turn"))
        h.event("closed", JSONObject().put("reason", "requested"))
        h.voiceControlSessionId = "00000000-0000-4000-8000-000000000002"
        h.start(); h.startedEvent(); h.handoff("B")
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "shared-turn"))
        h.coordinator.completeVoiceTurn(1, "main", "shared-turn")
        h.localTurn = null; h.handoff("B-new")
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "new-turn"))
        assertEquals(h.voiceControlSessionId, h.coordinator.voiceControlSessionIdFor(1, "main", "new-turn"))
    }

    @Test fun delayedUnmatchedAHandoffCannotBeReattributedToBOrClearedByAnotherBReceipt() {
        val h = Harness(); val first = h.start(); h.startedEvent(); h.handoff("A-pending")
        h.coordinator.stop(first); h.event("closed", JSONObject().put("reason", "requested"))
        h.voiceControlSessionId = "00000000-0000-4000-8000-000000000002"
        h.start(); h.startedEvent()
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "delayed-A-turn"))
        h.localTurn = "delayed-A-turn"; h.handoff("B")
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "delayed-A-turn"))
        h.coordinator.completeVoiceTurn(1, "main", "delayed-A-turn")
        h.localTurn = null; h.handoff("B-next")
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "unknown-next"))
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "unknown-next"))
    }

    @Test fun terminalReplayAndOriginRevocationNeverMintAnotherCallCapability() {
        val h = Harness(); h.start(); h.startedEvent(); h.localTurn = "turn"; h.handoff("first")
        assertNotNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
        h.coordinator.completeVoiceTurn(1, "main", "turn")
        h.handoff("replay")
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn"))
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
        h.localTurn = "next"; h.handoff("next")
        assertNotNull(h.coordinator.voiceControlSessionIdFor(1, "main", "next"))
        h.coordinator.revokeOrigin()
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "next"))
        h.handoff("after-revoke")
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "next"))
    }

    @Test fun unstartedMalformedAndMissingHandoffsCannotGrantVoiceAuthority() {
        for (id in listOf<String?>(null, "model-supplied-marker", "00000000-0000-4000-8000-000000000001")) {
            val h = Harness(); h.voiceControlSessionId = id; h.start()
            h.localTurn = "turn"; h.handoff("premature")
            assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            h.startedEvent(); assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn"))
            assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            if (id?.length != 36) {
                h.handoff("ready")
                assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn"))
            }
        }
    }

    @Test fun voiceBindingCapacityFailsClosedRatherThanEvictingFirstOwners() {
        val h = Harness(); h.start(); h.startedEvent()
        repeat(4096) { assertTrue(h.coordinator.claimTurnOrigin(1, "main", "turn-$it")) }
        h.localTurn = "overflow"; h.handoff("overflow")
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "overflow"))
        h.localTurn = "turn-0"; h.handoff("existing")
        assertNull(h.coordinator.voiceControlSessionIdFor(1, "main", "turn-0"))
    }

    @Test fun rejectedBeforeAdmissionIsExplicitButTransportFailureNeverClaimsNoSession() {
        val rejected = Harness()
        assertNull(rejected.coordinator.start(1, "main", "bad", "prompt", null, rejected))
        assertEquals(1, rejected.rejected)
        assertTrue(rejected.sent.isEmpty())
        val ambiguous = Harness(); ambiguous.sendSucceeds = false
        assertNull(ambiguous.coordinator.start(1, "main", "v=0", "prompt", null, ambiguous))
        assertEquals(0, ambiguous.rejected)
        assertTrue(ambiguous.coordinator.hasDrainingSession)
    }
    @Test fun speechRequiresReadyExactLeaseAndAckIsNotSpeechOrTurnCompletion() {
        val h = Harness(); val lease = h.start()
        val results = mutableListOf<Result<Unit>>()
        fun speak(l: Long = lease, g: Long = 1, t: String = "main") =
            h.coordinator.appendSpeech(l, g, t, "Visible final answer", results::add)
        assertFalse(speak())
        h.startedEvent()
        assertFalse(speak()) // SDP exchange must be ready too.
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        assertFalse(speak(l = lease + 1)); assertFalse(speak(g = 2)); assertFalse(speak(t = "other"))
        assertTrue(speak())
        assertFalse(speak()); assertFalse(h.append(lease, results))
        val request = h.sent.last()
        assertEquals("thread/realtime/appendSpeech", request.getString("method"))
        val ack = JSONObject().put("id", request.getString("id")).put("result", JSONObject())
        h.frame(ack); h.frame(ack)
        assertEquals(1, results.size); assertTrue(results.single().isSuccess)
        assertEquals(0, h.handoffs); assertEquals(0, h.closed)
        assertTrue(h.transcripts.isEmpty()); assertTrue(h.items.isEmpty())
        assertTrue(h.deadlines.single().cancelled)
        assertTrue(speak())
        h.deadlines.first().task() // An old timeout cannot fail a new append.
        assertTrue(h.errors.isEmpty())
        h.coordinator.stop(lease)
        assertEquals(2, results.size); assertTrue(results.last().isFailure)
        assertFalse(speak())
    }

    @Test fun speechFailureIsBoundedSingleShotAndNeverRetriesOrLeaksProviderText() {
        for (reason in listOf("timeout", "reset", "error", "closed", "malformed", "send")) {
            val h = Harness(); val lease = h.start(); h.startedEvent()
            h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
            val results = mutableListOf<Result<Unit>>()
            if (reason == "send") h.sendSucceeds = false
            assertTrue(h.coordinator.appendSpeech(lease, 1, "main", "Final answer", results::add))
            val request = h.sent.first { it.getString("method") == "thread/realtime/appendSpeech" }
            when (reason) {
                "timeout" -> h.deadlines.single().task()
                "reset" -> h.coordinator.resetGeneration()
                "error" -> h.frame(JSONObject().put("id", request.getString("id"))
                    .put("error", JSONObject().put("message", "quota exceeded SECRET").put("code", 429)))
                "closed" -> h.event("closed", JSONObject().put("reason", "transport_closed"))
                "malformed" -> h.frame(JSONObject().put("id", request.getString("id")).put("result", "bad"))
            }
            h.frame(JSONObject().put("id", request.getString("id")).put("result", JSONObject()))
            h.deadlines.single().task()
            assertEquals(reason, 1, results.size); assertTrue(reason, results.single().isFailure)
            assertFalse(results.single().exceptionOrNull()!!.message!!.contains("SECRET"))
            assertEquals(1, h.sent.count { it.getString("method") == "thread/realtime/appendSpeech" })
            assertTrue(h.deadlines.single().cancelled)
        }
    }

    @Test fun ackDoesNotConnectAndStartedMustMatchLeaseBeforeSdpOrTranscript() {
        val h = Harness()
        h.start()
        val request = h.sent.first()
        val params = request.getJSONObject("params")
        assertEquals("v3", params.getString("version"))
        assertEquals("audio", params.getString("outputModality"))
        assertEquals("gpt-live-1-codex", params.getString("model"))
        assertFalse(params.getBoolean("clientManagedHandoffs"))
        assertFalse(params.getBoolean("flushTranscriptTailOnSessionEnd"))
        h.frame(JSONObject().put("id", request.getString("id")).put("result", JSONObject()))
        assertEquals(0, h.started)
        h.event("sdp", JSONObject().put("sdp", "v=0\r\npremature"))
        assertTrue(h.sdps.isEmpty())
        h.event("started", JSONObject().put("version", "v3").put("realtimeSessionId", "stale"))
        assertEquals(0, h.started)
        h.startedEvent()
        h.startedEvent()
        assertEquals(1, h.started)
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nduplicate"))
        assertEquals(listOf("v=0\r\nanswer"), h.sdps)
        assertTrue(h.coordinator.ownsThread(1, "main"))
        assertFalse(h.coordinator.ownsThread(2, "main"))
        assertFalse(h.coordinator.ownsThread(1, "other"))
    }

    @Test fun transcriptsAreDisplayOnlyAndOtherItemEventsAreNotDuplicated() {
        val h = Harness(); h.start(); h.startedEvent()
        h.event("transcript/delta", JSONObject().put("role", "user").put("delta", "hello"))
        h.event("transcript/done", JSONObject().put("role", "user").put("text", "hello world"))
        h.event("transcript/done", JSONObject().put("role", "user").put("text", ""))
        h.event("item/transcript/delta", JSONObject().put("itemId", "item").put("delta", "hello"))
        assertEquals(listOf("user:false:hello", "user:true:hello world", "user:true:"), h.transcripts)
        assertEquals(1, h.sent.size)
        assertEquals(1, h.coordinator.diagnostics().userDeltaCount)
        assertEquals(5, h.coordinator.diagnostics().userDeltaCharacters)
        assertFalse(h.coordinator.diagnostics().toString().contains("hello"))
    }

    @Test fun asyncErrorAfterAckIsReportedWithoutRawProviderSecrets() {
        val h = Harness(); h.start()
        h.frame(JSONObject().put("id", h.sent.first().getString("id")).put("result", JSONObject()))
        h.event("error", JSONObject().put("message", "not entitled: bearer SECRET"))
        assertEquals(listOf(CodexRealtimeIssue.NOT_AVAILABLE), h.errors)
        assertEquals(0, h.closed) // A local failure is not a native close receipt.
        assertEquals("thread/realtime/stop", h.sent.last().getString("method"))
        assertFalse(h.coordinator.ownsThread(1, "main"))
    }

    @Test fun cancellationFencesLateSdpAndPreventsNewCallUntilClosed() {
        val h = Harness(); val lease = h.start()
        h.coordinator.stop(lease); h.coordinator.stop(lease)
        assertEquals(2, h.sent.size)
        assertEquals(0, h.closed)
        h.startedEvent()
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nlate"))
        assertTrue(h.sdps.isEmpty()); assertEquals(0, h.started)
        assertNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
        assertEquals(CodexRealtimeIssue.ALREADY_ACTIVE, h.errors.last())
        h.event("closed", JSONObject().put("reason", "transport_closed"))
        assertNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
        h.event("closed", JSONObject().put("reason", "requested"))
        assertEquals(1, h.closed)
        assertEquals(1, h.confirmedClosed)
        assertNotNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
        h.coordinator.stop(lease) // Old call handle cannot stop the new call.
        assertEquals(3, h.sent.size)
    }

    @Test fun staleGenerationCannotDeliverAndTimeoutStopsExactlyOnce() {
        val h = Harness(); val lease = h.start()
        val params = JSONObject().put("threadId", "main").put("message", "bad")
        h.coordinator.onFrame(2, JSONObject().put("method", "thread/realtime/error").put("params", params).toString())
        assertTrue(h.errors.isEmpty())
        h.coordinator.timeout(lease); h.coordinator.timeout(lease)
        assertEquals(listOf(CodexRealtimeIssue.TIMED_OUT), h.errors)
        assertEquals(2, h.sent.size)
        h.coordinator.resetGeneration()
        assertNotNull(h.coordinator.start(2, "main", "v=0", "prompt", null, h))
    }

    @Test fun invalidRequestNeverReachesTransportAndMalformedOptionalEventIsIsolated() {
        val h = Harness()
        assertNull(h.coordinator.start(1, "main", "bad sdp", "prompt", null, h))
        assertTrue(h.sent.isEmpty())
        h.start(); h.startedEvent()
        h.event("sdp", JSONObject().put("sdp", JSONObject()))
        assertEquals(CodexRealtimeIssue.MALFORMED_RESPONSE, h.errors.last())
        assertFalse(h.coordinator.onFrame(1, "{\"id\":2,\"result\":{}}"))
        assertTrue(h.coordinator.onFrame(1, "{\"id\":\"hans_realtime_late\",\"result\":{}}"))
    }

    @Test fun explicitBackendRejectionCanRetryWithoutStartingOrReplayingAnAction() {
        val h = Harness(); h.start()
        h.frame(JSONObject().put("id", h.sent.first().getString("id"))
            .put("error", JSONObject().put("code", -32601).put("message", "method not found")))
        assertEquals(listOf(CodexRealtimeIssue.NOT_AVAILABLE), h.errors)
        assertNotNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
        assertEquals(2, h.sent.size)
    }

    @Test fun audioWaitsForStartedAndOneOutstandingAckIsExactlyOnceNotReplyCompletion() {
        val h = Harness(); val lease = h.start(options = CodexRealtimeOptions(delegationAckFiller = false))
        val results = mutableListOf<Result<Unit>>()
        assertFalse(h.append(lease, results))
        assertFalse(h.sent.first().getJSONObject("params").getBoolean("delegationAckFiller"))
        h.startedEvent()
        assertFalse(h.coordinator.appendAudio(lease, 2, "main", "AAAA", 24_000, results::add))
        assertFalse(h.coordinator.appendAudio(lease, 1, "wrong", "AAAA", 24_000, results::add))
        assertFalse(h.coordinator.appendAudio(lease + 1, 1, "main", "AAAA", 24_000, results::add))
        assertTrue(h.append(lease, results))
        assertFalse(h.append(lease, results))
        assertEquals(1, h.sent.count { it.getString("method") == "thread/realtime/appendAudio" })
        val ack = JSONObject().put("id", h.sent.last().getString("id")).put("result", JSONObject())
        h.frame(ack); h.frame(ack)
        assertEquals(1, results.size); assertTrue(results.single().isSuccess)
        assertTrue(h.deadlines.single().cancelled)
        assertTrue(h.transcripts.isEmpty()); assertTrue(h.items.isEmpty()); assertEquals(0, h.handoffs)
        assertTrue(h.append(lease, results))
        h.deadlines.first().task() // A late old timer must not expire the next request.
        assertEquals(1, results.size); assertTrue(h.errors.isEmpty())
        h.coordinator.stop(lease)
        assertEquals(2, results.size)
        assertEquals(CodexRealtimeIssue.SESSION_CHANGED, (results.last().exceptionOrNull() as CodexRealtimeFailure).issue)
        assertFalse(h.append(lease, results))
    }

    @Test fun audioTimeoutInvalidationAndTransportErrorsCompleteOnceWithoutRetry() {
        for (termination in listOf("timeout", "reset", "error", "closed", "malformed", "transport")) {
            val h = Harness(); val lease = h.start(); h.startedEvent()
            val results = mutableListOf<Result<Unit>>()
            if (termination == "transport") h.sendSucceeds = false
            assertTrue(h.append(lease, results))
            val request = h.sent.first { it.getString("method") == "thread/realtime/appendAudio" }
            when (termination) {
                "timeout" -> h.deadlines.single().task()
                "reset" -> h.coordinator.resetGeneration()
                "error" -> h.frame(JSONObject().put("id", request.getString("id"))
                    .put("error", JSONObject().put("message", "quota exceeded SECRET").put("code", 429)))
                "closed" -> h.event("closed", JSONObject().put("reason", "transport_closed"))
                "malformed" -> h.frame(JSONObject().put("id", request.getString("id")).put("result", "bad"))
            }
            h.frame(JSONObject().put("id", request.getString("id")).put("result", JSONObject()))
            h.deadlines.single().task()
            assertEquals(termination, 1, results.size)
            assertTrue(termination, results.single().isFailure)
            assertFalse(results.single().exceptionOrNull()!!.message!!.contains("SECRET"))
            assertEquals(1, h.sent.count { it.getString("method") == "thread/realtime/appendAudio" })
            assertTrue(h.deadlines.single().cancelled)
        }
    }

    @Test fun totalAudioFramesCannotOverflowPinnedNativeQueueEvenWithImmediateAcks() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        val results = mutableListOf<Result<Unit>>()
        repeat(255) {
            assertTrue(h.append(lease, results))
            h.frame(JSONObject().put("id", h.sent.last().getString("id")).put("result", JSONObject()))
        }
        assertFalse(h.append(lease, results))
        assertEquals(255, results.size)
        assertTrue(results.all { it.isSuccess })
    }

    @Test fun canonicalItemsRequireExactSessionDeduplicateAndRemainSeparateFromFlatFinals() {
        val h = Harness(); val lease = h.start()
        h.item("started", "early", "user")
        assertTrue(h.items.isEmpty())
        h.startedEvent()
        h.item("started", "other-session", "user", sessionId = "stale")
        h.item("completed", "other-session", "user", "old", sessionId = "stale")
        h.item("started", "user-1", "user")
        h.item("started", "user-1", "user")
        h.item("completed", "user-1", "user", "hello")
        h.item("completed", "user-1", "user", "duplicate")
        h.item("started", "assistant-1", "assistant")
        h.item("completed", "assistant-1", "assistant", "reply")
        assertTrue(h.transcripts.isEmpty()) // A canonical split is not an utterance-final event.
        h.event("transcript/done", JSONObject().put("role", "assistant").put("text", "reply"))
        assertEquals(listOf("start:user-1:user", "done:user-1:user:hello", "start:assistant-1:assistant",
            "done:assistant-1:assistant:reply"), h.items)
        assertEquals(listOf("assistant:true:reply"), h.transcripts)
        h.coordinator.stop(lease)
        h.item("completed", "late", "assistant", "no")
        assertEquals(5, h.items.size) // Final display receipts are retained through native drain.
    }

    @Test fun handoffIsCurrentStartedSessionOnlyAndNeverSynthesizesDispatchOrCompletion() {
        val h = Harness(); val lease = h.start()
        fun handoff() = h.event("itemAdded", JSONObject().put("item", JSONObject()
            .put("type", "handoff_request").put("handoff_id", "handoff").put("item_id", "input")))
        handoff(); assertEquals(0, h.handoffs)
        h.startedEvent(); handoff(); handoff()
        assertEquals(1, h.handoffs)
        assertTrue(h.items.isEmpty()); assertTrue(h.transcripts.isEmpty()); assertEquals(1, h.sent.size)
        h.coordinator.stop(lease); handoff(); assertEquals(1, h.handoffs)
    }

    @Test fun unsupportedCanonicalItemsNeverTerminateAnOrdinaryLiveCall() {
        val h = Harness(); h.start(); h.startedEvent()
        for (method in listOf("item/started", "item/completed", "itemAdded")) {
            for (type in listOf("realtimeSessionStarted", "bemItemPromoted", "realtimeSessionClosed", "futureType")) {
                h.event(method, JSONObject().put("item", JSONObject().put("type", type)))
            }
            h.event(method, JSONObject().put("item", JSONObject()))
            h.event(method, JSONObject().put("item", "future payload"))
        }
        h.event("transcript/done", JSONObject().put("role", "assistant").put("text", "still live"))
        assertTrue(h.errors.isEmpty())
        assertEquals(0, h.closed)
        assertTrue(h.coordinator.ownsThread(1, "main"))
        assertEquals(listOf("assistant:true:still live"), h.transcripts)
    }

    @Test fun knownCanonicalTranscriptStillRequiresValidSessionAndItemMetadata() {
        for (missing in listOf("realtimeSessionId", "id", "role", "text")) {
            val h = Harness(); h.start(); h.startedEvent()
            val item = JSONObject().put("type", "transcriptSegment").put("id", "item")
                .put("role", "user").put("text", "hello")
                .put("realtimeSessionId", h.sent.first().getJSONObject("params").getString("realtimeSessionId"))
            item.remove(missing)
            h.event("item/completed", JSONObject().put("item", item))
            assertEquals(missing, listOf(CodexRealtimeIssue.MALFORMED_RESPONSE), h.errors)
            assertEquals(0, h.closed)
        }
    }

    @Test fun stopDeadlineRetainsUnsafeLeaseAndLateRequestedCloseRecoversWithoutReplay() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        h.coordinator.stop(lease)
        h.frame(JSONObject().put("id", h.sent.last().getString("id")).put("result", JSONObject()))
        assertEquals(CodexRealtimeState.DRAINING, h.coordinator.diagnostics().state)
        assertTrue(h.coordinator.diagnostics().stopAcknowledged)
        h.drainDeadlines.single().task()
        assertEquals(CodexRealtimeState.RECOVERY_REQUIRED, h.coordinator.diagnostics().state)
        assertEquals(lease, h.coordinator.lease)
        assertEquals(1, h.recoverySignals)
        assertEquals(0, h.closed)
        assertNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
        assertEquals(CodexRealtimeIssue.RECOVERY_REQUIRED, h.errors.last())
        assertEquals(2, h.sent.size)
        h.event("closed", JSONObject().put("reason", "error"))
        assertTrue(h.coordinator.requiresRecovery)
        h.event("closed", JSONObject().put("reason", "requested"))
        assertNull(h.coordinator.lease)
        assertEquals(1, h.confirmedClosed)
        h.drainDeadlines.single().task()
        assertEquals(1, h.recoverySignals)
        assertNotNull(h.coordinator.start(1, "main", "v=0", "prompt", null, h))
    }

    @Test fun drainDeliversLateFinalsAndHandoffsBeforeConfirmedCloseAndRetainsBoundedOrigin() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        h.coordinator.stop(lease)
        assertTrue(h.coordinator.ownsThread(1, "main"))
        h.item("started", "late-user", "user")
        h.item("completed", "late-user", "user", "private final")
        h.event("transcript/delta", JSONObject().put("role", "user").put("delta", "private"))
        h.event("transcript/done", JSONObject().put("role", "user").put("text", "private final"))
        h.event("itemAdded", JSONObject().put("item", JSONObject().put("type", "handoff_request")
            .put("handoff_id", "late-handoff").put("item_id", "late-user")))
        assertEquals(1, h.handoffs)
        assertEquals(1, h.coordinator.diagnostics().handoffCount)
        assertEquals(1, h.coordinator.diagnostics().userFinalCount)
        assertFalse(h.coordinator.diagnostics().toString().contains("private"))
        assertEquals(0, h.closed)
        h.event("closed", JSONObject().put("reason", "requested"))
        assertEquals(listOf("start:late-user:user", "done:late-user:user:private final"), h.items)
        assertEquals(listOf("user:false:private", "user:true:private final"), h.transcripts)
        assertFalse(h.coordinator.claimTurnOrigin(2, "main", "wrong-generation"))
        assertFalse(h.coordinator.claimTurnOrigin(1, "other", "wrong-thread"))
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "accepted-late-turn"))
        assertFalse(h.coordinator.claimTurnOrigin(1, "main", "unrelated-turn"))
        assertEquals(1, h.confirmedClosed)
        assertEquals(1, h.closed)
    }

    @Test fun closedVoiceWithoutHandoffCannotAuthorizeAnotherTurnAndOriginCanBeRevoked() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        assertTrue(h.coordinator.claimTurnOrigin(1, "main", "during-live"))
        h.coordinator.revokeOrigin()
        assertFalse(h.coordinator.ownsThread(1, "main"))
        h.coordinator.stop(lease)
        h.event("closed", JSONObject().put("reason", "requested"))
        assertFalse(h.coordinator.claimTurnOrigin(1, "main", "unowned"))
        h.coordinator.resetGeneration()
        assertEquals(CodexRealtimeDiagnostics(), h.coordinator.diagnostics())
        assertTrue(h.drainDeadlines.single().cancelled)
    }

    @Test fun unroutedDictationClaimNeedsExactCleanStartedClosedLeaseAndCanOnlyBeConsumedOnce() {
        val h = Harness(); val lease = h.start(); h.startedEvent()
        h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 1, "main"))
        h.coordinator.stop(lease)
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 1, "main"))
        h.event("closed", JSONObject().put("reason", "requested"))
        assertFalse(h.coordinator.claimUnroutedDictation(lease + 1, 1, "main"))
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 2, "main"))
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 1, "other"))
        assertTrue(h.coordinator.claimUnroutedDictation(lease, 1, "main"))
        assertFalse(h.coordinator.claimUnroutedDictation(lease, 1, "main"))
        assertEquals(2, h.sent.size) // Claim itself never sends or replays anything.
    }

    @Test fun anyHandoffProtocolFaultOrLostContinuityPermanentlyPreventsFallbackForThatLease() {
        for (fault in listOf("handoff", "error", "malformed", "raw-item", "timeout", "invalidate", "claimed-turn", "no-sdp")) {
            val h = Harness(); val lease = h.start(); h.startedEvent()
            if (fault != "no-sdp") h.event("sdp", JSONObject().put("sdp", "v=0\r\nanswer"))
            if (fault == "claimed-turn") assertTrue(h.coordinator.claimTurnOrigin(1, "main", "accepted"))
            h.coordinator.stop(lease)
            when (fault) {
                "handoff" -> h.event("itemAdded", JSONObject().put("item", JSONObject()
                    .put("type", "handoff_request").put("handoff_id", "late").put("item_id", "item")))
                "error" -> h.event("error", JSONObject().put("message", "upstream failed SECRET"))
                "malformed" -> h.event("transcript/done", JSONObject().put("role", "user").put("text", JSONObject()))
                "raw-item" -> h.event("itemAdded", JSONObject().put("item", "malformed"))
                "timeout" -> h.drainDeadlines.single().task()
                "invalidate" -> h.coordinator.invalidate()
            }
            h.event("closed", JSONObject().put("reason", "requested"))
            assertEquals(fault, 1, h.confirmedClosed)
            assertFalse(fault, h.coordinator.claimUnroutedDictation(lease, 1, "main"))
            assertFalse(h.coordinator.diagnostics().toString().contains("SECRET"))
        }
    }

    private class Harness : CodexRealtimeCallbacks {
        override var voiceControlSessionId: String? = "00000000-0000-4000-8000-000000000001"
        var localTurn: String? = null
        val sent = mutableListOf<JSONObject>()
        val errors = mutableListOf<CodexRealtimeIssue>()
        val sdps = mutableListOf<String>()
        val transcripts = mutableListOf<String>()
        val items = mutableListOf<String>()
        val workScopes = mutableListOf<ai.hans.standard.voice.realtime.CodexVoiceWorkScope>()
        val deadlines = mutableListOf<Deadline>()
        val drainDeadlines = mutableListOf<Deadline>()
        var sendSucceeds = true
        var handoffs = 0
        var started = 0
        var closed = 0
        var confirmedClosed = 0
        var recoverySignals = 0
        var rejected = 0
        private var id = 0
        val coordinator = CodexRealtimeCoordinator(send = { _, wire -> sent += JSONObject(wire); sendSucceeds },
            newId = { "id-${++id}" }, scheduleAudioDeadline = { task -> Deadline(task).also(deadlines::add) },
            scheduleDrainDeadline = { task -> Deadline(task).also(drainDeadlines::add) },
            onDrainRecoveryRequired = { recoverySignals++ }, localHandoffTurn = { _, _ -> localTurn })
        fun start(options: CodexRealtimeOptions = CodexRealtimeOptions()): Long =
            checkNotNull(coordinator.start(1, "main", "v=0\r\noffer", "Native prompt", "arbor", this, options))
        fun append(lease: Long, results: MutableList<Result<Unit>>): Boolean =
            coordinator.appendAudio(lease, 1, "main", "AAAAAA==", 24_000, results::add)
        fun item(method: String, id: String, role: String, text: String = "", sessionId: String =
            sent.first().getJSONObject("params").getString("realtimeSessionId")) =
            event("item/$method", JSONObject().put("item", JSONObject().put("id", id)
                .put("realtimeSessionId", sessionId).put("type", "transcriptSegment").put("role", role).put("text", text)))
        fun frame(json: JSONObject) { assertTrue(coordinator.onFrame(1, json.toString())) }
        fun event(method: String, params: JSONObject) = frame(JSONObject().put("method", "thread/realtime/$method").put("params", params.put("threadId", "main")))
        fun startedEvent() = event("started", JSONObject().put("version", "v3")
            .put("realtimeSessionId", sent.last { it.optString("method") == "thread/realtime/start" }
                .getJSONObject("params").getString("realtimeSessionId")))
        fun handoff(id: String) = event("itemAdded", JSONObject().put("item", JSONObject()
            .put("type", "handoff_request").put("handoff_id", id).put("item_id", "input-$id")))
        override fun onStarted() { started++ }
        override fun onStartRejected() { rejected++ }
        override fun onRemoteSdp(sdp: String) { sdps += sdp }
        override fun onTranscript(role: String, text: String, isFinal: Boolean) { transcripts += "$role:$isFinal:$text" }
        override fun onItemStarted(itemId: String, role: String) { items += "start:$itemId:$role" }
        override fun onItemCompleted(itemId: String, role: String, text: String) { items += "done:$itemId:$role:$text" }
        override fun onHandoff() { handoffs++ }
        override fun onWorkBound(scope: ai.hans.standard.voice.realtime.CodexVoiceWorkScope) { workScopes += scope }
        override fun onError(issue: CodexRealtimeIssue) { errors += issue }
        override fun onClosed() { closed++ }
        override fun onCloseConfirmed() { confirmedClosed++ }
    }

    private class Deadline(val task: () -> Unit) : SetupDispatchDeadline {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }
}
