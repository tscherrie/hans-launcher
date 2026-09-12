package ai.hans.standard.remotecontrol

import org.json.JSONObject

/** Exact experimental wire contract pinned by protocol/app-server/0.154.0. */
object RemoteControlProtocol {
    const val STATUS_NOTIFICATION = "remoteControl/status/changed"
    const val MAX_FRAME_BYTES = 262_144
    const val PAGE_SIZE = 50
    private val identifier = Regex("[A-Za-z0-9._:-]{1,512}")

    fun status(id: String) = request(id, "remoteControl/status/read", null)
    fun enable(id: String) = request(id, "remoteControl/enable", JSONObject().put("ephemeral", true))
    fun disable(id: String) = request(id, "remoteControl/disable", JSONObject().put("ephemeral", true))
    fun pair(id: String) = request(id, "remoteControl/pairing/start", JSONObject().put("manualCode", true))
    fun pairingStatus(id: String, pairing: RemoteControlPairing) = request(id,
        "remoteControl/pairing/status", JSONObject().put("pairingCode", pairing.pairingCode))
    fun clients(id: String, environmentId: String, cursor: String? = null): RemoteControlRequest {
        require(identifier.matches(environmentId))
        val params = JSONObject().put("environmentId", environmentId).put("limit", PAGE_SIZE).put("order", "desc")
        cursor?.let { require(it.length in 1..4_096); params.put("cursor", it) }
        return request(id, "remoteControl/client/list", params)
    }
    fun revoke(id: String, environmentId: String, clientId: String): RemoteControlRequest {
        require(identifier.matches(environmentId) && identifier.matches(clientId))
        return request(id, "remoteControl/client/revoke", JSONObject()
            .put("environmentId", environmentId).put("clientId", clientId))
    }

    private fun request(id: String, method: String, params: JSONObject?): RemoteControlRequest {
        require(identifier.matches(id))
        val frame = JSONObject().put("id", id).put("method", method)
        if (params != null) frame.put("params", params)
        return RemoteControlRequest(id, method, frame.toString())
    }

    internal fun envelope(raw: String): JSONObject {
        require(raw.length <= MAX_FRAME_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES)
        return JSONObject(raw)
    }

    internal fun connection(result: JSONObject): RemoteControlConnection = RemoteControlConnection(
        status = when (result.text("status", 16)) {
            "disabled" -> RemoteControlStatus.DISABLED
            "connecting" -> RemoteControlStatus.CONNECTING
            "connected" -> RemoteControlStatus.CONNECTED
            "errored" -> RemoteControlStatus.ERRORED
            else -> error("remote_status_invalid")
        },
        installationId = result.id("installationId"),
        serverName = result.text("serverName", 256),
        environmentId = result.optionalId("environmentId"),
    )

    internal fun pairing(result: JSONObject, nowMillis: Long): RemoteControlPairing {
        val expires = result.exactLong("expiresAt")
        // Upstream enroll.rs converts RFC3339 to unix_timestamp(): this is seconds, not ms.
        require(expires in 1..Long.MAX_VALUE / 1_000 && expires * 1_000 > nowMillis)
        return RemoteControlPairing(result.text("pairingCode", 16_384),
            result.optionalText("manualPairingCode", 256), result.id("environmentId"), expires)
    }

    internal fun clients(result: JSONObject): Pair<List<RemoteControlClient>, String?> {
        val data = result.getJSONArray("data")
        require(data.length() <= PAGE_SIZE)
        val clients = (0 until data.length()).map { index ->
            val client = data.getJSONObject(index)
            RemoteControlClient(client.id("clientId"), client.optionalText("displayName", 256),
                client.optionalText("deviceType", 128), client.optionalText("deviceModel", 128),
                client.optionalText("platform", 128), client.optionalText("osVersion", 128),
                client.optionalText("appVersion", 128),
                if (client.has("lastSeenAt") && !client.isNull("lastSeenAt")) client.exactLong("lastSeenAt") else null)
        }
        require(clients.map { it.clientId }.toSet().size == clients.size)
        return clients to result.optionalText("nextCursor", 4_096)
    }

    internal fun issue(error: JSONObject): RemoteControlIssue {
        val code = error.optInt("code", 0)
        val message = error.optString("message", "").take(2_048).lowercase()
        return when {
            code == -32601 -> RemoteControlIssue.UNSUPPORTED
            "authentication" in message || "not authenticated" in message -> RemoteControlIssue.AUTH_REQUIRED
            "managed requirements" in message || "disabled by" in message -> RemoteControlIssue.POLICY_BLOCKED
            "unavailable" in message || "sqlite" in message -> RemoteControlIssue.UNAVAILABLE
            else -> RemoteControlIssue.RPC_FAILED
        }
    }

    private fun JSONObject.id(key: String) = text(key, 512).also { require(identifier.matches(it)) }
    private fun JSONObject.optionalId(key: String) = if (!has(key) || isNull(key)) null else id(key)
    private fun JSONObject.optionalText(key: String, max: Int) = if (!has(key) || isNull(key)) null else text(key, max)
    private fun JSONObject.text(key: String, max: Int): String = (get(key) as? String)
        ?.also { require(it.length in 1..max && it.none(Char::isISOControl)) } ?: error("remote_string_invalid")
    private fun JSONObject.exactLong(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
}
