package ai.hans.standard.plugins

private const val MAX_RUNTIME_PROBES = 32
private const val MAX_OBSERVED_ENTRYPOINTS = 128
private const val MAX_OBSERVED_CAPABILITIES = 256
private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 512
private val OBSERVED_ID = Regex("[a-z][a-z0-9._:-]{0,127}")

internal enum class PluginRuntimeReadiness {
    READY,
    DEGRADED,
    UNAVAILABLE,
}

internal data class PluginRuntimeProbe(
    val kind: PluginRuntimeKind,
    val location: PluginRuntimeLocation,
    val version: PluginRuntimeVersion?,
    val abi: PluginRuntimeAbi,
    val readiness: PluginRuntimeReadiness,
)

internal data class PluginCompatibilityObservation(
    val installed: Boolean,
    val dependenciesResolved: Boolean,
    val probesComplete: Boolean,
    val runtimes: List<PluginRuntimeProbe> = emptyList(),
    val resolvedEntrypointIds: Set<String> = emptySet(),
    val availableCapabilityIds: Set<String> = emptySet(),
) {
    init {
        require(!dependenciesResolved || installed) {
            "Dependencies cannot be resolved before installation"
        }
        require(!probesComplete || dependenciesResolved) {
            "Runtime probes cannot complete before dependency resolution"
        }
        require(runtimes.size <= MAX_RUNTIME_PROBES) { "Runtime probe set is too large" }
        require(resolvedEntrypointIds.size <= MAX_OBSERVED_ENTRYPOINTS) {
            "Resolved entrypoint set is too large"
        }
        require(availableCapabilityIds.size <= MAX_OBSERVED_CAPABILITIES) {
            "Available capability set is too large"
        }
        requireUniqueRuntimeProbes(runtimes)
        resolvedEntrypointIds.forEach { requireObservedId(it, "Resolved entrypoint id") }
        availableCapabilityIds.forEach { requireObservedId(it, "Available capability id") }
    }
}

internal enum class PluginCompatibilityLifecycle(val wireName: String) {
    DISCOVERED("discovered"),
    INSTALLED("installed"),
    RESOLVED("resolved"),
    RUNNABLE("runnable"),
    DEGRADED("degraded"),
    INCOMPATIBLE("incompatible"),
}

internal enum class PluginCompatibilitySeverity(val wireName: String, val sortOrder: Int) {
    BLOCKING("blocking", 0),
    DEGRADED("degraded", 1),
    INFO("info", 2),
}

internal enum class PluginCompatibilityDiagnosticCode(val wireName: String) {
    INSTALL_REQUIRED("install_required"),
    RESOLUTION_PENDING("resolution_pending"),
    PROBE_PENDING("probe_pending"),
    ROOT_REQUIRED("root_required"),
    CHROOT_REQUIRED("chroot_required"),
    SYSTEM_UID_REQUIRED("system_uid_required"),
    PLATFORM_SIGNATURE_REQUIRED("platform_signature_required"),
    WRITABLE_NATIVE_CODE_REQUIRED("writable_native_code_required"),
    SOURCE_DISTRIBUTION_REQUIRED("source_distribution_required"),
    RUNTIME_UNAVAILABLE("runtime_unavailable"),
    RUNTIME_VERSION_UNKNOWN("runtime_version_unknown"),
    RUNTIME_VERSION_MISMATCH("runtime_version_mismatch"),
    RUNTIME_ABI_MISMATCH("runtime_abi_mismatch"),
    RUNTIME_DEGRADED("runtime_degraded"),
    ENTRYPOINT_UNRESOLVED("entrypoint_unresolved"),
    CAPABILITY_UNAVAILABLE("capability_unavailable"),
}

internal data class PluginCompatibilityDiagnostic(
    val severity: PluginCompatibilitySeverity,
    val code: PluginCompatibilityDiagnosticCode,
    val subject: String,
    val detail: String,
) {
    init {
        require(subject.isNotBlank() && subject.length <= 128 && subject.none(Char::isISOControl)) {
            "Diagnostic subject must be non-blank and bounded"
        }
        require(
            detail.isNotBlank() && detail.length <= MAX_DIAGNOSTIC_DETAIL_CHARS &&
                detail.none(Char::isISOControl),
        ) { "Diagnostic detail must be non-blank and bounded" }
    }

    val stableText: String
        get() = "${severity.wireName}|${code.wireName}|$subject|$detail"
}

