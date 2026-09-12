package ai.hans.standard.plugins

import ai.hans.standard.codex.AppServerMethod
import ai.hans.standard.codex.AppServerRequests
import ai.hans.standard.codex.EncodedRequest
import ai.hans.standard.codex.ExtensionResultDecoder
import ai.hans.standard.codex.FrameLimitException
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.MalformedEnvelopeException
import ai.hans.standard.codex.ProtocolLimits
import ai.hans.standard.codex.RequestId
import ai.hans.standard.codex.SkillSummary
import ai.hans.standard.codex.SkillsListResult
import ai.hans.standard.codex.UnsupportedProtocolValueException
import ai.hans.standard.codex.putIfNotNull
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private val supportedHookEventNames = setOf(
    "preToolUse",
    "permissionRequest",
    "postToolUse",
    "preCompact",
    "postCompact",
    "sessionStart",
    "sessionEnd",
    "userPromptSubmit",
    "subagentStart",
    "subagentStop",
    "stop",
)

/** Exact experimental plugin/app subset from Codex App Server 0.154.0 v2. */
internal object PluginAppServerRequests {
    private val allMarketplaceKinds = listOf(
        "local",
        "vertical",
        "workspace-directory",
        "shared-with-me",
        "created-by-me-remote",
    )

    fun pluginList(
        id: RequestId,
        workspacePath: String,
        forceRefetch: Boolean,
        additionalWorkingDirectories: List<String> = emptyList(),
    ): EncodedRequest {
        requireAbsolutePath(workspacePath, "Plugin working directory")
        require(additionalWorkingDirectories.size < ProtocolLimits.MAX_SKILL_ROOTS) {
            "Too many plugin discovery roots"
        }
        additionalWorkingDirectories.forEach {
            requireAbsolutePath(it, "Plugin discovery root")
        }
        val discoveryRoots = (listOf(workspacePath) + additionalWorkingDirectories).distinct()
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.PLUGIN_LIST,
            params = JSONObject()
                .put("cwds", JSONArray(discoveryRoots))
                .put("forceRefetch", forceRefetch)
                .put("marketplaceKinds", JSONArray(allMarketplaceKinds)),
            decoder = ExtensionResultDecoder(::parsePluginList),
        )
    }

    fun pluginRead(id: RequestId, locator: PluginLocator): EncodedRequest =
        AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.PLUGIN_READ,
            params = locatorParams(locator),
            decoder = ExtensionResultDecoder { result -> parsePluginRead(result, locator) },
        )

    fun pluginInstall(
        id: RequestId,
        locator: PluginLocator,
        installAttemptId: String,
    ): EncodedRequest {
        requireBoundedToken(installAttemptId, "Install attempt id")
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.PLUGIN_INSTALL,
            params = locatorParams(locator).put("installAttemptId", installAttemptId),
            decoder = ExtensionResultDecoder(::parsePluginInstall),
        )
    }

    fun pluginUninstall(id: RequestId, pluginId: String): EncodedRequest {
        requireBoundedToken(pluginId, "Plugin id")
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.PLUGIN_UNINSTALL,
            params = JSONObject().put("pluginId", pluginId),
            decoder = ExtensionResultDecoder(::parsePluginUninstall),
        )
    }

    fun marketplaceUpgrade(id: RequestId, marketplaceName: String?): EncodedRequest {
        marketplaceName?.let { requireBoundedToken(it, "Marketplace name", 512) }
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.MARKETPLACE_UPGRADE,
            params = JSONObject().putIfNotNull("marketplaceName", marketplaceName),
            decoder = ExtensionResultDecoder(::parseMarketplaceUpgrade),
        )
    }

    fun marketplaceAdd(id: RequestId, sourcePath: String): EncodedRequest {
        requireAbsolutePath(sourcePath, "Marketplace source")
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.MARKETPLACE_ADD,
            params = JSONObject().put("source", sourcePath),
            decoder = ExtensionResultDecoder(::parseMarketplaceAdd),
        )
    }

    fun appList(
        id: RequestId,
        threadId: String?,
        cursor: String?,
        forceRefetch: Boolean,
        limit: Int = ProtocolLimits.MAX_APPS_PER_PAGE,
    ): EncodedRequest {
        threadId?.let { requireBoundedToken(it, "Thread id") }
        cursor?.let { requireBoundedToken(it, "App cursor", 1_024) }
        require(limit in 1..ProtocolLimits.MAX_APPS_PER_PAGE) { "Invalid app page size" }
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.APP_LIST,
            params = JSONObject()
                .put("forceRefetch", forceRefetch)
                .put("limit", limit)
                .putIfNotNull("threadId", threadId)
                .putIfNotNull("cursor", cursor),
            decoder = ExtensionResultDecoder { result -> parseAppList(result, cursor) },
        )
    }

    fun skillConfigWrite(
        id: RequestId,
        path: String,
        enabled: Boolean,
    ): EncodedRequest {
        requireAbsolutePath(path, "Skill path")
        return AppServerRequests.extensionRequest(
            id = id,
            method = AppServerMethod.SKILLS_CONFIG_WRITE,
            params = JSONObject()
                .put("path", path)
                .put("enabled", enabled),
            decoder = ExtensionResultDecoder(::parseSkillConfigWrite),
        )
    }

    private fun locatorParams(locator: PluginLocator): JSONObject = JSONObject()
        .put("pluginName", locator.pluginName)
        .also { params ->
            if (locator.marketplacePath == null) {
                params.put("remoteMarketplaceName", locator.marketplaceName)
            } else {
                params.put("marketplacePath", locator.marketplacePath)
            }
        }
}

internal data class PluginWireRecord(
    val locator: PluginLocator,
    val card: PluginCard,
    val availableVersion: String?,
    val localVersion: String?,
    /** Exact local source root when App Server provided one; never projected to the UI. */
    val localSourcePath: String? = null,
)

