package ai.hans.standard.phone.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
import ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolCatalog
import ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolExecutor
import ai.hans.standard.devicecontrol.tools.AccessibilitySpecialAccessProbe
import ai.hans.standard.devicecontrol.tools.AccessibilityServiceConnectionProbe
import ai.hans.standard.devicecontrol.tools.AccessibilitySessionRecovery
import ai.hans.standard.devicecontrol.tools.GenericUiStepsContract
import ai.hans.standard.devicecontrol.tools.GenericUiStepsExecutor
import ai.hans.standard.devicecontrol.tools.FastPhoneUseContract
import ai.hans.standard.devicecontrol.tools.FastPhoneUseExecutor
import ai.hans.standard.fastphone.DecisionsAccess
import ai.hans.standard.fastphone.DecisionsClient
import ai.hans.standard.fastphone.OpenAiDecisionsClient
import ai.hans.standard.fastphone.AppChoicePlan
import ai.hans.standard.fastphone.AppChoiceResult
import ai.hans.standard.fastphone.AppChoiceResolver
import ai.hans.standard.fastphone.DecisionsCall
import ai.hans.standard.fastphone.DecisionsOutcome
import ai.hans.standard.phone.capabilities.CapabilityObservation
import ai.hans.standard.phone.capabilities.CapabilityBroker
import ai.hans.standard.phone.capabilities.CapabilityCommand
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.capabilities.CapabilityExecutionResult
import ai.hans.standard.phone.capabilities.CapabilityExecutionStatus
import ai.hans.standard.phone.capabilities.CapabilityId
import ai.hans.standard.phone.capabilities.CapabilityPostcondition
import ai.hans.standard.phone.capabilities.CapabilityProjection
import ai.hans.standard.phone.capabilities.ConfirmationRisk
import ai.hans.standard.phone.capabilities.IdempotencyKey
import ai.hans.standard.phone.capabilities.PostconditionKind
import ai.hans.standard.phone.capabilities.PostconditionStatus
import ai.hans.standard.phone.capabilities.SafeViewUriPolicy
import ai.hans.standard.phone.capabilities.SettingsDestination
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI
import java.util.concurrent.Executor
import org.json.JSONObject

data class DynamicToolConfirmationRequest(
    val callId: String,
    val capabilityId: CapabilityId,
    val idempotencyKey: IdempotencyKey,
    val risk: ConfirmationRisk,
    /** Trusted local metadata derived after strict command decoding, never supplied by the model. */
    val persistentConsent: DynamicToolPersistentConsent? = null,
)

data class DynamicToolPersistentConsent(
    val scope: PersistentAndroidConsentScope,
)

/** Converts only trusted decoded tool metadata into a durable category. */
object DynamicToolPersistentConsentPolicy {
    fun descriptorFor(
        request: DynamicToolConfirmationRequest,
    ): ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor? {
        val metadata = request.persistentConsent ?: return null
        val eligible = when (metadata.scope) {
            PersistentAndroidConsentScope.INSTALLED_APPS_READ ->
                request.capabilityId == CapabilityId.LIST_LAUNCHABLE_APPS &&
                    request.risk == ConfirmationRisk.SENSITIVE_DATA
            PersistentAndroidConsentScope.OPEN_APP ->
                request.capabilityId == CapabilityId.LAUNCH_APP &&
                    request.risk == ConfirmationRisk.USER_VISIBLE
            PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION ->
                request.capabilityId == CapabilityId.OPEN_VIEW &&
                    request.risk == ConfirmationRisk.USER_VISIBLE
            PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE ->
                request.capabilityId == CapabilityId.OPEN_SETTINGS &&
                    request.risk == ConfirmationRisk.USER_VISIBLE
            else -> false
        }
        return if (eligible) {
            ai.hans.standard.phone.consent.PersistentAndroidConsentPolicy.categoryDescriptor(
                metadata.scope,
            )
        } else {
            null
        }
    }
}

/** Activity/UI integration hook. Returning null is never interpreted as consent. */
fun interface DynamicToolConfirmationProvider {
    fun confirmedGrant(request: DynamicToolConfirmationRequest): CapabilityConfirmation?

    companion object {
        val NONE = DynamicToolConfirmationProvider { null }
    }
}

