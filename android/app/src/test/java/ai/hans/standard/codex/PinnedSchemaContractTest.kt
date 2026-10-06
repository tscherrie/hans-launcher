package ai.hans.standard.codex

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardrails against silently drifting away from the vendored 0.155.0 v2 schema. */
class PinnedSchemaContractTest {
    private val definitions: JSONObject by lazy {
        JSONObject(schemaFile().readText()).getJSONObject("definitions")
    }

    @Test
    fun yoloSandboxHasDifferentPinnedThreadAndTurnWireShapes() {
        val sandboxMode = definitions.getJSONObject("SandboxMode").getJSONArray("enum")
        assertEquals(
            listOf("read-only", "workspace-write", "danger-full-access"),
            (0 until sandboxMode.length()).map(sandboxMode::getString),
        )

        val policies = definitions.getJSONObject("SandboxPolicy").getJSONArray("oneOf")
        assertTrue(
            (0 until policies.length()).any { index ->
                policies.getJSONObject(index)
                    .getJSONObject("properties")
                    .getJSONObject("type")
                    .getJSONArray("enum")
                    .optString(0) == "dangerFullAccess"
            },
        )
        val threadProperties = definitions.getJSONObject("ThreadStartParams")
            .getJSONObject("properties")
        assertTrue(threadProperties.has("sandbox"))
        assertTrue(threadProperties.has("permissions"))
        val turnProperties = definitions.getJSONObject("TurnStartParams")
            .getJSONObject("properties")
        assertTrue(turnProperties.has("sandboxPolicy"))
        assertTrue(turnProperties.has("permissions"))
    }

    @Test
    fun typedStreamingFixturesUseAllPinnedRequiredFields() {
        assertEquals(
            setOf("threadId", "turn"),
            required("TurnCompletedNotification"),
        )
        assertEquals(
            setOf("id", "items", "status"),
            required("Turn"),
        )
        assertEquals(
            setOf("delta", "itemId", "threadId", "turnId"),
            required("AgentMessageDeltaNotification"),
        )
        assertEquals(
            setOf("item", "startedAtMs", "threadId", "turnId"),
            required("ItemStartedNotification"),
        )
        assertEquals(
            setOf("completedAtMs", "item", "threadId", "turnId"),
            required("ItemCompletedNotification"),
        )
    }

    @Test
    fun recoveryAndSkillsMethodsRemainStableInPinnedSchema() {
        assertEquals(setOf("threadId"), required("ThreadResumeParams"))
        assertEquals(setOf("thread", "model", "modelProvider", "approvalPolicy", "approvalsReviewer", "cwd", "sandbox"), required("ThreadResumeResponse"))
        assertEquals(setOf("threadId", "turnId"), required("TurnInterruptParams"))
        assertEquals(setOf("data"), required("SkillsListResponse"))
        assertEquals(setOf("cwd", "errors", "skills"), required("SkillsListEntry"))
    }

    @Test
    fun threadMemoryMigrationRpcRemainsStableInPinnedSchema() {
        assertEquals(
            listOf("enabled", "disabled"),
            definitions.getJSONObject("ThreadMemoryMode")
                .getJSONArray("enum")
                .let { values -> (0 until values.length()).map(values::getString) },
        )
        assertEquals(
            setOf("mode", "threadId"),
            required("ThreadMemoryModeSetParams"),
        )
        assertTrue(
            !definitions.getJSONObject("ThreadMemoryModeSetResponse").has("required"),
        )
    }

    @Test
    fun freshThreadPersistenceReadUsesPinnedSchemaWithoutChangingHistoryMode() {
        assertEquals(setOf("threadId"), required("ThreadReadParams"))
        assertTrue(definitions.getJSONObject("ThreadReadParams").getJSONObject("properties").has("includeTurns"))
        assertEquals(setOf("thread"), required("ThreadReadResponse"))
        assertEquals(listOf("legacy", "paginated"), definitions.getJSONObject("ThreadHistoryMode")
            .getJSONArray("enum").let { values -> (0 until values.length()).map(values::getString) })
    }

