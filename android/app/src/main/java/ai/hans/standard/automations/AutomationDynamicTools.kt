package ai.hans.standard.automations

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_ARGUMENT_BYTES
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

enum class AutomationToolRisk {
    USER_VISIBLE_CHANGE,
    EXECUTE_AGENT,
    DESTRUCTIVE,
}

data class AutomationToolApprovalRequest(
    val callId: String,
    val operation: String,
    val targetAutomationId: AutomationId?,
    val argumentsSha256: String,
    val risk: AutomationToolRisk,
)

data class AutomationToolApprovalReceipt(
    val callId: String,
    val operation: String,
    val argumentsSha256: String,
    val approvedAt: Instant,
    val expiresAt: Instant,
    val nonce: String,
) {
    init {
        require(argumentsSha256.matches(Regex("[a-f0-9]{64}")))
        require(expiresAt > approvedAt)
        require(Duration.between(approvedAt, expiresAt) <= Duration.ofMinutes(10))
        require(nonce.length in 16..160 && nonce.all { it.isLetterOrDigit() || it in "-_.:" })
    }
}

/** Trusted host approval seam. Null never means consent; models cannot supply a receipt. */
fun interface AutomationToolApprovalProvider {
    fun approve(request: AutomationToolApprovalRequest): AutomationToolApprovalReceipt?

    companion object {
        val NONE = AutomationToolApprovalProvider { null }
    }
}

