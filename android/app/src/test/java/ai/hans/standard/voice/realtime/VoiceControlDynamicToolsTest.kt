package ai.hans.standard.voice.realtime

import ai.hans.standard.codex.*
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceControlDynamicToolsTest {
    @Test fun markerlessHangupUsesInternalBindingAndDoesNotClaimClosure() {
        val h = Harness()
        h.tools.execute(call(), h.results::add)
        assertTrue(h.stopped.isEmpty())
        h.drain()
        assertEquals(listOf(10L), h.stopped)
        assertTrue(h.results.single().success)
        assertTrue(h.results.single().contentText.contains("close_requested"))
        assertFalse(h.results.single().contentText.contains("closed"))
    }

    @Test fun newerCallCannotBeEndedByOldQueuedTool() {
        val h = Harness()
        h.tools.execute(call(), h.results::add)
        h.token = 11
        h.drain()
        assertTrue(h.stopped.isEmpty())
        assertFalse(h.results.single().success)
    }

    @Test fun missingSessionNeverEndsLaterSession() {
        val h = Harness(); h.token = null
        h.tools.execute(call(), h.results::add)
        h.token = 12
        h.drain()
        assertTrue(h.stopped.isEmpty())
        assertFalse(h.results.single().success)
    }

    @Test fun delayedOldRequestCannotEndNewCallEvenBeforeToolAdmission() {
        val h = Harness(); h.sessionId = "new-call"
        h.tools.execute(call(), h.results::add); h.drain()
        assertTrue(h.stopped.isEmpty()); assertFalse(h.results.single().success)
    }

    @Test fun missingInternalBindingNeverFallsBackToTheActiveCallOrAsksForAnId() {
        val h = Harness(); h.boundSessionId = null
        h.tools.execute(call(), h.results::add)
        h.boundSessionId = h.sessionId
        h.drain()
        assertTrue(h.stopped.isEmpty())
        assertFalse(h.results.single().success)
        assertTrue(h.results.single().contentText.contains("voice_session_not_associated"))
        assertTrue(h.results.single().contentText.contains("Do not ask for a session ID"))
    }

    @Test fun revokedOrReboundTurnCannotChangeTheCapturedTarget() {
        listOf(null, "new-call").forEach { laterBinding ->
            val h = Harness()
            h.tools.execute(call(), h.results::add)
            h.boundSessionId = laterBinding
            h.drain()
            assertTrue(h.stopped.isEmpty())
            assertFalse(h.results.single().success)
        }
    }

    @Test fun modelSuppliedSessionIdentifierCannotGrantAuthority() {
        val h = Harness()
        h.tools.execute(call(args = "{\"session_id\":\"call-ten\"}"), h.results::add)
        h.drain()
        assertTrue(h.stopped.isEmpty())
        assertFalse(h.results.single().success)
    }

    @Test fun toolSchemaHasNoIdentifierOrOtherArguments() {
        val function = Harness().tools.specs.single().tools.single()
        assertTrue(function.description.contains("A clear farewell alone"))
        assertTrue(function.description.contains("without asking for an extra hang-up command or confirmation"))
        assertTrue(function.description.contains("continues or retracts"))
        val schema = JSONObject(function.inputSchemaJson)
        assertEquals(0, schema.getJSONObject("properties").length())
        assertFalse(schema.getBoolean("additionalProperties"))
        assertFalse(schema.has("required"))
    }

    @Test fun deniedBackgroundAndRevokedMainAuthorityCannotHangUp() {
        listOf(false, true).forEach { revokeLater ->
            val h = Harness(); h.allowed = revokeLater
            h.tools.execute(call(), h.results::add)
            h.allowed = false
            h.drain()
            assertTrue(h.stopped.isEmpty())
            assertFalse(h.results.single().success)
        }
    }

    @Test fun malformedOrWrongToolCannotHangUp() {
        listOf(call(args = "[]"), call(args = "{\"session\":11}"), call(tool = "stop_work"),
            call(namespace = "other")).forEach { params ->
            val h = Harness()
            h.tools.execute(params, h.results::add); h.drain()
            assertTrue(h.stopped.isEmpty()); assertFalse(h.results.single().success)
        }
    }

    @Test fun cancelledQueuedToolHasNoExternalEffect() {
        val h = Harness()
        val handle = h.tools.executeCancellable(call(), DynamicToolCancellation.NONE, h.results::add)
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        h.drain()
        assertTrue(h.stopped.isEmpty()); assertTrue(h.results.isEmpty())
    }

    private class Harness {
        val queue = ArrayDeque<Runnable>()
        var token: Long? = 10
        var sessionId = "call-ten"
        var boundSessionId: String? = "call-ten"
        var allowed = true
        val stopped = mutableListOf<Long>()
        val results = mutableListOf<DynamicToolExecutionResult>()
        val tools = VoiceControlDynamicTools(Executor(queue::add), { allowed }, { boundSessionId }, { token }, { sessionId }, { expected ->
            if (token == expected) { stopped += expected; true } else false
        })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun call(args: String = "{}", tool: String = "end_call", namespace: String = "hans_voice") =
        DynamicToolCallParams("main", "turn", "call", namespace, tool, args)
}