internal data class PluginRuntimeBinding(
    val requirementId: String,
    val kind: PluginRuntimeKind,
    val location: PluginRuntimeLocation,
    val version: PluginRuntimeVersion?,
    val abi: PluginRuntimeAbi,
)

internal data class PluginCompatibilitySnapshot(
    val pluginId: String,
    val lifecycle: PluginCompatibilityLifecycle,
    val runtimeBindings: List<PluginRuntimeBinding>,
    val diagnostics: List<PluginCompatibilityDiagnostic>,
) {
    val diagnosticTexts: List<String>
        get() = diagnostics.map(PluginCompatibilityDiagnostic::stableText)
}

/** Deterministic, side-effect-free compatibility projection for one plugin. */
internal class PluginCompatibilityScanner {
    fun scan(
        requirements: PluginRuntimeRequirements,
        observation: PluginCompatibilityObservation,
    ): PluginCompatibilitySnapshot {
        val diagnostics = mutableListOf<PluginCompatibilityDiagnostic>()
        requirements.unsupportedHostConstraints.sortedBy { it.wireName }.forEach { constraint ->
            diagnostics += constraint.blockingDiagnostic(requirements.pluginId)
        }
        if (diagnostics.isNotEmpty()) {
            return snapshot(
                requirements.pluginId,
                PluginCompatibilityLifecycle.INCOMPATIBLE,
                emptyList(),
                diagnostics,
            )
        }

        if (!observation.installed) {
            diagnostics += PluginCompatibilityDiagnostic(
                PluginCompatibilitySeverity.INFO,
                PluginCompatibilityDiagnosticCode.INSTALL_REQUIRED,
                requirements.pluginId,
                "plugin_is_discovered_but_not_installed",
            )
            return snapshot(
                requirements.pluginId,
                PluginCompatibilityLifecycle.DISCOVERED,
                emptyList(),
                diagnostics,
            )
        }
        if (!observation.dependenciesResolved) {
            diagnostics += PluginCompatibilityDiagnostic(
                PluginCompatibilitySeverity.INFO,
                PluginCompatibilityDiagnosticCode.RESOLUTION_PENDING,
                requirements.pluginId,
                "installed_requirements_are_not_resolved",
            )
            return snapshot(
                requirements.pluginId,
                PluginCompatibilityLifecycle.INSTALLED,
                emptyList(),
                diagnostics,
            )
        }
        if (!observation.probesComplete) {
            diagnostics += PluginCompatibilityDiagnostic(
                PluginCompatibilitySeverity.INFO,
                PluginCompatibilityDiagnosticCode.PROBE_PENDING,
                requirements.pluginId,
                "requirements_are_resolved_but_runtime_probes_are_pending",
            )
            return snapshot(
                requirements.pluginId,
                PluginCompatibilityLifecycle.RESOLVED,
                emptyList(),
                diagnostics,
            )
        }

        val bindings = mutableListOf<PluginRuntimeBinding>()
        requirements.runtimes.sortedBy(PluginRuntimeRequirement::id).forEach { requirement ->
            val evaluation = evaluateRuntime(requirement, observation.runtimes)
            evaluation.binding?.let(bindings::add)
            evaluation.diagnostic?.let(diagnostics::add)
        }
        val boundRuntimeIds = bindings.mapTo(mutableSetOf(), PluginRuntimeBinding::requirementId)

        requirements.entrypoints.sortedBy(PluginEntrypointRequirement::id).forEach { requirement ->
            if (
                requirement.runtimeRequirementId in boundRuntimeIds &&
                requirement.id !in observation.resolvedEntrypointIds
            ) {
                diagnostics += PluginCompatibilityDiagnostic(
                    requirement.missingSeverity(),
                    PluginCompatibilityDiagnosticCode.ENTRYPOINT_UNRESOLVED,
                    requirement.id,
                    "runtime=${requirement.runtimeRequirementId}",
                )
            }
        }
        requirements.capabilities.sortedBy(PluginCapabilityRequirement::id).forEach { requirement ->
            if (requirement.id !in observation.availableCapabilityIds) {
                diagnostics += PluginCompatibilityDiagnostic(
                    requirement.missingSeverity(),
                    PluginCompatibilityDiagnosticCode.CAPABILITY_UNAVAILABLE,
                    requirement.id,
                    "capability=${requirement.id}",
                )
            }
        }

        val lifecycle = when {
            diagnostics.any { it.severity == PluginCompatibilitySeverity.BLOCKING } ->
                PluginCompatibilityLifecycle.INCOMPATIBLE
            diagnostics.any { it.severity == PluginCompatibilitySeverity.DEGRADED } ->
                PluginCompatibilityLifecycle.DEGRADED
            else -> PluginCompatibilityLifecycle.RUNNABLE
        }
        return snapshot(requirements.pluginId, lifecycle, bindings, diagnostics)
    }

