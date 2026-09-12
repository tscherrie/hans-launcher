package ai.hans.standard.git

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import java.io.File
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitDynamicToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun catalogAdvertisesOnlyReadinessProbedOperations() {
        assertEquals(null, GitDynamicToolCatalog.namespace(readiness(local = false)))

        val local = GitDynamicToolCatalog.namespace(readiness())!!.tools.map { it.name }.toSet()
        assertTrue(local.containsAll(setOf("init", "open", "status", "diff", "log", "branch", "checkout", "stage")))
        assertFalse("commit" in local)
        assertFalse("clone" in local)
        assertFalse("fetch" in local)

        val complete = GitDynamicToolCatalog.namespace(
            readiness(identity = true, https = true),
        )!!.tools.map { it.name }.toSet()
        assertTrue(complete.containsAll(setOf("commit", "clone", "fetch", "pull", "push")))
    }

    @Test
    fun executorNeverPublishesCredentialsAndRejectsCredentialUrls() {
        val fixture = fixture(readiness(identity = true, https = true))
        val secret = "do-not-expose"

        val output = execute(
            fixture.executor,
            "clone",
            JSONObject()
                .put("repositoryId", "clone")
                .put("remoteUri", "https://$secret@example.com/project.git"),
        )

        assertFalse(output.success)
        assertEquals("git_remote_credentials_forbidden", JSONObject(output.contentText).getString("errorCode"))
        assertFalse(output.contentText.contains(secret))
    }

    @Test
    fun localToolsReturnOpaqueIdsAndVerifiedPostconditions() {
        val fixture = fixture(readiness(identity = true))

        val initialized = execute(
            fixture.executor,
            "init",
            JSONObject().put("repositoryId", "project"),
        )
        assertTrue(initialized.success)
        val initializedJson = JSONObject(initialized.contentText)
        assertEquals("project", initializedJson.getJSONObject("value").getString("repositoryId"))
        assertTrue(initializedJson.getJSONObject("postcondition").getBoolean("verified"))
        assertFalse(initialized.contentText.contains(fixture.boundary.absolutePath))

        fixture.repository("project").resolve("note.txt").writeText("hello\n")
        val staged = execute(
            fixture.executor,
            "stage",
            JSONObject()
                .put("repositoryId", "project")
                .put("paths", JSONArray().put("note.txt")),
        )
        assertTrue(staged.success)

        val committed = execute(
            fixture.executor,
            "commit",
            JSONObject()
                .put("repositoryId", "project")
                .put("message", "Add note"),
        )
        assertTrue(committed.success)
        assertEquals(
            "Hans Test",
            JSONObject(committed.contentText).getJSONObject("value").getString("authorName"),
        )
    }

    @Test
    fun cancellationBeforeDispatchCreatesNoRepository() {
        val fixture = fixture(readiness())
        var completion: DynamicToolExecutionResult? = null

        fixture.executor.executeCancellable(
            call("init", JSONObject().put("repositoryId", "cancelled")),
            DynamicToolCancellation { true },
        ) { completion = it }

        assertEquals(null, completion)
        assertFalse(fixture.repository("cancelled").exists())
    }

    @Test
    fun branchAndCheckoutHaveVerifiedTypedResults() {
        val fixture = fixture(readiness(identity = true))
        execute(fixture.executor, "init", JSONObject().put("repositoryId", "project"))
        fixture.repository("project").resolve("file.txt").writeText("one")
        execute(
            fixture.executor,
            "stage",
            JSONObject().put("repositoryId", "project").put("paths", JSONArray().put("file.txt")),
        )
        execute(
            fixture.executor,
            "commit",
            JSONObject().put("repositoryId", "project").put("message", "one"),
        )

        val branch = execute(
            fixture.executor,
            "branch",
            JSONObject()
                .put("repositoryId", "project")
                .put("action", "create")
                .put("branchName", "feature"),
        )
        assertTrue(JSONObject(branch.contentText).getJSONObject("postcondition").getBoolean("verified"))

        val checkout = execute(
            fixture.executor,
            "checkout",
            JSONObject().put("repositoryId", "project").put("branchName", "feature"),
        )
        assertEquals("feature", JSONObject(checkout.contentText).getJSONObject("value").getString("branch"))
    }

    private fun fixture(readiness: GitDynamicReadiness): Fixture {
        val boundary = temporary.newFolder("private-${System.nanoTime()}")
        val store = AppPrivateGitRepositoryStore(boundary.resolve("repositories"), boundary)
        val service = JGitRepositoryService(
            store,
            GitCredentialBroker { GitCredentialSession.PUBLIC },
            GitTransportReadinessProbe { readiness.transport },
        )
        return Fixture(
            boundary,
            GitDynamicToolExecutor(
                service = service,
                readinessProbe = GitDynamicReadinessProbe { readiness },
                identityProvider = {
                    if (readiness.commitIdentity) GitIdentity("Hans Test", "hans@example.test") else null
                },
                backgroundExecutor = Executor(Runnable::run),
            ),
        )
    }

    private fun execute(
        executor: GitDynamicToolExecutor,
        tool: String,
        arguments: JSONObject,
    ): DynamicToolExecutionResult {
        var output: DynamicToolExecutionResult? = null
        executor.execute(call(tool, arguments)) { output = it }
        return requireNotNull(output)
    }

    private fun call(tool: String, arguments: JSONObject) = DynamicToolCallParams(
        threadId = "thread-git",
        turnId = "turn-git",
        callId = "call-$tool-${System.nanoTime()}",
        namespace = GitDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments.toString(),
    )

    private fun readiness(
        local: Boolean = true,
        identity: Boolean = false,
        https: Boolean = false,
        ssh: Boolean = false,
    ) = GitDynamicReadiness(local, identity, GitTransportReadiness(https, ssh))

    private data class Fixture(
        val boundary: File,
        val executor: GitDynamicToolExecutor,
    ) {
        fun repository(id: String): File = boundary.resolve("repositories").resolve(id)
    }
}
