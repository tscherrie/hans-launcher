package ai.hans.standard.workspace

import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceDynamicToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun immutableWorkspaceAndArtifactFlowUsesOpaqueHandles() {
        val executor = executor()
        val empty = execute(executor, WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE, "create", JSONObject())
        val emptyHandle = JSONObject(empty.contentText).getString("workspaceHandle")

        val written = execute(
            executor,
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE,
            "write_text",
            JSONObject()
                .put("seedWorkspaceHandle", emptyHandle)
                .put("relativePath", "notes/result.md")
                .put("text", "# Ergebnis\n\nFertig."),
        )
        val writtenBody = JSONObject(written.contentText)
        val writtenHandle = writtenBody.getString("workspaceHandle")

        assertTrue(written.success)
        assertNotEquals(emptyHandle, writtenHandle)

        val listing = execute(
            executor,
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE,
            "list",
            JSONObject().put("workspaceHandle", writtenHandle),
        )
        assertEquals("notes/result.md", JSONObject(listing.contentText).getJSONArray("files")
            .getJSONObject(0).getString("relativePath"))

        val read = execute(
            executor,
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE,
            "read_text",
            JSONObject()
                .put("workspaceHandle", writtenHandle)
                .put("relativePath", "notes/result.md"),
        )
        assertEquals("# Ergebnis\n\nFertig.", JSONObject(read.contentText).getString("text"))

        val artifact = execute(
            executor,
            WorkspaceDynamicToolCatalog.ARTIFACT_NAMESPACE,
            "from_workspace",
            JSONObject()
                .put("workspaceHandle", writtenHandle)
                .put("relativePath", "notes/result.md")
                .put("displayName", "Ergebnis.md")
                .put("mimeType", "text/markdown"),
        )
        val artifactHandle = JSONObject(artifact.contentText).getString("artifactHandle")
        val artifactRead = execute(
            executor,
            WorkspaceDynamicToolCatalog.ARTIFACT_NAMESPACE,
            "read_text",
            JSONObject().put("artifactHandle", artifactHandle),
        )
        assertEquals("# Ergebnis\n\nFertig.", JSONObject(artifactRead.contentText).getString("text"))
    }

    @Test
    fun invalidPathsUnknownFieldsAndBinaryTextReadsFailClosed() {
        val executor = executor()
        val invalid = execute(
            executor,
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE,
            "write_text",
            JSONObject().put("relativePath", "../escape").put("text", "x"),
        )
        assertFalse(invalid.success)
        assertEquals("invalid_workspace_arguments", JSONObject(invalid.contentText).getString("errorCode"))

        val unknown = execute(
            executor,
            WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE,
            "create",
            JSONObject().put("ignored", true),
        )
        assertFalse(unknown.success)
    }

    @Test
    fun cancellationBeforeStorageBoundaryProducesNoWorkspace() {
        val boundary = temporary.newFolder("cancel-private")
        val workspaceRoot = boundary.resolve("workspaces")
        val executor = WorkspaceDynamicToolExecutor(
            PrivateWorkspaceStore(workspaceRoot, boundary),
            AtomicArtifactStore(boundary.resolve("artifacts"), boundary),
            Executor(Runnable::run),
        )
        var completion: DynamicToolExecutionResult? = null

        executor.executeCancellable(
            call(WorkspaceDynamicToolCatalog.WORKSPACE_NAMESPACE, "create", JSONObject()),
            DynamicToolCancellation { true },
        ) { completion = it }

        assertEquals(null, completion)
        assertTrue(workspaceRoot.listFiles().orEmpty().isEmpty())
    }

    private fun executor(): WorkspaceDynamicToolExecutor {
        val boundary = temporary.newFolder("private-${System.nanoTime()}")
        return WorkspaceDynamicToolExecutor(
            PrivateWorkspaceStore(boundary.resolve("workspaces"), boundary),
            AtomicArtifactStore(boundary.resolve("artifacts"), boundary),
            Executor(Runnable::run),
        )
    }

    private fun execute(
        executor: WorkspaceDynamicToolExecutor,
        namespace: String,
        tool: String,
        arguments: JSONObject,
    ): DynamicToolExecutionResult {
        var output: DynamicToolExecutionResult? = null
        executor.execute(call(namespace, tool, arguments)) { output = it }
        return requireNotNull(output)
    }

    private fun call(namespace: String, tool: String, arguments: JSONObject) = DynamicToolCallParams(
        threadId = "thread-workspace",
        turnId = "turn-workspace",
        callId = "call-$tool-${System.nanoTime()}",
        namespace = namespace,
        tool = tool,
        argumentsJson = arguments.toString(),
    )
}
