package ai.hans.standard.mcp

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMcpToolPolicyStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactPoliciesSurviveProcessRestartAndResolveOnlyExactDiscoveredMetadata() {
        val directory = temporaryFolder.newFolder("restart")
        val identity = identity()
        val tool = tool("tasks/list", "original")
        val store = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)

        val committed = store.compareAndSet(
            expectedRevision = 0,
            approvals = listOf(
                RemoteMcpToolPolicyApproval(identity, tool, RemoteMcpToolEffect.READ_ONLY),
            ),
        )
        assertEquals(1, committed.revision)
        assertEquals(1, committed.policies.single().generation)

        val restarted = RemoteMcpToolPolicyStore(
            directory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        ).snapshot()
        assertTrue(restarted.available)
        assertEquals(committed, restarted)
        val registry = RemoteMcpToolPolicyRegistry.fromStoreSnapshot(
            restarted,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        assertEquals(RemoteMcpToolEffect.READ_ONLY, registry.resolve(identity, tool)?.effect)
        assertNull(registry.resolve(identity, tool("tasks/list", "changed")))
        assertNull(
            registry.resolve(
                identity.copy(configurationDigest = "f".repeat(64)),
                tool,
            ),
        )
    }

    @Test
    fun mutatingPolicyRequiresVerifierIdFromSignedInAppRegistry() {
        val directory = temporaryFolder.newFolder("verifier")
        val tool = tool("tasks.create", "mutating")
        val unsignedStore = RemoteMcpToolPolicyStore(
            directory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )

        assertTrue(
            runCatching {
                unsignedStore.compareAndSet(
                    0,
                    listOf(
                        RemoteMcpToolPolicyApproval(
                            identity(),
                            tool,
                            RemoteMcpToolEffect.MUTATING,
                            "tasks.create.v1",
                        ),
                    ),
                )
            }.exceptionOrNull() is RemoteMcpFailure,
        )
        assertEquals(0, unsignedStore.snapshot().revision)
        assertTrue(
            runCatching {
                RemoteMcpToolPolicyApproval(
                    identity(),
                    tool,
                    RemoteMcpToolEffect.MUTATING,
                    null,
                ).toPolicy(1, RemoteMcpSignedVerifierRegistry.EMPTY)
            }.isFailure,
        )

        val verifier = RemoteMcpPostconditionVerifier { RemoteMcpPostcondition.VERIFIED }
        val signed = RemoteMcpSignedVerifierRegistry.fromSignedInAppCode(
            listOf(RemoteMcpSignedVerifierDefinition("tasks.create.v1", verifier)),
        )
        val signedStore = RemoteMcpToolPolicyStore(directory, signed)
        val snapshot = signedStore.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    tool,
                    RemoteMcpToolEffect.MUTATING,
                    "tasks.create.v1",
                ),
            ),
        )
        val resolved = RemoteMcpToolPolicyRegistry(snapshot.policies, signed)
            .resolve(identity(), tool)
        assertEquals(RemoteMcpToolEffect.MUTATING, resolved?.effect)
        assertTrue(resolved?.verifier === verifier)

        val reopenedWithoutSignedCode = RemoteMcpToolPolicyStore(
            directory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        assertFalse(reopenedWithoutSignedCode.snapshot().available)
    }

    @Test
    fun compareAndSetRejectsStaleRevisionAcrossStoreInstances() {
        val directory = temporaryFolder.newFolder("cas")
        val first = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)
        val stale = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)
        first.compareAndSet(
            0,
            listOf(
                RemoteMcpToolPolicyApproval(
                    identity(),
                    tool("tasks/list", "first"),
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )

        val failure = runCatching {
            stale.compareAndSet(
                0,
                listOf(
                    RemoteMcpToolPolicyApproval(
                        identity(),
                        tool("tasks/list", "stale"),
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is RemoteMcpFailure)
        assertEquals("mcp_tool_policy_revision_stale", (failure as RemoteMcpFailure).code)
        assertEquals(1, stale.snapshot().revision)
    }

    @Test
    fun activationScopedCasPreservesUnrelatedPoliciesAndEmptyReplacementRevokesOnlyExactIdentity() {
        val directory = temporaryFolder.newFolder("activation-cas")
        val store = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)
        val firstIdentity = identity()
        val secondIdentity = RemoteMcpActivationIdentity(
            pluginId = "calendar-plugin",
            serverId = "calendar",
            configurationDigest = "b".repeat(64),
        )
        val firstTool = tool("tasks/list", "first")
        val secondTool = tool("calendar/list", "second")

        var snapshot = store.compareAndSetForActivation(
            expectedRevision = 0,
            identity = firstIdentity,
            approvals = listOf(
                RemoteMcpToolPolicyApproval(
                    firstIdentity,
                    firstTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        snapshot = store.compareAndSetForActivation(
            expectedRevision = snapshot.revision,
            identity = secondIdentity,
            approvals = listOf(
                RemoteMcpToolPolicyApproval(
                    secondIdentity,
                    secondTool,
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        assertEquals(2L, snapshot.revision)
        assertEquals(setOf(firstIdentity, secondIdentity), snapshot.policies.mapTo(linkedSetOf()) {
            it.activationIdentity
        })

        snapshot = store.compareAndSetForActivation(
            expectedRevision = snapshot.revision,
            identity = firstIdentity,
            approvals = emptyList(),
        )
        assertEquals(3L, snapshot.revision)
        assertEquals(listOf(secondIdentity), snapshot.policies.map { it.activationIdentity })
        assertEquals(2L, snapshot.policies.single().generation)
    }

    @Test
    fun activationScopedCasRejectsStaleRevisionIdentityMixingAndUnknownMutatingVerifier() {
        val directory = temporaryFolder.newFolder("activation-fail-closed")
        val store = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)
        val exactIdentity = identity()
        val otherIdentity = exactIdentity.copy(configurationDigest = "c".repeat(64))

        val mixedFailure = runCatching {
            store.compareAndSetForActivation(
                0,
                exactIdentity,
                listOf(
                    RemoteMcpToolPolicyApproval(
                        otherIdentity,
                        tool("tasks/list", "other"),
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(mixedFailure is IllegalArgumentException)
        assertEquals(0L, store.snapshot().revision)

        val verifierFailure = runCatching {
            store.compareAndSetForActivation(
                0,
                exactIdentity,
                listOf(
                    RemoteMcpToolPolicyApproval(
                        exactIdentity,
                        tool("tasks/create", "mutating"),
                        RemoteMcpToolEffect.MUTATING,
                        "missing.signed.verifier",
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(verifierFailure is RemoteMcpFailure)
        assertEquals(
            "mcp_tool_policy_verifier_untrusted",
            (verifierFailure as RemoteMcpFailure).code,
        )
        assertEquals(0L, store.snapshot().revision)

        store.compareAndSetForActivation(
            0,
            exactIdentity,
            listOf(
                RemoteMcpToolPolicyApproval(
                    exactIdentity,
                    tool("tasks/list", "approved"),
                    RemoteMcpToolEffect.READ_ONLY,
                ),
            ),
        )
        val staleFailure = runCatching {
            store.compareAndSetForActivation(0, exactIdentity, emptyList())
        }.exceptionOrNull()
        assertTrue(staleFailure is RemoteMcpFailure)
        assertEquals("mcp_tool_policy_revision_stale", (staleFailure as RemoteMcpFailure).code)
        assertEquals(1L, store.snapshot().revision)
    }

    @Test
    fun corruptUnknownOversizedAndLinkedStateFailClosed() {
        val corruptDirectory = temporaryFolder.newFolder("corrupt")
        val corrupt = File(corruptDirectory, "trusted-tool-policies-v1.json")
        corrupt.writeText("""{"schema":"unknown","version":1,"revision":0,"policies":[]}""")
        var store = RemoteMcpToolPolicyStore(
            corruptDirectory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        assertFalse(store.snapshot().available)
        assertTrue(runCatching { store.compareAndSet(0, emptyList()) }.isFailure)

        val oversizedDirectory = temporaryFolder.newFolder("oversized")
        File(oversizedDirectory, "trusted-tool-policies-v1.json")
            .writeBytes(ByteArray(1024 * 1024 + 1))
        store = RemoteMcpToolPolicyStore(
            oversizedDirectory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        assertFalse(store.snapshot().available)

        val linkedDirectory = temporaryFolder.newFolder("linked")
        val outside = temporaryFolder.newFile("outside-policy.json").apply { writeText("{}") }
        Files.createSymbolicLink(
            File(linkedDirectory, "trusted-tool-policies-v1.json").toPath(),
            outside.toPath(),
        )
        store = RemoteMcpToolPolicyStore(
            linkedDirectory,
            RemoteMcpSignedVerifierRegistry.EMPTY,
        )
        assertFalse(store.snapshot().available)
    }

    @Test
    fun persistedPolicyAndPublicStringsDoNotLeakDiscoveredMetadataOrVerifierCode() {
        val directory = temporaryFolder.newFolder("redaction")
        val schemaMarker = "highly-private-schema-marker"
        val tool = RemoteMcpTool(
            name = "private/read",
            title = "private title marker",
            description = "private description marker",
            inputSchemaJson = """{"type":"object","description":"$schemaMarker"}""",
            outputSchemaJson = """{"type":"object","description":"private output marker"}""",
            annotationsJson = """{"title":"private annotation marker","readOnlyHint":true}""",
            iconsJson = """[{"src":"https://private.example/icon-marker.png"}]""",
            metaJson = """{"tenant":"private meta marker"}""",
        )
        val store = RemoteMcpToolPolicyStore(directory, RemoteMcpSignedVerifierRegistry.EMPTY)
        val snapshot = store.compareAndSet(
            0,
            listOf(RemoteMcpToolPolicyApproval(identity(), tool, RemoteMcpToolEffect.READ_ONLY)),
        )

        val persisted = File(directory, "trusted-tool-policies-v1.json").readText()
        assertFalse(persisted.contains(schemaMarker))
        assertFalse(persisted.contains("private title marker"))
        assertFalse(persisted.contains("private description marker"))
        assertFalse(persisted.contains("private output marker"))
        assertFalse(persisted.contains("private annotation marker"))
        assertFalse(persisted.contains("icon-marker"))
        assertFalse(persisted.contains("private meta marker"))
        assertFalse(snapshot.toString().contains(identity().configurationDigest))
        assertFalse(snapshot.policies.single().toString().contains("private/read"))
        assertFalse(snapshot.policies.single().toString().contains(schemaMarker))
        assertFalse(tool.toString().contains("private"))
    }

    @Test
    fun everyStandardAnnotationHintChangeInvalidatesExactApproval() {
        val baseHints = linkedMapOf(
            "readOnlyHint" to true,
            "destructiveHint" to false,
            "idempotentHint" to true,
            "openWorldHint" to false,
        )
        val base = toolWithMetadata(annotations = JSONObject(baseHints).toString())
        baseHints.forEach { (hint, value) ->
            val changed = JSONObject(baseHints).put(hint, !(value as Boolean))
            assertNotEquals(
                "Approval digest must change with $hint",
                remoteMcpToolMetadataDigest(base),
                remoteMcpToolMetadataDigest(
                    toolWithMetadata(annotations = changed.toString()),
                ),
            )
        }
    }

    @Test
    fun iconAndMetaChangesInvalidateExactApproval() {
        val base = toolWithMetadata(
            icons = """[{"src":"https://example.test/a.png","theme":"light"}]""",
            meta = """{"tenant":"alpha","routing":{"region":"eu"}}""",
        )
        assertNotEquals(
            remoteMcpToolMetadataDigest(base),
            remoteMcpToolMetadataDigest(
                toolWithMetadata(
                    icons = """[{"src":"https://example.test/b.png","theme":"light"}]""",
                    meta = base.metaJson,
                ),
            ),
        )
        assertNotEquals(
            remoteMcpToolMetadataDigest(base),
            remoteMcpToolMetadataDigest(
                toolWithMetadata(
                    icons = base.iconsJson,
                    meta = """{"tenant":"alpha","routing":{"region":"us"}}""",
                ),
            ),
        )
    }

    @Test
    fun objectKeyOrderIsCanonicalAcrossAllExactMetadata() {
        val first = RemoteMcpTool(
            name = "tasks/list",
            title = "Tasks",
            description = "List tasks",
            inputSchemaJson =
                """{"type":"object","properties":{"query":{"minLength":1,"type":"string"}}}""",
            outputSchemaJson = """{"required":["items"],"type":"object"}""",
            annotationsJson = """{"readOnlyHint":true,"openWorldHint":false}""",
            iconsJson =
                """[{"src":"https://example.test/icon.png","mimeType":"image/png","theme":"dark"}]""",
            metaJson = """{"routing":{"zone":"one","region":"eu"},"audience":"owner"}""",
        )
        val reordered = RemoteMcpTool(
            name = "tasks/list",
            title = "Tasks",
            description = "List tasks",
            inputSchemaJson =
                """{"properties":{"query":{"type":"string","minLength":1}},"type":"object"}""",
            outputSchemaJson = """{"type":"object","required":["items"]}""",
            annotationsJson = """{"openWorldHint":false,"readOnlyHint":true}""",
            iconsJson =
                """[{"theme":"dark","mimeType":"image/png","src":"https://example.test/icon.png"}]""",
            metaJson = """{"audience":"owner","routing":{"region":"eu","zone":"one"}}""",
        )

        assertEquals(first, reordered)
        assertEquals(remoteMcpToolMetadataDigest(first), remoteMcpToolMetadataDigest(reordered))
    }

    private fun identity() = RemoteMcpActivationIdentity(
        pluginId = "tasks-plugin",
        serverId = "tasks",
        configurationDigest = "a".repeat(64),
    )

    private fun tool(name: String, description: String) = RemoteMcpTool(
        name = name,
        title = null,
        description = description,
        inputSchemaJson = """{"required":["query"],"type":"object"}""",
        outputSchemaJson = """{"type":"object"}""",
    )

    private fun toolWithMetadata(
        annotations: String? = null,
        icons: String? = null,
        meta: String? = null,
    ) = RemoteMcpTool(
        name = "tasks/list",
        title = "Tasks",
        description = "List tasks",
        inputSchemaJson = """{"type":"object"}""",
        outputSchemaJson = """{"type":"object"}""",
        annotationsJson = annotations,
        iconsJson = icons,
        metaJson = meta,
    )
}