internal data class PluginListWireResult(
    val records: List<PluginWireRecord>,
    val featuredPluginIds: Set<String>,
    val marketplaceLoadIssueCount: Int,
)

internal data class PluginReadWireResult(
    val detail: PluginDetailSnapshot,
    /** Fresh plugin/read source identity; never projected to the UI. */
    val sourceKind: PluginSourceKind,
    val localSourcePath: String?,
)

internal data class PluginInstallWireResult(
    val authPolicy: PluginAuthPolicy,
    val appsNeedingAuth: List<PluginAppSummary>,
)

internal data object PluginUninstallWireResult

internal data class MarketplaceUpgradeWireResult(
    val selectedMarketplaceCount: Int,
    val upgradedRootCount: Int,
    val issueCount: Int,
)

internal data class MarketplaceAddWireResult(
    val marketplaceName: String,
    val installedRoot: String,
    val alreadyAdded: Boolean,
)

internal data class AppListPageWireResult(
    val apps: List<AppCard>,
    val nextCursor: String?,
    val requestedCursor: String?,
)

internal data class SkillConfigWriteWireResult(val effectiveEnabled: Boolean)

internal fun parseAppListUpdatedParams(rawParams: String): List<AppCard> {
    val params = JsonContract.parseObject(rawParams, ProtocolLimits.MAX_EVENT_FRAME_BYTES)
    JsonContract.requireOnlyKeys(params, setOf("data"), "app/list/updated params")
    return parseAppArray(JsonContract.requiredArray(params, "data"))
}

private fun parsePluginList(result: JSONObject): PluginListWireResult {
    JsonContract.requireOnlyKeys(
        result,
        setOf("marketplaces", "featuredPluginIds", "marketplaceLoadErrors"),
        "plugin/list result",
    )
    val marketplaces = JsonContract.requiredArray(result, "marketplaces")
    if (marketplaces.length() > ProtocolLimits.MAX_PLUGIN_MARKETPLACES) {
        throw FrameLimitException("plugin/list returned too many marketplaces")
    }
    val featured = optionalNonNullStringArray(
        result,
        "featuredPluginIds",
        ProtocolLimits.MAX_PLUGINS_TOTAL,
        256,
    ).toSet()
    val records = ArrayList<PluginWireRecord>()
    repeat(marketplaces.length()) { index ->
        val marketplace = marketplaces.requiredObject(index, "marketplace")
        JsonContract.requireOnlyKeys(
            marketplace,
            setOf("interface", "name", "path", "plugins"),
            "plugin marketplace",
        )
        val name = JsonContract.requiredString(marketplace, "name", 512)
        val path = JsonContract.optionalString(
            marketplace,
            "path",
            ProtocolLimits.MAX_PATH_CHARS,
        )?.also { requireAbsolutePath(it, "Marketplace") }
        val displayName = if (!marketplace.has("interface") || marketplace.isNull("interface")) {
            name
        } else {
            val marketplaceInterface = JsonContract.requiredObject(marketplace, "interface")
            JsonContract.requireOnlyKeys(
                marketplaceInterface,
                setOf("displayName"),
                "marketplace interface",
            )
            JsonContract.optionalString(marketplaceInterface, "displayName", 512) ?: name
        }
        val plugins = JsonContract.requiredArray(marketplace, "plugins")
        if (plugins.length() > ProtocolLimits.MAX_PLUGINS_PER_MARKETPLACE) {
            throw FrameLimitException("Marketplace returned too many plugins")
        }
        repeat(plugins.length()) { pluginIndex ->
            val summary = plugins.requiredObject(pluginIndex, "plugin")
            val parsed = parsePluginSummary(summary, name, path, displayName, featured)
            records += parsed
            if (records.size > ProtocolLimits.MAX_PLUGINS_TOTAL) {
                throw FrameLimitException("Merged plugin catalog is too large")
            }
        }
    }
    val loadErrors = optionalNonNullObjectArray(
        result,
        "marketplaceLoadErrors",
        ProtocolLimits.MAX_PLUGIN_MARKETPLACES,
    )
    loadErrors.forEach { error ->
        JsonContract.requireOnlyKeys(
            error,
            setOf("marketplacePath", "message"),
            "marketplace load error",
        )
        requireAbsolutePath(
            JsonContract.requiredString(error, "marketplacePath", ProtocolLimits.MAX_PATH_CHARS),
            "Marketplace load error",
        )
        JsonContract.requiredString(error, "message", 16_384, allowBlank = true)
    }
    return PluginListWireResult(records, featured, loadErrors.size)
}

