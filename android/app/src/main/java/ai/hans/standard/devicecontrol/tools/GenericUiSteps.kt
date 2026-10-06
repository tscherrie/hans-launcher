package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySessions
import ai.hans.standard.phone.accessibility.android.HansPhoneToolEvidence
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Generic short plans supplied by the agent, never app-specific recorded coordinates. */
internal object GenericUiStepsContract {
    const val NAME = "run_steps"
    const val MAX_STEPS = 4
    const val MAX_TIMEOUT_MS = 5_000L
    val spec = DynamicToolFunctionSpec(
        NAME,
        "Execute 1-4 already planned semantic clicks/text edits in one app without model round trips. " +
            "Optionally launch the known personal-profile app first. Use only exact selectors known from " +
            "a current view or a trusted skill; never guess labels. Each step waits event-driven for one " +
            "fresh, visible, enabled, actionable matching node within one overall deadline. Unknown or " +
            "incomplete UI waits; ambiguous UI stops the sequence. Steps are sequential, never parallel, " +
            "never retried. Existing permission, " +
            "live-target and postcondition checks apply. Returns compact per-step receipts, no intermediate " +
            "trees. An accepted click is not proof of task success; inspect if further evidence is needed. " +
            "Use set_text directly for editable fields; a focus click is usually unnecessary. " +
            "Use individual tools when the next step needs reasoning or a different app/profile.",
        obj(JSONObject()
            .put("packageName", string(255))
            .put("launch", JSONObject().put("type", "boolean"))
            .put("timeoutMs", JSONObject().put("type", "integer").put("minimum", 100).put("maximum", MAX_TIMEOUT_MS))
            .put("steps", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", MAX_STEPS)
                .put("items", obj(JSONObject()
                    .put("action", enum(listOf("click", "set_text")))
                    .put("target", obj(JSONObject()
                        .put("text", string(512)).put("contentDescription", string(512))
                        .put("className", string(255)).put("role", enum(SemanticUiRole.entries.map { it.name.lowercase(Locale.ROOT) })), emptyList()))
                    .put("value", string(32_768, allowBlank = true))
                    .put("postcondition", enum(UiPostconditionExpectation.entries.map { it.name.lowercase(Locale.ROOT) })),
                    listOf("action", "target")))), listOf("packageName", "steps")).toString(),
    )
    private fun string(max: Int, allowBlank: Boolean = false) = JSONObject().put("type", "string")
        .put("minLength", if (allowBlank) 0 else 1).put("maxLength", max)
    private fun enum(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))
    private fun obj(properties: JSONObject, required: List<String>) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
}

