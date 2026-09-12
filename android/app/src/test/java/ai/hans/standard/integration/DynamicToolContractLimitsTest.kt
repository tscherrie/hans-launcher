package ai.hans.standard.integration

import ai.hans.standard.codex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DynamicToolContractLimitsTest {
    @Test
    fun fullBuiltInCatalogIncludingFilesCanBePublishedAndEncodedForThreadStart() {
        // Match the built-in contributors in HansApplication and AndroidCodexSessionHost.
        val specs = listOf(
            ai.hans.standard.phone.tools.AndroidDynamicToolCatalog.namespace,
            ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolCatalog.namespace,
            ai.hans.standard.phone.notifications.NotificationInboxDynamicToolCatalog.namespace,
            ai.hans.standard.phone.notifications.facts.NotificationFactDynamicToolCatalog.namespace,
            ai.hans.standard.profile.UserProfileDynamicToolCatalog.namespace,
            ai.hans.standard.phone.publicapi.PublicPhoneDynamicToolCatalog.namespace,
            ai.hans.standard.automations.HansAutomationDynamicToolCatalog.namespace,
            ai.hans.standard.workspace.WorkspaceDynamicToolCatalog.workspaceNamespace,
            ai.hans.standard.workspace.WorkspaceDynamicToolCatalog.artifactNamespace,
            ai.hans.standard.files.FileDynamicToolCatalog.namespace,
            ai.hans.standard.work.WorkUtilityDynamicToolCatalog.namespace,
            checkNotNull(ai.hans.standard.git.GitDynamicToolCatalog.namespace(
                ai.hans.standard.git.GitDynamicReadiness(true, false,
                    ai.hans.standard.git.GitTransportReadiness(true, false)))),
            ai.hans.standard.runtime.python.PythonDynamicToolCatalog.namespace,
            ai.hans.standard.browser.BrowserDynamicToolCatalog.namespace(
                ai.hans.standard.browser.VisibleBrowserReadiness.UNAVAILABLE),
            ai.hans.standard.documents.DocumentDynamicToolCatalog.namespace,
            ai.hans.standard.setup.HansSetupDynamicToolCatalog.namespace,
            ai.hans.standard.phone.display.Mp01DisplayDynamicToolCatalog.namespace,
        )
        assertEquals(17, specs.size)
        assertEquals(17, encodePublished(specs).length())
    }

    @Test
    fun publicationAndWireEncodingAcceptTheSameNamespaceBoundary() {
        assertEquals(256, encodePublished(namespaces(256)).length())
    }

    @Test
    fun publicationAndWireEncodingBothRejectTooManyNamespaces() {
        val specs = namespaces(257)
        assertThrows(IllegalArgumentException::class.java) { publish(specs) }
        assertThrows(IllegalArgumentException::class.java) { encode(specs) }
    }

    @Test
    fun publicationAndWireEncodingBothRejectTooManyFunctions() {
        val specs = namespaces(65).map { namespace -> namespace.copy(
            tools = List(64) { namespace.tools.single().copy(name = "tool_$it") }) }
        assertThrows(IllegalArgumentException::class.java) { publish(specs) }
        assertThrows(IllegalArgumentException::class.java) { encode(specs) }
    }

    private fun namespaces(count: Int) = List(count) { index ->
        DynamicToolNamespaceSpec("namespace_$index", "Test namespace", listOf(
            DynamicToolFunctionSpec("probe", "Test tool", """{"type":"object"}""")))
    }

    private fun publish(specs: List<DynamicToolNamespaceSpec>): RevisionedDynamicToolSnapshot {
        val executor = object : DynamicToolExecutor {
            override val specs = specs
            override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) =
                error("Catalog test must not execute tools")
            override fun failureResult(call: DynamicToolCallParams, code: String): DynamicToolExecutionResult =
                error("Catalog test must not execute tools")
        }
        return RevisionedDynamicToolSnapshot.create(listOf(
            DynamicToolContributor(executor, DynamicToolPlacement.BACKGROUND_ALLOWED)))
    }

    private fun encodePublished(specs: List<DynamicToolNamespaceSpec>) = encode(publish(specs).interactiveSpecs)

    private fun encode(specs: List<DynamicToolNamespaceSpec>) = JSONObject(AppServerRequests.threadStart(
        RequestId.Number(1), DispatchOptions.DEFAULT, dynamicTools = specs).json)
        .getJSONObject("params").getJSONArray("dynamicTools")
}
