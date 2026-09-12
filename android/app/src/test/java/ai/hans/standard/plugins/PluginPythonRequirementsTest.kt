package ai.hans.standard.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginPythonRequirementsTest {
    @Test
    fun deterministicRoundTripPreservesPep508InputForTheSemanticResolver() {
        val input = PluginPythonRequirements(
            pluginId = "garage",
            requirements = listOf(
                "meross-iot>=0.4.7,<0.5; python_version >= '3.11'",
                "httpx[http2]==0.28.1",
            ),
            allowPrereleases = false,
        )

        assertEquals(input, PluginPythonRequirementsCodec.decode(PluginPythonRequirementsCodec.encode(input)))
    }

    @Test
    fun repositoryAndFieldsAreFailClosed() {
        val valid = JSONObject(
            PluginPythonRequirementsCodec.encode(
                PluginPythonRequirements("sample", listOf("pydantic>=2")),
            ).decodeToString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginPythonRequirementsCodec.decode(
                JSONObject(valid.toString())
                    .put("repository", "https://example.invalid/simple/")
                    .toString()
                    .encodeToByteArray(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginPythonRequirementsCodec.decode(
                JSONObject(valid.toString())
                    .put("extra", true)
                    .toString()
                    .encodeToByteArray(),
            )
        }
    }

    @Test
    fun stdlibOnlyIsAcceptedWhileDuplicateAndControlCharacterRequirementsAreRejected() {
        val stdlibOnly = PluginPythonRequirements("sample", emptyList())
        assertEquals(
            stdlibOnly,
            PluginPythonRequirementsCodec.decode(PluginPythonRequirementsCodec.encode(stdlibOnly)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginPythonRequirements("sample", listOf("httpx", "httpx"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginPythonRequirements("sample", listOf("httpx\nmalicious"))
        }
    }
}
