package ai.hans.standard.plugins

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRuntimeRequirementsCodecTest {
    @Test
    fun roundTripIsDeterministicAndPreservesNodeAsDistinctRuntime() {
        val requirements = PluginRuntimeRequirements(
            pluginId = "android-work",
            runtimes = listOf(
                PluginRuntimeRequirement(
                    id = "node",
                    kind = PluginRuntimeKind.NODE_JS,
                    placement = PluginRuntimePlacement.REMOTE,
                    versionRange = PluginRuntimeVersionRange(
                        PluginRuntimeVersion.parse("24.0"),
                        PluginRuntimeVersion.parse("25.0"),
                    ),
                    acceptedAbis = setOf(PluginRuntimeAbi.LINUX_X86_64),
                    required = false,
                ),
                PluginRuntimeRequirement(
                    id = "python",
                    kind = PluginRuntimeKind.EMBEDDED_PYTHON,
                    placement = PluginRuntimePlacement.LOCAL,
                    versionRange = PluginRuntimeVersionRange(
                        PluginRuntimeVersion.parse("3.14"),
                        PluginRuntimeVersion.parse("3.15"),
                    ),
                    acceptedAbis = setOf(PluginRuntimeAbi.ANDROID_ARM64_V8A),
                ),
            ),
            entrypoints = listOf(
                PluginEntrypointRequirement(
                    id = "main",
                    runtimeRequirementId = "python",
                    kind = PluginEntrypointKind.PYTHON_CALLABLE,
                    target = "plugin.main:run",
                ),
            ),
            capabilities = listOf(PluginCapabilityRequirement("phone.read_location")),
            unsupportedHostConstraints = setOf(PluginUnsupportedHostConstraint.ROOT_ACCESS),
        )

        val first = PluginRuntimeRequirementsCodec.encode(requirements)
        val decoded = PluginRuntimeRequirementsCodec.decode(first)
        val second = PluginRuntimeRequirementsCodec.encode(decoded)

        assertArrayEquals(first, second)
        assertEquals(PluginRuntimeKind.NODE_JS, decoded.runtimes.first { it.id == "node" }.kind)
        assertEquals(setOf(PluginUnsupportedHostConstraint.ROOT_ACCESS), decoded.unsupportedHostConstraints)
    }

    @Test
    fun unknownFieldsKindsAndNonUtf8AreRejected() {
        val valid = PluginRuntimeRequirementsCodec.encode(emptyRequirements())
        val withUnknown = JSONObject(valid.toString(StandardCharsets.UTF_8)).put("future", true)
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirementsCodec.decode(withUnknown.toString().toByteArray())
        }

        val unknownKind = JSONObject(valid.toString(StandardCharsets.UTF_8)).apply {
            getJSONArray("runtimes").put(
                JSONObject()
                    .put("id", "runtime")
                    .put("kind", "magic_shell")
                    .put("placement", "local")
                    .put("minimumVersionInclusive", JSONObject.NULL)
                    .put("maximumVersionExclusive", JSONObject.NULL)
                    .put("acceptedAbis", org.json.JSONArray())
                    .put("required", true),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirementsCodec.decode(unknownKind.toString().toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirementsCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun malformedLifecycleReferencesStillFailAtTheModelBoundary() {
        val root = JSONObject(
            PluginRuntimeRequirementsCodec.encode(emptyRequirements()).toString(StandardCharsets.UTF_8),
        )
        root.getJSONArray("entrypoints").put(
            JSONObject()
                .put("id", "main")
                .put("runtimeRequirementId", "missing")
                .put("kind", "relative_file")
                .put("target", "main.py")
                .put("required", true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirementsCodec.decode(root.toString().toByteArray())
        }
    }

    private fun emptyRequirements() = PluginRuntimeRequirements(
        pluginId = "empty",
        runtimes = emptyList(),
        entrypoints = emptyList(),
        capabilities = emptyList(),
    )
}