    private fun evaluateRuntime(
        requirement: PluginRuntimeRequirement,
        probes: List<PluginRuntimeProbe>,
    ): RuntimeEvaluation {
        val candidates = probes.filter { probe ->
            probe.kind == requirement.kind && requirement.placement.accepts(probe.location)
        }.sortedWith(RUNTIME_PREFERENCE)
        val available = candidates.filter { it.readiness != PluginRuntimeReadiness.UNAVAILABLE }
        if (available.isEmpty()) {
            return RuntimeEvaluation(
                diagnostic = requirement.diagnostic(
                    PluginCompatibilityDiagnosticCode.RUNTIME_UNAVAILABLE,
                    "kind=${requirement.kind.wireName},placement=${requirement.placement.wireName}",
                ),
            )
        }

        val versionCompatible = available.filter { probe ->
            val version = probe.version
            version != null && requirement.versionRange.contains(version)
        }.let { matching ->
            if (requirement.versionRange.describe() == "*") available else matching
        }
        if (versionCompatible.isEmpty()) {
            val knownVersions = available.mapNotNull(PluginRuntimeProbe::version)
            val code = if (knownVersions.isEmpty()) {
                PluginCompatibilityDiagnosticCode.RUNTIME_VERSION_UNKNOWN
            } else {
                PluginCompatibilityDiagnosticCode.RUNTIME_VERSION_MISMATCH
            }
            val observed = available.joinToString(",") { probe ->
                "${probe.version ?: "unknown"}@${probe.location.wireName}"
            }
            return RuntimeEvaluation(
                diagnostic = requirement.diagnostic(
                    code,
                    "required=${requirement.versionRange.describe()},observed=$observed",
                ),
            )
        }

        val abiCompatible = versionCompatible.filter { probe ->
            requirement.acceptedAbis.isEmpty() || probe.abi in requirement.acceptedAbis
        }
        if (abiCompatible.isEmpty()) {
            val required = requirement.acceptedAbis
                .sortedBy(PluginRuntimeAbi::wireName)
                .joinToString(",", transform = PluginRuntimeAbi::wireName)
            val observed = versionCompatible.joinToString(",") { probe ->
                "${probe.abi.wireName}@${probe.location.wireName}"
            }
            return RuntimeEvaluation(
                diagnostic = requirement.diagnostic(
                    PluginCompatibilityDiagnosticCode.RUNTIME_ABI_MISMATCH,
                    "required=$required,observed=$observed",
                ),
            )
        }

        val selected = abiCompatible.sortedWith(RUNTIME_PREFERENCE).first()
        val binding = PluginRuntimeBinding(
            requirementId = requirement.id,
            kind = selected.kind,
            location = selected.location,
            version = selected.version,
            abi = selected.abi,
        )
        val diagnostic = if (selected.readiness == PluginRuntimeReadiness.DEGRADED) {
            PluginCompatibilityDiagnostic(
                PluginCompatibilitySeverity.DEGRADED,
                PluginCompatibilityDiagnosticCode.RUNTIME_DEGRADED,
                requirement.id,
                "kind=${selected.kind.wireName},location=${selected.location.wireName}",
            )
        } else {
            null
        }
        return RuntimeEvaluation(binding, diagnostic)
    }

    private fun snapshot(
        pluginId: String,
        lifecycle: PluginCompatibilityLifecycle,
        bindings: List<PluginRuntimeBinding>,
        diagnostics: List<PluginCompatibilityDiagnostic>,
    ): PluginCompatibilitySnapshot = PluginCompatibilitySnapshot(
        pluginId = pluginId,
        lifecycle = lifecycle,
        runtimeBindings = bindings.sortedBy(PluginRuntimeBinding::requirementId),
        diagnostics = diagnostics.sortedWith(DIAGNOSTIC_ORDER),
    )

    private data class RuntimeEvaluation(
        val binding: PluginRuntimeBinding? = null,
        val diagnostic: PluginCompatibilityDiagnostic? = null,
    )

