package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.artifacts.ArtifactMetadata
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import ai.hans.standard.workspace.WorkspaceSnapshot

internal const val WORKBENCH_INVENTORY_LIMIT = 100

/** Maps only already verified store records; no path or credential can enter the UI model. */
internal fun projectWorkbenchInventory(
    workspaces: List<WorkspaceSnapshot>,
    artifacts: List<ArtifactMetadata>,
    python: PythonRuntimeSnapshot?,
    text: HansTextResolver,
    limit: Int = WORKBENCH_INVENTORY_LIMIT,
): WorkbenchUiState {
    require(limit in 1..500)
    return WorkbenchUiState(
        workspaces = workspaces.take(limit).map { snapshot ->
            WorkbenchWorkspaceUiModel(
                handle = snapshot.handle.value,
                fileCount = snapshot.manifest.files.size,
                byteCount = snapshot.manifest.byteCount,
            )
        },
        artifacts = artifacts.take(limit).map { metadata ->
            WorkbenchArtifactUiModel(
                handle = metadata.handle.value,
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                byteCount = metadata.byteCount,
                originLabel = metadata.origin.uiLabel(text),
                workspaceHandle = metadata.workspaceHandle,
            )
        },
        python = python.toWorkbenchUiState(text),
        workspacesTruncated = workspaces.size > limit,
        artifactsTruncated = artifacts.size > limit,
    )
}

private fun PythonRuntimeSnapshot?.toWorkbenchUiState(text: HansTextResolver): WorkbenchPythonUiState {
    if (this == null) return WorkbenchPythonUiState(phaseLabel = text.text(R.string.presentation_python_not_started))
    val readiness = readiness
    return WorkbenchPythonUiState(
        initialized = true,
        phaseLabel = phase.uiLabel(text),
        ready = phase == PythonRuntimePhase.READY && readiness?.ready == true,
        startsOnDemand = phase == PythonRuntimePhase.STOPPED,
        pythonVersion = readiness?.pythonVersion.orEmpty(),
        // Raw runtime diagnostics can contain private implementation details. The Workbench
        // renders only a stable, non-sensitive class of failure.
        detail = when {
            readiness?.ready == true -> text.text(R.string.presentation_python_checked)
            phase in setOf(PythonRuntimePhase.DEGRADED, PythonRuntimePhase.CRASHED) ->
                text.text(R.string.presentation_python_recheck_required)
            else -> ""
        },
    )
}

private fun PythonRuntimePhase.uiLabel(text: HansTextResolver): String = when (this) {
    PythonRuntimePhase.STOPPED -> text.text(R.string.presentation_python_stopped)
    PythonRuntimePhase.STARTING -> text.text(R.string.presentation_python_starting)
    PythonRuntimePhase.READY -> text.text(R.string.presentation_python_ready)
    PythonRuntimePhase.RUNNING -> text.text(R.string.presentation_python_running)
    PythonRuntimePhase.CANCELLING -> text.text(R.string.presentation_python_cancelling)
    PythonRuntimePhase.DEGRADED -> text.text(R.string.presentation_python_degraded)
    PythonRuntimePhase.CRASHED -> text.text(R.string.presentation_python_crashed)
    PythonRuntimePhase.RESTARTING -> text.text(R.string.presentation_python_restarting)
    PythonRuntimePhase.SHUTTING_DOWN -> text.text(R.string.presentation_python_shutting_down)
}

private fun ArtifactOrigin.uiLabel(text: HansTextResolver): String = when (this) {
    ArtifactOrigin.CODEX -> "Codex"
    ArtifactOrigin.PYTHON -> "Python"
    ArtifactOrigin.JAVASCRIPT -> "JavaScript"
    ArtifactOrigin.ANDROID -> "Android"
    ArtifactOrigin.USER_IMPORT -> text.text(R.string.presentation_artifact_import)
    ArtifactOrigin.REMOTE_WORKER -> text.text(R.string.presentation_artifact_remote_worker)
}
