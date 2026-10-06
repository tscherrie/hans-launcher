package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONObject

sealed interface CodexInput {
    fun toJson(): JSONObject

    data class Text(val text: String) : CodexInput {
        init {
            require(text.isNotBlank()) { "Text input must not be blank" }
            JsonContract.requireUtf8Bound(
                text,
                ProtocolLimits.MAX_INPUT_TEXT_BYTES,
                "Text input",
            )
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("type", "text")
            .put("text", text)
    }

    /**
     * Context supplied by Hans rather than typed by the user.
     *
     * App Server 0.154.0 has no separate per-turn developer-context input, so this is encoded as
     * an ordinary text input on the wire. Keeping it as a distinct Kotlin type prevents it from
     * being projected into the visible user-message bubble. Callers must wrap all external data
     * with explicit data-only boundaries before constructing this value.
     */
    data class UntrustedContext(val text: String) : CodexInput {
        init {
            require(text.isNotBlank()) { "Untrusted context input must not be blank" }
            JsonContract.requireUtf8Bound(
                text,
                ProtocolLimits.MAX_INPUT_TEXT_BYTES,
                "Untrusted context input",
            )
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("type", "text")
            .put("text", text)
    }

    data class LocalImage(val absolutePath: String) : CodexInput {
        init {
            requireAbsolutePath(absolutePath, "Local image")
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("type", "localImage")
            .put("path", absolutePath)
    }

    data class LocalAudio(val absolutePath: String) : CodexInput {
        init {
            requireAbsolutePath(absolutePath, "Local audio")
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("type", "localAudio")
            .put("path", absolutePath)
    }

    /**
     * Explicit App Server skill attachment. Merely mentioning a skill in text does not prove
     * that a newly installed plugin skill is loaded for this turn.
     */
    data class Skill(
        val name: String,
        val absolutePath: String,
    ) : CodexInput {
        init {
            requireWireToken(name, "Skill name")
            requireAbsolutePath(absolutePath, "Skill")
        }

        override fun toJson(): JSONObject = JSONObject()
            .put("type", "skill")
            .put("name", name)
            .put("path", absolutePath)
    }
}

object AppServerRequests {
    /**
     * In pinned 0.155, reading a loaded paginated thread with turns awaits persist_thread.
     * The caller must hold first-turn admission until this empty fresh-thread receipt arrives.
     * Never use this full-history compatibility path to bootstrap an existing thread.
     */
    fun materializeFreshThread(id: RequestId, threadId: String): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        return encode(
            id = id,
            method = AppServerMethod.THREAD_READ,
            params = JSONObject().put("threadId", threadId).put("includeTurns", true),
            context = RequestContext.ThreadMaterialize(threadId),
        )
    }

    fun initialize(
        id: RequestId,
        clientName: String = "hans-android",
        clientTitle: String = "Hans",
        clientVersion: String,
        experimentalApi: Boolean = true,
    ): EncodedRequest {
        requireWireToken(clientName, "Client name")
        require(clientTitle.isNotBlank() && clientTitle.length <= 128) {
            "Client title is invalid"
        }
        require(clientVersion.isNotBlank() && clientVersion.length <= 64) {
            "Client version is invalid"
        }
        return encode(
            id = id,
            method = AppServerMethod.INITIALIZE,
            params = JSONObject()
                .put(
                    "clientInfo",
                    JSONObject()
                        .put("name", clientName)
                        .put("title", clientTitle)
                        .put("version", clientVersion),
                )
                .put(
                    "capabilities",
                    JSONObject()
                        .put("experimentalApi", experimentalApi)
                        .put(
                            "optOutNotificationMethods",
                            JSONArray().put(
                                CodexProtocolContract.APP_LIST_UPDATED_NOTIFICATION,
                            ),
                        ),
                ),
        )
    }

    fun accountRead(id: RequestId, refreshToken: Boolean = false): EncodedRequest = encode(
        id = id,
        method = AppServerMethod.ACCOUNT_READ,
        params = JSONObject().put("refreshToken", refreshToken),
    )

    fun deviceCodeLogin(id: RequestId): EncodedRequest = encode(
        id = id,
        method = AppServerMethod.ACCOUNT_LOGIN_START,
        params = JSONObject().put("type", "chatgptDeviceCode"),
    )

    fun accountLogout(id: RequestId): EncodedRequest = encode(
        id = id,
        method = AppServerMethod.ACCOUNT_LOGOUT,
        params = null,
    )

