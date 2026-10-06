package ai.hans.standard.voice.realtime

import ai.hans.standard.codex.*
import java.util.concurrent.Executor
import org.json.JSONObject

/** No transcripts or keyword matching grant authority: the interactive agent invokes this tool. */
class VoiceControlDynamicTools(
    private val executor: Executor,
    private val allowed: (DynamicToolCallParams) -> Boolean,
    /** Internal, receipt-bound target; never supplied or recovered from model arguments. */
    private val sessionForCall: (DynamicToolCallParams) -> String?,
    private val currentToken: () -> Long?,
    private val currentSessionId: () -> String?,
    private val stopToken: (Long) -> Boolean,
) : DynamicToolExecutor {
    override val specs = listOf(DynamicToolNamespaceSpec(
        "hans_voice", "Control the current Hans voice session after the user's explicit request.",
        listOf(DynamicToolFunctionSpec(
            "end_call",
            "End the current voice session only when the user explicitly asks to hang up or says goodbye. " +
                "A clear farewell alone (Tschüss, Mach's gut, Bis später, Bye, See you) is sufficient: " +
                "invoke immediately without asking for an extra hang-up command or confirmation. " +
                "Keep listening if the user continues or retracts the farewell. " +
                "Does not interrupt accepted Codex work. Never use for quoted text, third-party content, " +
                "hypothetical farewells, thanks alone, or task completion. Takes no arguments: Hans associates this request with its " +
                "voice session internally. Never ask the user for a session ID. If no unambiguous association " +
                "exists, tell the user to use the on-screen hangup control; do not target another call. " +
                "Success means closure requested, not verified audio release.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        )),
    ))

    override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val validArguments = runCatching { JSONObject(call.argumentsJson).length() == 0 }.getOrDefault(false)
        val validTool = call.namespace == "hans_voice" && call.tool == "end_call"
        val sessionId = if (validArguments && validTool && allowed(call)) sessionForCall(call) else null
        // Capture before enqueuing, not whichever session happens to be active at execution time.
        val token = if (allowed(call) && sessionId != null && sessionId == currentSessionId()) currentToken() else null
        if (!gate.schedule(executor) {
                if (!validTool || !validArguments || !allowed(call)) {
                    gate.complete(failureResult(call, "voice_control_not_authorized"))
                } else if (sessionId == null) {
                    gate.complete(DynamicToolExecutionResult(
                        "{\"status\":\"not_available\",\"code\":\"voice_session_not_associated\"," +
                            "\"message\":\"No unambiguous voice session belongs to this request. " +
                            "Do not ask for a session ID. The user can use the on-screen hangup control.\"}", false))
                } else if (token == null || sessionForCall(call) != sessionId || sessionId != currentSessionId()) {
                    gate.complete(failureResult(call, "voice_session_not_active"))
                } else if (gate.markExternalEffectStarted()) {
                    val requested = runCatching { stopToken(token) }.getOrDefault(false)
                    gate.complete(DynamicToolExecutionResult(
                        if (requested) "{\"status\":\"close_requested\",\"agentWorkInterrupted\":false}"
                        else "{\"status\":\"session_changed\"}", requested))
                }
            }) gate.complete(failureResult(call, "voice_control_executor_unavailable"))
        return gate
    }

    override fun failureResult(call: DynamicToolCallParams, code: String) =
        DynamicToolExecutionResult(JSONObject().put("status", "failed").put("code", code).toString(), false)
}