/** One outer phone-tool lease. Child primitives never recursively enter the router/fence. */
internal class GenericUiStepsExecutor(
    private val backgroundExecutor: Executor,
    private val launch: (DynamicToolCallParams, DynamicToolExecutionGate) -> DynamicToolExecutionResult,
    private val sessions: () -> HansAccessibilitySession? = HansAccessibilitySessions::current,
    private val availability: UiInteractionAvailabilityProbe,
    private val evidenceEpoch: () -> Long = HansPhoneToolEvidence::epoch,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : DynamicToolExecutor {
    override val specs = emptyList<DynamicToolNamespaceSpec>() // Included in the existing android_ui namespace.
    private val ledger = LinkedHashMap<String, Pair<String, DynamicToolExecutionResult?>>()

    override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun failureResult(call: DynamicToolCallParams, code: String): DynamicToolExecutionResult =
        failure(code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{2,79}")) } ?: "ui_sequence_execution_failed")

    override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val waitingSession = AtomicReference<HansAccessibilitySession?>()
        val scheduled = gate.schedule(backgroundExecutor) {
            val key = sequenceKey(call)
            val fingerprint = digest(call.argumentsJson)
            val progress = Progress()
            var reserved = false
            val result = try {
                val previous = synchronized(ledger) {
                    val old = ledger[key]
                    if (old == null) {
                        if (ledger.size >= 128) ledger.entries.firstOrNull { it.value.second != null }
                            ?.key?.let(ledger::remove)
                        if (ledger.size < 128) { ledger[key] = fingerprint to null; reserved = true }
                    }
                    old
                }
                when {
                    previous != null && previous.first != fingerprint -> failure("idempotency_key_conflict")
                    previous != null -> previous.second?.let { replay ->
                        replay.copy(contentText = JSONObject(replay.contentText).put("replayed", true).toString())
                    } ?: failure("ui_sequence_busy")
                    !reserved -> failure("ui_sequence_capacity_exhausted")
                    else -> run(call, gate, waitingSession, progress)
                }
            } catch (_: Exception) {
                exceptionFailure(progress, gate)
            }
            if (reserved) synchronized(ledger) { ledger[key] = fingerprint to result }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failure("executor_rejected"))
        return object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition {
                val outcome = gate.cancel()
                runCatching { waitingSession.get()?.wakeSnapshotWaiters() }
                return outcome
            }
            override fun onQuiescent(listener: () -> Unit) = gate.onQuiescent(listener)
        }
    }

    private fun run(call: DynamicToolCallParams, gate: DynamicToolExecutionGate,
        waitingSession: AtomicReference<HansAccessibilitySession?>, progress: Progress): DynamicToolExecutionResult {
        val plan = runCatching { decode(call) }.getOrElse { return failure("invalid_arguments") }
        val session = sessions() ?: return failure("accessibility_session_unavailable")
        val epoch = evidenceEpoch()
        val receipts = progress.receipts
        val deadline = nowMillis() + plan.timeoutMs
        waitingSession.set(session)
        fun stopped() = gate.isCancellationRequested() || sessions() !== session ||
            evidenceEpoch() != epoch || !runCatching { availability.current().isAvailable }.getOrDefault(false)
        fun fail(code: String) = failure(code, receipts)
        if (stopped()) return fail("ui_sequence_context_unavailable")
        if (plan.launchApp) {
            val result = launch(call.copy(namespace = "android", tool = "launch_app",
                callId = "ui-sequence-launch-${digest("${sequenceKey(call)}\u0000launch")}",
                argumentsJson = JSONObject().put("packageName", plan.packageName).toString()), gate)
            if (!result.success) return wrapFailure(result, receipts, "launch")
        }
        for ((index, step) in plan.steps.withIndex()) {
            if (stopped()) return fail("ui_sequence_cancelled_or_context_changed")
            val remaining = deadline - nowMillis()
            if (remaining <= 0) return fail("ui_sequence_deadline_exceeded")
            var waitFailure = "ui_target_wait_expired"
            val view = session.awaitSnapshot(remaining.coerceAtMost(GenericUiStepsContract.MAX_TIMEOUT_MS), ::stopped) {
                val observation = select(it, plan.packageName, step)
                // An incomplete frame may omit another matching node. It authorizes nothing,
                // but a later Accessibility publication can become complete within this wait.
                // Keep only a fixed diagnostic for the latest observation, never an action target.
                waitFailure = if (observation is Selection.Incomplete) "ui_target_snapshot_incomplete"
                    else "ui_target_wait_expired"
                observation is Selection.Target || observation is Selection.Rejected
            } ?: return fail(if (stopped()) "ui_sequence_cancelled_or_context_changed" else waitFailure)
            if (stopped()) return fail("ui_sequence_cancelled_or_context_changed")
            if (nowMillis() >= deadline) return fail("ui_sequence_deadline_exceeded")
            // Re-evaluate the actual returned view; never rely on a captured predicate side effect.
            val selected = select(view, plan.packageName, step)
            if (selected is Selection.Rejected) return fail(selected.code)
            if (selected is Selection.Incomplete) return fail("ui_target_snapshot_incomplete")
            val target = (selected as? Selection.Target)?.node ?: return fail("ui_target_unavailable")
            if (view.correlation.sessionId != session.sessionId ||
                !session.retainSnapshotForCommands(view.correlation)) return fail("ui_target_snapshot_unavailable")
            val actionKey = AccessibilityIdempotencyKey("ui-steps-${digest("${sequenceKey(call)}\u0000step\u0000$index")}")
            val command = when (step.action) {
                "click" -> AccessibilityCommand.Click(actionKey, target.handle, postcondition = step.postcondition)
                else -> AccessibilityCommand.SetText(actionKey, target.handle, checkNotNull(step.value), postcondition = step.postcondition)
            }
            val completed = CountDownLatch(1)
            val result = AtomicReference<AccessibilityExecutionResult?>()
            // Queue-time guard is evaluated by the service worker immediately before executing.
            // Cancellation after entry does not claim that an already accepted action was undone.
            progress.inFlightStep = index + 1
            val submitted = try {
                session.submitGuarded(command, {
                    stopped() || nowMillis() >= deadline || !gate.markExternalEffectStarted()
                }) { value ->
                    if (result.compareAndSet(null, value)) completed.countDown()
                }
            } catch (_: Exception) {
                // A violating delegate might have admitted work before throwing. Report the
                // unknown outcome, but retain physical ownership until its actual callback.
                gate.complete(exceptionFailure(progress, gate))
                awaitPhysicalCompletion(completed)
                return exceptionFailure(progress, gate)
            }
            if (!submitted) { progress.inFlightStep = null; return fail("accessibility_queue_full") }
            // Do not release the outer phone fence on timeout/cancellation while a child might
            // still mutate Android. The real callback is the physical completion boundary.
            awaitPhysicalCompletion(completed)
            val actual = checkNotNull(result.get())
            val projected = DeviceControlProjection.execution(actual, null)
            receipts.put(JSONObject().put("step", index + 1).put("action", step.action)
                .put("result", JSONObject(projected.contentText)))
            progress.inFlightStep = null
            if (!projected.success) return wrapFailure(projected, receipts, "step")
            if (actual.replayed) return fail("ui_sequence_replayed_step_stopped")
        }
        return DynamicToolExecutionResult(JSONObject().put("status", "succeeded")
            .put("replayed", false).put("steps", receipts).put("intermediateViewsReturned", false)
            .put("outcomeMeaning", "Per-step receipts only; accepted actions do not prove external task success.")
            .toString(), success = true)
    }

    private fun awaitPhysicalCompletion(completed: CountDownLatch) {
        var interrupted = false
        while (true) {
            try { completed.await(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun exceptionFailure(progress: Progress, gate: DynamicToolExecutionGate): DynamicToolExecutionResult {
        val base = failure("ui_sequence_execution_failed", progress.receipts)
        return base.copy(contentText = JSONObject(base.contentText)
            .put("externalEffectMayHaveStarted", gate.disposition() == DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED)
            .apply { progress.inFlightStep?.let { put("unconfirmedStep", it) } }.toString())
    }

    private fun wrapFailure(result: DynamicToolExecutionResult, receipts: JSONArray, stage: String) =
        DynamicToolExecutionResult(JSONObject().put("status", "failed").put("stoppedAt", stage)
            .put("replayed", false).put("steps", receipts).put("failure", JSONObject(result.contentText))
            .put("retrySequence", false).toString(), success = false, failureDiagnostic = result.failureDiagnostic)

    private fun failure(code: String, receipts: JSONArray = JSONArray()) = DynamicToolExecutionResult(
        JSONObject().put("status", "failed").put("replayed", false).put("errorCode", code).put("steps", receipts)
            .put("retrySequence", false).toString(), success = false,
        failureDiagnostic = ai.hans.standard.diagnostics.ToolFailureDiagnostic.fromCodes(code),
    )

    private fun decode(call: DynamicToolCallParams): Plan {
        require(call.namespace == "android_ui" && call.tool == GenericUiStepsContract.NAME)
        val args = JsonContract.parseObject(call.argumentsJson, MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
        only(args, setOf("packageName", "launch", "timeoutMs", "steps"))
        val pkg = JsonContract.requiredString(args, "packageName", 255)
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val launch = if (args.has("launch")) (args.get("launch") as? Boolean ?: error("launch")) else false
        val timeout = if (args.has("timeoutMs")) {
            val raw = args.get("timeoutMs")
            require(raw is Int || raw is Long)
            (raw as Number).toLong().also { require(it in 100..GenericUiStepsContract.MAX_TIMEOUT_MS) }
        } else GenericUiStepsContract.MAX_TIMEOUT_MS
        val inputSteps = args.getJSONArray("steps")
        require(inputSteps.length() in 1..GenericUiStepsContract.MAX_STEPS)
        val steps = (0 until inputSteps.length()).map { index ->
            val obj = inputSteps.getJSONObject(index)
            only(obj, setOf("action", "target", "value", "postcondition"))
            val action = obj.getString("action").also { require(it in setOf("click", "set_text")) }
            val target = obj.getJSONObject("target")
            only(target, setOf("text", "contentDescription", "className", "role"))
            val text = optional(target, "text", 512)
            val description = optional(target, "contentDescription", 512)
            val className = optional(target, "className", 255)
            val role = optional(target, "role", 64)?.let { raw ->
                SemanticUiRole.entries.single { it.name.lowercase(Locale.ROOT) == raw }
            }
            require(text != null || description != null || className != null || role != null)
            val value = if (action == "set_text") JsonContract.requiredString(obj, "value", 32_768, allowBlank = true)
                else { require(!obj.has("value")); null }
            val postcondition = optional(obj, "postcondition", 64)?.let { raw ->
                UiPostconditionExpectation.entries.single { it.name.lowercase(Locale.ROOT) == raw }
            } ?: if (action == "set_text") UiPostconditionExpectation.TEXT_EQUALS_REQUEST else UiPostconditionExpectation.ACTION_ACCEPTED
            require(action == "set_text" || postcondition != UiPostconditionExpectation.TEXT_EQUALS_REQUEST)
            Step(action, text, description, className, role, value, postcondition)
        }
        return Plan(pkg, launch, timeout, steps)
    }

    private fun select(view: SemanticUiSnapshot, pkg: String, step: Step): Selection {
        val roots = view.roots.mapNotNull(view::resolve)
        if (roots.isEmpty() || roots.any { it.packageName?.value != pkg }) return Selection.Wait
        if (!view.isComplete) return Selection.Incomplete
        val action = if (step.action == "click") SemanticUiAction.CLICK else SemanticUiAction.SET_TEXT
        val matches = view.nodes.filter { node ->
            node.packageName?.value == pkg && node.visible && node.enabled && action in node.actions &&
                (step.action != "set_text" || node.editable) &&
                (step.text == null || node.text?.value == step.text) &&
                (step.description == null || node.contentDescription?.value == step.description) &&
                (step.className == null || node.className?.value == step.className) &&
                (step.role == null || node.role == step.role)
        }
        return when (matches.size) {
            0 -> Selection.Wait
            1 -> Selection.Target(matches.single())
            else -> Selection.Rejected("ui_target_ambiguous")
        }
    }

    private fun only(obj: JSONObject, keys: Set<String>) { require(obj.keys().asSequence().all { it in keys }) }
    private fun optional(obj: JSONObject, key: String, max: Int): String? =
        if (obj.has(key)) JsonContract.requiredString(obj, key, max) else null
    private data class Plan(val packageName: String, val launchApp: Boolean, val timeoutMs: Long, val steps: List<Step>)
    private class Progress(val receipts: JSONArray = JSONArray(), var inFlightStep: Int? = null)
    private data class Step(val action: String, val text: String?, val description: String?, val className: String?,
        val role: SemanticUiRole?, val value: String?, val postcondition: UiPostconditionExpectation)
    private sealed interface Selection {
        data object Wait : Selection
        data object Incomplete : Selection
        data class Rejected(val code: String) : Selection
        data class Target(val node: SemanticUiNode) : Selection
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun sequenceKey(call: DynamicToolCallParams) = digest("${call.threadId}\u0000${call.turnId}\u0000${call.callId}")
}