    @Test
    fun dynamicToolsRemainNestedNamespacesInPinnedSchema() {
        assertEquals(
            "#/definitions/DynamicToolSpec",
            definitions.getJSONObject("ThreadStartParams")
                .getJSONObject("properties")
                .getJSONObject("dynamicTools")
                .getJSONObject("items")
                .getString("\$ref"),
        )
        val dynamicToolVariants = definitions.getJSONObject("DynamicToolSpec")
            .getJSONArray("oneOf")
        val namespace = (0 until dynamicToolVariants.length())
            .map(dynamicToolVariants::getJSONObject)
            .single { it.optString("title") == "NamespaceDynamicToolSpec" }
        assertEquals(
            setOf("description", "name", "tools", "type"),
            requiredProperties(namespace),
        )
        assertEquals(
            "namespace",
            namespace.getJSONObject("properties")
                .getJSONObject("type")
                .getJSONArray("enum")
                .getString(0),
        )
        assertEquals(
            "#/definitions/DynamicToolNamespaceTool",
            namespace.getJSONObject("properties")
                .getJSONObject("tools")
                .getJSONObject("items")
                .getString("\$ref"),
        )

        val nestedFunction = definitions.getJSONObject("DynamicToolNamespaceTool")
            .getJSONArray("oneOf")
            .getJSONObject(0)
        assertEquals(
            setOf("description", "inputSchema", "name", "type"),
            requiredProperties(nestedFunction),
        )
        assertEquals(
            "function",
            nestedFunction.getJSONObject("properties")
                .getJSONObject("type")
                .getJSONArray("enum")
                .getString(0),
        )
    }

    @Test
    fun settingsOnlyUpdateHasEmptyAckAndSeparateNestedEffectiveSnapshot() {
        assertEquals(setOf("threadId"), required("ThreadSettingsUpdateParams"))
        val params = definitions.getJSONObject("ThreadSettingsUpdateParams").getJSONObject("properties")
        assertTrue(params.has("model"))
        assertTrue(params.has("effort"))
        assertTrue(params.has("serviceTier"))
        assertTrue(!params.has("input"))
        assertTrue(params.getJSONObject("serviceTier").getString("description").contains("omission leaves it unchanged"))
        val response = definitions.getJSONObject("ThreadSettingsUpdateResponse")
        assertEquals("object", response.getString("type"))
        assertTrue(!response.has("properties"))
        assertTrue(!response.has("required"))
        assertEquals(setOf("threadId", "threadSettings"), required("ThreadSettingsUpdatedNotification"))
        assertEquals(
            "#/definitions/ThreadSettings",
            definitions.getJSONObject("ThreadSettingsUpdatedNotification").getJSONObject("properties")
                .getJSONObject("threadSettings").getString("\$ref"),
        )
        assertEquals(
            setOf("approvalPolicy", "approvalsReviewer", "collaborationMode", "cwd", "model", "modelProvider", "sandboxPolicy"),
            required("ThreadSettings"),
        )
        val settings = definitions.getJSONObject("ThreadSettings").getJSONObject("properties")
        assertTrue(settings.has("effort"))
        assertTrue(settings.has("serviceTier"))
        assertTrue(!settings.has("reasoningEffort"))
    }

    private fun required(definition: String): Set<String> {
        val values = definitions.getJSONObject(definition).getJSONArray("required")
        return (0 until values.length()).map { index ->
            requireNotNull(values.getString(index))
        }.toSet()
    }

    private fun requiredProperties(definition: JSONObject): Set<String> {
        val values = definition.getJSONArray("required")
        return (0 until values.length()).map(values::getString).toSet()
    }

    private fun schemaFile(): File {
        val relative = "protocol/app-server/${CodexProtocolContract.APP_SERVER_VERSION}/" +
            "codex_app_server_protocol.v2.schemas.json"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val file = generateSequence(start) { current -> current.parentFile }
            .map { root -> File(root, relative) }
            .firstOrNull(File::isFile)
        assertNotNull("Vendored App Server schema not found from $start", file)
        return requireNotNull(file)
    }
}
