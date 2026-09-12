package ai.hans.standard.ui

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.ArtifactMetadata
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.runtime.python.PythonRuntimePhase
import ai.hans.standard.runtime.python.PythonRuntimeReadiness
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import ai.hans.standard.workspace.WorkspaceFileEntry
import ai.hans.standard.workspace.WorkspaceHandle
import ai.hans.standard.workspace.WorkspaceManifest
import ai.hans.standard.workspace.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchProjectionTest {
    @Test
    fun projectionIsBoundedAndContainsOnlyOpaqueVerifiedMetadata() {
        val workspace = WorkspaceSnapshot(
            WorkspaceHandle("a".repeat(64)),
            WorkspaceManifest(
                listOf(WorkspaceFileEntry("notes.txt", 12, "b".repeat(64))),
            ),
        )
        val artifact = ArtifactMetadata(
            handle = ArtifactHandle("art_${"c".repeat(64)}"),
            displayName = "Ergebnis.txt",
            mimeType = "text/plain",
            byteCount = 24,
            sha256 = "d".repeat(64),
            createdAtEpochMillis = 1,
            origin = ArtifactOrigin.PYTHON,
            workspaceHandle = workspace.handle.value,
        )

        val state = projectWorkbenchInventory(
            workspaces = listOf(workspace, workspace),
            artifacts = listOf(artifact, artifact.copy(handle = ArtifactHandle("art_${"e".repeat(64)}"))),
            python = null,
            limit = 1,
        )

        assertEquals(listOf(workspace.handle.value), state.workspaces.map { it.handle })
        assertEquals(1, state.workspaces.single().fileCount)
        assertEquals(12, state.workspaces.single().byteCount)
        assertEquals(artifact.handle.value, state.artifacts.single().handle)
        assertEquals("Python", state.artifacts.single().originLabel)
        assertEquals(workspace.handle.value, state.artifacts.single().workspaceHandle)
        assertTrue(state.workspacesTruncated)
        assertTrue(state.artifactsTruncated)
        assertFalse(state.python.initialized)
    }

    @Test
    fun runtimeProjectionNeverLeaksRawDiagnosticDetails() {
        val secret = "/data/user/0/ai.hans.standard/files/token-secret"
        val state = projectWorkbenchInventory(
            workspaces = emptyList(),
            artifacts = emptyList(),
            python = PythonRuntimeSnapshot(
                phase = PythonRuntimePhase.DEGRADED,
                generation = 2,
                runtimePid = 321,
                readiness = PythonRuntimeReadiness(
                    ready = false,
                    errorCode = "runtime_error",
                    detail = secret,
                ),
                detail = secret,
            ),
        )

        assertTrue(state.python.initialized)
        assertEquals("Eingeschränkt", state.python.phaseLabel)
        assertFalse(state.python.ready)
        assertFalse(state.python.detail.contains("/data/"))
        assertFalse(state.python.detail.contains("token-secret"))
    }

    @Test
    fun stoppedIsPresentedAsReadyOnDemandInsteadOfAFailure() {
        val state = projectWorkbenchInventory(
            workspaces = emptyList(),
            artifacts = emptyList(),
            python = PythonRuntimeSnapshot(
                phase = PythonRuntimePhase.STOPPED,
                generation = 3,
                runtimePid = 0,
            ),
        )

        assertTrue(state.python.initialized)
        assertEquals("Bereit bei Bedarf", state.python.phaseLabel)
        assertTrue(state.python.startsOnDemand)
        assertFalse(state.python.ready)
    }

    @Test
    fun byteFormattingIsStableAndHumanReadable() {
        assertEquals("0 B", formatWorkbenchBytes(0))
        assertEquals("1,0 KB", formatWorkbenchBytes(1_024))
        assertEquals("2,0 MB", formatWorkbenchBytes(2L * 1_024 * 1_024))
    }
}
