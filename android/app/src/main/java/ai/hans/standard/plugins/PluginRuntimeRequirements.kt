package ai.hans.standard.plugins

private const val MAX_PLUGIN_ID_CHARS = 128
private const val MAX_REQUIREMENT_ID_CHARS = 64
private const val MAX_CAPABILITY_ID_CHARS = 128
private const val MAX_ENTRYPOINT_TARGET_CHARS = 256
private const val MAX_RUNTIME_REQUIREMENTS = 16
private const val MAX_ENTRYPOINT_REQUIREMENTS = 64
private const val MAX_CAPABILITY_REQUIREMENTS = 128

private val PLUGIN_ID = Regex("[a-z][a-z0-9._-]{0,127}")
private val REQUIREMENT_ID = Regex("[a-z][a-z0-9._-]{0,63}")
private val CAPABILITY_ID = Regex("[a-z][a-z0-9._:-]{0,127}")
private val PYTHON_CALLABLE = Regex(
    "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*" +
        "(?::[A-Za-z_][A-Za-z0-9_]*)?",
)
private val LOGICAL_ENTRYPOINT = Regex("[a-z][a-z0-9._/-]{0,255}")
private val VERSION = Regex(
    "(?:0|[1-9][0-9]{0,5})(?:\\.(?:0|[1-9][0-9]{0,5})){0,3}",
)
private val RELATIVE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

/** Runtime families a plugin may explicitly depend on. */
internal enum class PluginRuntimeKind(val wireName: String) {
    INSTRUCTION_ONLY("instruction_only"),
    ANDROID_CAPABILITY("android_capability"),
    CODE_MODE_JAVASCRIPT("code_mode_javascript"),
    /** A real Node.js runtime, deliberately distinct from Codex's V8 Code Mode host. */
    NODE_JS("node_js"),
    EMBEDDED_PYTHON("embedded_python"),
    GIT("git"),
    HTTP_CLIENT("http_client"),
    MCP_SERVER("mcp_server"),
    MCP_REMOTE_HTTP("mcp_remote_http"),
    MCP_LOCAL_PROCESS("mcp_local_process"),
    POSIX_SHELL("posix_shell"),
    SIGNED_NATIVE_EXECUTABLE("signed_native_executable"),
    DESKTOP_UI("desktop_ui"),
}

/** Where Hans may satisfy a runtime requirement. */
internal enum class PluginRuntimePlacement(val wireName: String) {
    LOCAL("local"),
    REMOTE("remote"),
    EITHER("either"),
}

/** Concrete location reported by a runtime probe. */
internal enum class PluginRuntimeLocation(val wireName: String) {
    LOCAL("local"),
    REMOTE("remote"),
}

internal enum class PluginRuntimeAbi(val wireName: String) {
    ANDROID_ARM64_V8A("android_arm64_v8a"),
    ANDROID_X86_64("android_x86_64"),
    LINUX_ARM64("linux_arm64"),
    LINUX_X86_64("linux_x86_64"),
    PLATFORM_INDEPENDENT("platform_independent"),
}

internal enum class PluginEntrypointKind {
    RELATIVE_FILE,
    PYTHON_CALLABLE,
    DYNAMIC_TOOL,
    MCP_METHOD,
}

/**
 * Requirements that Hans Standard deliberately cannot satisfy. They are data used to explain an
 * incompatible plugin, not features or escape hatches implemented by Standard.
 */
internal enum class PluginUnsupportedHostConstraint(val wireName: String) {
    ROOT_ACCESS("root_access"),
    CHROOT("chroot"),
    SYSTEM_UID("system_uid"),
    PLATFORM_SIGNATURE("platform_signature"),
    WRITABLE_NATIVE_CODE("writable_native_code"),
    SOURCE_DISTRIBUTION_BUILD("source_distribution_build"),
}