private fun parsePluginSummary(
    value: JSONObject,
    marketplaceName: String,
    marketplacePath: String?,
    marketplaceDisplayName: String,
    featuredIds: Set<String>,
): PluginWireRecord {
    JsonContract.requireOnlyKeys(
        value,
        setOf(
            "authPolicy",
            "availability",
            "disabledReason",
            "eligiblePlanTypes",
            "enabled",
            "id",
            "installPolicy",
            "installPolicySource",
            "installed",
            "installedAt",
            "interface",
            "keywords",
            "localVersion",
            "mustShowInstallationInterstitial",
            "name",
            "remotePluginId",
            "shareContext",
            "source",
            "version",
        ),
        "plugin summary",
    )
    val pluginId = JsonContract.requiredString(value, "id", 256)
    val pluginName = JsonContract.requiredString(value, "name", 512)
    val pluginInterface = if (!value.has("interface") || value.isNull("interface")) {
        null
    } else {
        parsePluginInterface(JsonContract.requiredObject(value, "interface"))
    }
    val availability = enumValue(
        optionalNonNullString(value, "availability", 64) ?: "AVAILABLE",
        mapOf(
            "AVAILABLE" to PluginAvailability.AVAILABLE,
            "DISABLED_BY_ADMIN" to PluginAvailability.DISABLED_BY_ADMIN,
        ),
        "plugin availability",
    )
    val disabledReason = JsonContract.optionalString(value, "disabledReason", 64)?.let {
        enumValue(
            it,
            mapOf(
                "disabled_by_admin" to PluginDisabledReason.DISABLED_BY_ADMIN,
                "plan_not_eligible" to PluginDisabledReason.PLAN_NOT_ELIGIBLE,
                "required_app_unavailable" to PluginDisabledReason.REQUIRED_APP_UNAVAILABLE,
                "unknown" to PluginDisabledReason.UNKNOWN,
            ),
            "plugin disabled reason",
        )
    }
    val source = parseSource(JsonContract.requiredObject(value, "source"))
    optionalStringArray(value, "eligiblePlanTypes", 128, 256)
    optionalNonNullStringArray(value, "keywords", 256, 512)
    val localVersion = JsonContract.optionalString(value, "localVersion", 512)
    val availableVersion = JsonContract.optionalString(value, "version", 512)
    val remotePluginId = JsonContract.optionalString(value, "remotePluginId", 256)
    JsonContract.optionalLong(value, "installedAt")?.let { installedAt ->
        if (installedAt < 0) throw MalformedEnvelopeException("Negative plugin install time")
    }
    validateNullableBoolean(value, "mustShowInstallationInterstitial")
    JsonContract.optionalString(value, "installPolicySource", 64)?.let { source ->
        enumValue(
            source,
            mapOf(
                "WORKSPACE_SETTING" to Unit,
                "IMPLICIT_CANONICAL_APP" to Unit,
            ),
            "plugin install policy source",
        )
    }
    val sharedRemotePluginId = if (!value.has("shareContext") || value.isNull("shareContext")) {
        null
    } else {
        validatePluginShareContext(JsonContract.requiredObject(value, "shareContext"))
    }
    if (remotePluginId != null && sharedRemotePluginId != null &&
        remotePluginId != sharedRemotePluginId
    ) {
        throw MalformedEnvelopeException("Plugin share id correlation mismatch")
    }
    val locator = PluginLocator(pluginId, pluginName, marketplaceName, marketplacePath)
    val handle = pluginHandle(locator)
    return PluginWireRecord(
        locator = locator,
        availableVersion = availableVersion,
        localVersion = localVersion,
        localSourcePath = source.localPath,
        card = PluginCard(
            handle = handle,
            pluginId = pluginId,
            name = pluginName,
            displayName = pluginInterface?.displayName,
            shortDescription = pluginInterface?.shortDescription,
            marketplaceDisplayName = marketplaceDisplayName,
            installed = JsonContract.requiredBoolean(value, "installed"),
            enabled = JsonContract.requiredBoolean(value, "enabled"),
            availability = availability,
            disabledReason = disabledReason,
            installPolicy = enumValue(
                JsonContract.requiredString(value, "installPolicy", 64),
                PluginInstallPolicy.entries.associateBy { it.name },
                "plugin install policy",
            ),
            authPolicy = enumValue(
                JsonContract.requiredString(value, "authPolicy", 64),
                PluginAuthPolicy.entries.associateBy { it.name },
                "plugin auth policy",
            ),
            sourceKind = source.kind,
            logoUrl = pluginInterface?.logoUrl,
            logoDarkUrl = pluginInterface?.logoDarkUrl,
            capabilities = pluginInterface?.capabilities ?: emptyList(),
            featured = pluginId in featuredIds,
        ),
    )
}

private data class ParsedPluginInterface(
    val displayName: String?,
    val shortDescription: String?,
    val logoUrl: String?,
    val logoDarkUrl: String?,
    val capabilities: List<String>,
)

private fun parsePluginInterface(value: JSONObject): ParsedPluginInterface {
    JsonContract.requireOnlyKeys(
        value,
        setOf(
            "brandColor",
            "capabilities",
            "category",
            "composerIcon",
            "composerIconUrl",
            "defaultPrompt",
            "developerName",
            "displayName",
            "logo",
            "logoDark",
            "logoUrl",
            "logoUrlDark",
            "longDescription",
            "privacyPolicyUrl",
            "screenshotUrls",
            "screenshots",
            "shortDescription",
            "termsOfServiceUrl",
            "websiteUrl",
        ),
        "plugin interface",
    )
    val capabilities = requiredStringArray(
        value,
        "capabilities",
        ProtocolLimits.MAX_PLUGIN_CAPABILITIES,
        256,
    )
    requiredStringArray(value, "screenshots", 64, ProtocolLimits.MAX_PATH_CHARS)
        .forEach { requireAbsolutePath(it, "Plugin screenshot") }
    requiredStringArray(value, "screenshotUrls", 64, 4_096).forEach(::safeHttpsUrl)
    optionalStringArray(value, "defaultPrompt", 3, 128)
    JsonContract.optionalString(value, "brandColor", 128)
    JsonContract.optionalString(value, "category", 512)
    optionalAbsolutePath(value, "composerIcon", "Plugin composer icon")
    optionalHttpsUrl(value, "composerIconUrl")
    JsonContract.optionalString(value, "developerName", 512)
    optionalAbsolutePath(value, "logo", "Plugin logo")
    optionalAbsolutePath(value, "logoDark", "Plugin dark logo")
    JsonContract.optionalString(value, "longDescription", 65_536)
    optionalHttpsUrl(value, "privacyPolicyUrl")
    optionalHttpsUrl(value, "termsOfServiceUrl")
    optionalHttpsUrl(value, "websiteUrl")
    return ParsedPluginInterface(
        displayName = JsonContract.optionalString(value, "displayName", 512),
        shortDescription = JsonContract.optionalString(value, "shortDescription", 16_384),
        logoUrl = JsonContract.optionalString(value, "logoUrl", 4_096)?.let(::safeHttpsUrl),
        logoDarkUrl = JsonContract.optionalString(value, "logoUrlDark", 4_096)
            ?.let(::safeHttpsUrl),
        capabilities = capabilities,
    )
}

