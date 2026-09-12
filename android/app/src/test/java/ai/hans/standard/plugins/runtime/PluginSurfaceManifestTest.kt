package ai.hans.standard.plugins.runtime

import ai.hans.standard.plugins.PluginRuntimeKind
import ai.hans.standard.plugins.PluginRuntimePlacement
import ai.hans.standard.plugins.PluginRuntimeRequirement
import ai.hans.standard.plugins.PluginRuntimeRequirements
import ai.hans.standard.plugins.PluginUnsupportedHostConstraint
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSurfaceManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun strictManifestRoundTripsWithoutLeakingOauthHandle() {
        val manifest = manifest()
        val bytes = PluginSurfaceManifestCodec.encode(manifest)

        assertEquals(manifest, PluginSurfaceManifestCodec.decode(bytes))
        assertFalse(manifest.remoteMcpServers.single().oauthHandle.toString().contains("a".repeat(8)))
    }

    @Test
    fun executableFieldsPlaintextTokensAndInsecureEndpointsAreRejected() {
        val root = JSONObject(PluginSurfaceManifestCodec.encode(manifest()).toString(Charsets.UTF_8))
        root.put("command", "sh -c whoami")
        assertFails { PluginSurfaceManifestCodec.decode(root.toString().toByteArray()) }

        assertFails {
            manifest().copy(
                remoteMcpServers = listOf(
                    manifest().remoteMcpServers.single().copy(endpoint = "http://example.com/mcp"),
                ),
            )
        }
        assertFails { OAuthCredentialHandle("Bearer definitely-a-token") }
    }

    @Test
    fun loaderRejectsBoundaryEscapeMismatchAndSymlink() {
        val privateRoot = temporaryFolder.newFolder("private")
        val plugin = File(privateRoot, "plugin").apply { mkdir() }
        File(plugin, PluginSurfaceManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginSurfaceManifestCodec.encode(manifest()))
        val loader = PluginSurfaceManifestLoader(listOf(privateRoot))
        assertTrue(loader.load("sample", plugin) is PluginSurfaceManifestLoadResult.Declared)
        assertTrue(loader.load("different", plugin) is PluginSurfaceManifestLoadResult.Rejected)

        val outside = temporaryFolder.newFolder("outside")
        assertTrue(loader.load("sample", outside) is PluginSurfaceManifestLoadResult.Rejected)

        val linkedPlugin = File(privateRoot, "linked").apply { mkdir() }
        val target = temporaryFolder.newFile("surface.json").apply {
            writeBytes(PluginSurfaceManifestCodec.encode(manifest()))
        }
        val link = File(linkedPlugin, PluginSurfaceManifestLoader.MANIFEST_NAME).toPath()
        runCatching { java.nio.file.Files.createSymbolicLink(link, target.toPath()) }
        if (java.nio.file.Files.isSymbolicLink(link)) {
            assertTrue(loader.load("sample", linkedPlugin) is PluginSurfaceManifestLoadResult.Rejected)
        }
    }

    @Test
    fun rootFreePolicyExplicitlyRejectsEveryForbiddenHostSurface() {
        val requirements = PluginRuntimeRequirements(
            pluginId = "sample",
            runtimes = listOf(
                PluginRuntimeRequirement("local-mcp", PluginRuntimeKind.MCP_LOCAL_PROCESS, PluginRuntimePlacement.LOCAL),
                PluginRuntimeRequirement("shell", PluginRuntimeKind.POSIX_SHELL, PluginRuntimePlacement.LOCAL),
                PluginRuntimeRequirement("desktop", PluginRuntimeKind.DESKTOP_UI, PluginRuntimePlacement.LOCAL),
            ),
            entrypoints = emptyList(),
            capabilities = emptyList(),
            unsupportedHostConstraints = setOf(
                PluginUnsupportedHostConstraint.ROOT_ACCESS,
                PluginUnsupportedHostConstraint.WRITABLE_NATIVE_CODE,
            ),
        )

        assertEquals(
            listOf(
                "unsupported_runtime:desktop_ui:desktop",
                "unsupported_runtime:mcp_local_process:local-mcp",
                "unsupported_runtime:posix_shell:shell",
                "unsupported_host:root_access",
                "unsupported_host:writable_native_code",
            ),
            StandardRootFreePluginPolicy.rejectionCodes(requirements),
        )
    }

    private fun manifest() = PluginSurfaceManifest(
        pluginId = "sample",
        nativeSkills = listOf(NativeSkillRequirement("skill", "Sample skill", true)),
        nativeHooks = listOf(NativeHookRequirement("native-hook", "native.key", "turn.completed", false)),
        androidTools = listOf(AndroidToolRequirement("open", "phone.open", true)),
        hooks = listOf(
            DeclarativeHookRequirement(
                "network-hook",
                HansHookEvent.NETWORK_RESTORED,
                "network.refresh",
                false,
            ),
        ),
        remoteMcpServers = listOf(
            RemoteMcpRequirement(
                id = "tasks",
                endpoint = "https://mcp.example.com/v1",
                oauthHandle = OAuthCredentialHandle("a".repeat(64)),
                allowedTools = setOf("tasks/list", "tasks.create"),
                required = true,
                requestTimeoutMillis = 30_000,
                maxResponseBytes = 256 * 1024,
            ),
        ),
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
