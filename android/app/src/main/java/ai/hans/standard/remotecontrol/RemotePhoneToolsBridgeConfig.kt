package ai.hans.standard.remotecontrol

/** Ephemeral local endpoint. Enrollment secrets and this bearer never enter persistent config. */
class RemotePhoneToolsBridgeConfig(val port: Int, val token: String) {
    init {
        require(port in 1024..65535) { "Invalid phone tools port" }
        require(token.matches(Regex("[a-f0-9]{64}"))) { "Invalid phone tools credential" }
    }

    val url: String get() = "http://127.0.0.1:$port/mcp"

    fun arguments(): List<String> = listOf(
        "-c", "mcp_servers.hans_phone.url=\"$url\"",
        "-c", "mcp_servers.hans_phone.bearer_token_env_var=\"$TOKEN_ENV\"",
        "-c", "mcp_servers.hans_phone.enabled=true",
        "-c", "mcp_servers.hans_phone.required=true",
        "-c", "mcp_servers.hans_phone.tool_timeout_sec=120",
    )

    override fun toString(): String = "RemotePhoneToolsBridgeConfig(<redacted>)"

    companion object {
        const val TOKEN_ENV = "HANS_PHONE_TOOLS_TOKEN"
        const val PORT_ENV = "HANS_PHONE_TOOLS_PORT"
    }
}