private fun validatePluginShareContext(value: JSONObject): String {
    JsonContract.requireOnlyKeys(
        value,
        setOf(
            "canPublishToWorkspace",
            "creatorAccountUserId",
            "creatorName",
            "discoverability",
            "remotePluginId",
            "remoteVersion",
            "sharePrincipals",
            "shareUrl",
        ),
        "plugin share context",
    )
    validateNullableBoolean(value, "canPublishToWorkspace")
    JsonContract.optionalString(value, "creatorAccountUserId", 256)
    JsonContract.optionalString(value, "creatorName", 512)
    JsonContract.optionalString(value, "remoteVersion", 512)
    JsonContract.optionalString(value, "discoverability", 64)?.let { discoverability ->
        enumValue(
            discoverability,
            setOf("LISTED", "UNLISTED", "PRIVATE").associateWith { Unit },
            "plugin share discoverability",
        )
    }
    optionalHttpsUrl(value, "shareUrl")
    if (value.has("sharePrincipals") && !value.isNull("sharePrincipals")) {
        val principals = JsonContract.requiredArray(value, "sharePrincipals")
        if (principals.length() > 1_024) {
            throw FrameLimitException("Plugin has too many share principals")
        }
        repeat(principals.length()) { index ->
            val principal = principals.requiredObject(index, "plugin share principal")
            JsonContract.requireOnlyKeys(
                principal,
                setOf("name", "principalId", "principalType", "role"),
                "plugin share principal",
            )
            JsonContract.requiredString(principal, "name", 512)
            JsonContract.requiredString(principal, "principalId", 256)
            enumValue(
                JsonContract.requiredString(principal, "principalType", 64),
                setOf("user", "group", "workspace").associateWith { Unit },
                "plugin share principal type",
            )
            enumValue(
                JsonContract.requiredString(principal, "role", 64),
                setOf("reader", "editor", "owner").associateWith { Unit },
                "plugin share principal role",
            )
        }
    }
    return JsonContract.requiredString(value, "remotePluginId", 256)
}

private data class ParsedPluginSource(
    val kind: PluginSourceKind,
    val localPath: String? = null,
)

private fun parseSource(value: JSONObject): ParsedPluginSource = when (
    val type = JsonContract.requiredString(value, "type", 64)
) {
    "local" -> {
        JsonContract.requireOnlyKeys(value, setOf("path", "type"), "local plugin source")
        val path = JsonContract.requiredString(value, "path", ProtocolLimits.MAX_PATH_CHARS)
        requireAbsolutePath(path, "Local plugin")
        ParsedPluginSource(PluginSourceKind.LOCAL, path)
    }
    "git" -> {
        JsonContract.requireOnlyKeys(
            value,
            setOf("path", "refName", "sha", "type", "url"),
            "git plugin source",
        )
        JsonContract.requiredString(value, "url", 4_096)
        JsonContract.optionalString(value, "path", ProtocolLimits.MAX_PATH_CHARS)
        JsonContract.optionalString(value, "refName", 512)
        JsonContract.optionalString(value, "sha", 256)
        ParsedPluginSource(PluginSourceKind.GIT)
    }
    "npm" -> {
        JsonContract.requireOnlyKeys(
            value,
            setOf("package", "registry", "type", "version"),
            "npm plugin source",
        )
        JsonContract.requiredString(value, "package", 512)
        optionalHttpsUrl(value, "registry")
        JsonContract.optionalString(value, "version", 512)
        ParsedPluginSource(PluginSourceKind.NPM)
    }
    "remote" -> {
        JsonContract.requireOnlyKeys(value, setOf("type"), "remote plugin source")
        ParsedPluginSource(PluginSourceKind.REMOTE)
    }
    else -> throw UnsupportedProtocolValueException("Unknown plugin source type")
}

private fun parsePluginRead(result: JSONObject, locator: PluginLocator): PluginReadWireResult {
    JsonContract.requireOnlyKeys(result, setOf("plugin"), "plugin/read result")
    val plugin = JsonContract.requiredObject(result, "plugin")
    JsonContract.requireOnlyKeys(
        plugin,
        setOf(
            "appTemplates",
            "apps",
            "description",
            "hooks",
            "marketplaceName",
            "marketplacePath",
            "mcpServers",
            "scheduledTasks",
            "shareUrl",
            "skills",
            "summary",
        ),
        "plugin detail",
    )
    val marketplaceName = JsonContract.requiredString(plugin, "marketplaceName", 512)
    if (marketplaceName != locator.marketplaceName) {
        throw MalformedEnvelopeException("plugin/read marketplace correlation mismatch")
    }
    val marketplacePath = JsonContract.optionalString(
        plugin,
        "marketplacePath",
        ProtocolLimits.MAX_PATH_CHARS,
    )?.also { requireAbsolutePath(it, "Plugin detail marketplace") }
    if (marketplacePath != locator.marketplacePath) {
        throw MalformedEnvelopeException("plugin/read path correlation mismatch")
    }
    val summary = parsePluginSummary(
        JsonContract.requiredObject(plugin, "summary"),
        marketplaceName,
        marketplacePath,
        marketplaceName,
        emptySet(),
    )
    if (summary.locator.pluginName != locator.pluginName ||
        summary.locator.pluginId != locator.pluginId
    ) {
        throw MalformedEnvelopeException("plugin/read plugin correlation mismatch")
    }
    val apps = parsePluginAppSummaries(JsonContract.requiredArray(plugin, "apps"))
    val skills = parsePluginSkillSummaries(JsonContract.requiredArray(plugin, "skills"))
    val hooks = JsonContract.requiredArray(plugin, "hooks")
    if (hooks.length() > 1_024) throw FrameLimitException("Plugin has too many hooks")
    val hookSummaries = buildList(hooks.length()) {
        repeat(hooks.length()) { index ->
            val hook = hooks.requiredObject(index, "hook")
            JsonContract.requireOnlyKeys(hook, setOf("eventName", "key"), "plugin hook")
            val eventName = enumValue(
                JsonContract.requiredString(hook, "eventName", 256),
                supportedHookEventNames.associateWith { it },
                "plugin hook event",
            )
            add(
                PluginHookSummary(
                    key = JsonContract.requiredString(hook, "key", 256),
                    eventName = eventName,
                ),
            )
        }
    }
    val mcpServers = requiredStringArray(plugin, "mcpServers", 1_024, 256)
    validateAppTemplates(JsonContract.requiredArray(plugin, "appTemplates"))
    val scheduledTasks = if (!plugin.has("scheduledTasks") || plugin.isNull("scheduledTasks")) {
        emptyList()
    } else {
        val tasks = JsonContract.requiredArray(plugin, "scheduledTasks")
        if (tasks.length() > 1_024) throw FrameLimitException("Plugin has too many tasks")
        buildList(tasks.length()) {
            repeat(tasks.length()) { index ->
                add(tasks.requiredObject(index, "scheduled task").also(::validateScheduledTask))
            }
        }
    }
    return PluginReadWireResult(
        detail = PluginDetailSnapshot(
            handle = summary.card.handle,
            description = JsonContract.optionalString(plugin, "description", 65_536),
            apps = apps,
            skills = skills,
            hooks = hookSummaries,
            mcpServerCount = mcpServers.size,
            scheduledTaskCount = scheduledTasks.size,
            shareUrl = JsonContract.optionalString(plugin, "shareUrl", 4_096)
                ?.let(::safeHttpsUrl),
        ),
        sourceKind = summary.card.sourceKind,
        localSourcePath = summary.localSourcePath,
    )
}

