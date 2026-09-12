package ai.hans.standard.plugins.runtime

import java.io.File
import java.nio.file.Files

internal sealed interface PluginSurfaceManifestLoadResult {
    data object NotDeclared : PluginSurfaceManifestLoadResult
    data class Declared(val manifest: PluginSurfaceManifest) : PluginSurfaceManifestLoadResult
    data class Rejected(val reason: String) : PluginSurfaceManifestLoadResult
}

/** Loads only a regular manifest located directly in an already trusted private plugin root. */
internal class PluginSurfaceManifestLoader(
    allowedSourceRoots: List<File>,
) {
    private val allowedRoots = allowedSourceRoots.map(File::getCanonicalFile).also { roots ->
        require(roots.isNotEmpty() && roots.distinct().size == roots.size)
        require(roots.all(File::isDirectory))
    }

    fun load(pluginId: String, sourceRoot: File): PluginSurfaceManifestLoadResult = runCatching {
        val source = sourceRoot.canonicalFile
        require(source.isDirectory && allowedRoots.any { source == it || source.toPath().startsWith(it.toPath()) }) {
            "surface_source_outside_private_boundary"
        }
        val rawManifest = File(source, MANIFEST_NAME)
        if (!rawManifest.exists()) return PluginSurfaceManifestLoadResult.NotDeclared
        val manifestFile = rawManifest.canonicalFile
        require(manifestFile.parentFile == source) { "surface_manifest_escaped_source" }
        require(manifestFile.isFile && !Files.isSymbolicLink(rawManifest.toPath())) {
            "surface_manifest_not_regular"
        }
        require(manifestFile.length() in 1..PluginSurfaceManifestCodec.MAX_BYTES.toLong()) {
            "surface_manifest_size_invalid"
        }
        val manifest = PluginSurfaceManifestCodec.decode(manifestFile.readBytes())
        require(manifest.pluginId == pluginId) { "surface_manifest_plugin_mismatch" }
        PluginSurfaceManifestLoadResult.Declared(manifest)
    }.getOrElse { failure ->
        PluginSurfaceManifestLoadResult.Rejected(
            failure.message?.takeIf { SAFE_REASON.matches(it) } ?: "surface_manifest_invalid",
        )
    }

    companion object {
        const val MANIFEST_NAME = "hans.plugin-surface.json"
        private val SAFE_REASON = Regex("[a-z0-9_ .:-]{1,160}")
    }
}
