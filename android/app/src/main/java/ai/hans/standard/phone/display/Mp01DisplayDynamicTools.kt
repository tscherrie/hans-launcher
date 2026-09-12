package ai.hans.standard.phone.display

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object Mp01DisplayDynamicToolCatalog {
    const val NAMESPACE = "android_mp01_display"
    const val STATUS_TOOL = "status"
    const val SET_PROFILE_TOOL = "set_profile"
    const val FULL_REFRESH_TOOL = "full_refresh"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description = "Root-free Minimal Phone MP01 E-Ink controls. Availability is probed afresh; writes have no vendor acknowledgement and are reported as unverified.",
        tools = listOf(
            DynamicToolFunctionSpec(
                name = STATUS_TOOL,
                description = "Check whether this exact MP01 exposes the trusted root-free E-Ink socket.",
                inputSchemaJson = emptySchema(),
            ),
            DynamicToolFunctionSpec(
                name = SET_PROFILE_TOOL,
                description = "Request the documented Balanced, Smooth, or Speed E-Ink profile. The result confirms only the socket write, not the physical change.",
                inputSchemaJson = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put(
                        "properties",
                        JSONObject().put(
                            "profile",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", org.json.JSONArray(listOf("balanced", "smooth", "speed"))),
                        ),
                    )
                    .put("required", org.json.JSONArray(listOf("profile")))
                    .toString(),
            ),
            DynamicToolFunctionSpec(
                name = FULL_REFRESH_TOOL,
                description = "Request one documented full E-Ink refresh. The result confirms only the socket write.",
                inputSchemaJson = emptySchema(),
            ),
        ),
    )

    private fun emptySchema(): String = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("properties", JSONObject())
        .toString()
}

class Mp01DisplayDynamicToolExecutor(
    private val controller: Mp01DisplayController,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs: List<DynamicToolNamespaceSpec> =
        listOf(Mp01DisplayDynamicToolCatalog.namespace)

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        fun finish(result: DynamicToolExecutionResult) {
            if (completed.compareAndSet(false, true)) completion(result)
        }
        try {
            backgroundExecutor.execute {
                finish(runCatching { executeBounded(call) }.getOrElse {
                    failureResult(call, "mp01_display_failed")
                })
            }
        } catch (_: Exception) {
            finish(failureResult(call, "mp01_display_dispatch_failed"))
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = result(
        success = false,
        body = JSONObject()
            .put("status", "failed")
            .put("errorCode", safeCode(code)),
    )

    private fun executeBounded(call: DynamicToolCallParams): DynamicToolExecutionResult {
        if (call.namespace != Mp01DisplayDynamicToolCatalog.NAMESPACE) {
            return failureResult(call, "unknown_dynamic_tool_namespace")
        }
        val args = JSONObject(call.argumentsJson)
        return when (call.tool) {
            Mp01DisplayDynamicToolCatalog.STATUS_TOOL -> {
                requireExactKeys(args, emptySet())
                val probe = controller.probe()
                result(
                    success = true,
                    body = JSONObject()
                        .put("status", "ok")
                        .put("available", probe.available)
                        .put("availability", probe.availability.code)
                        .put("verification", "fresh_probe"),
                )
            }
            Mp01DisplayDynamicToolCatalog.SET_PROFILE_TOOL -> {
                requireExactKeys(args, setOf("profile"))
                val profile = Mp01EinkProfile.fromApiName(args.getString("profile"))
                    ?: return failureResult(call, "invalid_profile")
                commandResult(controller.setProfile(profile))
            }
            Mp01DisplayDynamicToolCatalog.FULL_REFRESH_TOOL -> {
                requireExactKeys(args, emptySet())
                commandResult(controller.fullRefresh())
            }
            else -> failureResult(call, "unknown_dynamic_tool")
        }
    }

    private fun commandResult(outcome: Mp01DisplayCommandResult): DynamicToolExecutionResult {
        val successfulWrite = outcome.status == Mp01DisplayCommandStatus.SENT_UNVERIFIED
        return result(
            success = successfulWrite,
            body = JSONObject()
                .put("status", outcome.status.code)
                .put("availability", outcome.availability.code)
                .put("action", outcome.action)
                .put("physicallyApplied", false)
                .put(
                    "verification",
                    if (successfulWrite) "device_observation_required" else "not_sent",
                ),
        )
    }

    private fun requireExactKeys(value: JSONObject, allowed: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        require(actual == allowed) { "Unexpected or missing arguments" }
    }

    private fun result(success: Boolean, body: JSONObject) = DynamicToolExecutionResult(
        contentText = body.toString(),
        success = success,
    )

    private fun safeCode(value: String): String = value.takeIf(SAFE_CODE::matches)
        ?: "mp01_display_failed"

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_.:-]{1,96}")
    }
}