object HansAutomationDynamicToolCatalog {
    const val NAMESPACE = "hans_automations"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description =
            "Manage root-free Hans automations only when requested by the user. Mutations use " +
                "the trusted Hans approval policy; full access avoids an extra Hans dialog. " +
                "A user-requested automation runs unattended under capability policy when its " +
                "confirmation policy is omitted; Android permissions and declared requirements " +
                "still apply. Set EVERY_RUN only when the user wants each occurrence confirmed. " +
                "Never create persistent goals or start automations without a user request.",
        tools = listOf(
            function("list", "List bounded automation summaries.", emptySchema()),
            function("read", "Read one automation definition.", idSchema()),
            function(
                "create",
                "Create a user-requested automation under the trusted Hans approval policy.",
                objectSchema(
                    JSONObject()
                        .put("id", idProperty())
                        .put("definition", definitionSchema()),
                    listOf("id", "definition"),
                ),
            ),
            function(
                "update",
                "Replace the expected revision on user request under the trusted Hans approval policy.",
                objectSchema(
                    JSONObject()
                        .put("id", idProperty())
                        .put("expectedRevision", revisionProperty())
                        .put("definition", definitionSchema()),
                    listOf("id", "expectedRevision", "definition"),
                ),
            ),
            function("enable", "Enable a user-requested automation under the Hans approval policy.", revisionSchema()),
            function("disable", "On user request, disable and cancel pending work under the Hans approval policy.", revisionSchema()),
            function("delete", "Delete a user-requested definition under the Hans approval policy.", revisionSchema()),
            function("run_now", "On user request, enqueue one idempotent immediate run; per-run requirements still apply.", revisionSchema()),
            function(
                "history",
                "Read bounded, paginated automation run history.",
                objectSchema(
                    JSONObject()
                        .put("id", nullable(idProperty()))
                        .put(
                            "states",
                            JSONObject()
                                .put("type", "array")
                                .put("maxItems", AutomationRunState.entries.size)
                                .put("uniqueItems", true)
                                .put("items", enumProperty(AutomationRunState.entries.map { it.name })),
                        )
                        .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 200))
                        .put("before", nullable(historyCursorSchema())),
                    emptyList(),
                ),
            ),
            function(
                "confirm",
                "Ask the user to confirm one exact pending run; the model cannot self-confirm.",
                objectSchema(
                    JSONObject()
                        .put("automationId", idProperty())
                        .put("scheduledAt", instantProperty())
                        .put("expectedRevision", revisionProperty()),
                    listOf("automationId", "scheduledAt", "expectedRevision"),
                ),
            ),
        ),
    )

    private fun function(name: String, description: String, schema: JSONObject) =
        DynamicToolFunctionSpec(name, description, schema.toString())

    private fun emptySchema() = objectSchema(JSONObject(), emptyList())

    private fun idSchema() = objectSchema(JSONObject().put("id", idProperty()), listOf("id"))

    private fun revisionSchema() = objectSchema(
        JSONObject().put("id", idProperty()).put("expectedRevision", revisionProperty()),
        listOf("id", "expectedRevision"),
    )

    private fun definitionSchema(): JSONObject = objectSchema(
        JSONObject()
            .put("schedule", scheduleSchema())
            .put("instruction", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 32_768))
            .put("target", targetSchema())
            .put("missedRun", missedRunSchema())
            .put("retry", retrySchema())
            .put("requirements", requirementsSchema())
            .put("timingPolicy", enumProperty(AutomationTimingPolicy.entries.map { it.name })),
        listOf("schedule", "instruction"),
    )

    private fun scheduleSchema() = objectSchema(
        JSONObject()
            .put("startLocal", JSONObject().put("type", "string").put("maxLength", 64))
            .put("timeZone", JSONObject().put("type", "string").put("maxLength", 128))
            .put("rrule", JSONObject().put("type", "string").put("maxLength", 1_024)),
        listOf("startLocal", "timeZone", "rrule"),
    )

    private fun targetSchema() = objectSchema(
        JSONObject()
            .put("kind", enumProperty(listOf("independent", "thread")))
            .put("threadId", nullable(JSONObject().put("type", "string").put("maxLength", 256))),
        listOf("kind"),
    )

    private fun missedRunSchema() = objectSchema(
        JSONObject()
            .put("mode", enumProperty(MissedRunMode.entries.map { it.name }))
            .put("graceSeconds", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 604_800))
            .put("maximumCatchUpRuns", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 256)),
        emptyList(),
    )

    private fun retrySchema() = objectSchema(
        JSONObject()
            .put("maximumAttempts", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20))
            .put("initialBackoffSeconds", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 2_592_000))
            .put("backoffMultiplier", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10))
            .put("maximumBackoffSeconds", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 2_592_000)),
        emptyList(),
    )

    private fun requirementsSchema() = objectSchema(
        JSONObject()
            .put("capabilities", stringArray(64, 128))
            .put("permissions", stringArray(64, 192))
            .put("requiresCodexAuthentication", JSONObject().put("type", "boolean"))
            .put("requiresNetwork", JSONObject().put("type", "boolean"))
            .put("requiresUnlockedDevice", JSONObject().put("type", "boolean"))
            .put(
                "confirmationPolicy",
                enumProperty(AutomationConfirmationPolicy.entries.map { it.name })
                    .put("default", AutomationConfirmationPolicy.CAPABILITY_POLICY.name)
                    .put(
                        "description",
                        "Omit for unattended capability-policy execution of this explicitly " +
                            "user-requested automation; use EVERY_RUN for exact-run confirmation.",
                    ),
            ),
        emptyList(),
    )

    private fun historyCursorSchema() = objectSchema(
        JSONObject()
            .put("updatedAt", instantProperty())
            .put("automationId", idProperty())
            .put("scheduledAt", instantProperty()),
        listOf("updatedAt", "automationId", "scheduledAt"),
    )

    private fun stringArray(maxItems: Int, maxLength: Int) = JSONObject()
        .put("type", "array")
        .put("maxItems", maxItems)
        .put("uniqueItems", true)
        .put("items", JSONObject().put("type", "string").put("maxLength", maxLength))

    private fun idProperty() = JSONObject().put("type", "string").put("minLength", 3).put("maxLength", 96)
    private fun revisionProperty() = JSONObject().put("type", "integer").put("minimum", 1)
    private fun instantProperty() = JSONObject().put("type", "string").put("format", "date-time").put("maxLength", 64)
    private fun enumProperty(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))
    private fun nullable(schema: JSONObject) = JSONObject().put("anyOf", JSONArray().put(schema).put(JSONObject().put("type", "null")))
    private fun objectSchema(properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)
}

