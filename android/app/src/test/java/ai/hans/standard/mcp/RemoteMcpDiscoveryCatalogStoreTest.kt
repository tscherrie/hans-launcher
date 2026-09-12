package ai.hans.standard.mcp

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpDiscoveryCatalogStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun finalizedCatalogRetainsEveryPolicyRelevantToolFieldAcrossProcessRestart() {
        val directory = temporaryFolder.newFolder("metadata-catalog")
        val identity = identity()
        val tool = tool(
            annotations =
                """{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}""",
            icons = """[{"src":"https://assets.example/icon.png","mimeType":"image/png"}]""",
            meta = """{"routing":{"region":"eu"},"audience":"owner"}""",
        )
        finalize(directory, identity, tool)

        val restarted = RemoteMcpDiscoveryCatalogStore(directory).finalizedCatalog(identity)
        val restored = restarted.discovery.tools.single()

        assertEquals(tool, restored)
        assertEquals(tool.annotationsJson, restored.annotationsJson)
        assertEquals(tool.iconsJson, restored.iconsJson)
        assertEquals(tool.metaJson, restored.metaJson)
        assertEquals(
            remoteMcpToolMetadataDigest(tool),
            remoteMcpToolMetadataDigest(restored),
        )
    }

    @Test
    fun annotationIconOrMetaDriftChangesTheFinalizedCatalogDigest() {
        val identity = identity()
        val first = definition(
            identity,
            tool(
                annotations = """{"readOnlyHint":true}""",
                icons = """[{"src":"https://assets.example/one.png"}]""",
                meta = """{"tenant":"one"}""",
            ),
        )
        val changed = definition(
            identity,
            tool(
                annotations = """{"readOnlyHint":false}""",
                icons = """[{"src":"https://assets.example/two.png"}]""",
                meta = """{"tenant":"two"}""",
            ),
        )

        assertNotEquals(first.catalogDigest, changed.catalogDigest)
        assertNotEquals(first.metadataDigest, changed.metadataDigest)
    }

    @Test
    fun schemaOneCatalogWithoutOptionalMetadataRemainsReadableWithStableRecoveryDigest() {
        val directory = temporaryFolder.newFolder("legacy-catalog")
        val identity = identity()
        val current = finalize(directory, identity, tool(null, null, null))
        val stateFile = File(directory, "discovery-catalog-v1.json")
        val root = JSONObject(stateFile.readText())
        root.put("version", 1)
        val tools = root.getJSONArray("finalized")
            .getJSONObject(0)
            .getJSONArray("tools")
        repeat(tools.length()) { index ->
            tools.getJSONObject(index).apply {
                remove("annotationsJson")
                remove("iconsJson")
                remove("metaJson")
            }
        }
        stateFile.writeText(root.toString())

        val restarted = RemoteMcpDiscoveryCatalogStore(directory)
        val snapshot = restarted.recoverySnapshot(identity.pluginId)
        val restored = restarted.finalizedCatalog(identity)

        assertTrue(snapshot.available)
        assertEquals(current.metadataDigest, snapshot.finalized.single().metadataDigest)
        assertEquals(current.catalogDigest, restored.catalogDigest)
        assertEquals(tool(null, null, null), restored.discovery.tools.single())
    }

    private fun finalize(
        directory: File,
        identity: RemoteMcpActivationIdentity,
        tool: RemoteMcpTool,
    ): RemoteMcpFinalizedDiscoveryCatalog {
        val store = RemoteMcpDiscoveryCatalogStore(directory)
        val definition = definition(identity, tool)
        val receipt = store.prepare(definition)
        store.commit(receipt)
        store.finalize(receipt)
        return store.finalizedCatalog(identity)
    }

    private fun definition(
        identity: RemoteMcpActivationIdentity,
        tool: RemoteMcpTool,
    ): RemoteMcpDiscoveryCatalogDefinition = RemoteMcpDiscoveryCatalogDefinition.ready(
        activation = RemoteMcpActivationReceipt(
            transactionId = "activation-1",
            pluginId = identity.pluginId,
            serverId = identity.serverId,
            metadataDigest = "b".repeat(64),
        ),
        identity = identity,
        discovery = RemoteMcpDiscoveryReceipt(
            tools = listOf(tool),
            allowedToolNames = setOf(tool.name),
        ),
    )

    private fun identity() = RemoteMcpActivationIdentity(
        pluginId = "tasks-plugin",
        serverId = "tasks",
        configurationDigest = "a".repeat(64),
    )

    private fun tool(
        annotations: String?,
        icons: String?,
        meta: String?,
    ) = RemoteMcpTool(
        name = "tasks/list",
        title = "Tasks",
        description = "List tasks",
        inputSchemaJson = """{"properties":{},"type":"object"}""",
        outputSchemaJson = """{"type":"object"}""",
        annotationsJson = annotations,
        iconsJson = icons,
        metaJson = meta,
    )
}