private fun parsePluginInstall(result: JSONObject): PluginInstallWireResult {
    JsonContract.requireOnlyKeys(result, setOf("appsNeedingAuth", "authPolicy"), "install")
    return PluginInstallWireResult(
        authPolicy = enumValue(
            JsonContract.requiredString(result, "authPolicy", 64),
            PluginAuthPolicy.entries.associateBy { it.name },
            "plugin auth policy",
        ),
        appsNeedingAuth = parsePluginAppSummaries(
            JsonContract.requiredArray(result, "appsNeedingAuth"),
        ),
    )
}

private fun parseSkillConfigWrite(result: JSONObject): SkillConfigWriteWireResult {
    JsonContract.requireOnlyKeys(
        result,
        setOf("effectiveEnabled"),
        "skills/config/write result",
    )
    return SkillConfigWriteWireResult(
        effectiveEnabled = JsonContract.requiredBoolean(result, "effectiveEnabled"),
    )
}

private fun parsePluginUninstall(result: JSONObject): PluginUninstallWireResult {
    JsonContract.requireOnlyKeys(result, emptySet(), "plugin/uninstall result")
    return PluginUninstallWireResult
}

private fun parseMarketplaceAdd(result: JSONObject): MarketplaceAddWireResult {
    JsonContract.requireOnlyKeys(
        result,
        setOf("alreadyAdded", "installedRoot", "marketplaceName"),
        "marketplace/add result",
    )
    val marketplaceName = JsonContract.requiredString(result, "marketplaceName", 512)
    requireBoundedToken(marketplaceName, "Marketplace name", 512)
    val installedRoot = JsonContract.requiredString(
        result,
        "installedRoot",
        ProtocolLimits.MAX_PATH_CHARS,
    )
    requireAbsolutePath(installedRoot, "Installed marketplace root")
    return MarketplaceAddWireResult(
        marketplaceName = marketplaceName,
        installedRoot = installedRoot,
        alreadyAdded = JsonContract.requiredBoolean(result, "alreadyAdded"),
    )
}

private fun parseMarketplaceUpgrade(result: JSONObject): MarketplaceUpgradeWireResult {
    JsonContract.requireOnlyKeys(
        result,
        setOf("errors", "selectedMarketplaces", "upgradedRoots"),
        "marketplace/upgrade result",
    )
    val errors = JsonContract.requiredArray(result, "errors")
    if (errors.length() > ProtocolLimits.MAX_PLUGIN_MARKETPLACES) {
        throw FrameLimitException("Too many marketplace upgrade errors")
    }
    repeat(errors.length()) { index ->
        val error = errors.requiredObject(index, "marketplace upgrade error")
        JsonContract.requireOnlyKeys(
            error,
            setOf("marketplaceName", "message"),
            "marketplace upgrade error",
        )
        JsonContract.requiredString(error, "marketplaceName", 512)
        JsonContract.requiredString(error, "message", 16_384, allowBlank = true)
    }
    val selected = requiredStringArray(
        result,
        "selectedMarketplaces",
        ProtocolLimits.MAX_PLUGIN_MARKETPLACES,
        512,
    )
    val roots = requiredStringArray(
        result,
        "upgradedRoots",
        ProtocolLimits.MAX_PLUGIN_MARKETPLACES,
        ProtocolLimits.MAX_PATH_CHARS,
    )
    roots.forEach { requireAbsolutePath(it, "Upgraded marketplace root") }
    return MarketplaceUpgradeWireResult(selected.size, roots.size, errors.length())
}

private fun parseAppList(result: JSONObject, requestedCursor: String?): AppListPageWireResult {
    JsonContract.requireOnlyKeys(result, setOf("data", "nextCursor"), "app/list result")
    return AppListPageWireResult(
        apps = parseAppArray(JsonContract.requiredArray(result, "data")),
        nextCursor = JsonContract.optionalString(result, "nextCursor", 1_024),
        requestedCursor = requestedCursor,
    )
}

