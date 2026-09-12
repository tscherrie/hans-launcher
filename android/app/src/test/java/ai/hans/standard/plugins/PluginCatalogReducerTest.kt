package ai.hans.standard.plugins

import ai.hans.standard.codex.CrossCorrelationException
import ai.hans.standard.mcp.RemoteMcpConnectionReason
import ai.hans.standard.mcp.RemoteMcpConnectionRequest
import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpDeclaredToolHints
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequest
import ai.hans.standard.mcp.RemoteMcpPolicyReviewToolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogReducerTest {
    @Test
    fun catalogPublishesInstalledPluginsFirstAndKeepsOpaqueLocatorsPrivate() {
        val reducer = PluginCatalogReducer()
        assertTrue(reducer.beginOperation("op-list", PluginOperationKind.REFRESH_PLUGINS))
        val available = wireRecord(installed = false, "plugin-gmail", "gmail")
        val installed = wireRecord(installed = true, "plugin-asana", "asana")
        reducer.applyPluginList(
            "op-list",
            PluginListWireResult(
                records = listOf(available, installed),
                featuredPluginIds = setOf("plugin-gmail"),
                marketplaceLoadIssueCount = 0,
            ),
        )

        val snapshot = reducer.snapshot()
        assertEquals(PluginCatalogPhase.READY, snapshot.phase)
        assertEquals(listOf("plugin-asana", "plugin-gmail"), snapshot.plugins.map { it.pluginId })
        assertEquals(PluginOperationStatus.SUCCESS, snapshot.operations.single().status)
        assertEquals("plugin-gmail", reducer.resolve(available.card.handle)?.pluginId)
        assertFalse(snapshot.toString().contains("remoteMarketplaceName"))
    }

    @Test
    fun mutationOnlySucceedsAfterItsCorrelatedResponseAndSessionLossIsAmbiguous() {
        val reducer = readyReducer()
        val handle = reducer.snapshot().plugins.single().handle
        assertTrue(
            reducer.beginOperation(
                "op-install",
                PluginOperationKind.INSTALL_PLUGIN,
                handle,
            ),
        )
        assertEquals(
            PluginOperationStatus.PENDING,
            reducer.snapshot().operations.last().status,
        )
        reducer.onSessionLost()
        val failed = reducer.snapshot().operations.last()
        assertEquals(PluginOperationStatus.FAILURE, failed.status)
        assertEquals(PluginOperationFailure.TRANSPORT_AMBIGUOUS, failed.failure)
        assertFalse(failed.retryable)
        assertEquals(PluginCatalogPhase.STALE, reducer.snapshot().phase)
    }

    @Test
    fun appPagesPublishAtomicallyAndRejectCursorReorderingAndLoops() {
        val reducer = PluginCatalogReducer()
        assertTrue(reducer.beginOperation("apps", PluginOperationKind.REFRESH_APPS))
        val gmail = AppCard(
            "gmail",
            "Gmail",
            null,
            null,
            null,
            null,
            true,
            true,
            emptyList(),
        )
        assertEquals(
            "next",
            reducer.applyAppPage(
                "apps",
                AppListPageWireResult(listOf(gmail), "next", requestedCursor = null),
            ),
        )
        assertTrue(reducer.snapshot().apps.isEmpty())
        assertThrows(CrossCorrelationException::class.java) {
            reducer.applyAppPage(
                "apps",
                AppListPageWireResult(emptyList(), null, requestedCursor = "wrong"),
            )
        }

        val drive = gmail.copy(id = "drive", name = "Drive")
        assertNull(
            reducer.applyAppPage(
                "apps",
                AppListPageWireResult(listOf(drive), null, requestedCursor = "next"),
            ),
        )
        assertEquals(listOf("drive", "gmail"), reducer.snapshot().apps.map { it.id })
        assertEquals(PluginOperationStatus.SUCCESS, reducer.snapshot().operations.single().status)
    }

    @Test
    fun observedAppUpdateReplacesTheVisibleListWithoutCompletingAnotherOperation() {
        val reducer = PluginCatalogReducer()
        val app = AppCard(
            "calendar",
            "Calendar",
            null,
            null,
            null,
            null,
            false,
            true,
            listOf("Calendar"),
        )
        reducer.applyAppListUpdated(listOf(app))
        assertEquals(listOf("calendar"), reducer.snapshot().apps.map { it.id })
        assertTrue(reducer.snapshot().operations.isEmpty())
    }

    @Test
    fun duplicatePendingOperationsAreDeduplicatedAndFailuresAreSafeEnums() {
        val reducer = readyReducer()
        assertTrue(reducer.beginOperation("read-1", PluginOperationKind.READ_PLUGIN,
            reducer.snapshot().plugins.single().handle))
        assertFalse(reducer.beginOperation("read-2", PluginOperationKind.READ_PLUGIN,
            reducer.snapshot().plugins.single().handle))
        reducer.fail("read-1", PluginOperationFailure.REMOTE_REJECTED, retryable = true)
        val operation = reducer.snapshot().operations.last()
        assertEquals(PluginOperationStatus.FAILURE, operation.status)
        assertEquals(PluginOperationFailure.REMOTE_REJECTED, operation.failure)
        assertTrue(operation.retryable)
    }

    @Test
    fun remoteMcpRequirementIsAConnectionActionInsteadOfInstallFailure() {
        val reducer = readyReducer()
        val handle = reducer.snapshot().plugins.single().handle
        val request = RemoteMcpConnectionRequest(
            pluginId = "plugin-gmail",
            serverId = "mail",
            reason = RemoteMcpConnectionReason.EXPIRED,
        )
        assertTrue(
            reducer.beginOperation("connect", PluginOperationKind.INSTALL_PLUGIN, handle),
        )

        reducer.requireRemoteMcpConnection("connect", request)

        val blocked = reducer.snapshot()
        assertEquals(PluginOperationStatus.ACTION_REQUIRED, blocked.operations.last().status)
        assertNull(blocked.operations.last().failure)
        assertEquals(PluginConnectionActionKind.CONNECT_REMOTE_MCP, blocked.connectionAction?.kind)
        assertEquals("plugin-gmail", blocked.connectionAction?.pluginId)
        assertEquals("mail", blocked.connectionAction?.serverId)
        assertTrue(reducer.markRemoteMcpConnected("plugin-gmail", "mail"))
        assertEquals(
            PluginConnectionActionKind.RETRY_INSTALL,
            reducer.snapshot().connectionAction?.kind,
        )
    }

    @Test
    fun oauthCallbackAfterProcessRecreationPublishesRetryButDoesNotCreateInstallOperation() {
        val reducer = readyReducer()
        val before = reducer.snapshot().operations.size

        assertTrue(reducer.markRemoteMcpConnected("plugin-gmail", "mail"))

        val snapshot = reducer.snapshot()
        assertEquals(before, snapshot.operations.size)
        assertEquals(PluginConnectionActionKind.RETRY_INSTALL, snapshot.connectionAction?.kind)
        assertEquals("plugin-gmail", snapshot.connectionAction?.pluginId)
        assertEquals("mail", snapshot.connectionAction?.serverId)
    }

    @Test
    fun policyReviewIsExactRedactedAndApprovalOnlyPublishesExplicitRetry() {
        val reducer = readyReducer()
        val handle = reducer.snapshot().plugins.single().handle
        val request = policyReviewRequest()
        assertTrue(reducer.beginOperation("review", PluginOperationKind.INSTALL_PLUGIN, handle))

        reducer.requireRemoteMcpPolicyReview("review", request, "d".repeat(64))

        val blocked = reducer.snapshot()
        assertEquals(
            PluginOperationStatus.ACTION_REQUIRED,
            blocked.operations.single { it.operationId == "review" }.status,
        )
        val action = checkNotNull(blocked.connectionAction)
        assertEquals(PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY, action.kind)
        val review = checkNotNull(action.policyReview)
        assertEquals(request.catalogDigest, review.catalogDigest)
        assertEquals(request.policyStoreRevision, review.policyStoreRevision)
        assertEquals(PluginRemoteMcpMutationVerification.SERVER_CONFIRMED,
            review.mutatingVerification)
        assertEquals(listOf("mail/send"), review.tools.map { it.name })
        assertEquals("Sends one mail", review.tools.single().description)
        assertFalse(action.toString().contains("https://"))
        assertFalse(action.toString().contains("inputSchema"))
        assertFalse(action.toString().contains("oauth", ignoreCase = true))
        assertFalse(action.toString().contains("_meta"))

        assertNull(
            reducer.resolveRemoteMcpPolicyReviewTarget(
                "plugin-gmail",
                "mail",
                review.copy(sourceDigest = "e".repeat(64)),
            ),
        )
        val exact = checkNotNull(
            reducer.resolveRemoteMcpPolicyReviewTarget("plugin-gmail", "mail", review),
        )
        assertEquals(request, exact.request)
        val operationCount = blocked.operations.size

        assertTrue(reducer.markRemoteMcpPolicyReviewed(request, "d".repeat(64)))
        val retry = reducer.snapshot()
        assertEquals(operationCount, retry.operations.size)
        assertEquals(PluginConnectionActionKind.RETRY_INSTALL, retry.connectionAction?.kind)
        assertNull(retry.connectionAction?.policyReview)
        assertEquals(handle, reducer.resolveRemoteMcpRetryTarget("plugin-gmail", "mail"))
        assertFalse(reducer.markRemoteMcpPolicyReviewed(request, "d".repeat(64)))
    }

    @Test
    fun skillConfigurationRequiresExactPluginDetailAndCatalogPath() {
        val reducer = readyReducer()
        val handle = reducer.snapshot().plugins.single().handle
        assertTrue(reducer.beginOperation("read", PluginOperationKind.READ_PLUGIN, handle))
        val baseDetail = parsedDetail(handle)
        reducer.applyPluginRead(
            "read",
            PluginReadWireResult(
                detail = baseDetail,
                sourceKind = PluginSourceKind.LOCAL,
                localSourcePath = "/private/plugin",
            ),
        )
        assertTrue(reducer.beginOperation("skills", PluginOperationKind.REFRESH_SKILLS))
        val path = "/data/user/0/ai.hans.standard/files/codex-workspace/skills/gmail/SKILL.md"
        reducer.applySkills(
            "skills",
            listOf(
                SkillCard(
                    name = "gmail-compose",
                    description = "Compose mail",
                    enabled = true,
                    displayName = "Compose Gmail",
                    shortDescription = "Compose",
                    iconSmallUrl = null,
                    path = path,
                ),
            ),
        )
        assertEquals(path, reducer.resolveSkillPath(handle, "gmail-compose"))

        assertTrue(
            reducer.beginOperation("configure", PluginOperationKind.CONFIGURE_SKILL, handle),
        )
        reducer.applySkillConfig(
            operationId = "configure",
            handle = handle,
            skillName = "gmail-compose",
            skillPath = path,
            result = SkillConfigWriteWireResult(effectiveEnabled = false),
        )
        assertFalse(reducer.snapshot().skills.single().enabled)
        assertFalse(reducer.snapshot().selectedPlugin!!.skills.single().enabled)
        assertEquals(
            PluginOperationStatus.SUCCESS,
            reducer.snapshot().operations.single { it.operationId == "configure" }.status,
        )
    }

    private fun readyReducer(): PluginCatalogReducer = PluginCatalogReducer().also { reducer ->
        reducer.beginOperation("initial", PluginOperationKind.REFRESH_PLUGINS)
        reducer.applyPluginList(
            "initial",
            PluginListWireResult(
                records = listOf(wireRecord(false, "plugin-gmail", "gmail")),
                featuredPluginIds = emptySet(),
                marketplaceLoadIssueCount = 0,
            ),
        )
    }

    private fun policyReviewRequest() = RemoteMcpPolicyReviewRequest(
        activationIdentity = RemoteMcpActivationIdentity(
            pluginId = "plugin-gmail",
            serverId = "mail",
            configurationDigest = "a".repeat(64),
        ),
        catalogDigest = "b".repeat(64),
        policyStoreRevision = 4L,
        tools = listOf(
            RemoteMcpPolicyReviewToolSummary(
                name = "mail/send",
                title = "Send mail",
                declaredHints = RemoteMcpDeclaredToolHints(false, true, false, true),
                metadataDigest = "c".repeat(64),
                description = "Sends one mail",
            ),
        ),
    )

    private fun wireRecord(
        installed: Boolean,
        pluginId: String,
        name: String,
    ): PluginWireRecord {
        val base = parsedRecord()
        val locator = base.locator.copy(pluginId = pluginId, pluginName = name)
        val handle = PluginHandle(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("${locator.marketplaceName}\u0000\u0000$pluginId\u0000$name".toByteArray())
                .joinToString("") { "%02x".format(it) },
        )
        return PluginWireRecord(
            locator,
            base.card.copy(
                handle = handle,
                pluginId = pluginId,
                name = name,
                displayName = name.replaceFirstChar(Char::uppercase),
                installed = installed,
            ),
            availableVersion = base.availableVersion,
            localVersion = base.localVersion,
            localSourcePath = "/private/plugins/$pluginId",
        )
    }

    private fun parsedRecord(): PluginWireRecord {
        val request = PluginAppServerRequests.pluginList(
            ai.hans.standard.codex.RequestId.Number(900),
            "/data/user/0/ai.hans.standard/files/codex-workspace",
            false,
        )
        val correlator = ai.hans.standard.codex.ResponseCorrelator()
        correlator.register(request)
        val response = correlator.accept(
            org.json.JSONObject()
                .put("id", 900)
                .put("result", pluginListJson())
                .toString(),
        ) as ai.hans.standard.codex.CorrelatedResponse.Success
        return ((response.result as ai.hans.standard.codex.ExtensionAppServerResult).payload
            as PluginListWireResult).records.single()
    }

    private fun parsedDetail(handle: PluginHandle): PluginDetailSnapshot {
        val record = reducerRecordForHandle(handle)
        val request = PluginAppServerRequests.pluginRead(
            ai.hans.standard.codex.RequestId.Number(901),
            record.locator,
        )
        val correlator = ai.hans.standard.codex.ResponseCorrelator()
        correlator.register(request)
        val response = correlator.accept(
            org.json.JSONObject()
                .put("id", 901)
                .put("result", pluginDetailJson())
                .toString(),
        ) as ai.hans.standard.codex.CorrelatedResponse.Success
        return (response.result as ai.hans.standard.codex.ExtensionAppServerResult)
            .payload.let { it as PluginReadWireResult }.detail
    }

    private fun reducerRecordForHandle(handle: PluginHandle): PluginWireRecord {
        val base = parsedRecord()
        return base.copy(
            locator = base.locator,
            card = base.card.copy(handle = handle),
        )
    }
}
