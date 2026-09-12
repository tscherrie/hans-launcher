package ai.hans.standard.plugins

internal sealed interface PluginRuntimeCompatibilityEvaluation {
    data object StandardCodexPlugin : PluginRuntimeCompatibilityEvaluation

    data class Evaluated(
        val manifest: PluginRuntimeManifestResult.Declared,
        val compatibility: PluginCompatibilitySnapshot,
    ) : PluginRuntimeCompatibilityEvaluation

    data class Rejected(val reason: String) : PluginRuntimeCompatibilityEvaluation
}

/** Side-effect-free compatibility service shared by install preflight and Workbench diagnostics. */
internal class PluginRuntimeCompatibilityService(
    private val manifests: PluginRuntimeManifestLoader,
    private val runtimes: PluginRuntimeProbeRegistry,
    private val scanner: PluginCompatibilityScanner = PluginCompatibilityScanner(),
) {
    fun evaluateForInstall(
        record: PluginWireRecord,
        dependenciesResolved: Boolean,
        resolvedEntrypointIds: Set<String>,
        availableCapabilityIds: Set<String>,
    ): PluginRuntimeCompatibilityEvaluation = when (val manifest = manifests.load(record)) {
        PluginRuntimeManifestResult.NotDeclared ->
            PluginRuntimeCompatibilityEvaluation.StandardCodexPlugin
        is PluginRuntimeManifestResult.Rejected ->
            PluginRuntimeCompatibilityEvaluation.Rejected(manifest.reason)
        is PluginRuntimeManifestResult.Declared -> {
            val compatibility = scanner.scan(
                manifest.requirements,
                PluginCompatibilityObservation(
                    // This is the hypothetical post-install state. App Server installation still
                    // needs an independent list proof before activation is committed.
                    installed = true,
                    dependenciesResolved = dependenciesResolved,
                    probesComplete = dependenciesResolved,
                    runtimes = if (dependenciesResolved) runtimes.snapshot() else emptyList(),
                    resolvedEntrypointIds = resolvedEntrypointIds,
                    availableCapabilityIds = availableCapabilityIds,
                ),
            )
            PluginRuntimeCompatibilityEvaluation.Evaluated(manifest, compatibility)
        }
    }
}

internal fun PluginRuntimeCompatibilityEvaluation.maySendAppServerInstall(): Boolean = when (this) {
    PluginRuntimeCompatibilityEvaluation.StandardCodexPlugin -> true
    is PluginRuntimeCompatibilityEvaluation.Rejected -> false
    is PluginRuntimeCompatibilityEvaluation.Evaluated -> compatibility.lifecycle in setOf(
        PluginCompatibilityLifecycle.RUNNABLE,
        PluginCompatibilityLifecycle.DEGRADED,
    )
}