private fun parseAppArray(array: JSONArray): List<AppCard> {
    if (array.length() > ProtocolLimits.MAX_APPS_PER_PAGE) {
        throw FrameLimitException("app/list returned too many apps")
    }
    return buildList(array.length()) {
        repeat(array.length()) { index ->
            val value = array.requiredObject(index, "app")
            JsonContract.requireOnlyKeys(
                value,
                setOf(
                    "appMetadata",
                    "branding",
                    "description",
                    "distributionChannel",
                    "iconAssets",
                    "iconDarkAssets",
                    "id",
                    "installUrl",
                    "isAccessible",
                    "isEnabled",
                    "labels",
                    "logoUrl",
                    "logoUrlDark",
                    "name",
                    "pluginDisplayNames",
                ),
                "app info",
            )
            validateAppMetadata(value)
            validateAppBranding(value)
            JsonContract.optionalString(value, "distributionChannel", 512)
            validateOptionalStringMap(value, "iconAssets")
            validateOptionalStringMap(value, "iconDarkAssets")
            validateOptionalStringMap(value, "labels")
            add(
                AppCard(
                    id = JsonContract.requiredString(value, "id", 256),
                    name = JsonContract.requiredString(value, "name", 512),
                    description = JsonContract.optionalString(value, "description", 65_536),
                    installUrl = JsonContract.optionalString(value, "installUrl", 4_096)
                        ?.let(::safeHttpsUrl),
                    logoUrl = JsonContract.optionalString(value, "logoUrl", 4_096)
                        ?.let(::safeHttpsUrl),
                    logoDarkUrl = JsonContract.optionalString(value, "logoUrlDark", 4_096)
                        ?.let(::safeHttpsUrl),
                    accessible = optionalNonNullBoolean(value, "isAccessible", false),
                    enabled = optionalNonNullBoolean(value, "isEnabled", true),
                    pluginDisplayNames = optionalNonNullStringArray(
                        value,
                        "pluginDisplayNames",
                        256,
                        512,
                    ),
                ),
            )
        }
    }
}

private fun validateAppMetadata(parent: JSONObject) {
    if (!parent.has("appMetadata") || parent.isNull("appMetadata")) return
    val metadata = JsonContract.requiredObject(parent, "appMetadata")
    JsonContract.requireOnlyKeys(
        metadata,
        setOf(
            "categories",
            "developer",
            "firstPartyRequiresInstall",
            "review",
            "screenshots",
            "seoDescription",
            "showInComposerWhenUnlinked",
            "subCategories",
            "version",
            "versionId",
            "versionNotes",
        ),
        "app metadata",
    )
    optionalStringArray(metadata, "categories", 256, 512)
    optionalStringArray(metadata, "subCategories", 256, 512)
    JsonContract.optionalString(metadata, "developer", 512)
    JsonContract.optionalString(metadata, "seoDescription", 65_536)
    JsonContract.optionalString(metadata, "version", 512)
    JsonContract.optionalString(metadata, "versionId", 512)
    JsonContract.optionalString(metadata, "versionNotes", 65_536)
    validateNullableBoolean(metadata, "firstPartyRequiresInstall")
    validateNullableBoolean(metadata, "showInComposerWhenUnlinked")
    if (metadata.has("review") && !metadata.isNull("review")) {
        val review = JsonContract.requiredObject(metadata, "review")
        JsonContract.requireOnlyKeys(review, setOf("status"), "app review")
        JsonContract.requiredString(review, "status", 256)
    }
    if (metadata.has("screenshots") && !metadata.isNull("screenshots")) {
        val screenshots = JsonContract.requiredArray(metadata, "screenshots")
        if (screenshots.length() > 256) throw FrameLimitException("App has too many screenshots")
        repeat(screenshots.length()) { index ->
            val screenshot = screenshots.requiredObject(index, "app screenshot")
            JsonContract.requireOnlyKeys(
                screenshot,
                setOf("fileId", "url", "userPrompt"),
                "app screenshot",
            )
            JsonContract.optionalString(screenshot, "fileId", 512)
            optionalHttpsUrl(screenshot, "url")
            JsonContract.requiredString(
                screenshot,
                "userPrompt",
                ProtocolLimits.MAX_INPUT_TEXT_BYTES,
                allowBlank = true,
            )
        }
    }
}

private fun validateAppBranding(parent: JSONObject) {
    if (!parent.has("branding") || parent.isNull("branding")) return
    val branding = JsonContract.requiredObject(parent, "branding")
    JsonContract.requireOnlyKeys(
        branding,
        setOf(
            "category",
            "developer",
            "isDiscoverableApp",
            "privacyPolicy",
            "termsOfService",
            "website",
        ),
        "app branding",
    )
    JsonContract.optionalString(branding, "category", 512)
    JsonContract.optionalString(branding, "developer", 512)
    JsonContract.requiredBoolean(branding, "isDiscoverableApp")
    optionalHttpsUrl(branding, "privacyPolicy")
    optionalHttpsUrl(branding, "termsOfService")
    optionalHttpsUrl(branding, "website")
}

private fun parsePluginAppSummaries(array: JSONArray): List<PluginAppSummary> {
    if (array.length() > ProtocolLimits.MAX_APPS_TOTAL) {
        throw FrameLimitException("Plugin has too many apps")
    }
    return buildList(array.length()) {
        repeat(array.length()) { index ->
            val app = array.requiredObject(index, "plugin app")
            JsonContract.requireOnlyKeys(
                app,
                setOf("category", "description", "id", "installUrl", "name"),
                "plugin app summary",
            )
            JsonContract.optionalString(app, "category", 512)
            add(
                PluginAppSummary(
                    id = JsonContract.requiredString(app, "id", 256),
                    name = JsonContract.requiredString(app, "name", 512),
                    description = JsonContract.optionalString(app, "description", 65_536),
                    installUrl = JsonContract.optionalString(app, "installUrl", 4_096)
                        ?.let(::safeHttpsUrl),
                ),
            )
        }
    }
}