    fun modelList(
        id: RequestId,
        cursor: String? = null,
        limit: Int = ProtocolLimits.MAX_MODELS_PER_PAGE,
        includeHidden: Boolean = false,
    ): EncodedRequest {
        require(limit in 1..ProtocolLimits.MAX_MODELS_PER_PAGE) {
            "Model page limit must be between 1 and ${ProtocolLimits.MAX_MODELS_PER_PAGE}"
        }
        cursor?.let {
            JsonContract.requireUtf8Bound(it, 1_024, "Model cursor")
            require(it.isNotBlank()) { "Model cursor must not be blank" }
        }
        return encode(
            id = id,
            method = AppServerMethod.MODEL_LIST,
            params = JSONObject()
                .put("includeHidden", includeHidden)
                .put("limit", limit)
                .putIfNotNull("cursor", cursor),
            context = RequestContext.ModelList(cursor),
        )
    }

    /** Update only next-turn model settings; this never starts or interrupts a turn. */
    fun threadSettingsUpdate(
        id: RequestId,
        threadId: String,
        model: String,
        effort: ReasoningEffort,
        serviceTier: String?,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        requireWireToken(model, "Model")
        serviceTier?.let { requireWireToken(it, "Service tier") }
        return encode(
            id = id,
            method = AppServerMethod.THREAD_SETTINGS_UPDATE,
            params = JSONObject()
                .put("threadId", threadId)
                .put("model", model)
                .put("effort", effort.wireValue)
                // In 0.154 null explicitly clears a sticky tier; omission preserves it.
                .put("serviceTier", serviceTier ?: JSONObject.NULL),
            context = RequestContext.ThreadSettingsUpdate(threadId, model, effort, serviceTier),
        )
    }

    fun threadStart(
        id: RequestId,
        options: DispatchOptions,
        developerInstructions: String? = null,
        baseInstructions: String? = null,
        ephemeral: Boolean = false,
        dynamicTools: List<DynamicToolNamespaceSpec> = emptyList(),
        disablePhoneToolsMcp: Boolean = false,
    ): EncodedRequest {
        developerInstructions?.let {
            JsonContract.requireUtf8Bound(
                it,
                ProtocolLimits.MAX_DEVELOPER_INSTRUCTIONS_BYTES,
                "Developer instructions",
            )
        }
        baseInstructions?.let {
            JsonContract.requireUtf8Bound(
                it,
                ProtocolLimits.MAX_DEVELOPER_INSTRUCTIONS_BYTES,
                "Base instructions",
            )
        }
        val params = JSONObject()
            .put("allowProviderModelFallback", false)
            .put("model", options.model)
            // ThreadStartParams has no top-level effort field. Set the supported config
            // override so thread/start already acknowledges the selected effort instead of
            // inheriting the process default and rejecting non-default automation runs.
            .put("config", JSONObject().put("model_reasoning_effort", options.effort.wireValue)
                .apply { if (disablePhoneToolsMcp) put("mcp_servers.hans_phone.enabled", false) })
            .put("ephemeral", ephemeral)
            .put("serviceTier", options.serviceTier)
            .putIfNotNull("approvalPolicy", options.approvalPolicy?.wireValue)
            .putIfNotNull("permissions", options.permissionsProfile)
            .putIfNotNull("sandbox", options.sandbox?.threadStartWireValue)
            .putIfNotNull("cwd", options.cwd)
            .putIfNotNull("personality", options.personality?.wireValue)
            .putIfNotNull("developerInstructions", developerInstructions)
            .putIfNotNull("baseInstructions", baseInstructions)
        if (dynamicTools.isNotEmpty()) {
            params.put("dynamicTools", dynamicTools.toDynamicToolsJson())
        }
        return encode(
            id = id,
            method = AppServerMethod.THREAD_START,
            params = params,
            context = RequestContext.ThreadStart(options),
        )
    }

    fun threadResume(
        id: RequestId,
        threadId: String,
        excludeTurns: Boolean = true,
        developerInstructions: String? = null,
        disablePhoneToolsMcp: Boolean = false,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        val initialTurnsLimit = ProtocolLimits.RECENT_HISTORY_TURN_LIMIT
        developerInstructions?.let {
            JsonContract.requireUtf8Bound(
                it,
                ProtocolLimits.MAX_DEVELOPER_INSTRUCTIONS_BYTES,
                "Developer instructions",
            )
        }
        return encode(
            id = id,
            method = AppServerMethod.THREAD_RESUME,
            params = JSONObject()
                .put("threadId", threadId)
                .put("excludeTurns", excludeTurns)
                .apply { if (disablePhoneToolsMcp) put("config", JSONObject().put("mcp_servers.hans_phone.enabled", false)) }
                .put(
                    "initialTurnsPage",
                    JSONObject()
                        .put("limit", initialTurnsLimit)
                        .put("sortDirection", "desc")
                        // Summary is deliberately transport-safe: App Server returns only
                        // the first user message and final agent message of each turn, never
                        // persisted command/MCP/dynamic-tool payloads.
                        .put("itemsView", "summary"),
                )
                .putIfNotNull("developerInstructions", developerInstructions),
            context = RequestContext.ThreadResume(
                threadId = threadId,
                requestedInitialTurnsLimit = initialTurnsLimit,
            ),
        )
    }

