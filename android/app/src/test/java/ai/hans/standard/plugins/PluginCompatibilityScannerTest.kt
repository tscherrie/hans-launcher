package ai.hans.standard.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCompatibilityScannerTest {
    private val scanner = PluginCompatibilityScanner()

    @Test
    fun lifecycleProgressIsExplicitAndDiagnosticsAreExact() {
        val requirements = requiredPythonPlugin()

        val discovered = scanner.scan(
            requirements,
            PluginCompatibilityObservation(false, false, false),
        )
        assertEquals(PluginCompatibilityLifecycle.DISCOVERED, discovered.lifecycle)
        assertEquals(
            listOf(
                "info|install_required|garage|plugin_is_discovered_but_not_installed",
            ),
            discovered.diagnosticTexts,
        )

        val installed = scanner.scan(
            requirements,
            PluginCompatibilityObservation(true, false, false),
        )
        assertEquals(PluginCompatibilityLifecycle.INSTALLED, installed.lifecycle)
        assertEquals(
            listOf(
                "info|resolution_pending|garage|installed_requirements_are_not_resolved",
            ),
            installed.diagnosticTexts,
        )

        val resolved = scanner.scan(
            requirements,
            PluginCompatibilityObservation(true, true, false),
        )
        assertEquals(PluginCompatibilityLifecycle.RESOLVED, resolved.lifecycle)
        assertEquals(
            listOf(
                "info|probe_pending|garage|" +
                    "requirements_are_resolved_but_runtime_probes_are_pending",
            ),
            resolved.diagnosticTexts,
        )
    }

    @Test
    fun compatibleLocalPythonPluginBecomesRunnable() {
        val result = scanner.scan(requiredPythonPlugin(), readyObservation())

        assertEquals(PluginCompatibilityLifecycle.RUNNABLE, result.lifecycle)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(PluginRuntimeLocation.LOCAL, result.runtimeBindings.single().location)
        assertEquals("3.14.7", result.runtimeBindings.single().version.toString())
    }

    @Test
    fun eitherPlacementSelectsCompatibleRemoteRuntimeDeterministically() {
        val requirements = requiredPythonPlugin(
            runtime = pythonRequirement(placement = PluginRuntimePlacement.EITHER),
        )
        val observation = readyObservation(
            runtimes = listOf(
                pythonProbe("3.13.9", PluginRuntimeLocation.LOCAL),
                pythonProbe(
                    "3.14.7",
                    PluginRuntimeLocation.REMOTE,
                    PluginRuntimeAbi.LINUX_X86_64,
                ),
            ),
        )
        val remoteCompatible = requirements.copy(
            runtimes = listOf(
                requirements.runtimes.single().copy(
                    acceptedAbis = setOf(
                        PluginRuntimeAbi.ANDROID_ARM64_V8A,
                        PluginRuntimeAbi.LINUX_X86_64,
                    ),
                ),
            ),
        )

        val result = scanner.scan(remoteCompatible, observation)

        assertEquals(PluginCompatibilityLifecycle.RUNNABLE, result.lifecycle)
        assertEquals(PluginRuntimeLocation.REMOTE, result.runtimeBindings.single().location)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun newestCompatibleRuntimeWinsWithoutLexicalVersionOrdering() {
        val requirements = requiredPythonPlugin(
            runtime = pythonRequirement(placement = PluginRuntimePlacement.REMOTE).copy(
                acceptedAbis = setOf(
                    PluginRuntimeAbi.LINUX_ARM64,
                    PluginRuntimeAbi.LINUX_X86_64,
                ),
            ),
        )
        val result = scanner.scan(
            requirements,
            readyObservation(
                runtimes = listOf(
                    pythonProbe("3.14.9", PluginRuntimeLocation.REMOTE, PluginRuntimeAbi.LINUX_ARM64),
                    pythonProbe("3.14.10", PluginRuntimeLocation.REMOTE, PluginRuntimeAbi.LINUX_X86_64),
                ),
            ),
        )

        assertEquals("3.14.10", result.runtimeBindings.single().version.toString())
    }

    @Test
    fun optionalMissingRequirementsProduceStableDegradedResult() {
        val requirements = requiredPythonPlugin().copy(
            runtimes = requiredPythonPlugin().runtimes + PluginRuntimeRequirement(
                id = "desktop-helper",
                kind = PluginRuntimeKind.DESKTOP_UI,
                placement = PluginRuntimePlacement.REMOTE,
                required = false,
            ),
            capabilities = requiredPythonPlugin().capabilities +
                PluginCapabilityRequirement("phone.optional_sensor", required = false),
        )

        val result = scanner.scan(requirements, readyObservation())

        assertEquals(PluginCompatibilityLifecycle.DEGRADED, result.lifecycle)
        assertEquals(
            listOf(
                "degraded|capability_unavailable|phone.optional_sensor|" +
                    "capability=phone.optional_sensor",
                "degraded|runtime_unavailable|desktop-helper|" +
                    "kind=desktop_ui,placement=remote",
            ),
            result.diagnosticTexts,
        )
    }

    @Test
    fun rootWritableNativeCodeAndSdistAreAlwaysIncompatible() {
        val requirements = requiredPythonPlugin().copy(
            unsupportedHostConstraints = setOf(
                PluginUnsupportedHostConstraint.WRITABLE_NATIVE_CODE,
                PluginUnsupportedHostConstraint.ROOT_ACCESS,
                PluginUnsupportedHostConstraint.SOURCE_DISTRIBUTION_BUILD,
            ),
        )

        val result = scanner.scan(
            requirements,
            PluginCompatibilityObservation(false, false, false),
        )

        assertEquals(PluginCompatibilityLifecycle.INCOMPATIBLE, result.lifecycle)
        assertEquals(
            listOf(
                "blocking|root_required|garage|hans_standard_never_provides_root_access",
                "blocking|source_distribution_required|garage|" +
                    "hans_standard_never_provides_source_distribution_build",
                "blocking|writable_native_code_required|garage|" +
                    "hans_standard_never_provides_writable_native_code",
            ),
            result.diagnosticTexts,
        )
    }

    @Test
    fun requiredRuntimeVersionAbiEntrypointAndCapabilityFailuresAreBlocking() {
        val versionMismatch = scanner.scan(
            requiredPythonPlugin(),
            readyObservation(
                runtimes = listOf(pythonProbe("3.13.9", PluginRuntimeLocation.LOCAL)),
            ),
        )
        assertEquals(PluginCompatibilityLifecycle.INCOMPATIBLE, versionMismatch.lifecycle)
        assertEquals(
            listOf(
                "blocking|runtime_version_mismatch|python|" +
                    "required=>=3.14,<3.15,observed=3.13.9@local",
            ),
            versionMismatch.diagnosticTexts,
        )

        val missingEntrypointAndCapability = scanner.scan(
            requiredPythonPlugin(),
            readyObservation(
                resolvedEntrypoints = emptySet(),
                capabilities = emptySet(),
            ),
        )
        assertEquals(
            listOf(
                "blocking|capability_unavailable|phone.read_location|" +
                    "capability=phone.read_location",
                "blocking|entrypoint_unresolved|main|runtime=python",
            ),
            missingEntrypointAndCapability.diagnosticTexts,
        )

        val abiMismatch = scanner.scan(
            requiredPythonPlugin(),
            readyObservation(
                runtimes = listOf(
                    pythonProbe(
                        "3.14.7",
                        PluginRuntimeLocation.LOCAL,
                        PluginRuntimeAbi.ANDROID_X86_64,
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                "blocking|runtime_abi_mismatch|python|" +
                    "required=android_arm64_v8a,observed=android_x86_64@local",
            ),
            abiMismatch.diagnosticTexts,
        )
    }

    @Test
    fun observationRejectsImpossibleLifecycleAndDuplicateRuntimeProbes() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginCompatibilityObservation(false, true, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCompatibilityObservation(true, false, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginCompatibilityObservation(
                installed = true,
                dependenciesResolved = true,
                probesComplete = true,
                runtimes = listOf(
                    pythonProbe("3.14.7", PluginRuntimeLocation.LOCAL),
                    pythonProbe("3.14.6", PluginRuntimeLocation.LOCAL),
                ),
            )
        }
    }

    private fun requiredPythonPlugin(
        runtime: PluginRuntimeRequirement = pythonRequirement(),
    ): PluginRuntimeRequirements = PluginRuntimeRequirements(
        pluginId = "garage",
        runtimes = listOf(runtime),
        entrypoints = listOf(
            PluginEntrypointRequirement(
                id = "main",
                runtimeRequirementId = runtime.id,
                kind = PluginEntrypointKind.RELATIVE_FILE,
                target = "scripts/garage.py",
            ),
        ),
        capabilities = listOf(PluginCapabilityRequirement("phone.read_location")),
    )

    private fun pythonRequirement(
        placement: PluginRuntimePlacement = PluginRuntimePlacement.LOCAL,
    ): PluginRuntimeRequirement = PluginRuntimeRequirement(
        id = "python",
        kind = PluginRuntimeKind.EMBEDDED_PYTHON,
        placement = placement,
        versionRange = PluginRuntimeVersionRange(
            PluginRuntimeVersion.parse("3.14"),
            PluginRuntimeVersion.parse("3.15"),
        ),
        acceptedAbis = setOf(PluginRuntimeAbi.ANDROID_ARM64_V8A),
    )

    private fun readyObservation(
        runtimes: List<PluginRuntimeProbe> = listOf(
            pythonProbe("3.14.7", PluginRuntimeLocation.LOCAL),
        ),
        resolvedEntrypoints: Set<String> = setOf("main"),
        capabilities: Set<String> = setOf("phone.read_location"),
    ): PluginCompatibilityObservation = PluginCompatibilityObservation(
        installed = true,
        dependenciesResolved = true,
        probesComplete = true,
        runtimes = runtimes,
        resolvedEntrypointIds = resolvedEntrypoints,
        availableCapabilityIds = capabilities,
    )

    private fun pythonProbe(
        version: String,
        location: PluginRuntimeLocation,
        abi: PluginRuntimeAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
    ): PluginRuntimeProbe = PluginRuntimeProbe(
        kind = PluginRuntimeKind.EMBEDDED_PYTHON,
        location = location,
        version = PluginRuntimeVersion.parse(version),
        abi = abi,
        readiness = PluginRuntimeReadiness.READY,
    )
}
