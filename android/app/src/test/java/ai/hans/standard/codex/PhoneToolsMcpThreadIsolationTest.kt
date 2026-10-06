package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsMcpThreadIsolationTest {
    @Test fun personalStartDisablesOnlyTheConfiguredMcpCopyWhileRetainingDynamicTools() {
        val request = AppServerRequests.threadStart(
            RequestId.Number(1), DispatchOptions.ASTRA_MEDIUM,
            disablePhoneToolsMcp = true,
        )
        val config = JSONObject(request.json).getJSONObject("params").getJSONObject("config")
        assertFalse(config.getBoolean("mcp_servers.hans_phone.enabled"))
        assertTrue(config.has("model_reasoning_effort"))
    }

    @Test fun personalResumeKeepsTheMcpCopyDisabled() {
        val request = AppServerRequests.threadResume(RequestId.Number(2), "local-thread", disablePhoneToolsMcp = true)
        assertFalse(JSONObject(request.json).getJSONObject("params")
            .getJSONObject("config").getBoolean("mcp_servers.hans_phone.enabled"))
    }

    @Test fun independentProcessesDoNotCreateAnIncompletePhantomMcpServer() {
        val start = AppServerRequests.threadStart(RequestId.Number(3), DispatchOptions.ASTRA_MEDIUM)
        assertFalse(JSONObject(start.json).getJSONObject("params").getJSONObject("config")
            .has("mcp_servers.hans_phone.enabled"))
        val resume = AppServerRequests.threadResume(RequestId.Number(4), "independent-thread")
        assertFalse(JSONObject(resume.json).getJSONObject("params").has("config"))
    }
}