    /**
     * Enables memory generation for an exact persisted personal thread. Hans calls this after
     * both start and resume, because Codex 0.149 deliberately preserves the stored mode of an
     * existing thread when the process-wide memory feature is enabled later.
     */
    fun threadMemoryModeSetEnabled(
        id: RequestId,
        threadId: String,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        return encode(
            id = id,
            method = AppServerMethod.THREAD_MEMORY_MODE_SET,
            params = JSONObject()
                .put("threadId", threadId)
                .put("mode", "enabled"),
        )
    }

    fun threadList(
        id: RequestId,
        cursor: String? = null,
        limit: Int = 50,
    ): EncodedRequest {
        require(limit in 1..ProtocolLimits.MAX_THREADS_PER_PAGE) {
            "Thread page limit must be between 1 and ${ProtocolLimits.MAX_THREADS_PER_PAGE}"
        }
        cursor?.let {
            JsonContract.requireUtf8Bound(it, 1_024, "Thread cursor")
            require(it.isNotBlank()) { "Thread cursor must not be blank" }
        }
        return encode(
            id = id,
            method = AppServerMethod.THREAD_LIST,
            params = JSONObject()
                .put("limit", limit)
                .put("sortDirection", "desc")
                .put("sortKey", "updated_at")
                .putIfNotNull("cursor", cursor),
        )
    }

    fun turnStart(
        id: RequestId,
        threadId: String,
        input: List<CodexInput>,
        options: DispatchOptions,
        clientUserMessageId: String? = null,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        require(input.isNotEmpty()) { "A turn requires at least one input" }
        require(input.size <= 32) { "A turn has too many input items" }
        clientUserMessageId?.let { requireOpaqueId(it, "Client user message id") }
        val params = JSONObject()
            .put("threadId", threadId)
            .put("input", input.toJsonArray())
            .put("model", options.model)
            .put("effort", options.effort.wireValue)
            .put("serviceTier", options.serviceTier)
            .putIfNotNull("approvalPolicy", options.approvalPolicy?.wireValue)
            .putIfNotNull("permissions", options.permissionsProfile)
            .putIfNotNull(
                "sandboxPolicy",
                options.sandbox?.let { JSONObject().put("type", it.turnStartPolicyType) },
            )
            .putIfNotNull("cwd", options.cwd)
            .putIfNotNull("personality", options.personality?.wireValue)
            .putIfNotNull("summary", options.reasoningSummary?.wireValue)
            .putIfNotNull("clientUserMessageId", clientUserMessageId)
        return encode(
            id = id,
            method = AppServerMethod.TURN_START,
            params = params,
            context = RequestContext.TurnStart(threadId, options),
        )
    }

    /** External notification data enters at tool authority, never as a user-message steer. */
    internal fun notificationToolOutputTurnStart(
        id: RequestId,
        threadId: String,
        output: String,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        JsonContract.requireUtf8Bound(output, 64 * 1024, "Notification output")
        require(output.isNotBlank())
        return encode(id, AppServerMethod.TURN_START,
            JSONObject().put("threadId", threadId).put("input", JSONArray()).put("toolOutput",
                JSONObject().put("name", "push_event").put("namespace", "hans_notifications")
                    .put("output", output)),
            context = RequestContext.NotificationToolOutputTurn(threadId))
    }

    /** One bounded passive persisted-history page; no model execution or UI hydration. */
    internal fun notificationExternalHistory(
        id: RequestId,
        threadId: String,
        decoder: ExtensionResultDecoder,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        return encode(id, AppServerMethod.THREAD_TURNS_LIST,
            JSONObject().put("threadId", threadId).put("limit", 8)
                .put("sortDirection", "desc").put("itemsView", "full"),
            extensionResultDecoder = decoder)
    }

    internal fun turnSteer(
        id: RequestId,
        activeTurn: ActiveTurn,
        input: List<CodexInput>,
        clientUserMessageId: String? = null,
    ): EncodedRequest = encodeTurnSteer(
        id, activeTurn.threadId, activeTurn.turnId, input, clientUserMessageId,
        activeTurn.effectiveOptions,
    )