/** The exact six public-Android capabilities currently implemented by CapabilityBroker. */
object AndroidDynamicToolCatalog {
    const val NAMESPACE = "android"

    internal val DESTINATIONS = linkedMapOf(
        "app_details" to SettingsDestination.APP_DETAILS,
        "home_app" to SettingsDestination.HOME_APP,
        "default_apps" to SettingsDestination.DEFAULT_APPS,
        "notification_listener" to SettingsDestination.NOTIFICATION_LISTENER,
        "accessibility" to SettingsDestination.ACCESSIBILITY,
        "battery_optimization" to SettingsDestination.BATTERY_OPTIMIZATION,
        "wireless" to SettingsDestination.WIRELESS,
        "wifi" to SettingsDestination.WIFI,
        "bluetooth" to SettingsDestination.BLUETOOTH,
    )

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description = "Public Android actions available inside the Hans app sandbox. " +
            "Hans confirmation follows the selected action policy: user-authorized full access needs no extra Hans prompt. " +
            "Actual Android permissions, profile visibility and action validation still apply.",
        tools = listOf(
            function(
                name = "list_launchable_apps",
                description = "List Android apps visible and launchable to Hans under the current Android permissions and profile access.",
                schema = emptyObjectSchema(),
            ),
            function(
                name = "launch_app",
                description = "Open an installed Android app directly. Provide either a known packageName or the user's appName (not both). An exact app name is resolved locally without listing apps to you; optional enabled Fast Phone Use resolves fuzzy names with Decisions. Pass an opaque profileId for a non-personal profile; omit for personal. Existing Android capability checks and selected action policy still apply.",
                schema = objectSchema(
                    properties = JSONObject()
                        .put(
                            "packageName",
                            JSONObject().put("type", "string").put("maxLength", 255),
                        )
                        .put(
                            "appName",
                            JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 255),
                        )
                        .put(
                            "profileId",
                            JSONObject().put("type", "string").put("maxLength", 64),
                        ),
                    required = emptyList(),
                ).put("oneOf", org.json.JSONArray()
                    .put(JSONObject().put("required", org.json.JSONArray(listOf("packageName"))))
                    .put(JSONObject().put("required", org.json.JSONArray(listOf("appName"))))),
            ),
            function(
                name = "open_view",
                description = "Open a policy-checked public HTTP(S), geo or market URI in Android. Confirmation follows the selected Hans action policy. Allowed URI schemes and argument checks remain enforced.",
                schema = objectSchema(
                    properties = JSONObject().put(
                        "uri",
                        JSONObject()
                            .put("type", "string")
                            .put("format", "uri")
                            .put("maxLength", 4_096),
                    ),
                    required = listOf("uri"),
                ),
            ),
            function(
                name = "open_settings",
                description = "Open one of Hans's known public Android settings pages. Confirmation follows the selected Hans action policy; opening a page does not grant its Android permissions.",
                schema = objectSchema(
                    properties = JSONObject()
                        .put(
                            "destination",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", org.json.JSONArray(DESTINATIONS.keys.toList())),
                        )
                        .put(
                            "packageName",
                            JSONObject()
                                .put("type", listOf("string", "null"))
                                .put("maxLength", 255),
                        ),
                    required = listOf("destination"),
                ),
            ),
            function(
                name = "read_battery",
                description = "Read current battery, charging and power-save state.",
                schema = emptyObjectSchema(),
            ),
            function(
                name = "read_network",
                description = "Read current Android network connectivity state.",
                schema = emptyObjectSchema(),
            ),
        ),
    )

    private fun function(
        name: String,
        description: String,
        schema: JSONObject,
    ): DynamicToolFunctionSpec = DynamicToolFunctionSpec(
        name = name,
        description = description,
        inputSchemaJson = schema.toString(),
    )

    private fun emptyObjectSchema(): JSONObject = objectSchema(JSONObject(), emptyList())

    private fun objectSchema(properties: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", org.json.JSONArray(required))
            .put("additionalProperties", false)

}

