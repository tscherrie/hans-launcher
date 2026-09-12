package ai.hans.standard.plugins

import ai.hans.standard.codex.CorrelatedResponse
import ai.hans.standard.codex.ExtensionAppServerResult
import ai.hans.standard.codex.FrameLimitException
import ai.hans.standard.codex.MalformedEnvelopeException
import ai.hans.standard.codex.RequestId
import ai.hans.standard.codex.ResponseCorrelator
import ai.hans.standard.codex.UnsupportedProtocolValueException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAppServerProtocolTest {
    @Test
    fun pluginListUsesEveryPinnedMarketplaceKindAndStrictlyParsesCards() {
        val request = PluginAppServerRequests.pluginList(
            RequestId.Number(1),
            "/data/user/0/ai.hans.standard/files/codex-workspace",
            forceRefetch = true,
        )
        val params = JSONObject(request.json).getJSONObject("params")
        assertTrue(params.getBoolean("forceRefetch"))
        assertEquals(
            listOf(
                "local",
                "vertical",
                "workspace-directory",
                "shared-with-me",
                "created-by-me-remote",
            ),
            params.getJSONArray("marketplaceKinds").strings(),
        )

        val parsed = accept(request, pluginListJson()) as PluginListWireResult
        val record = parsed.records.single()
        val card = record.card
        assertEquals("Gmail", card.displayName)
        assertTrue(card.installable)
        assertTrue(card.featured)
        assertEquals(PluginSourceKind.REMOTE, card.sourceKind)
        assertEquals(64, card.handle.value.length)
        assertEquals(null, record.availableVersion)
        assertEquals(null, record.localVersion)
    }

    @Test
    fun remoteReadAndInstallUseMarketplaceNameWhileUninstallUsesPluginId() {
        val locator = parsedLocator()
        val read = JSONObject(
            PluginAppServerRequests.pluginRead(RequestId.Number(2), locator).json,
        ).getJSONObject("params")
        assertEquals("gmail", read.getString("pluginName"))
        assertEquals("official", read.getString("remoteMarketplaceName"))
        assertFalse(read.has("marketplacePath"))

        val install = JSONObject(
            PluginAppServerRequests.pluginInstall(
                RequestId.Number(3),
                locator,
                "hans-plugin-1",
            ).json,
        ).getJSONObject("params")
        assertEquals("hans-plugin-1", install.getString("installAttemptId"))
        assertEquals("official", install.getString("remoteMarketplaceName"))

        val uninstall = JSONObject(
            PluginAppServerRequests.pluginUninstall(RequestId.Number(4), locator.pluginId).json,
        ).getJSONObject("params")
        assertEquals("plugin-gmail", uninstall.getString("pluginId"))
        assertFalse(uninstall.has("pluginName"))
    }

    @Test
    fun pluginReadIsCorrelatedToTheExactCatalogEntry() {
        val record = parsedRecord()
        val locator = record.locator
        val request = PluginAppServerRequests.pluginRead(RequestId.Number(5), locator)
        val detail = accept(request, pluginDetailJson()) as PluginReadWireResult
        assertEquals(record.card.handle, detail.detail.handle)
        assertEquals(record.card.sourceKind, detail.sourceKind)
        assertEquals(record.localSourcePath, detail.localSourcePath)
        assertEquals("Gmail capability", detail.detail.description)
        assertEquals("app-gmail", detail.detail.apps.single().id)
        assertEquals("gmail-compose", detail.detail.skills.single().name)
        assertTrue(detail.detail.hooks.isEmpty())

        val withHook = pluginDetailJson().also {
            it.getJSONObject("plugin").put(
                "hooks",
                JSONArray().put(
                    JSONObject().put("eventName", "sessionStart").put("key", "hook-1"),
                ),
            )
        }
        val hookDetail = accept(
            PluginAppServerRequests.pluginRead(RequestId.Number(51), locator),
            withHook,
        ) as PluginReadWireResult
        assertEquals(PluginHookSummary("hook-1", "sessionStart"), hookDetail.detail.hooks.single())

        val mismatched = pluginDetailJson().also {
            it.getJSONObject("plugin").put("marketplaceName", "other")
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginRead(RequestId.Number(6), locator),
                mismatched,
            )
        }
    }

    @Test
    fun installUninstallAndMarketplaceResultsHaveClosedShapes() {
        val locator = parsedLocator()
        val install = PluginAppServerRequests.pluginInstall(
            RequestId.Number(7),
            locator,
            "hans-plugin-7",
        )
        val installResult = accept(
            install,
            JSONObject()
                .put("appsNeedingAuth", JSONArray())
                .put("authPolicy", "ON_INSTALL"),
        ) as PluginInstallWireResult
        assertEquals(PluginAuthPolicy.ON_INSTALL, installResult.authPolicy)

        val uninstall = PluginAppServerRequests.pluginUninstall(
            RequestId.Number(8),
            locator.pluginId,
        )
        assertEquals(PluginUninstallWireResult, accept(uninstall, JSONObject()))
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginUninstall(RequestId.Number(9), locator.pluginId),
                JSONObject().put("unexpected", true),
            )
        }

        val upgrade = PluginAppServerRequests.marketplaceUpgrade(RequestId.Number(10), null)
        val upgradeParams = JSONObject(upgrade.json).getJSONObject("params")
        assertEquals(0, upgradeParams.length())
        val upgraded = accept(
            upgrade,
            JSONObject()
                .put("errors", JSONArray())
                .put("selectedMarketplaces", JSONArray().put("official"))
                .put("upgradedRoots", JSONArray().put("/data/plugins/official")),
        ) as MarketplaceUpgradeWireResult
        assertEquals(1, upgraded.upgradedRootCount)
    }

    @Test
    fun skillConfigurationUsesExactPathAndStrictEffectiveState() {
        val request = PluginAppServerRequests.skillConfigWrite(
            id = RequestId.Number(11),
            path = "/data/user/0/ai.hans.standard/files/codex-workspace/skills/gmail/SKILL.md",
            enabled = false,
        )
        val envelope = JSONObject(request.json)
        assertEquals("skills/config/write", envelope.getString("method"))
        val params = envelope.getJSONObject("params")
        assertEquals(false, params.getBoolean("enabled"))
        assertEquals(
            "/data/user/0/ai.hans.standard/files/codex-workspace/skills/gmail/SKILL.md",
            params.getString("path"),
        )
        assertFalse(params.has("name"))

        val result = accept(
            request,
            JSONObject().put("effectiveEnabled", false),
        ) as SkillConfigWriteWireResult
        assertFalse(result.effectiveEnabled)
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.skillConfigWrite(
                    RequestId.Number(12),
                    "/data/local/tmp/SKILL.md",
                    true,
                ),
                JSONObject()
                    .put("effectiveEnabled", true)
                    .put("unexpected", true),
            )
        }
    }

    @Test
    fun marketplaceAddUsesAnExactLocalRootAndStrictlyParsesItsProof() {
        val root = "/data/user/0/ai.hans.standard/files/hans-bundled-marketplace"
        val request = PluginAppServerRequests.marketplaceAdd(RequestId.Number(101), root)
        val params = JSONObject(request.json).getJSONObject("params")
        assertEquals(setOf("source"), params.keys().asSequence().toSet())
        assertEquals(root, params.getString("source"))

        val result = accept(
            request,
            JSONObject()
                .put("alreadyAdded", false)
                .put("installedRoot", root)
                .put("marketplaceName", "hans-bundled"),
        ) as MarketplaceAddWireResult
        assertEquals("hans-bundled", result.marketplaceName)
        assertEquals(root, result.installedRoot)
        assertFalse(result.alreadyAdded)

        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                request,
                JSONObject()
                    .put("alreadyAdded", false)
                    .put("installedRoot", "relative")
                    .put("marketplaceName", "hans-bundled"),
            )
        }
    }

    @Test
    fun appListPaginationCarriesAndCorrelatesTheOpaqueCursor() {
        val firstRequest = PluginAppServerRequests.appList(
            RequestId.Number(11),
            threadId = "thread-1",
            cursor = null,
            forceRefetch = true,
        )
        val first = accept(firstRequest, appListJson("page-2")) as AppListPageWireResult
        assertEquals("page-2", first.nextCursor)
        assertEquals(null, first.requestedCursor)
        assertTrue(first.apps.single().accessible)

        val secondRequest = PluginAppServerRequests.appList(
            RequestId.Number(12),
            threadId = "thread-1",
            cursor = "page-2",
            forceRefetch = false,
        )
        assertEquals(
            "page-2",
            JSONObject(secondRequest.json).getJSONObject("params").getString("cursor"),
        )
        val second = accept(secondRequest, appListJson()) as AppListPageWireResult
        assertEquals("page-2", second.requestedCursor)
    }

    @Test
    fun unsafeUrlsAndUnknownEnumsFailClosed() {
        val unsafe = pluginListJson().also {
            it.getJSONArray("marketplaces").getJSONObject(0)
                .getJSONArray("plugins").getJSONObject(0)
                .getJSONObject("interface")
                .put("logoUrl", "javascript:alert(1)")
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(13),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                unsafe,
            )
        }

        val unknown = pluginListJson().also {
            it.getJSONArray("marketplaces").getJSONObject(0)
                .getJSONArray("plugins").getJSONObject(0)
                .put("availability", "MAYBE")
        }
        assertThrows(UnsupportedProtocolValueException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(14),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                unknown,
            )
        }
    }

    @Test
    fun everyKnownOptionalPluginShapeIsValidatedAndUnknownNestedFieldsFailClosed() {
        val full = pluginListJson()
        val summary = full.getJSONArray("marketplaces").getJSONObject(0)
            .getJSONArray("plugins").getJSONObject(0)
        summary
            .put("disabledReason", JSONObject.NULL)
            .put("eligiblePlanTypes", JSONArray().put("pro"))
            .put("installPolicySource", "WORKSPACE_SETTING")
            .put("installedAt", 0L)
            .put("keywords", JSONArray().put("mail"))
            .put("localVersion", "1.0.0")
            .put("mustShowInstallationInterstitial", false)
            .put("remotePluginId", "remote-gmail")
            .put("version", "1.1.0")
            .put(
                "shareContext",
                JSONObject()
                    .put("canPublishToWorkspace", true)
                    .put("creatorAccountUserId", "user-1")
                    .put("creatorName", "Creator")
                    .put("discoverability", "PRIVATE")
                    .put("remotePluginId", "remote-gmail")
                    .put("remoteVersion", "1.1.0")
                    .put("shareUrl", "https://chatgpt.com/plugins/gmail")
                    .put(
                        "sharePrincipals",
                        JSONArray().put(
                            JSONObject()
                                .put("name", "Workspace")
                                .put("principalId", "workspace-1")
                                .put("principalType", "workspace")
                                .put("role", "reader"),
                        ),
                    ),
            )

        val parsed = accept(
            PluginAppServerRequests.pluginList(
                RequestId.Number(140),
                "/data/user/0/ai.hans.standard/files/codex-workspace",
                false,
            ),
            full,
        ) as PluginListWireResult
        assertEquals("1.1.0", parsed.records.single().availableVersion)
        assertEquals("1.0.0", parsed.records.single().localVersion)
        summary.getJSONObject("interface")
            .put("brandColor", "#ffffff")
            .put("category", "productivity")
            .put("composerIcon", "/data/plugins/gmail/composer.png")
            .put("composerIconUrl", "https://cdn.example.test/composer.png")
            .put("defaultPrompt", JSONArray().put("Read mail"))
            .put("developerName", "OpenAI")
            .put("logo", "/data/plugins/gmail/logo.png")
            .put("logoDark", "/data/plugins/gmail/logo-dark.png")
            .put("longDescription", "Long description")
            .put("privacyPolicyUrl", "https://example.test/privacy")
            .put("termsOfServiceUrl", "https://example.test/terms")
            .put("websiteUrl", "https://example.test")

        val request = PluginAppServerRequests.pluginList(
            RequestId.Number(140),
            "/data/user/0/ai.hans.standard/files/codex-workspace",
            false,
        )
        assertEquals("plugin-gmail", (accept(request, full) as PluginListWireResult)
            .records.single().card.pluginId)

        val unknownNested = pluginListJson().also {
            it.getJSONArray("marketplaces").getJSONObject(0)
                .getJSONArray("plugins").getJSONObject(0)
                .getJSONObject("interface")
                .put("futureWireField", true)
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(141),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                unknownNested,
            )
        }
    }

    @Test
    fun wrongNullabilityAndMalformedKnownOptionalFieldsFailClosed() {
        val nullAvailability = pluginListJson().also {
            it.getJSONArray("marketplaces").getJSONObject(0)
                .getJSONArray("plugins").getJSONObject(0)
                .put("availability", JSONObject.NULL)
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(142),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                nullAvailability,
            )
        }

        val wrongInstallTime = pluginListJson().also {
            it.getJSONArray("marketplaces").getJSONObject(0)
                .getJSONArray("plugins").getJSONObject(0)
                .put("installedAt", "yesterday")
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(143),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                wrongInstallTime,
            )
        }

        val nullAppEnabled = appListJson().also {
            it.getJSONArray("data").getJSONObject(0).put("isEnabled", JSONObject.NULL)
        }
        assertThrows(MalformedEnvelopeException::class.java) {
            accept(
                PluginAppServerRequests.appList(
                    RequestId.Number(144),
                    threadId = null,
                    cursor = null,
                    forceRefetch = false,
                ),
                nullAppEnabled,
            )
        }
    }

    @Test
    fun appMetadataAndPluginHookEnumsAreStrictlyDecoded() {
        val fullApp = appListJson().also { result ->
            result.getJSONArray("data").getJSONObject(0)
                .put("distributionChannel", "marketplace")
                .put("iconAssets", JSONObject().put("small", "asset-small"))
                .put("iconDarkAssets", JSONObject.NULL)
                .put("labels", JSONObject().put("category", "mail"))
                .put(
                    "branding",
                    JSONObject()
                        .put("isDiscoverableApp", true)
                        .put("category", "productivity")
                        .put("developer", "OpenAI")
                        .put("privacyPolicy", "https://example.test/privacy")
                        .put("termsOfService", "https://example.test/terms")
                        .put("website", "https://example.test"),
                )
                .put(
                    "appMetadata",
                    JSONObject()
                        .put("categories", JSONArray().put("productivity"))
                        .put("developer", "OpenAI")
                        .put("firstPartyRequiresInstall", true)
                        .put("review", JSONObject().put("status", "approved"))
                        .put(
                            "screenshots",
                            JSONArray().put(
                                JSONObject()
                                    .put("fileId", "file-1")
                                    .put("url", "https://cdn.example.test/app.png")
                                    .put("userPrompt", "Show inbox"),
                            ),
                        )
                        .put("seoDescription", "Mail")
                        .put("showInComposerWhenUnlinked", false)
                        .put("subCategories", JSONArray())
                        .put("version", "1.0")
                        .put("versionId", "v1")
                        .put("versionNotes", "Initial"),
                )
        }
        val request = PluginAppServerRequests.appList(
            RequestId.Number(145),
            threadId = null,
            cursor = null,
            forceRefetch = true,
        )
        assertEquals("app-gmail", (accept(request, fullApp) as AppListPageWireResult)
            .apps.single().id)

        val badHook = pluginDetailJson().also {
            it.getJSONObject("plugin").put(
                "hooks",
                JSONArray().put(
                    JSONObject().put("eventName", "futureHook").put("key", "hook-1"),
                ),
            )
        }
        assertThrows(UnsupportedProtocolValueException::class.java) {
            accept(
                PluginAppServerRequests.pluginRead(RequestId.Number(146), parsedLocator()),
                badHook,
            )
        }
    }

    @Test
    fun appUpdateNotificationParserRejectsUnexpectedFields() {
        val update = JSONObject().put("data", appListJson().getJSONArray("data"))
        assertEquals("app-gmail", parseAppListUpdatedParams(update.toString()).single().id)
        assertThrows(MalformedEnvelopeException::class.java) {
            parseAppListUpdatedParams(update.put("unexpected", true).toString())
        }
    }

    @Test
    fun oversizedCatalogFailsBeforePublishingAnyPartialData() {
        val plugins = JSONArray()
        repeat(ai.hans.standard.codex.ProtocolLimits.MAX_PLUGINS_PER_MARKETPLACE + 1) {
            plugins.put(JSONObject())
        }
        val oversized = JSONObject().put(
            "marketplaces",
            JSONArray().put(
                JSONObject()
                    .put("name", "oversized")
                    .put("plugins", plugins),
            ),
        )
        assertThrows(FrameLimitException::class.java) {
            accept(
                PluginAppServerRequests.pluginList(
                    RequestId.Number(15),
                    "/data/user/0/ai.hans.standard/files/codex-workspace",
                    false,
                ),
                oversized,
            )
        }
    }

    private fun parsedLocator(): PluginLocator = parsedRecord().locator

    private fun parsedRecord(): PluginWireRecord {
        val request = PluginAppServerRequests.pluginList(
            RequestId.Number(100),
            "/data/user/0/ai.hans.standard/files/codex-workspace",
            false,
        )
        return (accept(request, pluginListJson()) as PluginListWireResult).records.single()
    }

    private fun accept(request: ai.hans.standard.codex.EncodedRequest, result: JSONObject): Any {
        val correlator = ResponseCorrelator()
        correlator.register(request)
        val response = correlator.accept(
            JSONObject().put("id", (request.id as RequestId.Number).value)
                .put("result", result)
                .toString(),
        ) as CorrelatedResponse.Success
        return (response.result as ExtensionAppServerResult).payload
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)
}