private fun parsePluginSkillSummaries(array: JSONArray): List<PluginSkillSummary> {
    if (array.length() > ProtocolLimits.MAX_SKILLS_TOTAL) {
        throw FrameLimitException("Plugin has too many skills")
    }
    return buildList(array.length()) {
        repeat(array.length()) { index ->
            val skill = array.requiredObject(index, "plugin skill")
            JsonContract.requireOnlyKeys(
                skill,
                setOf("description", "enabled", "interface", "name", "path", "shortDescription"),
                "plugin skill summary",
            )
            val ui = if (!skill.has("interface") || skill.isNull("interface")) {
                null
            } else {
                JsonContract.requiredObject(skill, "interface").also(::validateSkillInterface)
            }
            JsonContract.optionalString(skill, "path", ProtocolLimits.MAX_PATH_CHARS)
                ?.let { requireAbsolutePath(it, "Plugin skill") }
            add(
                PluginSkillSummary(
                    name = JsonContract.requiredString(skill, "name", 512),
                    description = JsonContract.requiredString(
                        skill,
                        "description",
                        65_536,
                        allowBlank = true,
                    ),
                    enabled = JsonContract.requiredBoolean(skill, "enabled"),
                    displayName = ui?.let { JsonContract.optionalString(it, "displayName", 512) },
                    shortDescription = ui?.let {
                        JsonContract.optionalString(it, "shortDescription", 16_384)
                    } ?: JsonContract.optionalString(skill, "shortDescription", 16_384),
                    iconSmallUrl = ui?.let {
                        JsonContract.optionalString(it, "iconSmallUrl", 4_096)
                    }?.let(::safeHttpsUrl),
                ),
            )
        }
    }
}

private fun validateSkillInterface(value: JSONObject) {
    JsonContract.requireOnlyKeys(
        value,
        setOf(
            "brandColor",
            "defaultPrompt",
            "displayName",
            "iconLarge",
            "iconLargeUrl",
            "iconSmall",
            "iconSmallUrl",
            "shortDescription",
        ),
        "skill interface",
    )
    JsonContract.optionalString(value, "brandColor", 128)
    JsonContract.optionalString(value, "defaultPrompt", 16_384)
    JsonContract.optionalString(value, "displayName", 512)
    JsonContract.optionalString(value, "shortDescription", 16_384)
    optionalAbsolutePath(value, "iconLarge", "Skill large icon")
    optionalAbsolutePath(value, "iconSmall", "Skill small icon")
    optionalHttpsUrl(value, "iconLargeUrl")
    optionalHttpsUrl(value, "iconSmallUrl")
}

internal fun flattenSkills(result: SkillsListResult): List<SkillCard> {
    val byName = LinkedHashMap<String, SkillCard>()
    result.roots.forEach { root ->
        root.skills.forEach { skill: SkillSummary ->
            val incoming = SkillCard(
                name = skill.name,
                description = skill.description,
                enabled = skill.enabled,
                displayName = skill.displayName,
                shortDescription = skill.shortDescription,
                iconSmallUrl = skill.iconSmallUrl?.let(::safeHttpsUrl),
                path = skill.path,
            )
            val previous = byName.putIfAbsent(skill.name, incoming)
            if (previous != null && previous != incoming) {
                throw MalformedEnvelopeException("Conflicting duplicate skill")
            }
        }
    }
    if (byName.size > ProtocolLimits.MAX_SKILLS_TOTAL) {
        throw FrameLimitException("Merged skill list is too large")
    }
    return byName.values.sortedBy { it.displayName ?: it.name }
}

private fun validateAppTemplates(array: JSONArray) {
    if (array.length() > ProtocolLimits.MAX_APPS_TOTAL) {
        throw FrameLimitException("Plugin has too many app templates")
    }
    repeat(array.length()) { index ->
        val template = array.requiredObject(index, "app template")
        JsonContract.requireOnlyKeys(
            template,
            setOf(
                "canonicalConnectorId",
                "category",
                "description",
                "logoUrl",
                "logoUrlDark",
                "materializedAppIds",
                "name",
                "reason",
                "templateId",
            ),
            "app template",
        )
        JsonContract.requiredString(template, "templateId", 256)
        JsonContract.requiredString(template, "name", 512)
        requiredStringArray(template, "materializedAppIds", 256, 256)
        JsonContract.optionalString(template, "canonicalConnectorId", 256)
        JsonContract.optionalString(template, "category", 512)
        JsonContract.optionalString(template, "description", 65_536)
        optionalHttpsUrl(template, "logoUrl")
        optionalHttpsUrl(template, "logoUrlDark")
        JsonContract.optionalString(template, "reason", 64)?.let { reason ->
            enumValue(
                reason,
                mapOf(
                    "NOT_CONFIGURED_FOR_WORKSPACE" to Unit,
                    "NO_ACTIVE_WORKSPACE" to Unit,
                ),
                "app template unavailable reason",
            )
        }
    }
}

private fun validateScheduledTask(task: JSONObject) {
    JsonContract.requireOnlyKeys(
        task,
        setOf("key", "name", "prompt", "schedule"),
        "scheduled task",
    )
    JsonContract.requiredString(task, "key", 256)
    JsonContract.requiredString(task, "name", 512)
    JsonContract.requiredString(task, "prompt", ProtocolLimits.MAX_INPUT_TEXT_BYTES)
    val schedule = JsonContract.requiredObject(task, "schedule")
    when (JsonContract.requiredString(schedule, "type", 64)) {
        "hourly" -> {
            JsonContract.requireOnlyKeys(
                schedule,
                setOf("days", "intervalHours", "type"),
                "hourly schedule",
            )
            val intervalHours = JsonContract.requiredLong(schedule, "intervalHours")
            if (intervalHours !in 0..UInt.MAX_VALUE.toLong()) {
                throw MalformedEnvelopeException("Scheduled task interval is outside uint32")
            }
            optionalWeekdays(schedule, "days")
        }
        "daily", "weekdays" -> {
            JsonContract.requireOnlyKeys(schedule, setOf("time", "type"), "daily schedule")
            JsonContract.requiredString(schedule, "time", 64)
        }
        "weekly" -> {
            JsonContract.requireOnlyKeys(
                schedule,
                setOf("days", "time", "type"),
                "weekly schedule",
            )
            JsonContract.requiredString(schedule, "time", 64)
            requiredWeekdays(schedule, "days")
        }
        else -> throw UnsupportedProtocolValueException("Unknown scheduled task type")
    }
}