/** Executes CapabilityBroker commands away from the controller and Binder callback threads. */
class AndroidDynamicToolExecutor(
    private val broker: CapabilityBroker,
    private val backgroundExecutor: Executor,
    private val confirmationProvider: DynamicToolConfirmationProvider =
        DynamicToolConfirmationProvider.NONE,
    private val accessibilitySpecialAccess: AccessibilitySpecialAccessProbe =
        AccessibilitySpecialAccessProbe { false },
    private val accessibilityUiAvailability: UiInteractionAvailabilityProbe =
        UiInteractionAvailabilityProbe.FAIL_CLOSED,
    private val fastPhoneUseAccess: DecisionsAccess? = null,
    private val decisionsClient: DecisionsClient = OpenAiDecisionsClient(),
    private val onFastPhoneGoalCompleted: (ai.hans.standard.fastphone.routines.RoutineObservation) -> Unit = {},
) : DynamicToolExecutor {
    private val accessibilityExecutor = AndroidAccessibilityDynamicToolExecutor(
        // The outer cancellable dispatch owns queueing. Running the delegate inline on that worker
        // prevents an uncancellable second queue from surviving an automation stop.
        backgroundExecutor = Executor(Runnable::run),
        specialAccess = accessibilitySpecialAccess,
        uiAvailability = accessibilityUiAvailability,
    )
    private val uiStepsExecutor = GenericUiStepsExecutor(
        backgroundExecutor = backgroundExecutor,
        launch = ::executeSafely,
        availability = accessibilityUiAvailability,
        missingSessionResult = ::missingAccessibilitySession,
    )
    private val fastPhoneUseExecutor = fastPhoneUseAccess?.let { access ->
        FastPhoneUseExecutor(
            backgroundExecutor = backgroundExecutor,
            launch = ::executeSafely,
            availability = accessibilityUiAvailability,
            access = access,
            client = decisionsClient,
            onCompleted = onFastPhoneGoalCompleted,
            missingSessionResult = ::missingAccessibilitySession,
        )
    }
    private val activeAppChoices = java.util.concurrent.ConcurrentHashMap<DynamicToolExecutionGate, DecisionsCall>()

    private fun missingAccessibilitySession(): DynamicToolExecutionResult =
        AccessibilitySessionRecovery.unavailable(
            accessibilitySpecialAccess,
            AccessibilityServiceConnectionProbe {
                ai.hans.standard.phone.accessibility.android.HansAccessibilitySessions.isServiceConnected()
            },
        )

    /** A paid-mode/key revision must wake waits and cancel the old HTTP request immediately. */
    fun invalidateFastPhoneUseConfiguration() {
        fastPhoneUseExecutor?.invalidateConfiguration()
        activeAppChoices.keys.forEach { it.cancel() }
        activeAppChoices.values.forEach(DecisionsCall::cancel)
    }

    override val specs: List<DynamicToolNamespaceSpec> =
        listOf(AndroidDynamicToolCatalog.namespace) + accessibilityExecutor.specs

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
        if (call.namespace == AndroidAccessibilityDynamicToolCatalog.NAMESPACE &&
            call.tool == GenericUiStepsContract.NAME
        ) return uiStepsExecutor.executeCancellable(call, cancellation, completion)
        if (call.namespace == AndroidAccessibilityDynamicToolCatalog.NAMESPACE &&
            call.tool == FastPhoneUseContract.NAME && fastPhoneUseExecutor != null
        ) return fastPhoneUseExecutor.executeCancellable(call, cancellation, completion)
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val scheduled = gate.schedule(backgroundExecutor) {
            if (call.namespace == AndroidAccessibilityDynamicToolCatalog.NAMESPACE) {
                // The delegate may synchronously enter AccessibilityService/session APIs. Treat
                // that whole call as one external boundary and never queue it after cancellation.
                if (!gate.markExternalEffectStarted()) return@schedule
                try {
                    accessibilityExecutor.execute(call, gate::complete)
                } catch (_: Exception) {
                    gate.complete(accessibilityExecutor.failureResult(call, "dynamic_tool_exception"))
                }
                return@schedule
            }
            val output = try {
                executeSafely(call, gate)
            } catch (_: Exception) {
                projected(failed(call, "dynamic_tool_exception"))
            }
            gate.complete(output)
        }
        if (!scheduled) {
            gate.complete(
                if (call.namespace == AndroidAccessibilityDynamicToolCatalog.NAMESPACE) {
                    accessibilityExecutor.failureResult(call, "executor_rejected")
                } else {
                    projected(failed(call, "executor_rejected"))
                },
            )
        }
        return object : DynamicToolExecutionHandle {
            override fun cancel(): ai.hans.standard.codex.DynamicToolCancellationDisposition {
                val disposition = gate.cancel()
                activeAppChoices[gate]?.cancel()
                return disposition
            }
            override fun onQuiescent(listener: () -> Unit) = gate.onQuiescent(listener)
        }
    }

    override fun failureResult(
        call: DynamicToolCallParams,
        code: String,
    ): DynamicToolExecutionResult = if (
        call.namespace == AndroidAccessibilityDynamicToolCatalog.NAMESPACE
    ) {
        accessibilityExecutor.failureResult(call, code)
    } else {
        projected(failed(call, safeFailureCode(code)))
    }

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        if (call.namespace == AndroidDynamicToolCatalog.NAMESPACE && call.tool == "launch_app") {
            val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
            if (args?.has("appName") == true) return launchByAppName(call, args, gate)
        }
        val decoded = decode(call)
        if (decoded is Decoded.Rejected) return projected(decoded.result)
        decoded as Decoded.Command
        return projected(executeCapability(call, decoded, gate))
    }

    private fun executeCapability(
        call: DynamicToolCallParams,
        decoded: Decoded.Command,
        gate: DynamicToolExecutionGate,
    ): CapabilityExecutionResult {
        val confirmation = if (decoded.risk == ConfirmationRisk.NONE) {
            null
        } else {
            if (!gate.markExternalEffectStarted()) {
                return failed(call, "dynamic_tool_cancelled")
            }
            try {
                confirmationProvider.confirmedGrant(
                    DynamicToolConfirmationRequest(
                        callId = call.callId,
                        capabilityId = decoded.command.capabilityId,
                        idempotencyKey = decoded.command.idempotencyKey,
                        risk = decoded.risk,
                        persistentConsent = decoded.persistentConsent,
                    ),
                )
            } catch (_: Exception) {
                return failed(call, "confirmation_provider_failed")
            }
        }
        if (!gate.markExternalEffectStarted()) {
            return failed(call, "dynamic_tool_cancelled")
        }
        val result = broker.execute(decoded.command, confirmation).let { capabilityResult ->
            if (capabilityResult.errorCode?.startsWith("confirmation_required_") == true) {
                capabilityResult.copy(errorCode = "confirmation_required")
            } else {
                capabilityResult
            }
        }
        return result
    }

    /** Keeps discovery, optional choice and launch inside the same existing outer phone lease. */
    private fun launchByAppName(
        call: DynamicToolCallParams,
        args: JSONObject,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        val name: String
        val profile: String?
        try {
            requireOnlyKeys(args, setOf("appName", "profileId"))
            name = JsonContract.requiredString(args, "appName", 255)
            profile = JsonContract.optionalString(args, "profileId", 64)
        } catch (_: Exception) { return projected(rejected(call, "invalid_arguments")) }
        val listCall = call.copy(tool = "list_launchable_apps", argumentsJson = "{}",
            callId = "app-choice-list-${callIdempotencyKey(call.callId).value}")
        val command = decode(listCall) as? Decoded.Command
            ?: return projected(failed(call, "app_catalog_unavailable"))
        val listed = executeCapability(listCall, command, gate)
        if (listed.status != CapabilityExecutionStatus.SUCCEEDED) return projected(listed)
        val catalog = (listed.observation as? CapabilityObservation.LaunchableApps)?.apps
            ?: return projected(failed(call, "app_catalog_unavailable"))
        val chosen = when (val plan = AppChoiceResolver.prepare(catalog, name, profile)) {
            is AppChoicePlan.Resolved -> plan.app
            is AppChoicePlan.Rejected -> return projected(failed(call, plan.code))
            is AppChoicePlan.NeedsDecision -> {
                val access = fastPhoneUseAccess
                    ?: return projected(failed(call, "fast_phone_disabled"))
                if (!access.isEnabled()) return projected(failed(call, "fast_phone_disabled"))
                val revision = access.revision()
                val key = access.readApiKey() ?: return projected(failed(call, "fast_phone_key_missing"))
                val request = try {
                    decisionsClient.create(plan.question, key, OpenAiDecisionsClient.MAX_CALL_MS)
                } finally { key.fill('\u0000') }
                activeAppChoices[gate] = request
                gate.onQuiescent { activeAppChoices.remove(gate, request) }
                val result = try {
                    if (!gate.markExternalEffectStarted() || access.revision() != revision || !access.isEnabled()) {
                        request.cancel()
                        return projected(failed(call, "dynamic_tool_cancelled"))
                    }
                    request.execute()
                } catch (_: Exception) {
                    return projected(failed(call, "fast_phone_service_unavailable"))
                }
                if (access.revision() != revision || !access.isEnabled()) {
                    return projected(failed(call, "fast_phone_configuration_changed"))
                }
                if (result is DecisionsOutcome.Failure) access.recordFailure(result.code, revision)
                else access.recordSuccess(revision)
                when (val selection = plan.resolve(result)) {
                    is AppChoiceResult.Rejected -> return projected(failed(call, selection.code))
                    is AppChoiceResult.Resolved -> selection.app
                }
            }
        }
        // The final public API execution rechecks the gate, permissions and live profile access.
        return executeSafely(call.copy(argumentsJson = JSONObject().put("packageName", chosen.packageName)
            .put("profileId", chosen.profileId).toString()), gate)
    }

    private fun decode(call: DynamicToolCallParams): Decoded {
        val idempotencyKey = callIdempotencyKey(call.callId)
        if (call.namespace != AndroidDynamicToolCatalog.NAMESPACE) {
            return Decoded.Rejected(rejected(call, "unknown_dynamic_tool"))
        }
        val args = try {
            JSONObject(call.argumentsJson)
        } catch (_: Exception) {
            return Decoded.Rejected(rejected(call, "invalid_arguments"))
        }
        return try {
            when (call.tool) {
                "list_launchable_apps" -> {
                    requireOnlyKeys(args, emptySet())
                    Decoded.Command(
                        CapabilityCommand.ListLaunchableApps(idempotencyKey),
                        ConfirmationRisk.SENSITIVE_DATA,
                        DynamicToolPersistentConsent(
                            PersistentAndroidConsentScope.INSTALLED_APPS_READ,
                        ),
                    )
                }
                "launch_app" -> {
                    requireOnlyKeys(args, setOf("packageName", "profileId"))
                    Decoded.Command(
                        CapabilityCommand.LaunchApp(
                            idempotencyKey,
                            JsonContract.requiredString(args, "packageName", 255),
                            JsonContract.optionalString(args, "profileId", 64),
                        ),
                        ConfirmationRisk.USER_VISIBLE,
                        DynamicToolPersistentConsent(PersistentAndroidConsentScope.OPEN_APP),
                    )
                }
                "open_view" -> {
                    requireOnlyKeys(args, setOf("uri"))
                    val uri = JsonContract.requiredString(args, "uri", 4_096)
                    Decoded.Command(
                        CapabilityCommand.OpenView(
                            idempotencyKey,
                            uri,
                        ),
                        ConfirmationRisk.USER_VISIBLE,
                        if (
                            SafeViewUriPolicy.accept(uri) != null &&
                            runCatching { URI(uri).scheme.lowercase() in SAFE_DURABLE_VIEW_SCHEMES }
                                .getOrDefault(false)
                        ) {
                            DynamicToolPersistentConsent(
                                PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION,
                            )
                        } else {
                            null
                        },
                    )
                }
                "open_settings" -> {
                    requireOnlyKeys(args, setOf("destination", "packageName"))
                    val destination = AndroidDynamicToolCatalog.DESTINATIONS[
                        JsonContract.requiredString(args, "destination", 64)
                    ] ?: return Decoded.Rejected(rejected(call, "invalid_arguments"))
                    Decoded.Command(
                        CapabilityCommand.OpenSettings(
                            idempotencyKey,
                            destination,
                            JsonContract.optionalString(args, "packageName", 255),
                        ),
                        ConfirmationRisk.USER_VISIBLE,
                        if (destination in DURABLE_SETTINGS_DESTINATIONS) {
                            DynamicToolPersistentConsent(
                                PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE,
                            )
                        } else {
                            null
                        },
                    )
                }
                "read_battery" -> {
                    requireOnlyKeys(args, emptySet())
                    Decoded.Command(
                        CapabilityCommand.ReadBattery(idempotencyKey),
                        ConfirmationRisk.NONE,
                    )
                }
                "read_network" -> {
                    requireOnlyKeys(args, emptySet())
                    Decoded.Command(
                        CapabilityCommand.ReadNetwork(idempotencyKey),
                        ConfirmationRisk.NONE,
                    )
                }
                else -> Decoded.Rejected(rejected(call, "unknown_dynamic_tool"))
            }
        } catch (_: Exception) {
            Decoded.Rejected(rejected(call, "invalid_arguments"))
        }
    }

    private fun requireOnlyKeys(args: JSONObject, allowed: Set<String>) {
        JsonContract.requireOnlyKeys(args, allowed, "Android dynamic tool arguments")
    }

    private fun projected(result: CapabilityExecutionResult): DynamicToolExecutionResult {
        val normalized = CapabilityProjection.resultJson(result)
        val withinLimit =
            normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
        val text = if (withinLimit) {
            normalized
        } else {
            CapabilityProjection.resultJson(
                result.copy(
                    status = CapabilityExecutionStatus.FAILED,
                    observation = null,
                    postcondition = CapabilityPostcondition(
                        PostconditionKind.ADAPTER_OPERATION,
                        PostconditionStatus.FAILED,
                        "output_not_returned",
                    ),
                    errorCode = "tool_output_too_large",
                ),
            )
        }
        return DynamicToolExecutionResult(
            contentText = text,
            success = result.status == CapabilityExecutionStatus.SUCCEEDED && withinLimit,
        )
    }

    private fun rejected(call: DynamicToolCallParams, code: String): CapabilityExecutionResult =
        baseFailure(call, CapabilityExecutionStatus.REJECTED, code)

    private fun failed(call: DynamicToolCallParams, code: String): CapabilityExecutionResult =
        baseFailure(call, CapabilityExecutionStatus.FAILED, code)

    private fun baseFailure(
        call: DynamicToolCallParams,
        status: CapabilityExecutionStatus,
        code: String,
    ): CapabilityExecutionResult = CapabilityExecutionResult(
        capabilityId = capabilityIdFor(call.tool),
        idempotencyKey = callIdempotencyKey(call.callId),
        status = status,
        replayed = false,
        observation = null,
        postcondition = CapabilityPostcondition(
            if (status == CapabilityExecutionStatus.REJECTED) {
                PostconditionKind.REQUEST_NOT_EXECUTED
            } else {
                PostconditionKind.ADAPTER_OPERATION
            },
            if (status == CapabilityExecutionStatus.REJECTED) {
                PostconditionStatus.NOT_EVALUATED
            } else {
                PostconditionStatus.FAILED
            },
            if (status == CapabilityExecutionStatus.REJECTED) "not_executed" else "tool_failed",
        ),
        errorCode = code,
    )

    private fun capabilityIdFor(tool: String): CapabilityId = when (tool) {
        "list_launchable_apps" -> CapabilityId.LIST_LAUNCHABLE_APPS
        "launch_app" -> CapabilityId.LAUNCH_APP
        "open_view" -> CapabilityId.OPEN_VIEW
        "open_settings" -> CapabilityId.OPEN_SETTINGS
        "read_battery" -> CapabilityId.READ_BATTERY
        "read_network" -> CapabilityId.READ_NETWORK
        else -> UNKNOWN_DYNAMIC_TOOL_CAPABILITY
    }

    private fun callIdempotencyKey(callId: String): IdempotencyKey {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(callId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return IdempotencyKey("call:$digest")
    }

    private fun safeFailureCode(value: String): String = value.takeIf {
        it.matches(Regex("[a-z][a-z0-9_]{2,79}"))
    } ?: "dynamic_tool_failure"

    private sealed interface Decoded {
        data class Command(
            val command: CapabilityCommand,
            val risk: ConfirmationRisk,
            val persistentConsent: DynamicToolPersistentConsent? = null,
        ) : Decoded

        data class Rejected(val result: CapabilityExecutionResult) : Decoded
    }

    private companion object {
        val UNKNOWN_DYNAMIC_TOOL_CAPABILITY = CapabilityId("android.dynamic.unknown")
        val SAFE_DURABLE_VIEW_SCHEMES = setOf("https", "geo", "market")
        val DURABLE_SETTINGS_DESTINATIONS = setOf(
            SettingsDestination.WIRELESS,
            SettingsDestination.WIFI,
            SettingsDestination.BLUETOOTH,
        )
    }
}