    companion object {
        private val RUNTIME_PREFERENCE = Comparator<PluginRuntimeProbe> { left, right ->
            compareValues(
                if (left.readiness == PluginRuntimeReadiness.READY) 0 else 1,
                if (right.readiness == PluginRuntimeReadiness.READY) 0 else 1,
            ).takeIf { it != 0 }
                ?: compareValues(
                    if (left.location == PluginRuntimeLocation.LOCAL) 0 else 1,
                    if (right.location == PluginRuntimeLocation.LOCAL) 0 else 1,
                ).takeIf { it != 0 }
                // Prefer the newest compatible version, not lexical version text.
                ?: when {
                    left.version == null && right.version == null -> 0
                    left.version == null -> 1
                    right.version == null -> -1
                    else -> right.version.compareTo(left.version)
                }.takeIf { it != 0 }
                ?: left.abi.wireName.compareTo(right.abi.wireName)
        }
        private val DIAGNOSTIC_ORDER = compareBy<PluginCompatibilityDiagnostic>(
            { it.severity.sortOrder },
            { it.code.wireName },
            PluginCompatibilityDiagnostic::subject,
            PluginCompatibilityDiagnostic::detail,
        )
    }
}

private fun PluginRuntimePlacement.accepts(location: PluginRuntimeLocation): Boolean = when (this) {
    PluginRuntimePlacement.LOCAL -> location == PluginRuntimeLocation.LOCAL
    PluginRuntimePlacement.REMOTE -> location == PluginRuntimeLocation.REMOTE
    PluginRuntimePlacement.EITHER -> true
}

private fun PluginRuntimeRequirement.diagnostic(
    code: PluginCompatibilityDiagnosticCode,
    detail: String,
): PluginCompatibilityDiagnostic = PluginCompatibilityDiagnostic(
    severity = if (required) {
        PluginCompatibilitySeverity.BLOCKING
    } else {
        PluginCompatibilitySeverity.DEGRADED
    },
    code = code,
    subject = id,
    detail = detail,
)

private fun PluginEntrypointRequirement.missingSeverity(): PluginCompatibilitySeverity =
    if (required) PluginCompatibilitySeverity.BLOCKING else PluginCompatibilitySeverity.DEGRADED

private fun PluginCapabilityRequirement.missingSeverity(): PluginCompatibilitySeverity =
    if (required) PluginCompatibilitySeverity.BLOCKING else PluginCompatibilitySeverity.DEGRADED

private fun PluginUnsupportedHostConstraint.blockingDiagnostic(
    pluginId: String,
): PluginCompatibilityDiagnostic {
    val code = when (this) {
        PluginUnsupportedHostConstraint.ROOT_ACCESS ->
            PluginCompatibilityDiagnosticCode.ROOT_REQUIRED
        PluginUnsupportedHostConstraint.CHROOT ->
            PluginCompatibilityDiagnosticCode.CHROOT_REQUIRED
        PluginUnsupportedHostConstraint.SYSTEM_UID ->
            PluginCompatibilityDiagnosticCode.SYSTEM_UID_REQUIRED
        PluginUnsupportedHostConstraint.PLATFORM_SIGNATURE ->
            PluginCompatibilityDiagnosticCode.PLATFORM_SIGNATURE_REQUIRED
        PluginUnsupportedHostConstraint.WRITABLE_NATIVE_CODE ->
            PluginCompatibilityDiagnosticCode.WRITABLE_NATIVE_CODE_REQUIRED
        PluginUnsupportedHostConstraint.SOURCE_DISTRIBUTION_BUILD ->
            PluginCompatibilityDiagnosticCode.SOURCE_DISTRIBUTION_REQUIRED
    }
    return PluginCompatibilityDiagnostic(
        PluginCompatibilitySeverity.BLOCKING,
        code,
        pluginId,
        "hans_standard_never_provides_$wireName",
    )
}

private fun requireUniqueRuntimeProbes(probes: List<PluginRuntimeProbe>) {
    val localKeys = probes
        .filter { it.location == PluginRuntimeLocation.LOCAL }
        .map(PluginRuntimeProbe::kind)
    require(localKeys.distinct().size == localKeys.size) {
        "Local runtime probes must be unique by kind"
    }
    val remoteKeys = probes
        .filter { it.location == PluginRuntimeLocation.REMOTE }
        .map { listOf(it.kind, it.version, it.abi, it.readiness) }
    require(remoteKeys.distinct().size == remoteKeys.size) {
        "Remote runtime probes must not contain duplicate instances"
    }
}

private fun requireObservedId(value: String, label: String) {
    require(OBSERVED_ID.matches(value)) { "$label is invalid" }
}
