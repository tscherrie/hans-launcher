package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.workspace.PrivateWorkspaceStore
import ai.hans.standard.workspace.WorkspaceHandle
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteWorkDynamicToolContribution(
    val activation: RemoteWorkerActivation,
    val executor: DynamicToolExecutor,
)

/** Creates no contributor and performs no probe while the optional feature is disabled. */
internal class RemoteWorkDynamicToolContributorFactory(
    private val activationProbe: RemoteWorkerActivationProbe,
    private val transport: RemoteWorkerTransport,
    private val journal: RemoteWorkJournal,
    private val workspaceStore: PrivateWorkspaceStore,
    private val artifactStore: AtomicArtifactStore,
    private val backgroundExecutor: Executor,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun create(configuration: RemoteWorkerConfiguration): RemoteWorkDynamicToolContribution? {
        val activation = activationProbe.activate(configuration) ?: return null
        activation.requireFresh(nowEpochMillis())
        val runtime = RemoteWorkRuntime(
            activation = activation,
            transport = transport,
            journal = journal,
            artifactStore = artifactStore,
            nowEpochMillis = nowEpochMillis,
        )
        return RemoteWorkDynamicToolContribution(
            activation = activation,
            executor = RemoteWorkDynamicToolExecutor(
                activation = activation,
                runtime = runtime,
                workspaceStore = workspaceStore,
                artifactStore = artifactStore,
                backgroundExecutor = backgroundExecutor,
            ),
        )
    }
}

internal class RemoteWorkDynamicToolExecutor(
    private val activation: RemoteWorkerActivation,
    private val runtime: RemoteWorkRuntime,
    private val workspaceStore: PrivateWorkspaceStore,
    private val artifactStore: AtomicArtifactStore,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> = listOf(
        RemoteWorkDynamicToolCatalog.namespace(activation.adapters),
    )

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) = executeCancellable(call, DynamicToolCancellation.NONE, completion).let { Unit }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate) }
                .getOrElse { failureResult(call, it.remoteFailureCode()) }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failureResult(call, "remote_work_executor_rejected"))
        return object : DynamicToolExecutionHandle {
            override fun onQuiescent(listener: () -> Unit): Boolean = gate.onQuiescent(listener)
            override fun cancel(): DynamicToolCancellationDisposition = gate.cancel()
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(REMOTE_ERROR_CODE::matches) ?: "remote_work_failed"),
        success = false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        require(call.namespace == RemoteWorkDynamicToolCatalog.NAMESPACE)
        val args = JsonContract.parseObject(call.argumentsJson, MAX_DYNAMIC_ARGUMENT_BYTES)
        if (!gate.markExternalEffectStarted()) return failureResult(call, "dynamic_tool_cancelled")
        return when (call.tool) {
            "submit" -> submit(args)
            "status" -> record(runtime.status(args.operationIdOnly()))
            "cancel" -> record(runtime.cancel(args.operationIdOnly()))
            "recover" -> record(runtime.recoverAmbiguous(args.operationIdOnly()))
            "import" -> imported(runtime.import(args.operationIdOnly()))
            else -> failureResult(call, "unknown_remote_work_tool")
        }
    }

    private fun submit(args: JSONObject): DynamicToolExecutionResult {
        args.requireOnly(
            "operationId",
            "adapter",
            "workspaceHandle",
            "artifactHandles",
            "arguments",
            "limits",
        )
        val operationId = RemoteWorkOperationId(args.requiredString("operationId", 128))
        val adapter = RemoteWorkAdapterId(args.requiredString("adapter", 128)).also {
            activation.requireAdapter(it)
        }
        val workspace = args.optionalString("workspaceHandle", 64)?.let(::WorkspaceHandle)
        val artifacts = args.optJSONArray("artifactHandles")?.let { array ->
            require(array.length() <= RemoteWorkRequest.MAX_INPUT_ARTIFACTS)
            (0 until array.length()).map { index -> ArtifactHandle(array.getString(index)) }
        }.orEmpty()
        val arguments = if (args.has("arguments")) args.getJSONObject("arguments") else JSONObject()
        val limits = args.optJSONObject("limits")?.let { value ->
            value.requireOnly("maximumRuntimeSeconds", "maximumOutputBytes", "maximumArtifacts")
            RemoteWorkLimits(
                maximumRuntimeSeconds = value.optInt("maximumRuntimeSeconds", 15 * 60),
                maximumOutputBytes = value.optLong("maximumOutputBytes", 512L * 1024L * 1024L),
                maximumArtifacts = value.optInt("maximumArtifacts", 32),
            )
        } ?: RemoteWorkLimits()
        val request = RemoteWorkRequest(
            operationId = operationId,
            worker = activation.configuration.identity.workerId,
            adapter = adapter,
            workspace = workspace,
            inputArtifacts = artifacts,
            argumentsJson = arguments.toString(),
            limits = limits,
        )
        val source = StoreRemoteWorkPayloadSource(request, workspaceStore, artifactStore)
        return record(runtime.submit(request, source))
    }

    private fun record(record: RemoteWorkJournalRecord): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "ok")
            .put("operationId", record.operationId.value)
            .put("phase", record.phase.name.lowercase())
            .put("executionId", record.executionId?.value ?: JSONObject.NULL)
            .put(
                "resultArtifacts",
                JSONArray().apply {
                    record.resultArtifacts.forEach { artifact ->
                        put(
                            JSONObject()
                                .put("remoteId", artifact.opaqueRemoteId)
                                .put("displayName", artifact.displayName)
                                .put("mimeType", artifact.mimeType)
                                .put("byteCount", artifact.byteCount)
                                .put("sha256", artifact.sha256),
                        )
                    }
                },
            )
            .put("output", record.outputJson?.let(::JSONObject) ?: JSONObject.NULL)
            .put(
                "importedArtifactHandles",
                JSONArray(record.importedArtifacts.map { it.localHandle.value }),
            ),
    )

    private fun imported(receipt: RemoteWorkImportReceipt): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "ok")
            .put("operationId", receipt.operationId.value)
            .put("executionId", receipt.executionId.value)
            .put("artifactHandles", JSONArray(receipt.artifacts.map { it.localHandle.value }))
            .put("output", receipt.outputJson?.let(::JSONObject) ?: JSONObject.NULL),
    )

    private fun result(value: JSONObject, success: Boolean = true): DynamicToolExecutionResult {
        val text = value.toString()
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_DYNAMIC_RESULT_BYTES)
        return DynamicToolExecutionResult(text, success)
    }

    private fun JSONObject.operationIdOnly(): RemoteWorkOperationId {
        requireOnly("operationId")
        return RemoteWorkOperationId(requiredString("operationId", 128))
    }

    private companion object {
        const val MAX_DYNAMIC_ARGUMENT_BYTES = 512 * 1024
        const val MAX_DYNAMIC_RESULT_BYTES = 1024 * 1024
    }
}