class HansAutomationDynamicToolExecutor(
    private val runtime: AutomationRuntimeOwner,
    private val backgroundExecutor: Executor,
    private val approvalProvider: AutomationToolApprovalProvider = AutomationToolApprovalProvider.NONE,
    private val instantSource: AutomationInstantSource = AutomationInstantSource(Instant::now),
) : DynamicToolExecutor {
    override val specs = listOf(HansAutomationDynamicToolCatalog.namespace)

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { dispatch(call, gate) }
                .getOrElse { failure("dynamic_tool_exception") }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failure("executor_rejected"))
        return gate
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = failure(safeCode(code))

    private fun dispatch(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        if (call.namespace != HansAutomationDynamicToolCatalog.NAMESPACE) {
            return failure("unknown_dynamic_tool")
        }
        val args = parseArguments(call.argumentsJson) ?: return failure("invalid_arguments")
        return try {
            when (call.tool) {
                "list" -> list(args, gate)
                "read" -> read(args, gate)
                "create" -> create(call, args, gate)
                "update" -> update(call, args, gate)
                "enable" -> setEnabled(call, args, enabled = true, gate = gate)
                "disable" -> setEnabled(call, args, enabled = false, gate = gate)
                "delete" -> delete(call, args, gate)
                "run_now" -> runNow(call, args, gate)
                "history" -> history(args, gate)
                "confirm" -> confirm(call, args, gate)
                else -> failure("unknown_dynamic_tool")
            }
        } catch (_: RuntimeException) {
            failure("invalid_arguments")
        }
    }

    private fun list(
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, emptySet())
        if (!gate.markExternalEffectStarted()) return cancelled()
        val definitions = runtime.storage.definitions().take(256)
        return success(
            "list",
            JSONObject()
                .put("count", definitions.size)
                .put("items", JSONArray().also { out -> definitions.forEach { out.put(it.summaryJson()) } }),
        )
    }

    private fun read(
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id"))
        val id = AutomationId(string(args, "id", 96))
        if (!gate.markExternalEffectStarted()) return cancelled()
        val definition = runtime.storage.definition(id) ?: return failure("automation_not_found")
        return success("read", definition.detailJson())
    }

    private fun create(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "definition"))
        val id = AutomationId(string(args, "id", 96))
        val draft = decodeDraft(objectValue(args, "definition"), enabled = true)
        requireApproval(call, args, "create", id, AutomationToolRisk.USER_VISIBLE_CHANGE, gate)
            ?: return confirmationRequired("create")
        if (!gate.markExternalEffectStarted()) return cancelled()
        return when (val result = runtime.definitions.create(id, draft)) {
            is AutomationDefinitionMutationResult.Applied -> {
                // The write and scheduler reconciliation form one logical operation. Once the
                // write crossed its boundary, cancellation only suppresses the App Server reply;
                // it must not strand durable work without a platform wakeup.
                successAfterScheduleReconciliation("create", result.definition.detailJson())
            }
            is AutomationDefinitionMutationResult.InvalidSchedule -> failure(result.errorCode)
            AutomationDefinitionMutationResult.AlreadyExists -> failure("automation_already_exists")
            else -> failure("automation_create_conflict")
        }
    }

    private fun update(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "expectedRevision", "definition"))
        val id = AutomationId(string(args, "id", 96))
        val revision = positiveLong(args, "expectedRevision")
        if (!gate.markExternalEffectStarted()) return cancelled()
        val current = runtime.storage.definition(id) ?: return failure("automation_not_found")
        if (current.revision != revision) return failure("revision_conflict")
        val draft = decodeDraft(
            objectValue(args, "definition"),
            enabled = current.enabled,
            defaultRequirements = current.requirements,
        )
        requireApproval(call, args, "update", id, AutomationToolRisk.USER_VISIBLE_CHANGE, gate)
            ?: return confirmationRequired("update")
        if (!gate.markExternalEffectStarted()) return cancelled()
        return when (val result = runtime.definitions.replace(id, revision, draft)) {
            is AutomationDefinitionMutationResult.Applied -> {
                successAfterScheduleReconciliation("update", result.definition.detailJson())
            }
            is AutomationDefinitionMutationResult.InvalidSchedule -> failure(result.errorCode)
            AutomationDefinitionMutationResult.NotFound -> failure("automation_not_found")
            AutomationDefinitionMutationResult.RevisionConflict -> failure("revision_conflict")
            AutomationDefinitionMutationResult.RevisionExhausted -> failure("revision_exhausted")
            else -> failure("automation_update_failed")
        }
    }

    private fun setEnabled(
        call: DynamicToolCallParams,
        args: JSONObject,
        enabled: Boolean,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "expectedRevision"))
        val id = AutomationId(string(args, "id", 96))
        val revision = positiveLong(args, "expectedRevision")
        if (!gate.markExternalEffectStarted()) return cancelled()
        val current = runtime.storage.definition(id) ?: return failure("automation_not_found")
        if (current.revision != revision) return failure("revision_conflict")
        val operation = if (enabled) "enable" else "disable"
        requireApproval(call, args, operation, id, AutomationToolRisk.USER_VISIBLE_CHANGE, gate)
            ?: return confirmationRequired(operation)
        if (current.enabled == enabled) return success(operation, current.detailJson())
        if (!enabled) {
            if (!gate.markExternalEffectStarted()) return cancelled()
            return when (runtime.definitions.cancel(id, revision)) {
                is AutomationCancellationResult.Cancelled -> {
                    val scheduleResult = runtime.scheduleChanged()
                    if (!gate.markExternalEffectStarted()) return cancelled()
                    val effective = runtime.storage.definition(id)
                        ?: return failure("automation_not_found")
                    successAfterScheduleReconciliation(
                        operation,
                        effective.detailJson(),
                        scheduleResult,
                    )
                }
                AutomationCancellationResult.RevisionConflict -> failure("revision_conflict")
                AutomationCancellationResult.NotFound -> failure("automation_not_found")
                AutomationCancellationResult.AlreadyDisabled -> success(operation, current.detailJson())
            }
        }
        if (!gate.markExternalEffectStarted()) return cancelled()
        val result = runtime.definitions.replace(id, revision, current.toDraft(enabled = true))
        return if (result is AutomationDefinitionMutationResult.Applied) {
            successAfterScheduleReconciliation(operation, result.definition.detailJson())
        } else {
            failure("automation_enable_failed")
        }
    }

    private fun delete(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "expectedRevision"))
        val id = AutomationId(string(args, "id", 96))
        val revision = positiveLong(args, "expectedRevision")
        if (!gate.markExternalEffectStarted()) return cancelled()
        val current = runtime.storage.definition(id) ?: return failure("automation_not_found")
        if (current.revision != revision) return failure("revision_conflict")
        requireApproval(call, args, "delete", id, AutomationToolRisk.DESTRUCTIVE, gate)
            ?: return confirmationRequired("delete")
        val definitionWasDisabledForDelete = current.enabled
        val revisionToDelete = if (definitionWasDisabledForDelete) {
            if (!gate.markExternalEffectStarted()) return cancelled()
            when (val cancellation = runtime.definitions.cancel(id, revision)) {
                is AutomationCancellationResult.Cancelled -> cancellation.newRevision
                AutomationCancellationResult.RevisionConflict -> return failure("revision_conflict")
                AutomationCancellationResult.NotFound -> return failure("automation_not_found")
                AutomationCancellationResult.AlreadyDisabled -> return failure("automation_delete_conflict")
            }
        } else {
            revision
        }
        // Once cancelDefinition changed an enabled definition, finish the compare-and-delete as
        // part of that same mutation. For an already-disabled definition this is still the first
        // persistent effect, so cancellation gets one final chance immediately before it.
        if (!definitionWasDisabledForDelete && !gate.markExternalEffectStarted()) {
            return cancelled()
        }
        if (!runtime.storage.removeDefinition(id, revisionToDelete)) {
            return failure("automation_delete_conflict")
        }
        return successAfterScheduleReconciliation(
            "delete",
            JSONObject().put("id", id.value).put("deleted", true),
        )
    }

    private fun runNow(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "expectedRevision"))
        val id = AutomationId(string(args, "id", 96))
        val revision = positiveLong(args, "expectedRevision")
        requireApproval(call, args, "run_now", id, AutomationToolRisk.EXECUTE_AGENT, gate)
            ?: return confirmationRequired("run_now")
        val requestId = AutomationManualRequestId(
            sha256(listOf(call.threadId, call.turnId, call.callId, "run_now").joinToString("\u0000")),
        )
        if (!gate.markExternalEffectStarted()) return cancelled()
        return when (val result = runtime.enqueueRunNow(id, revision, requestId)) {
            is AutomationManualEnqueueResult.Enqueued -> success(
                "run_now",
                runKeyJson(result.key).put("duplicate", false),
            )
            is AutomationManualEnqueueResult.Duplicate -> success(
                "run_now",
                runKeyJson(result.key).put("duplicate", true),
            )
            is AutomationManualEnqueueResult.DispatchRejected -> failure(result.errorCode)
            AutomationManualEnqueueResult.DefinitionNotFound -> failure("automation_not_found")
            AutomationManualEnqueueResult.RevisionConflict -> failure("revision_conflict")
            AutomationManualEnqueueResult.DefinitionDisabled -> failure("automation_disabled")
            AutomationManualEnqueueResult.RequestConflict -> failure("idempotency_conflict")
        }
    }

    private fun history(
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("id", "states", "limit", "before"))
        val id = nullableString(args, "id", 96)?.let(::AutomationId)
        val states = if (!args.has("states") || args.isNull("states")) emptySet() else {
            val array = args.opt("states") as? JSONArray ?: error("states must be array")
            require(array.length() <= AutomationRunState.entries.size)
            buildSet {
                repeat(array.length()) {
                    require(add(AutomationRunState.valueOf(array.getString(it))))
                }
            }
        }
        val limit = optionalLong(args, "limit")?.toInt() ?: 50
        require(limit in 1..200)
        val cursor = if (!args.has("before") || args.isNull("before")) null else {
            val value = objectValue(args, "before")
            only(value, setOf("updatedAt", "automationId", "scheduledAt"))
            AutomationHistoryCursor(
                Instant.parse(string(value, "updatedAt", 64)),
                AutomationRunKey(
                    AutomationId(string(value, "automationId", 96)),
                    Instant.parse(string(value, "scheduledAt", 64)),
                ),
            )
        }
        if (!gate.markExternalEffectStarted()) return cancelled()
        val page = runtime.history.page(id, states, cursor, limit)
        return success(
            "history",
            JSONObject()
                .put("items", JSONArray().also { out -> page.runs.forEach { out.put(it.historyJson()) } })
                .put("hasMore", page.hasMore)
                .put("nextCursor", page.nextCursor?.json() ?: JSONObject.NULL),
        )
    }

    private fun confirm(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        only(args, setOf("automationId", "scheduledAt", "expectedRevision"))
        val id = AutomationId(string(args, "automationId", 96))
        val key = AutomationRunKey(id, Instant.parse(string(args, "scheduledAt", 64)))
        val revision = positiveLong(args, "expectedRevision")
        if (!gate.markExternalEffectStarted()) return cancelled()
        val run = runtime.storage.snapshot().runs.firstOrNull { it.key == key }
            ?: return failure("automation_run_not_found")
        if (run.definitionRevision != revision) return failure("revision_conflict")
        val approval = requireApproval(
            call,
            args,
            "confirm",
            id,
            AutomationToolRisk.EXECUTE_AGENT,
            gate,
        ) ?: return confirmationRequired("confirm")
        val now = instantSource.now()
        val receipt = AutomationRunConfirmationReceipt(
            id = AutomationConfirmationId("confirmation:${sha256(approval.nonce)}"),
            key = key,
            definitionRevision = revision,
            confirmedAt = now,
            expiresAt = minOf(now.plus(Duration.ofHours(24)), approval.expiresAt.plus(Duration.ofHours(23))),
        )
        if (!gate.markExternalEffectStarted()) return cancelled()
        return when (runtime.storage.recordConfirmation(receipt, now)) {
            AutomationConfirmationWriteResult.STORED,
            AutomationConfirmationWriteResult.DUPLICATE,
            -> {
                // Confirmation persistence and its wakeup are one atomic semantic effect. The
                // response projection below may stop, but the newly runnable work must not.
                val scheduleResult = runtime.scheduleChanged()
                if (!gate.markExternalEffectStarted()) return cancelled()
                val effectiveReceipt = runtime.storage.snapshot().confirmations
                    .firstOrNull { it.key == key && it.definitionRevision == revision }
                    ?: return failure("automation_confirmation_state_changed")
                val response = successAfterScheduleReconciliation(
                    "confirm",
                    runKeyJson(key)
                        .put("confirmed", true)
                        .put("expiresAt", effectiveReceipt.expiresAt.toString()),
                    scheduleResult,
                )
                response
            }
            AutomationConfirmationWriteResult.CONFIRMATION_ID_CONFLICT ->
                failure("confirmation_receipt_conflict")
            AutomationConfirmationWriteResult.RUN_NOT_FOUND -> failure("automation_run_not_found")
            AutomationConfirmationWriteResult.REVISION_CONFLICT -> failure("revision_conflict")
            AutomationConfirmationWriteResult.RUN_NOT_CONFIRMABLE -> failure("automation_run_not_confirmable")
        }
    }

    private fun requireApproval(
        call: DynamicToolCallParams,
        args: JSONObject,
        operation: String,
        id: AutomationId?,
        risk: AutomationToolRisk,
        gate: DynamicToolExecutionGate,
    ): AutomationToolApprovalReceipt? {
        val digest = sha256(args.toString())
        if (!gate.markExternalEffectStarted()) return null
        val receipt = approvalProvider.approve(
            AutomationToolApprovalRequest(call.callId, operation, id, digest, risk),
        ) ?: return null
        val now = instantSource.now()
        return receipt.takeIf {
            it.callId == call.callId &&
                it.operation == operation &&
                it.argumentsSha256 == digest &&
                it.approvedAt <= now.plusSeconds(30) &&
                it.expiresAt > now
        }
    }

    private fun decodeDraft(
        value: JSONObject,
        enabled: Boolean,
        defaultRequirements: AutomationRequirements = AutomationRequirements(
            confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
        ),
    ): AutomationDefinitionDraft {
        only(
            value,
            setOf("schedule", "instruction", "target", "missedRun", "retry", "requirements", "timingPolicy"),
        )
        val scheduleJson = objectValue(value, "schedule")
        only(scheduleJson, setOf("startLocal", "timeZone", "rrule"))
        val timeZone = string(scheduleJson, "timeZone", 128)
        val schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.parse(string(scheduleJson, "startLocal", 64)),
            timeZone = if (timeZone == "system") {
                AutomationTimeZone.FollowSystem
            } else {
                AutomationTimeZone.Fixed(timeZone)
            },
            rrule = string(scheduleJson, "rrule", 1_024),
        )
        val target = if (!value.has("target") || value.isNull("target")) {
            CodexAutomationTarget.Independent
        } else {
            val targetJson = objectValue(value, "target")
            only(targetJson, setOf("kind", "threadId"))
            when (string(targetJson, "kind", 32)) {
                "independent" -> {
                    require(nullableString(targetJson, "threadId", 256) == null)
                    CodexAutomationTarget.Independent
                }
                "thread" -> CodexAutomationTarget.ThreadBound(string(targetJson, "threadId", 256))
                else -> error("invalid target")
            }
        }
        return AutomationDefinitionDraft(
            enabled = enabled,
            schedule = schedule,
            missedRunPolicy = decodeMissed(value),
            retryPolicy = decodeRetry(value),
            target = target,
            instruction = string(value, "instruction", 32_768),
            requirements = decodeRequirements(value, defaultRequirements),
            timingPolicy = optionalEnum(value, "timingPolicy", AutomationTimingPolicy::valueOf)
                ?: AutomationTimingPolicy.RELIABLE_INEXACT,
        )
    }

    private fun decodeMissed(parent: JSONObject): MissedRunPolicy {
        if (!parent.has("missedRun") || parent.isNull("missedRun")) return MissedRunPolicy()
        val value = objectValue(parent, "missedRun")
        only(value, setOf("mode", "graceSeconds", "maximumCatchUpRuns"))
        return MissedRunPolicy(
            mode = optionalEnum(value, "mode", MissedRunMode::valueOf) ?: MissedRunMode.RUN_LATEST,
            gracePeriod = Duration.ofSeconds(optionalLong(value, "graceSeconds") ?: 300),
            maximumCatchUpRuns = (optionalLong(value, "maximumCatchUpRuns") ?: 16).toInt(),
        )
    }

    private fun decodeRetry(parent: JSONObject): AutomationRetryPolicy {
        if (!parent.has("retry") || parent.isNull("retry")) return AutomationRetryPolicy()
        val value = objectValue(parent, "retry")
        only(value, setOf("maximumAttempts", "initialBackoffSeconds", "backoffMultiplier", "maximumBackoffSeconds"))
        return AutomationRetryPolicy(
            maximumAttempts = (optionalLong(value, "maximumAttempts") ?: 4).toInt(),
            initialBackoff = Duration.ofSeconds(optionalLong(value, "initialBackoffSeconds") ?: 30),
            backoffMultiplier = (optionalLong(value, "backoffMultiplier") ?: 2).toInt(),
            maximumBackoff = Duration.ofSeconds(optionalLong(value, "maximumBackoffSeconds") ?: 21_600),
        )
    }

    private fun decodeRequirements(
        parent: JSONObject,
        defaults: AutomationRequirements,
    ): AutomationRequirements {
        if (!parent.has("requirements") || parent.isNull("requirements")) {
            return defaults
        }
        val value = objectValue(parent, "requirements")
        only(
            value,
            setOf(
                "capabilities",
                "permissions",
                "requiresCodexAuthentication",
                "requiresNetwork",
                "requiresUnlockedDevice",
                "confirmationPolicy",
            ),
        )
        val capabilities = stringSet(value, "capabilities", 64, 128)
            ?.mapTo(linkedSetOf(), ::AutomationCapabilityId)
            ?: defaults.requiredCapabilities
        require(AutomationCapabilityId.CODEX_APP_SERVER in capabilities)
        return AutomationRequirements(
            requiredCapabilities = capabilities,
            requiredPermissions = stringSet(value, "permissions", 64, 192)
                ?.mapTo(linkedSetOf(), ::AutomationPermissionId)
                ?: defaults.requiredPermissions,
            requiresCodexAuthentication = optionalBoolean(value, "requiresCodexAuthentication")
                ?: defaults.requiresCodexAuthentication,
            requiresNetwork = optionalBoolean(value, "requiresNetwork")
                ?: defaults.requiresNetwork,
            requiresUnlockedDevice = optionalBoolean(value, "requiresUnlockedDevice")
                ?: defaults.requiresUnlockedDevice,
            confirmationPolicy = optionalEnum(
                value,
                "confirmationPolicy",
                AutomationConfirmationPolicy::valueOf,
            ) ?: defaults.confirmationPolicy,
        )
    }

    private fun AutomationDefinition.toDraft(enabled: Boolean) = AutomationDefinitionDraft(
        enabled,
        schedule,
        missedRunPolicy,
        retryPolicy,
        target,
        instruction,
        requirements,
        timingPolicy,
    )

    private fun AutomationDefinition.summaryJson() = JSONObject()
        .put("id", id.value)
        .put("revision", revision)
        .put("enabled", enabled)
        .put("nextTimingPolicy", timingPolicy.name)
        .put("rrule", schedule.rrule)
        .put("startLocal", schedule.dtStartLocal.toString())
        .put("timeZone", (schedule.timeZone as? AutomationTimeZone.Fixed)?.zoneId ?: "system")

    private fun AutomationDefinition.detailJson() = summaryJson()
        .put("instruction", instruction)
        .put("target", when (val value = target) {
            is CodexAutomationTarget.ThreadBound -> JSONObject().put("kind", "thread").put("threadId", value.threadId)
            CodexAutomationTarget.Independent -> JSONObject().put("kind", "independent")
        })
        .put("requirements", JSONObject()
            .put("capabilities", JSONArray(requirements.requiredCapabilities.sorted().map { it.value }))
            .put("permissions", JSONArray(requirements.requiredPermissions.sorted().map { it.value }))
            .put("requiresCodexAuthentication", requirements.requiresCodexAuthentication)
            .put("requiresNetwork", requirements.requiresNetwork)
            .put("requiresUnlockedDevice", requirements.requiresUnlockedDevice)
            .put("confirmationPolicy", requirements.confirmationPolicy.name))

    private fun AutomationRun.historyJson() = runKeyJson(key)
        .put("definitionRevision", definitionRevision)
        .put("state", state.name)
        .put("attemptCount", attemptCount)
        .put("availableAt", availableAt.toString())
        .put("createdAt", createdAt.toString())
        .put("updatedAt", updatedAt.toString())
        .put("lastFailureCode", lastFailureCode ?: JSONObject.NULL)

    private fun AutomationHistoryCursor.json() = JSONObject()
        .put("updatedAt", updatedAt.toString())
        .put("automationId", key.automationId.value)
        .put("scheduledAt", key.scheduledAt.toString())

    private fun runKeyJson(key: AutomationRunKey) = JSONObject()
        .put("automationId", key.automationId.value)
        .put("scheduledAt", key.scheduledAt.toString())

    private fun success(operation: String, data: JSONObject) = result(
        success = true,
        JSONObject().put("status", "succeeded").put("operation", operation).put("data", data),
    )

    /**
     * The mutation is already durable here. If Android rejects both the immediate dispatch and
     * its fallback, report that durable-but-pending state without inviting a duplicate mutation.
     * Startup, user-present and connectivity reconciliation all retry the retained work ledger.
     */
    private fun successAfterScheduleReconciliation(
        operation: String,
        data: JSONObject,
        scheduleResult: AutomationAdapterResult = runtime.scheduleChanged(),
    ): DynamicToolExecutionResult = when (scheduleResult) {
        AutomationAdapterResult.Accepted -> success(
            operation,
            data.put("scheduleState", "scheduled"),
        )
        is AutomationAdapterResult.Rejected -> success(
            operation,
            data
                .put("scheduleState", "reconciliation_pending")
                .put("warningCode", "automation_reconciliation_pending"),
        )
    }

    private fun confirmationRequired(operation: String) = result(
        success = false,
        JSONObject().put("status", "confirmation_required").put("operation", operation)
            .put("errorCode", "user_confirmation_required"),
    )

    private fun failure(code: String) = result(
        success = false,
        JSONObject().put("status", "failed").put("errorCode", safeCode(code)),
    )

    private fun cancelled() = failure("dynamic_tool_cancelled")

    private fun result(success: Boolean, value: JSONObject): DynamicToolExecutionResult {
        val text = value.toString()
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES) {
            return DynamicToolExecutionResult(
                JSONObject().put("status", "failed").put("errorCode", "tool_output_too_large").toString(),
                false,
            )
        }
        return DynamicToolExecutionResult(text, success)
    }

    private fun parseArguments(raw: String): JSONObject? = runCatching {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_DYNAMIC_TOOL_ARGUMENT_BYTES)
        JSONObject(raw)
    }.getOrNull()

    private fun only(value: JSONObject, allowed: Set<String>) {
        val keys = value.keys()
        while (keys.hasNext()) require(keys.next() in allowed)
    }

    private fun objectValue(parent: JSONObject, key: String): JSONObject =
        parent.opt(key) as? JSONObject ?: error("$key must be object")

    private fun string(parent: JSONObject, key: String, maximumBytes: Int): String {
        val value = parent.opt(key) as? String ?: error("$key must be string")
        require(value.isNotBlank())
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes)
        return value
    }

    private fun nullableString(parent: JSONObject, key: String, maximumBytes: Int): String? =
        if (!parent.has(key) || parent.isNull(key)) null else string(parent, key, maximumBytes)

    private fun positiveLong(parent: JSONObject, key: String): Long =
        optionalLong(parent, key)?.also { require(it >= 1) } ?: error("$key required")

    private fun optionalLong(parent: JSONObject, key: String): Long? {
        if (!parent.has(key) || parent.isNull(key)) return null
        return when (val value = parent.opt(key)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> error("$key must be integer")
        }
    }

    private fun optionalBoolean(parent: JSONObject, key: String): Boolean? {
        if (!parent.has(key) || parent.isNull(key)) return null
        return parent.opt(key) as? Boolean ?: error("$key must be boolean")
    }

    private fun stringSet(
        parent: JSONObject,
        key: String,
        maximumItems: Int,
        maximumBytes: Int,
    ): Set<String>? {
        if (!parent.has(key) || parent.isNull(key)) return null
        val values = parent.opt(key) as? JSONArray ?: error("$key must be array")
        require(values.length() <= maximumItems)
        return buildSet {
            repeat(values.length()) {
                val value = values.opt(it) as? String ?: error("$key item must be string")
                require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes)
                require(add(value))
            }
        }
    }

    private fun <T> optionalEnum(
        parent: JSONObject,
        key: String,
        decode: (String) -> T,
    ): T? = nullableString(parent, key, 64)?.let(decode)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun safeCode(code: String): String = code.takeIf {
        it.matches(Regex("[a-z][a-z0-9_]{2,95}"))
    } ?: "dynamic_tool_failure"
}
