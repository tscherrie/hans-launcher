package ai.hans.standard.plugins.runtime

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal object PluginSurfaceManifestCodec {
    private const val SCHEMA = "hans.plugin-surface"
    private const val VERSION = 1
    const val MAX_BYTES = 256 * 1024

    fun decode(document: ByteArray): PluginSurfaceManifest {
        require(document.isNotEmpty() && document.size <= MAX_BYTES) {
            "Plugin surface manifest has an invalid size"
        }
        val text = document.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(document)) {
            "Plugin surface manifest is not canonical UTF-8"
        }
        val root = JSONObject(text)
        root.requireOnly(
            "schema",
            "version",
            "pluginId",
            "nativeSkills",
            "nativeHooks",
            "androidTools",
            "hooks",
            "remoteMcpServers",
        )
        require(root.getString("schema") == SCHEMA) { "Unsupported plugin surface schema" }
        require(root.getInt("version") == VERSION) { "Unsupported plugin surface version" }
        return PluginSurfaceManifest(
            pluginId = root.getString("pluginId"),
            nativeSkills = root.getJSONArray("nativeSkills").objects { item ->
                item.requireOnly("id", "name", "required")
                NativeSkillRequirement(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    required = item.getBoolean("required"),
                )
            },
            nativeHooks = root.getJSONArray("nativeHooks").objects { item ->
                item.requireOnly("id", "key", "eventName", "required")
                NativeHookRequirement(
                    id = item.getString("id"),
                    key = item.getString("key"),
                    eventName = item.getString("eventName"),
                    required = item.getBoolean("required"),
                )
            },
            androidTools = root.getJSONArray("androidTools").objects { item ->
                item.requireOnly("id", "bindingId", "required")
                AndroidToolRequirement(
                    id = item.getString("id"),
                    bindingId = item.getString("bindingId"),
                    required = item.getBoolean("required"),
                )
            },
            hooks = root.getJSONArray("hooks").objects { item ->
                item.requireOnly("id", "event", "actionId", "required")
                DeclarativeHookRequirement(
                    id = item.getString("id"),
                    event = HansHookEvent.entries.singleOrNull {
                        it.wireName == item.getString("event")
                    } ?: throw IllegalArgumentException("Unsupported Hans hook event"),
                    actionId = item.getString("actionId"),
                    required = item.getBoolean("required"),
                )
            },
            remoteMcpServers = root.getJSONArray("remoteMcpServers").objects { item ->
                item.requireOnly(
                    "id",
                    "endpoint",
                    "oauthHandle",
                    "allowedTools",
                    "required",
                    "requestTimeoutMillis",
                    "maxResponseBytes",
                )
                RemoteMcpRequirement(
                    id = item.getString("id"),
                    endpoint = item.getString("endpoint"),
                    oauthHandle = if (item.isNull("oauthHandle")) {
                        null
                    } else {
                        OAuthCredentialHandle(item.getString("oauthHandle"))
                    },
                    allowedTools = item.getJSONArray("allowedTools").strings().toCollection(linkedSetOf()),
                    required = item.getBoolean("required"),
                    requestTimeoutMillis = item.getLong("requestTimeoutMillis"),
                    maxResponseBytes = item.getInt("maxResponseBytes"),
                )
            },
        )
    }

    fun encode(manifest: PluginSurfaceManifest): ByteArray {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("pluginId", manifest.pluginId)
            .put(
                "nativeSkills",
                JSONArray().also { array ->
                    manifest.nativeSkills.sortedBy(NativeSkillRequirement::id).forEach {
                        array.put(JSONObject().put("id", it.id).put("name", it.name).put("required", it.required))
                    }
                },
            )
            .put(
                "nativeHooks",
                JSONArray().also { array ->
                    manifest.nativeHooks.sortedBy(NativeHookRequirement::id).forEach {
                        array.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("key", it.key)
                                .put("eventName", it.eventName)
                                .put("required", it.required),
                        )
                    }
                },
            )
            .put(
                "androidTools",
                JSONArray().also { array ->
                    manifest.androidTools.sortedBy(AndroidToolRequirement::id).forEach {
                        array.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("bindingId", it.bindingId)
                                .put("required", it.required),
                        )
                    }
                },
            )
            .put(
                "hooks",
                JSONArray().also { array ->
                    manifest.hooks.sortedBy(DeclarativeHookRequirement::id).forEach {
                        array.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("event", it.event.wireName)
                                .put("actionId", it.actionId)
                                .put("required", it.required),
                        )
                    }
                },
            )
            .put(
                "remoteMcpServers",
                JSONArray().also { array ->
                    manifest.remoteMcpServers.sortedBy(RemoteMcpRequirement::id).forEach {
                        array.put(
                            JSONObject()
                                .put("id", it.id)
                                .put("endpoint", it.endpoint)
                                .put("oauthHandle", it.oauthHandle?.value ?: JSONObject.NULL)
                                .put("allowedTools", JSONArray(it.allowedTools.sorted()))
                                .put("required", it.required)
                                .put("requestTimeoutMillis", it.requestTimeoutMillis)
                                .put("maxResponseBytes", it.maxResponseBytes),
                        )
                    }
                },
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "Plugin surface manifest is too large" }
        }
    }
}

private fun JSONObject.requireOnly(vararg expected: String) {
    require(keys().asSequence().toSet() == expected.toSet()) {
        "Unexpected plugin surface manifest fields"
    }
}

private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
