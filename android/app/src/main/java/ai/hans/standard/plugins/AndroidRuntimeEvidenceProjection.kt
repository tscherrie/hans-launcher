package ai.hans.standard.plugins

import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot

/**
 * Pure projections from the effective owner state into plugin-runtime evidence.
 *
 * A packaged binary is useful installation evidence but is not proof that its live readiness
 * gate passed. Therefore an inactive owner is degraded rather than ready. A compatibility scan
 * may still resolve the plugin, while the transaction must wait for a real ready observation
 * before activation or execution.
 */
internal object AndroidRuntimeEvidenceProjection {
    fun codeMode(
        runtimePhase: ClientRuntimePhase?,
        packagedVersion: String,
        abi: PluginRuntimeAbi,
    ): PluginRuntimeEvidence = PluginRuntimeEvidence(
        version = packagedVersion,
        abi = abi,
        readiness = if (runtimePhase == ClientRuntimePhase.READY) {
            PluginRuntimeReadiness.READY
        } else {
            PluginRuntimeReadiness.DEGRADED
        },
    )

    fun python(
        snapshot: PythonRuntimeSnapshot?,
        packagedVersion: String,
        abi: PluginRuntimeAbi,
    ): PluginRuntimeEvidence {
        val readiness = snapshot?.readiness
        val effectiveVersion = readiness?.pythonVersion ?: packagedVersion
        val readyPhase = snapshot?.phase in setOf(
            PythonRuntimePhase.READY,
            PythonRuntimePhase.RUNNING,
            PythonRuntimePhase.CANCELLING,
        )
        return PluginRuntimeEvidence(
            version = effectiveVersion,
            abi = abi,
            readiness = if (readyPhase && readiness?.ready == true) {
                PluginRuntimeReadiness.READY
            } else {
                PluginRuntimeReadiness.DEGRADED
            },
        )
    }

    fun ready(
        version: String,
        abi: PluginRuntimeAbi,
    ): PluginRuntimeEvidence = PluginRuntimeEvidence(
        version = version,
        abi = abi,
        readiness = PluginRuntimeReadiness.READY,
    )
}
