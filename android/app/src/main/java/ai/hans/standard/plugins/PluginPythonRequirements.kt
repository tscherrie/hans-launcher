package ai.hans.standard.plugins

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Requested PyPI roots. Semantic PEP 508 validation and closure belong to the resolver. */
internal data class PluginPythonRequirements(
    val pluginId: String,
    val requirements: List<String>,
    val allowPrereleases: Boolean = false,
) {
    init {
        require(pluginId.matches(PLUGIN_ID)) { "Invalid Python dependency plugin id" }
        require(requirements.size <= MAX_REQUIREMENTS) {
            "Invalid Python dependency count"
        }
        requirements.forEach { requirement ->
            require(
                requirement.isNotBlank() &&
                    requirement.length <= MAX_REQUIREMENT_CHARS &&
                    requirement.none(Char::isISOControl),
            ) { "Invalid Python package requirement" }
        }
        require(requirements.distinct().size == requirements.size) {
            "Duplicate Python package requirement"
        }
    }

    companion object {
        private const val MAX_REQUIREMENTS = 128
        private const val MAX_REQUIREMENT_CHARS = 512
        private val PLUGIN_ID = Regex("[a-z][a-z0-9._-]{0,127}")
    }
}

/**
 * Strict authoring contract for online dependency resolution. The resolver may use only the
 * PyPI Simple repository, return a complete exact hash-pinned lock, and reject sdists/builds.
 */
internal object PluginPythonRequirementsCodec {
    const val FILE_NAME = "hans.python-requirements.json"
    const val LOCK_FILE_NAME = "hans.python.lock.json"
    private const val SCHEMA = "hans.python-requirements"
    private const val VERSION = 1
    private const val MAX_DOCUMENT_BYTES = 128 * 1024

    fun decode(document: ByteArray): PluginPythonRequirements {
        require(document.isNotEmpty() && document.size <= MAX_DOCUMENT_BYTES) {
            "Python requirements document has an invalid size"
        }
        val text = document.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(document)) {
            "Python requirements document is not canonical UTF-8"
        }
        val json = JSONObject(text)
        require(json.keys().asSequence().toSet() == EXPECTED_KEYS) {
            "Unexpected Python requirements fields"
        }
        require(json.getString("schema") == SCHEMA && json.getInt("version") == VERSION) {
            "Unsupported Python requirements schema"
        }
        require(json.getString("repository") == PYPI_SIMPLE_REPOSITORY) {
            "Unsupported Python package repository"
        }
        val values = json.getJSONArray("requirements")
        return PluginPythonRequirements(
            pluginId = json.getString("pluginId"),
            requirements = (0 until values.length()).map(values::getString),
            allowPrereleases = json.getBoolean("allowPrereleases"),
        )
    }

    fun encode(requirements: PluginPythonRequirements): ByteArray = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("pluginId", requirements.pluginId)
        .put("repository", PYPI_SIMPLE_REPOSITORY)
        .put("requirements", JSONArray(requirements.requirements))
        .put("allowPrereleases", requirements.allowPrereleases)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
        .also { require(it.size <= MAX_DOCUMENT_BYTES) }

    const val PYPI_SIMPLE_REPOSITORY = "https://pypi.org/simple/"
    private val EXPECTED_KEYS = setOf(
        "schema",
        "version",
        "pluginId",
        "repository",
        "requirements",
        "allowPrereleases",
    )
}