private fun requiredWeekdays(parent: JSONObject, key: String) {
    val days = requiredStringArray(parent, key, 7, 16)
    validateWeekdays(days)
}

private fun optionalWeekdays(parent: JSONObject, key: String) {
    if (!parent.has(key) || parent.isNull(key)) return
    requiredWeekdays(parent, key)
}

private fun validateWeekdays(days: List<String>) {
    val supported = setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    if (days.any { it !in supported } || days.toSet().size != days.size) {
        throw UnsupportedProtocolValueException("Invalid scheduled task weekday")
    }
}

private fun pluginHandle(locator: PluginLocator): PluginHandle {
    val value = listOf(
        locator.marketplaceName,
        locator.marketplacePath.orEmpty(),
        locator.pluginId,
        locator.pluginName,
    ).joinToString("\u0000")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    return PluginHandle(digest.joinToString("") { byte -> "%02x".format(byte) })
}

private fun safeHttpsUrl(value: String): String {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw MalformedEnvelopeException("Invalid HTTPS URL in plugin metadata")
    }
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.isNullOrBlank() ||
        uri.userInfo != null
    ) {
        throw MalformedEnvelopeException("Unsafe URL in plugin metadata")
    }
    return value
}

private fun requireAbsolutePath(value: String, label: String) {
    if (!value.startsWith('/') || value.length > ProtocolLimits.MAX_PATH_CHARS || '\u0000' in value) {
        throw MalformedEnvelopeException("$label path is invalid")
    }
}

private fun optionalAbsolutePath(parent: JSONObject, key: String, label: String) {
    JsonContract.optionalString(parent, key, ProtocolLimits.MAX_PATH_CHARS)
        ?.let { requireAbsolutePath(it, label) }
}

private fun optionalHttpsUrl(parent: JSONObject, key: String): String? =
    JsonContract.optionalString(parent, key, 4_096)?.let(::safeHttpsUrl)

private fun optionalNonNullString(
    parent: JSONObject,
    key: String,
    maximumBytes: Int,
): String? {
    if (!parent.has(key)) return null
    return JsonContract.requiredString(parent, key, maximumBytes)
}

private fun optionalNonNullBoolean(
    parent: JSONObject,
    key: String,
    default: Boolean,
): Boolean {
    if (!parent.has(key)) return default
    return JsonContract.requiredBoolean(parent, key)
}

private fun validateNullableBoolean(parent: JSONObject, key: String) {
    if (!parent.has(key) || parent.isNull(key)) return
    JsonContract.requiredBoolean(parent, key)
}

private fun validateOptionalStringMap(parent: JSONObject, key: String) {
    if (!parent.has(key) || parent.isNull(key)) return
    val values = JsonContract.requiredObject(parent, key)
    if (values.length() > 1_024) throw FrameLimitException("$key has too many entries")
    val keys = values.keys()
    while (keys.hasNext()) {
        val entryKey = keys.next()
        JsonContract.requireUtf8Bound(entryKey, 512, "$key key")
        JsonContract.requiredString(values, entryKey, 4_096, allowBlank = true)
    }
}

private fun requiredStringArray(
    parent: JSONObject,
    key: String,
    maximumItems: Int,
    maximumBytes: Int,
): List<String> {
    val values = JsonContract.requiredArray(parent, key)
    return parseStringArray(values, maximumItems, maximumBytes, key)
}

private fun optionalStringArray(
    parent: JSONObject,
    key: String,
    maximumItems: Int,
    maximumBytes: Int,
): List<String> {
    if (!parent.has(key) || parent.isNull(key)) return emptyList()
    return parseStringArray(
        JsonContract.requiredArray(parent, key),
        maximumItems,
        maximumBytes,
        key,
    )
}

private fun optionalNonNullStringArray(
    parent: JSONObject,
    key: String,
    maximumItems: Int,
    maximumBytes: Int,
): List<String> {
    if (!parent.has(key)) return emptyList()
    return parseStringArray(
        JsonContract.requiredArray(parent, key),
        maximumItems,
        maximumBytes,
        key,
    )
}

private fun parseStringArray(
    array: JSONArray,
    maximumItems: Int,
    maximumBytes: Int,
    label: String,
): List<String> {
    if (array.length() > maximumItems) throw FrameLimitException("$label has too many items")
    return buildList(array.length()) {
        repeat(array.length()) { index ->
            val value = array.opt(index) as? String
                ?: throw MalformedEnvelopeException("Expected string in $label")
            JsonContract.requireUtf8Bound(value, maximumBytes, label)
            add(value)
        }
    }
}

private fun optionalNonNullObjectArray(
    parent: JSONObject,
    key: String,
    maximumItems: Int,
): List<JSONObject> {
    if (!parent.has(key)) return emptyList()
    val values = JsonContract.requiredArray(parent, key)
    if (values.length() > maximumItems) throw FrameLimitException("$key has too many items")
    return buildList(values.length()) {
        repeat(values.length()) { index -> add(values.requiredObject(index, key)) }
    }
}

private fun JSONArray.requiredObject(index: Int, label: String): JSONObject =
    opt(index) as? JSONObject
        ?: throw MalformedEnvelopeException("Expected $label object at index $index")

private fun <T> enumValue(value: String, values: Map<String, T>, label: String): T =
    values[value] ?: throw UnsupportedProtocolValueException("Unknown $label")
