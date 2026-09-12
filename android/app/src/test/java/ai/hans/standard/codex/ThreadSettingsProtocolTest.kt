package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSettingsProtocolTest {
    @Test
    fun updateHasOnlyExplicitModelSettingsAndKeepsRequestCorrelation() {
        val request = request()
        val json = JSONObject(request.json)
        val params = json.getJSONObject("params")
        assertEquals("thread/settings/update", json.getString("method"))
        assertEquals(1L, json.getLong("id"))
        assertFalse(json.has("jsonrpc"))
        assertEquals(setOf("threadId", "model", "effort", "serviceTier"), params.keys().asSequence().toSet())
        assertEquals("thread-1", params.getString("threadId"))
        assertEquals("gpt-6-astra", params.getString("model"))
        assertEquals("medium", params.getString("effort"))
        assertEquals("priority", params.getString("serviceTier"))
        assertEquals(
            RequestContext.ThreadSettingsUpdate("thread-1", "gpt-6-astra", ReasoningEffort.MEDIUM, "priority"),
            request.context,
        )
    }

    @Test
    fun clearingTierIsExplicitNullAndStandardTierIsAnExplicitSentinel() {
        val cleared = JSONObject(request(serviceTier = null).json).getJSONObject("params")
        assertTrue(cleared.has("serviceTier"))
        assertSame(JSONObject.NULL, cleared.get("serviceTier"))
        assertEquals(
            "default",
            JSONObject(request(serviceTier = CodexServiceTier.STANDARD).json)
                .getJSONObject("params").getString("serviceTier"),
        )
    }

    @Test
    fun invalidSettingsIdentifiersAreRejectedBeforeEncoding() {
        for (invalid in listOf("", " ", "model\ninjected", "a".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { request(model = invalid) }
            assertThrows(IllegalArgumentException::class.java) { request(serviceTier = invalid) }
        }
        for (invalid in listOf("", "thread\nother", "a".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { request(threadId = invalid) }
        }
    }

    @Test
    fun emptyAckIsTypedButContainsNoInventedEffectiveSettings() {
        val correlator = ResponseCorrelator().apply { register(request()) }
        val ack = correlator.accept("""{"id":1,"result":{}}""") as CorrelatedResponse.Success
        assertEquals(AppServerMethod.THREAD_SETTINGS_UPDATE, ack.method)
        assertSame(ThreadSettingsUpdateResult, ack.result)
        assertEquals(0, correlator.pendingCount())
        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept("""{"id":1,"result":{}}""")
        }
    }

    @Test
    fun unknownAckAndUnexpectedResultFieldsCannotConfirmSettings() {
        val correlator = ResponseCorrelator().apply { register(request()) }
        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept("""{"id":2,"result":{}}""")
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            correlator.accept("""{"id":1,"result":{"model":"gpt-6-astra"}}""")
        }
        assertEquals(1, correlator.pendingCount())
    }

    @Test
    fun nativeRejectionRemainsFailureNotAnAppliedSettingsSnapshot() {
        val correlator = ResponseCorrelator().apply { register(request()) }
        val failure = correlator.accept(
            """{"id":1,"error":{"code":-32600,"message":"thread not found"}}""",
        ) as CorrelatedResponse.Failure
        assertEquals(AppServerMethod.THREAD_SETTINGS_UPDATE, failure.method)
        assertEquals(-32600L, failure.error.code)
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun appliedSettingsComeFromTheNestedNativeSnapshotNotDecoyTopLevelFields() {
        val params = params().apply {
            put("model", "gpt-5.6-luna")
            put("effort", "max")
            put("serviceTier", "default")
        }
        params.getJSONObject("threadSettings").put("futureMetadata", "MUST_NOT_RETAIN")
        val event = decode(params)
        assertEquals("thread/settings/updated", event.method)
        assertEquals("thread-1", event.threadId)
        assertEquals("gpt-6-astra", event.model)
        assertEquals(ReasoningEffort.MEDIUM, event.effort)
        assertEquals("priority", event.serviceTier)
        assertFalse(event.toString().contains("MUST_NOT_RETAIN"))
    }

    @Test
    fun absentOrNullOptionalEchoesStayUnknownAndNeverInheritRequestedValues() {
        val absent = params().apply {
            getJSONObject("threadSettings").remove("effort")
            getJSONObject("threadSettings").remove("serviceTier")
        }
        assertNull(decode(absent).effort)
        assertNull(decode(absent).serviceTier)
        val nulls = params().apply {
            getJSONObject("threadSettings").put("effort", JSONObject.NULL)
            getJSONObject("threadSettings").put("serviceTier", JSONObject.NULL)
        }
        assertNull(decode(nulls).effort)
        assertNull(decode(nulls).serviceTier)
    }

    @Test
    fun missingNestedSnapshotOrCorrelationCannotBecomeTypedProof() {
        for (field in listOf("threadId", "threadSettings")) {
            assertThrows(MalformedEnvelopeException::class.java) { decode(params().apply { remove(field) }) }
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            decode(params().apply { getJSONObject("threadSettings").remove("model") })
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            decode(params().apply { put("threadSettings", "gpt-6-astra") })
        }
    }

    @Test
    fun consumedSnapshotValuesRejectTypeCoercionAndUnboundedOrUnsafeTokens() {
        for (field in listOf("model", "effort", "serviceTier")) {
            for (invalid in listOf(7, true, JSONObject(), "", "two words", "unsafe\ntoken", "a".repeat(129))) {
                assertThrows(IllegalArgumentException::class.java) {
                    decode(params().apply { getJSONObject("threadSettings").put(field, invalid) })
                }
            }
        }
        for (invalid in listOf(7, "", "thread\nother", "a".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) {
                decode(params().apply { put("threadId", invalid) })
            }
        }
    }

    @Test
    fun settingsReceiptAndNotificationNeverRewriteTheAlreadyRunningTurn() {
        val reducer = CodexSessionReducer()
        val running = DispatchOptions.LUNA_MAX
        reducer.apply(
            CorrelatedResponse.Success(
                RequestId.Number(2), AppServerMethod.TURN_START,
                TurnStartResult("thread-1", "turn-1", TurnStatus.IN_PROGRESS, running),
            ),
        )
        val before = reducer.snapshot().threads.single()
        reducer.apply(
            CorrelatedResponse.Success(RequestId.Number(1), AppServerMethod.THREAD_SETTINGS_UPDATE, ThreadSettingsUpdateResult),
        )
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 0), decode(params())))
        assertEquals(before, reducer.snapshot().threads.single())
        reducer.apply(DeliveredServerEvent(DeliveryCursor(1, 1), decode(params().put("threadId", "foreign-thread"))))
        assertEquals(listOf(before), reducer.snapshot().threads)
    }

    private fun request(
        threadId: String = "thread-1",
        model: String = "gpt-6-astra",
        serviceTier: String? = "priority",
    ) = AppServerRequests.threadSettingsUpdate(RequestId.Number(1), threadId, model, ReasoningEffort.MEDIUM, serviceTier)

    private fun decode(params: JSONObject): ServerEvent.ThreadSettingsUpdated = AppServerEventDecoder.decode(
        JSONObject().put("method", "thread/settings/updated").put("params", params).toString(),
    ) as ServerEvent.ThreadSettingsUpdated

    /** Complete pinned native shape; decoder retains only the model-settings projection. */
    private fun params(): JSONObject = JSONObject().put("threadId", "thread-1").put(
        "threadSettings",
        JSONObject().put("model", "gpt-6-astra").put("effort", "medium").put("serviceTier", "priority")
            .put("modelProvider", "openai").put("cwd", "/tmp/hans-protocol-test")
            .put("approvalPolicy", "never").put("approvalsReviewer", "user")
            .put("sandboxPolicy", JSONObject().put("type", "dangerFullAccess"))
            .put("collaborationMode", JSONObject().put("mode", "default").put(
                "settings", JSONObject().put("model", "gpt-6-astra").put("reasoning_effort", "medium")
                    .put("developer_instructions", JSONObject.NULL),
            )),
    )
}
