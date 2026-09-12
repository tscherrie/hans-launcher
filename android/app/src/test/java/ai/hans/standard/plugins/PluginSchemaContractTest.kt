package ai.hans.standard.plugins

import ai.hans.standard.codex.CodexProtocolContract
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSchemaContractTest {
    private val definitions: JSONObject by lazy {
        JSONObject(schemaFile().readText()).getJSONObject("definitions")
    }

    @Test
    fun pinnedSchemaContainsTheExactPluginAndCapabilityMethods() {
        val methods = definitions.getJSONObject("ClientRequest").getJSONArray("oneOf")
        val wireMethods = buildSet {
            repeat(methods.length()) { index ->
                val method = methods.getJSONObject(index)
                    .getJSONObject("properties")
                    .getJSONObject("method")
                    .getJSONArray("enum")
                    .getString(0)
                add(method)
            }
        }
        assertTrue(
            wireMethods.containsAll(
                setOf(
                    "plugin/list",
                    "plugin/read",
                    "plugin/install",
                    "plugin/uninstall",
                    "marketplace/add",
                    "marketplace/upgrade",
                    "app/list",
                    "skills/list",
                ),
            ),
        )
    }

    @Test
    fun pinnedRequestAndResponseFieldsCannotDriftSilently() {
        assertEquals(setOf("pluginName"), required("PluginInstallParams"))
        assertEquals(setOf("pluginId"), required("PluginUninstallParams"))
        assertEquals(setOf("marketplaces"), required("PluginListResponse"))
        assertEquals(setOf("plugin"), required("PluginReadResponse"))
        assertEquals(setOf("appsNeedingAuth", "authPolicy"), required("PluginInstallResponse"))
        assertEquals(setOf("source"), required("MarketplaceAddParams"))
        assertEquals(
            setOf("alreadyAdded", "installedRoot", "marketplaceName"),
            required("MarketplaceAddResponse"),
        )
        assertEquals(
            setOf("errors", "selectedMarketplaces", "upgradedRoots"),
            required("MarketplaceUpgradeResponse"),
        )
        assertEquals(setOf("data"), required("AppsListResponse"))
        assertEquals(setOf("data"), required("SkillsListResponse"))
    }

    @Test
    fun nestedPluginAndAppShapesArePinnedToo() {
        assertEquals(
            setOf(
                "authPolicy", "availability", "disabledReason", "eligiblePlanTypes",
                "enabled", "id", "installPolicy", "installPolicySource", "installed",
                "installedAt", "interface", "keywords", "localVersion",
                "mustShowInstallationInterstitial", "name", "remotePluginId",
                "shareContext", "source", "version",
            ),
            properties("PluginSummary"),
        )
        assertEquals(
            setOf(
                "appMetadata", "branding", "description", "distributionChannel",
                "iconAssets", "iconDarkAssets", "id", "installUrl", "isAccessible",
                "isEnabled", "labels", "logoUrl", "logoUrlDark", "name",
                "pluginDisplayNames",
            ),
            properties("AppInfo"),
        )
        assertEquals(
            setOf(
                "appTemplates", "apps", "description", "hooks", "marketplaceName",
                "marketplacePath", "mcpServers", "scheduledTasks", "shareUrl", "skills",
                "summary",
            ),
            properties("PluginDetail"),
        )
    }

    @Test
    fun appAndFilesystemChangeNotificationsRemainObservable() {
        val notifications = definitions.getJSONObject("ServerNotification").getJSONArray("oneOf")
        val methods = buildSet {
            repeat(notifications.length()) { index ->
                add(
                    notifications.getJSONObject(index)
                        .getJSONObject("properties")
                        .getJSONObject("method")
                        .getJSONArray("enum")
                        .getString(0),
                )
            }
        }
        assertTrue("app/list/updated" in methods)
        assertTrue("skills/changed" in methods)
    }

    private fun required(definition: String): Set<String> {
        val values = definitions.getJSONObject(definition).optJSONArray("required")
            ?: return emptySet()
        return (0 until values.length()).map(values::getString).toSet()
    }

    private fun properties(definition: String): Set<String> {
        val values = definitions.getJSONObject(definition).getJSONObject("properties")
        return buildSet {
            val keys = values.keys()
            while (keys.hasNext()) add(keys.next())
        }
    }

    private fun schemaFile(): File {
        val relative = "protocol/app-server/${CodexProtocolContract.APP_SERVER_VERSION}/" +
            "codex_app_server_protocol.v2.schemas.json"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val file = generateSequence(start) { it.parentFile }
            .map { root -> File(root, relative) }
            .firstOrNull(File::isFile)
        assertNotNull("Vendored ${CodexProtocolContract.APP_SERVER_VERSION} schema not found", file)
        return requireNotNull(file)
    }
}
