package ai.hans.standard.ui

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
                originLabel = metadata.origin.uiLabel(),
                workspaceHandle = metadata.workspaceHandle,
            )
        },
        python = python.toWorkbenchUiState(),
        workspacesTruncated = workspaces.size > limit,
        artifactsTruncated = artifacts.size > limit,
    )
}

private fun PythonRuntimeSnapshot?.toWorkbenchUiState(): WorkbenchPythonUiState {
    if (this == null) return WorkbenchPythonUiState()
    val readiness = readiness
    return WorkbenchPythonUiState(
        initialized = true,
        phaseLabel = phase.uiLabel(),
        ready = phase == PythonRuntimePhase.READY && readiness?.ready == true,
        startsOnDemand = phase == PythonRuntimePhase.STOPPED,
        pythonVersion = readiness?.pythonVersion.orEmpty(),
        // Raw runtime diagnostics can contain private implementation details. The Workbench
        // renders only a stable, non-sensitive class of failure.
        detail = when {
            readiness?.ready == true -> "Standardbibliothek und Kernmodule geprüft"
            phase in setOf(PythonRuntimePhase.DEGRADED, PythonRuntimePhase.CRASHED) ->
                "Die Laufzeit muss vor der nächsten Ausführung erneut geprüft werden."
            else -> ""
        },
    )
}

private fun PythonRuntimePhase.uiLabel(): String = when (this) {
    PythonRuntimePhase.STOPPED -> "Bereit bei Bedarf"
    PythonRuntimePhase.STARTING -> "Startet"
    PythonRuntimePhase.READY -> "Bereit"
    PythonRuntimePhase.RUNNING -> "Führt Python aus"
    PythonRuntimePhase.CANCELLING -> "Bricht Ausführung ab"
    PythonRuntimePhase.DEGRADED -> "Eingeschränkt"
    PythonRuntimePhase.CRASHED -> "Unterbrochen"
    PythonRuntimePhase.RESTARTING -> "Startet neu"
    PythonRuntimePhase.SHUTTING_DOWN -> "Wird beendet"
}

private fun ArtifactOrigin.uiLabel(): String = when (this) {
    ArtifactOrigin.CODEX -> "Codex"
    ArtifactOrigin.PYTHON -> "Python"
    ArtifactOrigin.JAVASCRIPT -> "JavaScript"
    ArtifactOrigin.ANDROID -> "Android"
    ArtifactOrigin.USER_IMPORT -> "Import"
    ArtifactOrigin.REMOTE_WORKER -> "Remote-Worker"
}
