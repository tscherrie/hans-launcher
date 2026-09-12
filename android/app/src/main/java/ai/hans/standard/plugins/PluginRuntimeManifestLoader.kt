package ai.hans.standard.plugins

import java.io.File
import java.nio.file.Files

internal sealed interface PluginRuntimeManifestResult {
    data object NotDeclared : PluginRuntimeManifestResult

    data class Declared(
        val requirements: PluginRuntimeRequirements,
        val sourceRoot: File,
    ) : PluginRuntimeManifestResult

    data class Rejected(val reason: String) : PluginRuntimeManifestResult
}

/**
 * Loads the optional Hans runtime extension of a local Codex plugin.
 *
 * Standard App Server components remain usable without this file. When it exists, malformed,
 * cross-plugin or boundary-escaping metadata is a hard rejection rather than an instruction-only
 * fallback. This prevents a plugin from hiding executable requirements behind parse failures.
 */
internal class PluginRuntimeManifestLoader(
    allowedSourceRoots: List<File>,
) {
    private val allowedRoots = allowedSourceRoots.map { it.canonicalFile }.also {
        require(it.isNotEmpty()) { "At least one plugin source root is required" }
        require(it.distinct().size == it.size) { "Duplicate plugin source root" }
        require(it.all(File::isDirectory)) { "Plugin source root must be a directory" }
    }

    fun load(record: PluginWireRecord): PluginRuntimeManifestResult {
        if (record.card.sourceKind != PluginSourceKind.LOCAL) {
            return PluginRuntimeManifestResult.NotDeclared
        }
        val rawSource = record.localSourcePath
            ?: return PluginRuntimeManifestResult.Rejected("local_source_path_missing")
        return runCatching {
            val source = File(rawSource).canonicalFile
            require(source.isDirectory && source.isInsideAny(allowedRoots)) {
                "local_source_outside_private_boundary"
            }
            val manifest = File(source, MANIFEST_NAME)
            if (!manifest.exists()) return PluginRuntimeManifestResult.NotDeclared
            val canonicalManifest = manifest.canonicalFile
            require(canonicalManifest.parentFile == source) { "runtime_manifest_escaped_source" }
            require(canonicalManifest.isFile && !Files.isSymbolicLink(manifest.toPath())) {
                "runtime_manifest_not_regular"
            }
            require(canonicalManifest.length() in 1..MAX_MANIFEST_BYTES) {
                "runtime_manifest_size_invalid"
            }
            val requirements = PluginRuntimeRequirementsCodec.decode(canonicalManifest.readBytes())
            require(requirements.pluginId == record.card.pluginId) {
                "runtime_manifest_plugin_mismatch"
            }
            PluginRuntimeManifestResult.Declared(requirements, source)
        }.getOrElse { failure ->
            PluginRuntimeManifestResult.Rejected(
                failure.message?.takeIf { it.matches(SAFE_REASON) }
                    ?: "runtime_manifest_invalid",
            )
        }
    }

    private fun File.isInsideAny(roots: List<File>): Boolean = roots.any { root ->
        this == root || toPath().startsWith(root.toPath())
    }

    companion object {
        const val MANIFEST_NAME = "hans.plugin-runtime.json"
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private val SAFE_REASON = Regex("[a-z0-9_ .:-]{1,160}")
    }
}
