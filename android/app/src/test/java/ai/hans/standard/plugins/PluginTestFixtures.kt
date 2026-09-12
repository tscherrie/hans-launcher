package ai.hans.standard.plugins

import org.json.JSONArray
import org.json.JSONObject

internal fun pluginListJson(
    installed: Boolean = false,
    pluginId: String = "plugin-gmail",
    pluginName: String = "gmail",
): JSONObject = JSONObject()
    .put(
        "marketplaces",
        JSONArray().put(
            JSONObject()
                .put("name", "official")
                .put("path", JSONObject.NULL)
                .put("interface", JSONObject().put("displayName", "Official"))
                .put(
                    "plugins",
                    JSONArray().put(
                        pluginSummaryJson(installed, pluginId, pluginName),
                    ),
                ),
        ),
    )
    .put("featuredPluginIds", JSONArray().put(pluginId))
    .put("marketplaceLoadErrors", JSONArray())

internal fun pluginSummaryJson(
    installed: Boolean,
    pluginId: String,
    pluginName: String,
): JSONObject = JSONObject()
    .put("id", pluginId)
    .put("name", pluginName)
    .put("authPolicy", "ON_USE")
    .put("availability", "AVAILABLE")
    .put("enabled", true)
    .put("installPolicy", "AVAILABLE")
    .put("installed", installed)
    .put("source", JSONObject().put("type", "remote"))
    .put(
        "interface",
        JSONObject()
            .put("displayName", "Gmail")
            .put("shortDescription", "Mail lesen und schreiben")
            .put("logoUrl", "https://cdn.example.test/gmail.png")
            .put("logoUrlDark", "https://cdn.example.test/gmail-dark.png")
            .put("capabilities", JSONArray().put("mail"))
            .put("screenshots", JSONArray())
            .put("screenshotUrls", JSONArray()),
    )

internal fun pluginDetailJson(): JSONObject = JSONObject()
    .put(
        "plugin",
        JSONObject()
            .put("marketplaceName", "official")
            .put("marketplacePath", JSONObject.NULL)
            .put("description", "Gmail capability")
            .put("summary", pluginSummaryJson(false, "plugin-gmail", "gmail"))
            .put(
                "apps",
                JSONArray().put(
                    JSONObject()
                        .put("id", "app-gmail")
                        .put("name", "Gmail")
                        .put("description", "Mail connector")
                        .put("installUrl", "https://chatgpt.com/apps/gmail"),
                ),
            )
            .put(
                "skills",
                JSONArray().put(
                    JSONObject()
                        .put("name", "gmail-compose")
                        .put("description", "Compose mail")
                        .put("enabled", true)
                        .put("path", JSONObject.NULL)
                        .put("shortDescription", "Compose")
                        .put(
                            "interface",
                            JSONObject()
                                .put("displayName", "Compose Gmail")
                                .put("iconSmallUrl", "https://cdn.example.test/skill.png"),
                        ),
                ),
            )
            .put("hooks", JSONArray())
            .put("mcpServers", JSONArray().put("gmail"))
            .put("appTemplates", JSONArray())
            .put("scheduledTasks", JSONArray())
            .put("shareUrl", "https://chatgpt.com/plugins/gmail"),
    )

internal fun appListJson(nextCursor: String? = null): JSONObject = JSONObject()
    .put(
        "data",
        JSONArray().put(
            JSONObject()
                .put("id", "app-gmail")
                .put("name", "Gmail")
                .put("description", "Mail connector")
                .put("installUrl", "https://chatgpt.com/apps/gmail")
                .put("logoUrl", "https://cdn.example.test/gmail.png")
                .put("logoUrlDark", JSONObject.NULL)
                .put("isAccessible", true)
                .put("isEnabled", true)
                .put("pluginDisplayNames", JSONArray().put("Gmail")),
        ),
    )
    .put("nextCursor", nextCursor ?: JSONObject.NULL)