internal class PluginRuntimeVersion private constructor(
    private val components: List<Int>,
    private val canonical: String,
) : Comparable<PluginRuntimeVersion> {
    override fun compareTo(other: PluginRuntimeVersion): Int {
        repeat(maxOf(components.size, other.components.size)) { index ->
            val comparison = components.getOrElse(index) { 0 }
                .compareTo(other.components.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = canonical

    override fun equals(other: Any?): Boolean =
        other is PluginRuntimeVersion && compareTo(other) == 0

    override fun hashCode(): Int {
        val normalized = components.toMutableList()
        while (normalized.size > 1 && normalized.last() == 0) normalized.removeAt(normalized.lastIndex)
        return normalized.hashCode()
    }

    companion object {
        fun parse(value: String): PluginRuntimeVersion {
            require(value.length <= 32 && VERSION.matches(value)) {
                "Runtime version must contain one to four bounded numeric components"
            }
            val components = value.split('.').map(String::toInt)
            return PluginRuntimeVersion(components, components.joinToString("."))
        }
    }
}

internal data class PluginRuntimeVersionRange(
    val minimumInclusive: PluginRuntimeVersion? = null,
    val maximumExclusive: PluginRuntimeVersion? = null,
) {
    init {
        require(
            minimumInclusive == null || maximumExclusive == null ||
                minimumInclusive < maximumExclusive,
        ) { "Runtime version range must be increasing" }
    }

    fun contains(version: PluginRuntimeVersion): Boolean =
        (minimumInclusive == null || version >= minimumInclusive) &&
            (maximumExclusive == null || version < maximumExclusive)

    fun describe(): String = buildList {
        minimumInclusive?.let { add(">=$it") }
        maximumExclusive?.let { add("<$it") }
    }.joinToString(",").ifEmpty { "*" }
}

internal data class PluginRuntimeRequirement(
    val id: String,
    val kind: PluginRuntimeKind,
    val placement: PluginRuntimePlacement,
    val versionRange: PluginRuntimeVersionRange = PluginRuntimeVersionRange(),
    val acceptedAbis: Set<PluginRuntimeAbi> = emptySet(),
    val required: Boolean = true,
) {
    init {
        requireRequirementId(id, "Runtime requirement id")
        require(acceptedAbis.size <= PluginRuntimeAbi.values().size) {
            "Runtime ABI set is too large"
        }
    }
}

internal data class PluginEntrypointRequirement(
    val id: String,
    val runtimeRequirementId: String,
    val kind: PluginEntrypointKind,
    val target: String,
    val required: Boolean = true,
) {
    init {
        requireRequirementId(id, "Entrypoint id")
        requireRequirementId(runtimeRequirementId, "Entrypoint runtime requirement id")
        require(target.isNotBlank() && target.length <= MAX_ENTRYPOINT_TARGET_CHARS) {
            "Entrypoint target must be non-blank and bounded"
        }
        require(target.none(Char::isISOControl)) {
            "Entrypoint target contains control characters"
        }
        when (kind) {
            PluginEntrypointKind.RELATIVE_FILE -> requireSafeRelativeFile(target)
            PluginEntrypointKind.PYTHON_CALLABLE -> require(PYTHON_CALLABLE.matches(target)) {
                "Python entrypoint must be a module or module:callable"
            }
            PluginEntrypointKind.DYNAMIC_TOOL,
            PluginEntrypointKind.MCP_METHOD,
            -> require(
                LOGICAL_ENTRYPOINT.matches(target) &&
                    !target.contains("..") &&
                    !target.contains("//"),
            ) { "Logical entrypoint target is invalid" }
        }
    }
}

internal data class PluginCapabilityRequirement(
    val id: String,
    val required: Boolean = true,
) {
    init {
        requireCapabilityId(id)
    }
}

/** Strict, bounded declaration consumed by the compatibility scanner. */
internal data class PluginRuntimeRequirements(
    val pluginId: String,
    val runtimes: List<PluginRuntimeRequirement>,
    val entrypoints: List<PluginEntrypointRequirement>,
    val capabilities: List<PluginCapabilityRequirement>,
    val unsupportedHostConstraints: Set<PluginUnsupportedHostConstraint> = emptySet(),
) {
    init {
        require(pluginId.length <= MAX_PLUGIN_ID_CHARS && PLUGIN_ID.matches(pluginId)) {
            "Plugin id is invalid"
        }
        require(runtimes.size <= MAX_RUNTIME_REQUIREMENTS) {
            "Plugin declares too many runtime requirements"
        }
        require(entrypoints.size <= MAX_ENTRYPOINT_REQUIREMENTS) {
            "Plugin declares too many entrypoint requirements"
        }
        require(capabilities.size <= MAX_CAPABILITY_REQUIREMENTS) {
            "Plugin declares too many capability requirements"
        }
        require(unsupportedHostConstraints.size <= PluginUnsupportedHostConstraint.values().size) {
            "Plugin host constraint set is too large"
        }
        requireUnique(runtimes.map(PluginRuntimeRequirement::id), "runtime requirement")
        requireUnique(entrypoints.map(PluginEntrypointRequirement::id), "entrypoint")
        requireUnique(capabilities.map(PluginCapabilityRequirement::id), "capability")

        val runtimesById = runtimes.associateBy(PluginRuntimeRequirement::id)
        entrypoints.forEach { entrypoint ->
            val runtime = requireNotNull(runtimesById[entrypoint.runtimeRequirementId]) {
                "Entrypoint references an unknown runtime requirement"
            }
            require(!entrypoint.required || runtime.required) {
                "A required entrypoint cannot depend on an optional runtime"
            }
        }
    }
}

private fun requireRequirementId(value: String, label: String) {
    require(value.length <= MAX_REQUIREMENT_ID_CHARS && REQUIREMENT_ID.matches(value)) {
        "$label is invalid"
    }
}

private fun requireCapabilityId(value: String) {
    require(value.length <= MAX_CAPABILITY_ID_CHARS && CAPABILITY_ID.matches(value)) {
        "Capability id is invalid"
    }
}

private fun requireSafeRelativeFile(value: String) {
    require(!value.startsWith('/') && '\\' !in value && ':' !in value) {
        "Entrypoint file must be a normalized relative path"
    }
    val segments = value.split('/')
    require(
        segments.isNotEmpty() &&
            segments.all { it != "." && it != ".." && RELATIVE_PATH_SEGMENT.matches(it) },
    ) { "Entrypoint file must be a normalized relative path" }
}

private fun requireUnique(values: List<String>, label: String) {
    require(values.distinct().size == values.size) { "Duplicate $label id" }
}
