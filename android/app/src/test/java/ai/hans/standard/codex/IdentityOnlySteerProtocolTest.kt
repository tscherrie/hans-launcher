package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityOnlySteerProtocolTest {
    @Test
    fun recoveredIdentitySteerCarriesOnlyExactIdsAndInputNeverModelSettings() {
        val request = request()
        val json = JSONObject(request.json)
        val params = json.getJSONObject("params")
        assertEquals("turn/steer", json.getString("method"))
        assertEquals(setOf("threadId", "expectedTurnId", "input", "clientUserMessageId"), params.keys().asSequence().toSet())
        assertEquals("thread-resumed", params.getString("threadId"))
        assertEquals("turn-resumed", params.getString("expectedTurnId"))
        assertEquals("dictation-final-1", params.getString("clientUserMessageId"))
        assertEquals("Noch eine Ergänzung", params.getJSONArray("input").getJSONObject(0).getString("text"))
        assertNull((request.context as RequestContext.TurnSteer).effectiveOptions)
    }

    @Test
    fun successfulIdentityAckNeverInventsOptionsInResultOrReducer() {
        val correlator = ResponseCorrelator().apply { register(request()) }
        val response = correlator.accept("""{"id":1,"result":{"turnId":"turn-resumed"}}""") as CorrelatedResponse.Success
        val result = response.result as TurnSteerResult
        assertEquals("thread-resumed", result.threadId)
        assertEquals("turn-resumed", result.turnId)
        assertNull(result.effectiveOptions)
        val snapshot = CodexSessionReducer().apply(response)
        assertEquals("thread-resumed", snapshot.currentThreadId)
        assertEquals("turn-resumed", snapshot.threads.single().currentTurn?.turnId)
        assertEquals(TurnStatus.IN_PROGRESS, snapshot.threads.single().currentTurn?.status)
        assertNull(snapshot.threads.single().effectiveOptions)
    }

    @Test
    fun aDifferentAcknowledgedTurnCannotSatisfyTheRecoveredIdentityFence() {
        val correlator = ResponseCorrelator().apply { register(request()) }
        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept("""{"id":1,"result":{"turnId":"turn-foreign"}}""")
        }
        assertEquals(1, correlator.pendingCount())
        val exact = correlator.accept("""{"id":1,"result":{"turnId":"turn-resumed"}}""") as CorrelatedResponse.Success
        assertNull((exact.result as TurnSteerResult).effectiveOptions)
    }

    @Test
    fun knownOptionsSteerRetainsItsPreviousWireAndConfirmedOptionsSemantics() {
        val active = ActiveTurn("thread-resumed", "turn-resumed", DispatchOptions.LUNA_MAX)
        val known = AppServerRequests.turnSteer(
            RequestId.Number(1), active, listOf(CodexInput.Text("Noch eine Ergänzung")), "dictation-final-1",
        )
        assertEquals(request().json, known.json)
        val correlator = ResponseCorrelator().apply { register(known) }
        val response = correlator.accept("""{"id":1,"result":{"turnId":"turn-resumed"}}""") as CorrelatedResponse.Success
        assertEquals(DispatchOptions.LUNA_MAX, (response.result as TurnSteerResult).effectiveOptions)
        assertEquals(DispatchOptions.LUNA_MAX, CodexSessionReducer().apply(response).threads.single().effectiveOptions)
    }

    @Test
    fun recoveredSteerRequiresBoundedNonblankIdentitiesAndAtLeastOneInput() {
        for (invalid in listOf("", "turn\ninjected", "a".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { request(threadId = invalid) }
            assertThrows(IllegalArgumentException::class.java) { request(turnId = invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { request(input = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { request(input = List(33) { CodexInput.Text("x") }) }
    }

    private fun request(
        threadId: String = "thread-resumed",
        turnId: String = "turn-resumed",
        input: List<CodexInput> = listOf(CodexInput.Text("Noch eine Ergänzung")),
    ) = AppServerRequests.turnSteerKnownIdentity(
        RequestId.Number(1), threadId, turnId, input, "dictation-final-1",
    )
}
