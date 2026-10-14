package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.fastphone.*
import ai.hans.standard.fastphone.routines.RoutineObservation
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySessions
import ai.hans.standard.phone.accessibility.android.HansPhoneToolEvidence
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal object FastPhoneUseContract {
    const val NAME = "perform_goal"
    const val MAX_STEPS = 8
    const val MAX_TIMEOUT_MS = 45_000L
    const val MAX_CANDIDATES = 64
    val spec = DynamicToolFunctionSpec(NAME,
        "Optional Fast Phone Use: delegate one bounded semantic UI goal in one personal-profile app to " +
            "the user's explicitly enabled, separately billed Decisions API helper. Use public Android tools " +
            "first; use run_steps for already known steps. Do not use when Fast Phone Use is disabled. " +
            "Supply the precise goal, permitted action kinds and any exact text to enter. The helper chooses " +
            "only locally supplied targets, validates fresh UI and postconditions after each sequential action, " +
            "and returns compact receipts plus a small final view, not intermediate trees. It cannot invent text, " +
            "use coordinates, switch apps or handle credential UI. Missing/ambiguous UI, uncertain results or " +
            "exhausted budget return to you without retrying actions. A model-assessed completion is not " +
            "independent proof of external task success. Never repeat a send/payment/delete after an uncertain receipt.",
        obj(JSONObject()
            .put("packageName", string(255))
            .put("goal", string(2_048))
            .put("allowedActions", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 4)
                .put("uniqueItems", true).put("items", enum(listOf("click", "set_text", "scroll", "back"))))
            .put("textToEnter", string(32_768, true))
            .put("launch", JSONObject().put("type", "boolean"))
            .put("maxSteps", integer(1, MAX_STEPS.toLong()))
            .put("timeoutMs", integer(1_000, MAX_TIMEOUT_MS))
            .put("confirmationRisk", enum(listOf("none", "external_communication", "destructive"))),
            listOf("packageName", "goal", "allowedActions")).toString())
    private fun string(max: Int, blank: Boolean = false) = JSONObject().put("type", "string")
        .put("minLength", if (blank) 0 else 1).put("maxLength", max)
    private fun integer(min: Long, max: Long) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    private fun enum(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))
    private fun obj(properties: JSONObject, required: List<String>) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
}