internal object RemoteWorkDynamicToolCatalog {
    const val NAMESPACE = "hans_remote_work"

    fun namespace(adapters: List<RemoteWorkAdapterDescriptor>): DynamicToolNamespaceSpec {
        require(adapters.isNotEmpty())
        val adapterIds = JSONArray(adapters.map { it.id.value })
        return DynamicToolNamespaceSpec(
            name = NAMESPACE,
            description =
                "Optional pinned remote compute for explicitly approved non-phone adapters. " +
                    "Android, device, and phone actions always remain local.",
            tools = listOf(
                function(
                    "submit",
                    "Submit one idempotently named remote job with immutable workspace/artifact inputs.",
                    JSONObject()
                        .put("operationId", string(128))
                        .put("adapter", string(128).put("enum", adapterIds))
                        .put("workspaceHandle", string(64))
                        .put(
                            "artifactHandles",
                            JSONObject()
                                .put("type", "array")
                                .put("maxItems", RemoteWorkRequest.MAX_INPUT_ARTIFACTS)
                                .put("items", string(68)),
                        )
                        .put("arguments", JSONObject().put("type", "object"))
                        .put(
                            "limits",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("maximumRuntimeSeconds", integer(1, RemoteWorkLimits.MAX_RUNTIME_SECONDS.toLong()))
                                        .put("maximumOutputBytes", integer(1, RemoteWorkLimits.MAX_OUTPUT_BYTES))
                                        .put("maximumArtifacts", integer(1, RemoteWorkLimits.MAX_ARTIFACTS.toLong())),
                                )
                                .put("additionalProperties", false),
                        ),
                    listOf("operationId", "adapter"),
                ),
                operationFunction("status", "Read the current exact receipt for a remote job."),
                operationFunction("cancel", "Request cancellation once; ambiguous cancellation is never retried silently."),
                operationFunction("recover", "Resolve an ambiguous submit or cancellation through exact receipts only."),
                operationFunction("import", "Verify and import completed result artifacts into Hans' private artifact store."),
            ),
        )
    }

    private fun operationFunction(name: String, description: String) = function(
        name,
        description,
        JSONObject().put("operationId", string(128)),
        listOf("operationId"),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = DynamicToolFunctionSpec(
        name = name,
        description = description,
        inputSchemaJson = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString(),
    )

    private fun string(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun integer(minimum: Long, maximum: Long) = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)
}

private fun JSONObject.requireOnly(vararg keys: String) {
    require(this.keys().asSequence().all { it in keys.toSet() })
}

private fun JSONObject.requiredString(name: String, maximumCharacters: Int): String {
    require(has(name) && !isNull(name))
    return getString(name).also { value ->
        require(value.isNotBlank() && value.length <= maximumCharacters && value.none(Char::isISOControl))
    }
}

private fun JSONObject.optionalString(name: String, maximumCharacters: Int): String? =
    if (!has(name) || isNull(name)) null else requiredString(name, maximumCharacters)

private fun Throwable.remoteFailureCode(): String = when (this) {
    is RemoteWorkFailure -> code
    is RemoteWorkAmbiguousFailure -> "remote_work_ambiguous"
    is IllegalArgumentException -> "remote_work_invalid_request"
    else -> "remote_work_failed"
}

private val REMOTE_ERROR_CODE = Regex("[a-z][a-z0-9_]{1,63}")
