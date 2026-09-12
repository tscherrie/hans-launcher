package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.plugins.PluginRuntimeReadiness
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private val ANDROID_BINDING_ID = Regex("[a-z][a-z0-9._:-]{0,95}")
private val ANDROID_CAPABILITY_ID = Regex("[a-z][a-z0-9._:-]{0,127}")

internal fun interface PassiveCapabilityProbe {
    fun readiness(): PluginRuntimeReadiness
}

/** A trusted Android dynamic-tool binding owned by signed application code. */
internal data class AndroidDynamicToolRegistration(
    val bindingId: String,
    val capabilityId: String,
    val namespace: String,
    val tool: String,
    val executor: DynamicToolExecutor,
    val capabilityProbe: PassiveCapabilityProbe,
) {
    init {
        require(bindingId.matches(ANDROID_BINDING_ID)) { "Invalid Android binding id" }
        require(capabilityId.matches(ANDROID_CAPABILITY_ID)) { "Invalid Android capability id" }
        val namespaceSpec = executor.specs.singleOrNull { it.name == namespace }
            ?: throw IllegalArgumentException("Android binding namespace is not exported")
        require(namespaceSpec.tools.any { it.name == tool }) {
            "Android binding tool is not exported"
        }
    }

    fun spec(): DynamicToolFunctionSpec = executor.specs
        .single { it.name == namespace }
        .tools.single { it.name == tool }
}

internal data class AndroidDynamicToolBindingSnapshot(
    val bindingId: String,
    val capabilityId: String,
    val namespace: String,
    val tool: String,
    val readiness: PluginRuntimeReadiness,
    /** Exact signed-APK tool contract, without an executor/class identity. */
    val contractSha256: String,
)

internal data class AndroidEntrypointResolution(
    val resolvedRequirementIds: Set<String>,
    val availableCapabilityIds: Set<String>,
    val missingRequiredIds: Set<String>,
    val degradedIds: Set<String>,
)

/**
 * Passive registry for plugin-to-Android entrypoints. A plugin references only [bindingId]; class
 * names, arbitrary namespaces and executable targets never cross the plugin trust boundary.
 */