/** One existing outer phone lease, not one new Codex turn per click. No background/idle work. */
internal class FastPhoneUseExecutor(
    private val backgroundExecutor: Executor,
    private val launch: (DynamicToolCallParams, DynamicToolExecutionGate) -> DynamicToolExecutionResult,
    private val client: DecisionsClient,
    private val access: DecisionsAccess,
    private val availability: UiInteractionAvailabilityProbe,
    private val sessions: () -> HansAccessibilitySession? = HansAccessibilitySessions::current,
    private val evidenceEpoch: () -> Long = HansPhoneToolEvidence::epoch,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onCompleted: (RoutineObservation) -> Unit = {},
    private val missingSessionResult: () -> DynamicToolExecutionResult = {
        DeviceControlProjection.failure("accessibility_session_unavailable")
    },
) : DynamicToolExecutor {
    override val specs = emptyList<DynamicToolNamespaceSpec>()
    private val ledger = LinkedHashMap<String, Pair<String, DynamicToolExecutionResult?>>()
    private val active = ConcurrentHashMap.newKeySet<Active>()

    /** Settings changes interrupt in-flight HTTP immediately, without starting any work at idle. */
    fun invalidateConfiguration() = active.forEach { it.cancelNetworkAndWake() }

    override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun failureResult(call: DynamicToolCallParams, code: String) = failure(
        code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{2,79}")) } ?: "fast_phone_execution_failed")

    override fun executeCancellable(call: DynamicToolCallParams, cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val state = Active()
        active += state
        val scheduled = gate.schedule(backgroundExecutor) {
            val key = key(call)
            val fingerprint = digest(call.argumentsJson)
            var reserved = false
            val progress = Progress()
            val result = try {
                val previous = synchronized(ledger) {
                    val old = ledger[key]
                    if (old == null) {
                        if (ledger.size >= 128) ledger.entries.firstOrNull { it.value.second != null }?.key?.let(ledger::remove)
                        if (ledger.size < 128) { ledger[key] = fingerprint to null; reserved = true }
                    }
                    old
                }
                when {
                    previous != null && previous.first != fingerprint -> failure("idempotency_key_conflict")
                    previous != null -> previous.second?.let {
                        it.copy(contentText = JSONObject(it.contentText).put("replayed", true).toString())
                    } ?: failure("fast_phone_busy")
                    !reserved -> failure("fast_phone_capacity_exhausted")
                    else -> run(call, gate, state, progress)
                }
            } catch (_: Exception) { exceptionFailure(progress, gate) }
            if (reserved) synchronized(ledger) { ledger[key] = fingerprint to result }
            gate.complete(result)
        }
        gate.onQuiescent { active -= state }
        if (!scheduled) gate.complete(failure("executor_rejected"))
        return object : DynamicToolExecutionHandle {
            override fun cancel(): DynamicToolCancellationDisposition {
                val result = gate.cancel()
                state.cancelNetworkAndWake()
                return result
            }
            override fun onQuiescent(listener: () -> Unit) = gate.onQuiescent(listener)
        }
    }

    private fun run(call: DynamicToolCallParams, gate: DynamicToolExecutionGate, state: Active,
        progress: Progress): DynamicToolExecutionResult {
        val plan = runCatching { decode(call) }.getOrElse { return failure("invalid_arguments") }
        if (!access.isEnabled()) return failure("fast_phone_disabled")
        val revision = access.revision()
        val session = sessions() ?: return missingSessionResult().let { unavailable ->
            unavailable.copy(contentText = JSONObject(unavailable.contentText)
                .put("replayed", false).put("steps", JSONArray()).put("decisions", 0)
                .put("retryActions", false).put("externalEffectMayHaveStarted", false).toString())
        }
        val epoch = evidenceEpoch()
        val deadline = nowMillis() + plan.timeoutMs
        state.session.set(session)
        fun stopped() = gate.isCancellationRequested() || !access.isEnabled() || access.revision() != revision ||
            sessions() !== session || evidenceEpoch() != epoch ||
            !runCatching { availability.current().isAvailable }.getOrDefault(false)
        fun fail(code: String) = failure(code, progress)
        if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
        val credential = access.readApiKey() ?: return fail("fast_phone_key_missing").also {
            access.recordFailure("fast_phone_key_missing", revision)
        }
        try {
            if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
            if (plan.launchApp) {
                val result = launch(call.copy(namespace = "android", tool = "launch_app",
                    callId = "fast-phone-launch-${digest("${key(call)}\u0000launch")}",
                    argumentsJson = JSONObject().put("packageName", plan.packageName).toString()), gate)
                if (!result.success) return wrapFailure(result, progress, "launch")
            }
            val performed = mutableSetOf<String>()
            var staleDecisions = 0
            var decisions = 0
            var nextView: SemanticUiSnapshot? = null
            while (progress.receipts.length() <= plan.maxSteps) {
                if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
                val remaining = deadline - nowMillis()
                if (remaining <= 0) return fail("fast_phone_deadline_exceeded")
                val view = nextView.also { nextView = null } ?: session.awaitSnapshot(remaining.coerceAtMost(5_000), ::stopped) {
                    it.isComplete && inApp(it, plan.packageName)
                } ?: return fail(if (stopped()) "fast_phone_cancelled_or_context_changed" else "fast_phone_ui_unavailable")
                if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
                if (!view.isComplete || !inApp(view, plan.packageName) || view.correlation.sessionId != session.sessionId) {
                    return fail("fast_phone_ui_unavailable")
                }
                if (view.nodes.any { it.visible && it.role == SemanticUiRole.PASSWORD_FIELD }) {
                    return fail("fast_phone_credential_ui")
                }
                val candidates = buildCandidates(view, plan)
                    ?: return fail("fast_phone_ui_too_complex")
                val evidence = evidence(view, plan, progress)
                val question = DecisionsQuestion(evidence, candidates.map { DecisionsChoice(it.id, it.description) } + listOf(
                    DecisionsChoice("done", "The CURRENT visible screen proves this bounded goal is already completed. Do not infer completion from an accepted click."),
                    DecisionsChoice("escalate", "No safe unambiguous permitted next action, incomplete evidence, credential UI, uncertain outcome or additional reasoning is needed. Return to the main agent."),
                ))
                val requestRemaining = (deadline - nowMillis()).coerceAtMost(OpenAiDecisionsClient.MAX_CALL_MS)
                if (requestRemaining <= 0) return fail("fast_phone_deadline_exceeded")
                if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
                // API invocation is itself an externally observable, separately billed effect.
                if (!gate.markExternalEffectStarted()) return fail("fast_phone_cancelled_or_context_changed")
                val request = client.create(question, credential, requestRemaining)
                state.request.set(request)
                if (stopped()) { request.cancel(); return fail("fast_phone_cancelled_or_context_changed") }
                val started = nowMillis()
                val outcome = try { request.execute() } finally { state.request.compareAndSet(request, null) }
                progress.decisionMillis += (nowMillis() - started).coerceAtLeast(0)
                progress.decisions = ++decisions
                if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
                if (nowMillis() >= deadline) return fail("fast_phone_deadline_exceeded")
                if (outcome is DecisionsOutcome.Failure) {
                    access.recordFailure(outcome.code, revision)
                    return fail(outcome.code)
                }
                access.recordSuccess(revision)
                val selected = outcome as DecisionsOutcome.Selected
                if (selected.id == "escalate") return fail("fast_phone_escalated")
                if (!selected.confidence.isFinite() || selected.confidence < MIN_CONFIDENCE || selected.confidence > 1) {
                    return fail("fast_phone_low_confidence")
                }
                // The network decision may have become stale. One local fresh capture is cheap;
                // it prevents applying a valid old target in a changed semantic context.
                val fresh = session.refreshSnapshot() ?: return fail("fast_phone_ui_unavailable")
                if (stopped()) return fail("fast_phone_cancelled_or_context_changed")
                if (!fresh.isComplete || !inApp(fresh, plan.packageName) ||
                    fresh.correlation.sessionId != session.sessionId) return fail("fast_phone_ui_unavailable")
                if (frameFingerprint(fresh) != frameFingerprint(view)) {
                    if (++staleDecisions > 2) return fail("fast_phone_ui_keeps_changing")
                    continue
                }
                if (selected.id == "done") {
                    val result = completed(progress, fresh)
                    // A private candidate template, not verified external success or authority.
                    // No entered text, labels, targets, screenshots or credential is supplied.
                    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .replace(Regex("[\\p{Z}\\s]+"), " ").trim().lowercase(Locale.ROOT)
                    val text = plan.text?.takeIf { it.isNotBlank() }?.let(::normalized)
                    // The goal itself can quote textToEnter; do not copy that payload into a template.
                    if (text == null || !normalized(plan.goal).contains(text)) {
                        runCatching { onCompleted(RoutineObservation(plan.packageName, plan.goal, plan.actions, plan.maxSteps)) }
                    }
                    return result
                }
                if (progress.receipts.length() >= plan.maxSteps) return fail("fast_phone_step_budget_exhausted")
                val candidate = candidates.singleOrNull { it.id == selected.id }
                    ?: return fail("fast_phone_invalid_response")
                val repeatKey = digest("${if (candidate.action.startsWith("scroll_")) frameFingerprint(fresh) else ""}\u0000${candidate.description}\u0000${plan.text.orEmpty()}")
                if (!performed.add(repeatKey)) return fail("fast_phone_repeat_action_blocked")
                // Map by ordinal only after whole-frame equality, then retain the fresh handle.
                val target = candidate.ordinal?.let { ordinal -> fresh.nodes.singleOrNull { it.handle.nodeOrdinal == ordinal } }
                if (candidate.ordinal != null && target == null) return fail("fast_phone_target_unavailable")
                if (!session.retainSnapshotForCommands(fresh.correlation)) return fail("fast_phone_target_unavailable")
                val stepIndex = progress.receipts.length() + 1
                val actionKey = AccessibilityIdempotencyKey("fast-phone-${digest("${key(call)}\u0000$stepIndex")}")
                val command = when (candidate.action) {
                    "click" -> AccessibilityCommand.Click(actionKey, checkNotNull(target).handle,
                        confirmationRisk = plan.risk)
                    "set_text" -> AccessibilityCommand.SetText(actionKey, checkNotNull(target).handle,
                        checkNotNull(plan.text), confirmationRisk = plan.risk)
                    "scroll_forward", "scroll_backward" -> AccessibilityCommand.Scroll(actionKey,
                        checkNotNull(target).handle,
                        if (candidate.action == "scroll_forward") UiScrollDirection.FORWARD else UiScrollDirection.BACKWARD,
                        confirmationRisk = plan.risk)
                    else -> AccessibilityCommand.Global(actionKey, session.sessionId, AccessibilityGlobalAction.BACK,
                        confirmationRisk = plan.risk)
                }
                val physicalDone = CountDownLatch(1)
                val result = AtomicReference<AccessibilityExecutionResult?>()
                progress.inFlightStep = stepIndex
                val submitted = try {
                    session.submitGuarded(command, {
                        stopped() || nowMillis() >= deadline || !gate.markExternalEffectStarted()
                    }) { value -> if (result.compareAndSet(null, value)) physicalDone.countDown() }
                } catch (_: Exception) {
                    gate.complete(exceptionFailure(progress, gate))
                    awaitPhysicalCompletion(physicalDone)
                    return exceptionFailure(progress, gate)
                }
                if (!submitted) { progress.inFlightStep = null; return fail("accessibility_queue_full") }
                // Even a cancelled/deadlined request retains the outer phone fence until the
                // real platform callback; never overlap an uncertain mutation with a new turn.
                awaitPhysicalCompletion(physicalDone)
                val actual = checkNotNull(result.get())
                val projected = DeviceControlProjection.execution(actual, null)
                progress.receipts.put(JSONObject().put("step", stepIndex).put("action", candidate.action)
                    .put("result", JSONObject(projected.contentText)))
                progress.privateHistory.put(JSONObject().put("action", candidate.action)
                    .put("target", candidate.description).put("postcondition", actual.postcondition.status.name)
                    .put("detail", actual.postcondition.detailCode))
                progress.inFlightStep = null
                if (!projected.success) return wrapFailure(projected, progress, "action")
                if (actual.replayed) return fail("fast_phone_replayed_action_stopped")
                val receiptView = actual.postcondition.after?.let(session::receiptSnapshotForObservation)
                    ?.takeIf { it.isComplete && inApp(it, plan.packageName) && it.correlation.sessionId == session.sessionId }
                if (actual.postcondition.status != AccessibilityPostconditionStatus.VERIFIED) {
                    // ACTION_ACCEPTED intentionally promises no app result. Require a visible
                    // semantic transition before continuing; never press again to make it happen.
                    nextView = receiptView?.takeIf { frameFingerprint(it) != frameFingerprint(fresh) }
                        ?: (deadline - nowMillis()).takeIf { it > 0 }?.let { budget ->
                            session.awaitSnapshot(budget.coerceAtMost(1_000), ::stopped) {
                                it.isComplete && inApp(it, plan.packageName) &&
                                    frameFingerprint(it) != frameFingerprint(fresh)
                            }
                        }
                    if (nextView == null) return fail("fast_phone_unverified_action_stopped")
                    progress.receipts.getJSONObject(progress.receipts.length() - 1)
                        .put("followupEvidence", "semantic_screen_changed_not_external_task_proof")
                } else {
                    nextView = receiptView
                }
            }
            return fail("fast_phone_step_budget_exhausted")
        } finally { credential.fill('\u0000') }
    }

    private fun buildCandidates(view: SemanticUiSnapshot, plan: Plan): List<Candidate>? {
        // A helper never decides about a password/credential form or sends its value to the API.
        if (view.nodes.any { it.visible && it.role == SemanticUiRole.PASSWORD_FIELD }) return emptyList()
        val result = mutableListOf<Candidate>()
        val parents = view.nodes.flatMap { parent -> parent.children.map { it.nodeOrdinal to parent } }.toMap()
        fun add(action: String, node: SemanticUiNode?) {
            val description = if (node == null) "back: Android Back within this app; only if needed for the bounded goal."
            else buildString {
                append(action).append("; role=").append(node.role.name.lowercase(Locale.ROOT))
                node.text?.value?.takeIf(String::isNotBlank)?.let { append("; text=").append(it.take(256)) }
                node.contentDescription?.value?.takeIf(String::isNotBlank)?.let { append("; label=").append(it.take(256)) }
                parents[node.handle.nodeOrdinal]?.let { parent ->
                    listOfNotNull(parent.text?.value, parent.contentDescription?.value).joinToString(" ")
                        .takeIf(String::isNotBlank)?.let { append("; parent=").append(it.take(128)) }
                }
                append("; bounds=").append(node.bounds.left).append(',').append(node.bounds.top)
                    .append(',').append(node.bounds.right).append(',').append(node.bounds.bottom)
                if (action == "set_text") append("; enter exactly the supplied textToEnter, directly without a focus click.")
            }
            result += Candidate("a${result.size}", action, node?.handle?.nodeOrdinal, description)
        }
        view.nodes.filter { it.packageName?.value == plan.packageName && it.visible && it.enabled &&
            it.bounds.width > 0 && it.bounds.height > 0 }.forEach { node ->
            if ("click" in plan.actions && SemanticUiAction.CLICK in node.actions &&
                !("set_text" in plan.actions && node.editable && SemanticUiAction.SET_TEXT in node.actions)) add("click", node)
            if ("set_text" in plan.actions && node.editable && SemanticUiAction.SET_TEXT in node.actions) add("set_text", node)
            if ("scroll" in plan.actions && node.scrollable) {
                if (SemanticUiAction.SCROLL_FORWARD in node.actions) add("scroll_forward", node)
                if (SemanticUiAction.SCROLL_BACKWARD in node.actions) add("scroll_backward", node)
            }
            if (result.size > FastPhoneUseContract.MAX_CANDIDATES) return null
        }
        if ("back" in plan.actions) add("back", null)
        if (result.size > FastPhoneUseContract.MAX_CANDIDATES) return null
        // Identical descriptions at different ordinals would hide an ambiguity from the model.
        if (result.map { it.description }.toSet().size != result.size) return null
        return result
    }

    private fun evidence(view: SemanticUiSnapshot, plan: Plan, progress: Progress): String = JSONObject()
        .put("goal", plan.goal).put("screenTrust", "untrusted_external")
        .put("currentScreenIsComplete", true)
        .put("visibleContextIsCompact", true)
        .put("credentialUi", view.nodes.any { it.visible && it.role == SemanticUiRole.PASSWORD_FIELD })
        .put("textToEnter", plan.text ?: JSONObject.NULL)
        .put("completedActionCount", progress.receipts.length())
        .put("remainingActionBudget", plan.maxSteps - progress.receipts.length())
        .put("actionsAlreadyAttempted", progress.privateHistory)
        .put("visibleContext", JSONArray(visibleLabels(view, 8_000)))
        .toString()

    private fun visibleLabels(view: SemanticUiSnapshot, budget: Int): List<String> {
        val labels = mutableListOf<String>()
        var remaining = budget
        view.nodes.filter { it.visible && it.role != SemanticUiRole.PASSWORD_FIELD }.forEach { node ->
            listOfNotNull(node.text?.value, node.contentDescription?.value).forEach { text ->
                val value = text.take(256)
                if (value.isNotBlank() && value !in labels && remaining >= value.length) {
                    labels += value; remaining -= value.length
                }
            }
        }
        return labels
    }

    private fun frameFingerprint(view: SemanticUiSnapshot): String {
        fun bounds(value: UiBounds) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
        // Escape individual strings rather than joining with a separator: app-owned strings
        // may contain that separator and must not create false equal-state fingerprints.
        return digest(JSONObject().put("window", view.correlation.windowId.value)
            .put("bounds", bounds(view.displayBounds)).put("roots", JSONArray(view.roots.map { it.nodeOrdinal }))
            .put("nodes", JSONArray(view.nodes.map { node ->
                JSONObject().put("ordinal", node.handle.nodeOrdinal)
                    .put("package", node.packageName?.value ?: JSONObject.NULL)
                    .put("class", node.className?.value ?: JSONObject.NULL)
                    .put("role", node.role.name).put("bounds", bounds(node.bounds))
                    .put("visible", node.visible).put("enabled", node.enabled)
                    .put("editable", node.editable).put("scrollable", node.scrollable)
                    .put("actions", JSONArray(node.actions.map { it.name }.sorted()))
                    .put("children", JSONArray(node.children.map { it.nodeOrdinal }))
                    .apply {
                        if (node.role != SemanticUiRole.PASSWORD_FIELD) {
                            put("text", node.text?.value ?: JSONObject.NULL)
                            put("description", node.contentDescription?.value ?: JSONObject.NULL)
                        }
                    }
            })).toString())
    }

    private fun inApp(view: SemanticUiSnapshot, pkg: String): Boolean {
        val roots = view.roots.mapNotNull(view::resolve)
        return roots.isNotEmpty() && roots.all { it.packageName?.value == pkg }
    }

    private fun completed(progress: Progress, view: SemanticUiSnapshot): DynamicToolExecutionResult =
        DynamicToolExecutionResult(base(progress).put("status", "completed")
            .put("goalAssessment", "model_assessed_from_visible_evidence")
            .put("externalTaskIndependentlyVerified", false)
            .put("finalView", JSONObject().put("trust", "untrusted_external")
                .put("visibleLabels", JSONArray(visibleLabels(view, 2_048))))
            .put("outcomeMeaning", "Action receipts are local postconditions; use final visible evidence to assess external task success.")
            .toString(), true)

    private fun base(progress: Progress) = JSONObject().put("replayed", false)
        .put("steps", progress.receipts).put("decisions", progress.decisions).put("decisionTimeMs", progress.decisionMillis)
        .put("intermediateViewsReturned", false).put("retryActions", false)

    private fun failure(code: String, progress: Progress = Progress()) = DynamicToolExecutionResult(
        base(progress).put("status", "failed").put("errorCode", code).toString(), false,
        failureDiagnostic = ai.hans.standard.diagnostics.ToolFailureDiagnostic.fromCodes(code))

    private fun wrapFailure(result: DynamicToolExecutionResult, progress: Progress, stage: String) =
        DynamicToolExecutionResult(base(progress).put("status", "failed").put("stoppedAt", stage)
            .put("failure", JSONObject(result.contentText)).toString(), false, failureDiagnostic = result.failureDiagnostic)

    private fun exceptionFailure(progress: Progress, gate: DynamicToolExecutionGate): DynamicToolExecutionResult {
        val result = failure("fast_phone_execution_failed", progress)
        return result.copy(contentText = JSONObject(result.contentText)
            .put("externalEffectMayHaveStarted", gate.disposition() == DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED)
            .apply { progress.inFlightStep?.let { put("unconfirmedStep", it) } }.toString())
    }

    private fun awaitPhysicalCompletion(completed: CountDownLatch) {
        var interrupted = false
        while (true) try { completed.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun decode(call: DynamicToolCallParams): Plan {
        require(call.namespace == "android_ui" && call.tool == FastPhoneUseContract.NAME)
        val args = JsonContract.parseObject(call.argumentsJson, MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
        require(args.keys().asSequence().all { it in setOf("packageName", "goal", "allowedActions", "textToEnter", "launch", "maxSteps", "timeoutMs", "confirmationRisk") })
        val pkg = JsonContract.requiredString(args, "packageName", 255)
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val goal = JsonContract.requiredString(args, "goal", 2_048)
        val actionsJson = args.getJSONArray("allowedActions")
        require(actionsJson.length() in 1..4)
        val actions = (0 until actionsJson.length()).map { actionsJson.get(it) as? String ?: error("action") }
        require(actions.toSet().size == actions.size && actions.all { it in setOf("click", "set_text", "scroll", "back") })
        val text = if (args.has("textToEnter")) JsonContract.requiredString(args, "textToEnter", 32_768, allowBlank = true) else null
        require(("set_text" in actions) == (text != null))
        val launch = if (args.has("launch")) args.get("launch") as? Boolean ?: error("launch") else false
        fun integer(name: String, fallback: Long, range: LongRange): Long {
            if (!args.has(name)) return fallback
            val value = args.get(name)
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it in range) }
        }
        val maxSteps = integer("maxSteps", 4, 1L..FastPhoneUseContract.MAX_STEPS).toInt()
        val timeout = integer("timeoutMs", 20_000, 1_000..FastPhoneUseContract.MAX_TIMEOUT_MS)
        val risk = if (args.has("confirmationRisk")) when (args.get("confirmationRisk")) {
            "none" -> AccessibilityConfirmationRisk.NONE
            "external_communication" -> AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION
            "destructive" -> AccessibilityConfirmationRisk.DESTRUCTIVE
            else -> error("risk")
        } else AccessibilityConfirmationRisk.NONE
        return Plan(pkg, goal, actions.toSet(), text, launch, maxSteps, timeout, risk)
    }

    private class Active {
        val request = AtomicReference<DecisionsCall?>()
        val session = AtomicReference<HansAccessibilitySession?>()
        fun cancelNetworkAndWake() { runCatching { request.get()?.cancel() }; runCatching { session.get()?.wakeSnapshotWaiters() } }
    }
    private class Progress(val receipts: JSONArray = JSONArray(), var decisions: Int = 0,
        var decisionMillis: Long = 0, var inFlightStep: Int? = null, val privateHistory: JSONArray = JSONArray())
    private class Plan(val packageName: String, val goal: String, val actions: Set<String>, val text: String?,
        val launchApp: Boolean, val maxSteps: Int, val timeoutMs: Long, val risk: AccessibilityConfirmationRisk)
    private class Candidate(val id: String, val action: String, val ordinal: Int?, val description: String)
    private fun key(call: DynamicToolCallParams) = digest("${call.threadId}\u0000${call.turnId}\u0000${call.callId}")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private companion object {
        // Conservative initial routing threshold, not a claimed accuracy guarantee. Real task
        // measurements must calibrate it before broad automatic use; ambiguity returns to Hans.
        const val MIN_CONFIDENCE = 0.8
    }
}
