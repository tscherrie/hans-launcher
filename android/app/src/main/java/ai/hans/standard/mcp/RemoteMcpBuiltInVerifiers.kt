package ai.hans.standard.mcp

import org.json.JSONObject

/**
 * Signed Hans-owned verifier ids available to user-approved Remote MCP policies.
 *
 * A remote MCP server is the authority that performs its own tool action. For a generic server,
 * Hans cannot independently inspect every external SaaS postcondition. This verifier therefore
 * proves exactly one bounded claim and no more: the authenticated server returned a structurally
 * valid MCP result which the protocol did not mark as an error. UI must describe such a policy as
 * "vom Server bestätigt", never as independently observed on Android.
 */
internal object RemoteMcpBuiltInVerifiers {
    const val SERVER_ACKNOWLEDGED_V1 = "server-acknowledged-v1"

    val registry: RemoteMcpSignedVerifierRegistry =
        RemoteMcpSignedVerifierRegistry.fromSignedInAppCode(
            listOf(
                RemoteMcpSignedVerifierDefinition(SERVER_ACKNOWLEDGED_V1) { context ->
                    runCatching {
                        val parsed = RemoteMcpProtocol.parseToolResult(JSONObject(context.resultJson))
                        if (parsed.isError) {
                            RemoteMcpPostcondition.FAILED
                        } else {
                            RemoteMcpPostcondition.VERIFIED
                        }
                    }.getOrDefault(RemoteMcpPostcondition.AMBIGUOUS)
                },
            ),
        )
}
