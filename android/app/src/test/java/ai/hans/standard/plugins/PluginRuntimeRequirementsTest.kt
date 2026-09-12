package ai.hans.standard.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRuntimeRequirementsTest {
    @Test
    fun versionRangesAreNumericBoundedAndCanonical() {
        val minimum = PluginRuntimeVersion.parse("3.14")
        val maximum = PluginRuntimeVersion.parse("3.15.0")
        val range = PluginRuntimeVersionRange(minimum, maximum)

        assertEquals(">=3.14,<3.15.0", range.describe())
        assertEquals(true, range.contains(PluginRuntimeVersion.parse("3.14.7")))
        assertEquals(false, range.contains(PluginRuntimeVersion.parse("3.15")))
        assertEquals(PluginRuntimeVersion.parse("3.14"), PluginRuntimeVersion.parse("3.14.0"))
        assertEquals(
            PluginRuntimeVersion.parse("3.14").hashCode(),
            PluginRuntimeVersion.parse("3.14.0").hashCode(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeVersion.parse("03.14")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeVersion.parse("3.14.0.1.2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeVersionRange(maximum, minimum)
        }
    }

    @Test
    fun declarationPinsRuntimeAbiEntrypointCapabilityAndPlacement() {
        val declaration = validRequirements()

        assertEquals("garage", declaration.pluginId)
        assertEquals(PluginRuntimeKind.EMBEDDED_PYTHON, declaration.runtimes.single().kind)
        assertEquals(PluginRuntimePlacement.LOCAL, declaration.runtimes.single().placement)
        assertEquals(
            setOf(PluginRuntimeAbi.ANDROID_ARM64_V8A),
            declaration.runtimes.single().acceptedAbis,
        )
        assertEquals("garage.runtime:main", declaration.entrypoints.single().target)
        assertEquals("phone.read_location", declaration.capabilities.single().id)
    }

    @Test
    fun declarationRejectsUnknownRuntimeDuplicateIdsAndUnsafePaths() {
        val runtime = pythonRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirements(
                pluginId = "garage",
                runtimes = listOf(runtime, runtime),
                entrypoints = emptyList(),
                capabilities = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirements(
                pluginId = "garage",
                runtimes = listOf(runtime),
                entrypoints = listOf(
                    PluginEntrypointRequirement(
                        id = "main",
                        runtimeRequirementId = "missing-runtime",
                        kind = PluginEntrypointKind.RELATIVE_FILE,
                        target = "scripts/main.py",
                    ),
                ),
                capabilities = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginEntrypointRequirement(
                id = "main",
                runtimeRequirementId = "python",
                kind = PluginEntrypointKind.RELATIVE_FILE,
                target = "../outside.py",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginEntrypointRequirement(
                id = "main",
                runtimeRequirementId = "python",
                kind = PluginEntrypointKind.PYTHON_CALLABLE,
                target = "garage.runtime;delete_all",
            )
        }
    }

    @Test
    fun requiredEntrypointCannotHideBehindOptionalRuntime() {
        val optionalRuntime = pythonRuntime().copy(required = false)
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirements(
                pluginId = "garage",
                runtimes = listOf(optionalRuntime),
                entrypoints = listOf(
                    PluginEntrypointRequirement(
                        id = "main",
                        runtimeRequirementId = "python",
                        kind = PluginEntrypointKind.PYTHON_CALLABLE,
                        target = "garage.runtime:main",
                        required = true,
                    ),
                ),
                capabilities = emptyList(),
            )
        }
    }

    @Test
    fun declarationRejectsUnboundedAndNonCanonicalInput() {
        assertThrows(IllegalArgumentException::class.java) {
            validRequirements().copy(pluginId = "Garage Plugin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeRequirements(
                pluginId = "many-runtimes",
                runtimes = (0..16).map { index ->
                    pythonRuntime().copy(id = "python-$index")
                },
                entrypoints = emptyList(),
                capabilities = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCapabilityRequirement("Phone.Location")
        }
    }

    private fun validRequirements(): PluginRuntimeRequirements = PluginRuntimeRequirements(
        pluginId = "garage",
        runtimes = listOf(pythonRuntime()),
        entrypoints = listOf(
            PluginEntrypointRequirement(
                id = "main",
                runtimeRequirementId = "python",
                kind = PluginEntrypointKind.PYTHON_CALLABLE,
                target = "garage.runtime:main",
            ),
        ),
        capabilities = listOf(PluginCapabilityRequirement("phone.read_location")),
    )

    private fun pythonRuntime(): PluginRuntimeRequirement = PluginRuntimeRequirement(
        id = "python",
        kind = PluginRuntimeKind.EMBEDDED_PYTHON,
        placement = PluginRuntimePlacement.LOCAL,
        versionRange = PluginRuntimeVersionRange(
            minimumInclusive = PluginRuntimeVersion.parse("3.14"),
            maximumExclusive = PluginRuntimeVersion.parse("3.15"),
        ),
        acceptedAbis = setOf(PluginRuntimeAbi.ANDROID_ARM64_V8A),
    )
}