internal class AndroidDynamicToolEntrypointRegistry(
    registrations: List<AndroidDynamicToolRegistration>,
) {
    private val byId = registrations.associateBy(AndroidDynamicToolRegistration::bindingId)

    init {
        require(registrations.size <= 256)
        require(byId.size == registrations.size) { "Duplicate Android binding id" }
        val targets = registrations.map { it.namespace to it.tool }
        require(targets.distinct().size == targets.size) { "Duplicate Android binding target" }
    }

    fun snapshot(): List<AndroidDynamicToolBindingSnapshot> = byId.values
        .map { registration ->
            AndroidDynamicToolBindingSnapshot(
                bindingId = registration.bindingId,
                capabilityId = registration.capabilityId,
                namespace = registration.namespace,
                tool = registration.tool,
                readiness = runCatching(registration.capabilityProbe::readiness)
                    .getOrDefault(PluginRuntimeReadiness.UNAVAILABLE),
                contractSha256 = sha256(
                    registration.spec().toJson().toString().toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }
        .sortedBy(AndroidDynamicToolBindingSnapshot::bindingId)

    fun resolve(requirements: List<AndroidToolRequirement>): AndroidEntrypointResolution {
        return resolve(requirements, snapshot())
    }

    internal fun resolve(
        requirements: List<AndroidToolRequirement>,
        evidence: List<AndroidDynamicToolBindingSnapshot>,
    ): AndroidEntrypointResolution {
        val snapshots = evidence.associateBy(AndroidDynamicToolBindingSnapshot::bindingId)
        val resolved = linkedSetOf<String>()
        val capabilities = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        val degraded = linkedSetOf<String>()
        requirements.sortedBy(AndroidToolRequirement::id).forEach { requirement ->
            when (val registration = snapshots[requirement.bindingId]) {
                null -> if (requirement.required) missing += requirement.id
                else -> when (registration.readiness) {
                    PluginRuntimeReadiness.READY -> {
                        resolved += requirement.id
                        capabilities += registration.capabilityId
                    }
                    PluginRuntimeReadiness.DEGRADED -> {
                        resolved += requirement.id
                        degraded += requirement.id
                    }
                    PluginRuntimeReadiness.UNAVAILABLE -> if (requirement.required) missing += requirement.id
                }
            }
        }
        return AndroidEntrypointResolution(resolved, capabilities, missing, degraded)
    }

    /** Executor owners remain disjoint; callers compose them through the standard router. */
    fun executorsFor(requirements: List<AndroidToolRequirement>): List<DynamicToolExecutor> {
        val resolvedIds = resolve(requirements).resolvedRequirementIds
        return executorsForResolvedRequirements(requirements, resolvedIds)
    }

    internal fun executorsForResolvedRequirements(
        requirements: List<AndroidToolRequirement>,
        resolvedRequirementIds: Set<String>,
    ): List<DynamicToolExecutor> {
        return requirements.filter { it.id in resolvedRequirementIds }
            .mapNotNull { byId[it.bindingId]?.executor }
            .distinct()
    }
}

internal data class HansHookInvocation(
    val event: HansHookEvent,
    val correlationId: String,
    val sourceId: String,
) {
    init {
        require(correlationId.matches(OPAQUE_ID)) { "Invalid hook correlation id" }
        require(sourceId.matches(LOGICAL_SOURCE)) { "Invalid hook source id" }
    }

    private companion object {
        val OPAQUE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val LOGICAL_SOURCE = Regex("[a-z][a-z0-9._:-]{0,95}")
    }
}

internal data class HansHookActionReceipt(
    val actionStarted: Boolean,
    val postconditionVerified: Boolean,
    val statusCode: String,
) {
    init {
        require(statusCode.matches(Regex("[a-z0-9_.:-]{1,96}")))
        require(!postconditionVerified || actionStarted)
    }
}

internal fun interface HansDeclarativeHookAction {
    fun invoke(invocation: HansHookInvocation): HansHookActionReceipt
}

internal data class HansDeclarativeHookRegistration(
    val actionId: String,
    val capabilityId: String,
    val probe: PassiveCapabilityProbe,
    val action: HansDeclarativeHookAction,
) {
    init {
        require(actionId.matches(Regex("[a-z][a-z0-9._:-]{0,95}")))
        require(capabilityId.matches(Regex("[a-z][a-z0-9._:-]{0,127}")))
    }
}

internal data class HansHookResolution(
    val resolvedRequirementIds: Set<String>,
    val availableCapabilityIds: Set<String>,
    val missingRequiredIds: Set<String>,
    val degradedIds: Set<String>,
)

internal data class HansDeclarativeHookBindingSnapshot(
    val actionId: String,
    val capabilityId: String,
    val readiness: PluginRuntimeReadiness,
)

/**
 * Dispatches only predefined Android actions for allowlisted lifecycle events. The typed event has
 * no command, argument JSON, shell fragment or path field, so a plugin cannot turn a hook into an
 * executable-file channel.
 */
internal class HansDeclarativeHookRegistry(
    registrations: List<HansDeclarativeHookRegistration>,
) {
    private val byId = registrations.associateBy(HansDeclarativeHookRegistration::actionId)

    init {
        require(registrations.size <= 128)
        require(byId.size == registrations.size) { "Duplicate Hans hook action" }
    }

    fun snapshot(): List<HansDeclarativeHookBindingSnapshot> = byId.values.map { registration ->
        HansDeclarativeHookBindingSnapshot(
            actionId = registration.actionId,
            capabilityId = registration.capabilityId,
            readiness = runCatching(registration.probe::readiness)
                .getOrDefault(PluginRuntimeReadiness.UNAVAILABLE),
        )
    }.sortedBy(HansDeclarativeHookBindingSnapshot::actionId)

    fun resolve(requirements: List<DeclarativeHookRequirement>): HansHookResolution {
        return resolve(requirements, snapshot())
    }

    internal fun resolve(
        requirements: List<DeclarativeHookRequirement>,
        evidence: List<HansDeclarativeHookBindingSnapshot>,
    ): HansHookResolution {
        val evidenceById = evidence.associateBy(HansDeclarativeHookBindingSnapshot::actionId)
        val resolved = linkedSetOf<String>()
        val capabilities = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        val degraded = linkedSetOf<String>()
        requirements.sortedBy(DeclarativeHookRequirement::id).forEach { requirement ->
            val registration = byId[requirement.actionId]
            when (evidenceById[requirement.actionId]?.readiness) {
                PluginRuntimeReadiness.READY -> {
                    resolved += requirement.id
                    capabilities += requireNotNull(registration).capabilityId
                }
                PluginRuntimeReadiness.DEGRADED -> {
                    resolved += requirement.id
                    degraded += requirement.id
                }
                PluginRuntimeReadiness.UNAVAILABLE,
                null,
                -> if (requirement.required) missing += requirement.id
            }
        }
        return HansHookResolution(resolved, capabilities, missing, degraded)
    }

    fun dispatch(
        requirement: DeclarativeHookRequirement,
        invocation: HansHookInvocation,
    ): HansHookActionReceipt {
        require(requirement.event == invocation.event) { "Hook event correlation mismatch" }
        val registration = byId[requirement.actionId]
            ?: return HansHookActionReceipt(false, false, "hook_action_unavailable")
        if (runCatching(registration.probe::readiness).getOrDefault(PluginRuntimeReadiness.UNAVAILABLE) !=
            PluginRuntimeReadiness.READY
        ) {
            return HansHookActionReceipt(false, false, "hook_capability_unavailable")
        }
        return runCatching { registration.action.invoke(invocation) }
            .getOrElse { HansHookActionReceipt(false, false, "hook_action_failed") }
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
