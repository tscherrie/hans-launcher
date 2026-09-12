package ai.hans.standard.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteMcpBuiltInVerifiersTest {
    @Test
    fun serverAcknowledgementProvesOnlyAValidNonErrorMcpResult() {
        val verifier = requireNotNull(
            RemoteMcpBuiltInVerifiers.registry.resolve(
                RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1,
            ),
        )

        assertEquals(
            RemoteMcpPostcondition.VERIFIED,
            verifier.verify(context(result(isError = false))),
        )
        assertEquals(
            RemoteMcpPostcondition.FAILED,
            verifier.verify(context(result(isError = true))),
        )
        assertEquals(
            RemoteMcpPostcondition.AMBIGUOUS,
            verifier.verify(context("{not-json")),
        )
    }

    @Test
    fun registryProjectionDoesNotExposeRemoteResultOrArguments() {
        val privateMarker = "private-marker"
        val registry = RemoteMcpBuiltInVerifiers.registry
        val verifier = requireNotNull(
            registry.resolve(RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1),
        )
        verifier.verify(context(result(false), privateMarker))

        assertFalse(registry.toString().contains(privateMarker))
        assertFalse(verifier.toString().contains(privateMarker))
    }

    private fun result(isError: Boolean): String = JSONObject()
        .put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", "acknowledged")),
        )
        .put("isError", isError)
        .toString()

    private fun context(
        result: String,
        arguments: String = "{}",
    ) = RemoteMcpVerificationContext(
        pluginId = "tasks-plugin",
        serverId = "tasks",
        configurationDigest = "a".repeat(64),
        toolName = "tasks/create",
        argumentsJson = arguments,
        resultJson = result,
    )
}