    /** A recovered active identity permits steering, but proves no model or reasoning options. */
    fun turnSteerKnownIdentity(
        id: RequestId,
        threadId: String,
        expectedTurnId: String,
        input: List<CodexInput>,
        clientUserMessageId: String? = null,
    ): EncodedRequest = encodeTurnSteer(
        id, threadId, expectedTurnId, input, clientUserMessageId, effectiveOptions = null,
    )

    private fun encodeTurnSteer(
        id: RequestId,
        threadId: String,
        expectedTurnId: String,
        input: List<CodexInput>,
        clientUserMessageId: String?,
        effectiveOptions: DispatchOptions?,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        requireOpaqueId(expectedTurnId, "Expected turn id")
        require(input.isNotEmpty()) { "A steer requires at least one input" }
        require(input.size <= 32) { "A steer has too many input items" }
        clientUserMessageId?.let { requireOpaqueId(it, "Client user message id") }
        return encode(
            id = id,
            method = AppServerMethod.TURN_STEER,
            params = JSONObject()
                .put("threadId", threadId)
                .put("expectedTurnId", expectedTurnId)
                .put("input", input.toJsonArray())
                .putIfNotNull("clientUserMessageId", clientUserMessageId),
            context = RequestContext.TurnSteer(
                threadId = threadId,
                expectedTurnId = expectedTurnId,
                effectiveOptions = effectiveOptions,
            ),
        )
    }

    fun turnInterrupt(
        id: RequestId,
        threadId: String,
        turnId: String,
    ): EncodedRequest {
        requireOpaqueId(threadId, "Thread id")
        requireOpaqueId(turnId, "Turn id")
        return encode(
            id = id,
            method = AppServerMethod.TURN_INTERRUPT,
            params = JSONObject()
                .put("threadId", threadId)
                .put("turnId", turnId),
            context = RequestContext.TurnInterrupt(threadId, turnId),
        )
    }

    fun skillsList(
        id: RequestId,
        workingDirectories: List<String>,
        forceReload: Boolean = false,
    ): EncodedRequest {
        require(workingDirectories.size <= ProtocolLimits.MAX_SKILL_ROOTS) {
            "Too many skill working directories"
        }
        workingDirectories.forEach { requireAbsolutePath(it, "Skill working directory") }
        return encode(
            id = id,
            method = AppServerMethod.SKILLS_LIST,
            params = JSONObject()
                .put("cwds", JSONArray(workingDirectories))
                .put("forceReload", forceReload),
        )
    }

    /** Module-internal extension seam; method and decoder remain closed and correlated. */
    internal fun extensionRequest(
        id: RequestId,
        method: AppServerMethod,
        params: JSONObject,
        decoder: ExtensionResultDecoder,
    ): EncodedRequest {
        require(
            method in setOf(
                AppServerMethod.PLUGIN_LIST,
                AppServerMethod.PLUGIN_READ,
                AppServerMethod.PLUGIN_INSTALL,
                AppServerMethod.PLUGIN_UNINSTALL,
                AppServerMethod.MARKETPLACE_ADD,
                AppServerMethod.MARKETPLACE_UPGRADE,
                AppServerMethod.APP_LIST,
                AppServerMethod.SKILLS_CONFIG_WRITE,
            ),
        ) { "Method is not an App Server extension method" }
        return encode(
            id = id,
            method = method,
            params = params,
            extensionResultDecoder = decoder,
        )
    }

    private fun encode(
        id: RequestId,
        method: AppServerMethod,
        params: JSONObject?,
        context: RequestContext = RequestContext.None,
        extensionResultDecoder: ExtensionResultDecoder? = null,
    ): EncodedRequest {
        val envelope = JSONObject()
        when (id) {
            is RequestId.Number -> envelope.put("id", id.value)
            is RequestId.Text -> envelope.put("id", id.value)
        }
        envelope.put("method", method.wireName)
        if (params != null) envelope.put("params", params)
        return EncodedRequest(
            id = id,
            method = method,
            json = JsonContract.encodeBounded(
                envelope,
                ProtocolLimits.MAX_OUTBOUND_FRAME_BYTES,
            ),
            context = context,
            extensionResultDecoder = extensionResultDecoder,
        )
    }
}

private fun List<CodexInput>.toJsonArray(): JSONArray = JSONArray().also { result ->
    forEach { result.put(it.toJson()) }
}

internal fun requireOpaqueId(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= ProtocolLimits.MAX_OPAQUE_ID_CHARS) { "$label is too long" }
    require(value.none { it.isISOControl() }) { "$label contains control characters" }
}

private fun requireAbsolutePath(value: String, label: String) {
    require(value.startsWith('/')) { "$label path must be absolute" }
    require(value.length <= ProtocolLimits.MAX_PATH_CHARS) { "$label path is too long" }
    require(value.none { it == '\u0000' }) { "$label path contains NUL" }
}
