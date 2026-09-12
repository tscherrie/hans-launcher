package ai.hans.standard.plugins

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private const val RUNTIME_MANIFEST_SCHEMA = "hans.plugin-runtime"
private const val RUNTIME_MANIFEST_VERSION = 1
private const val MAX_RUNTIME_MANIFEST_BYTES = 256 * 1024

/** Strict, deterministic codec for the Android runtime declaration shipped by a plugin. */
internal object PluginRuntimeRequirementsCodec {
    fun decode(document: ByteArray): PluginRuntimeRequirements {
        require(document.isNotEmpty() && document.size <= MAX_RUNTIME_MANIFEST_BYTES) {
            "Plugin runtime manifest has an invalid size"
        }
        val text = document.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(document)) {
            "Plugin runtime manifest is not canonical UTF-8"
        }
        val root = JSONObject(text)
        root.requireOnly(
            setOf(
                "schema",
                "version",
                "pluginId",
                "runtimes",
                "entrypoints",
                "capabilities",
                "unsupportedHostConstraints",
            ),
        )
        require(root.getString("schema") == RUNTIME_MANIFEST_SCHEMA) {
            "Unsupported plugin runtime manifest schema"
        }
        require(root.getInt("version") == RUNTIME_MANIFEST_VERSION) {
            "Unsupported plugin runtime manifest version"
        }
        return PluginRuntimeRequirements(
            pluginId = root.getString("pluginId"),
            runtimes = root.getJSONArray("runtimes").mapObjects(::decodeRuntime),
            entrypoints = root.getJSONArray("entrypoints").mapObjects(::decodeEntrypoint),
            capabilities = root.getJSONArray("capabilities").mapObjects(::decodeCapability),
            unsupportedHostConstraints = root.getJSONArray("unsupportedHostConstraints")
                .mapStrings()
                .mapTo(linkedSetOf(), ::unsupportedConstraint),
        )
    }

    fun encode(requirements: PluginRuntimeRequirements): ByteArray {
        val root = JSONObject()
            .put("schema", RUNTIME_MANIFEST_SCHEMA)
            .put("version", RUNTIME_MANIFEST_VERSION)
            .put("pluginId", requirements.pluginId)
            .put(
                "runtimes",
                JSONArray().also { output ->
                    requirements.runtimes.sortedBy(PluginRuntimeRequirement::id).forEach { runtime ->
                        output.put(
                            JSONObject()
                                .put("id", runtime.id)
                                .put("kind", runtime.kind.wireName)
                                .put("placement", runtime.placement.wireName)
                                .put(
                                    "minimumVersionInclusive",
                                    runtime.versionRange.minimumInclusive?.toString() ?: JSONObject.NULL,
                                )
                                .put(
                                    "maximumVersionExclusive",
                                    runtime.versionRange.maximumExclusive?.toString() ?: JSONObject.NULL,
                                )
                                .put(
                                    "acceptedAbis",
                                    JSONArray(
                                        runtime.acceptedAbis
                                            .sortedBy(PluginRuntimeAbi::wireName)
                                            .map(PluginRuntimeAbi::wireName),
                                    ),
                                )
                                .put("required", runtime.required),
                        )
                    }
                },
            )
            .put(
                "entrypoints",
                JSONArray().also { output ->
                    requirements.entrypoints.sortedBy(PluginEntrypointRequirement::id).forEach {
                        output.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("runtimeRequirementId", it.runtimeRequirementId)
                                .put("kind", it.kind.wireName())
                                .put("target", it.target)
                                .put("required", it.required),
                        )
                    }
                },
            )
            .put(
                "capabilities",
                JSONArray().also { output ->
                    requirements.capabilities.sortedBy(PluginCapabilityRequirement::id).forEach {
                        output.put(JSONObject().put("id", it.id).put("required", it.required))
                    }
                },
            )
            .put(
                "unsupportedHostConstraints",
                JSONArray(
                    requirements.unsupportedHostConstraints
                        .sortedBy(PluginUnsupportedHostConstraint::wireName)
                        .map(PluginUnsupportedHostConstraint::wireName),
                ),
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_RUNTIME_MANIFEST_BYTES) { "Plugin runtime manifest is too large" }
        }
    }

    private fun decodeRuntime(value: JSONObject): PluginRuntimeRequirement {
        value.requireOnly(
            setOf(
                "id",
                "kind",
                "placement",
                "minimumVersionInclusive",
                "maximumVersionExclusive",
                "acceptedAbis",
                "required",
            ),
        )
        return PluginRuntimeRequirement(
            id = value.getString("id"),
            kind = runtimeKind(value.getString("kind")),
            placement = runtimePlacement(value.getString("placement")),
            versionRange = PluginRuntimeVersionRange(
                minimumInclusive = value.nullableString("minimumVersionInclusive")
                    ?.let(PluginRuntimeVersion::parse),
                maximumExclusive = value.nullableString("maximumVersionExclusive")
                    ?.let(PluginRuntimeVersion::parse),
            ),
            acceptedAbis = value.getJSONArray("acceptedAbis")
                .mapStrings()
                .mapTo(linkedSetOf(), ::runtimeAbi),
            required = value.getBoolean("required"),
        )
    }

    private fun decodeEntrypoint(value: JSONObject): PluginEntrypointRequirement {
        value.requireOnly(setOf("id", "runtimeRequirementId", "kind", "target", "required"))
        return PluginEntrypointRequirement(
            id = value.getString("id"),
            runtimeRequirementId = value.getString("runtimeRequirementId"),
            kind = entrypointKind(value.getString("kind")),
            target = value.getString("target"),
            required = value.getBoolean("required"),
        )
    }

    private fun decodeCapability(value: JSONObject): PluginCapabilityRequirement {
        value.requireOnly(setOf("id", "required"))
        return PluginCapabilityRequirement(value.getString("id"), value.getBoolean("required"))
    }

    private fun runtimeKind(value: String): PluginRuntimeKind =
        PluginRuntimeKind.entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown plugin runtime kind")

    private fun runtimePlacement(value: String): PluginRuntimePlacement =
        PluginRuntimePlacement.entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown plugin runtime placement")

    private fun runtimeAbi(value: String): PluginRuntimeAbi =
        PluginRuntimeAbi.entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown plugin runtime ABI")

    private fun unsupportedConstraint(value: String): PluginUnsupportedHostConstraint =
        PluginUnsupportedHostConstraint.entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown plugin host constraint")

    private fun entrypointKind(value: String): PluginEntrypointKind = when (value) {
        "relative_file" -> PluginEntrypointKind.RELATIVE_FILE
        "python_callable" -> PluginEntrypointKind.PYTHON_CALLABLE
        "dynamic_tool" -> PluginEntrypointKind.DYNAMIC_TOOL
        "mcp_method" -> PluginEntrypointKind.MCP_METHOD
        else -> throw IllegalArgumentException("Unknown plugin entrypoint kind")
    }
}

private fun PluginEntrypointKind.wireName(): String = when (this) {
    PluginEntrypointKind.RELATIVE_FILE -> "relative_file"
    PluginEntrypointKind.PYTHON_CALLABLE -> "python_callable"
    PluginEntrypointKind.DYNAMIC_TOOL -> "dynamic_tool"
    PluginEntrypointKind.MCP_METHOD -> "mcp_method"
}

private fun JSONObject.requireOnly(expected: Set<String>) {
    val actual = keys().asSequence().toSet()
    require(actual == expected) { "Unexpected plugin runtime manifest fields" }
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { getString(it) }
