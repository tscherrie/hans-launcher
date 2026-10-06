package ai.hans.standard.backup

import ai.hans.standard.automations.AutomationCapabilityId
import ai.hans.standard.automations.AutomationConfirmationPolicy
import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.automations.AutomationId
import ai.hans.standard.automations.AutomationPermissionId
import ai.hans.standard.automations.AutomationRequirements
import ai.hans.standard.automations.AutomationRetryPolicy
import ai.hans.standard.automations.AutomationSchedule
import ai.hans.standard.automations.AutomationTimeZone
import ai.hans.standard.automations.AutomationTimingPolicy
import ai.hans.standard.automations.CodexAutomationTarget
import ai.hans.standard.automations.MissedRunMode
import ai.hans.standard.automations.MissedRunPolicy
import ai.hans.standard.automations.RRuleParseResult
import ai.hans.standard.automations.RRuleParser
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.settings.ActiveTurnInputMode
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.ReadAloudMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, strict and deterministic external document codec. SHA-256 detects corruption only. */
object HansBackupDocumentCodec {
    const val MIME_TYPE = "application/vnd.ai.hans.backup+json"
    const val FILE_EXTENSION = ".hansbackup"
    private const val FORMAT = "ai.hans.standard.backup"
    private const val VERSION = 1
    private const val INTEGRITY_ALGORITHM = "SHA-256"

    fun encode(payload: HansBackupPayload, createdAtEpochMillis: Long): ByteArray {
        require(createdAtEpochMillis >= 0) { "backup_created_at" }
        validatePortablePayload(payload)
        val payloadJson = payload.json()
        val digest = sha256(canonicalJson(payloadJson).toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAtEpochMillis", createdAtEpochMillis)
            .put("payload", payloadJson)
            .put(
                "integrity",
                JSONObject()
                    .put("algorithm", INTEGRITY_ALGORITHM)
                    .put("payloadSha256", digest),
            )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
            .also { bytes ->
                require(bytes.size <= HansBackupLimits.MAX_DOCUMENT_BYTES) {
                    "backup_document_oversize"
                }
            }
    }

    fun decode(bytes: ByteArray): HansBackupDocument {
        if (bytes.isEmpty()) throw HansBackupException("backup_document_empty")
        if (bytes.size > HansBackupLimits.MAX_DOCUMENT_BYTES) {
            throw HansBackupException("backup_document_oversize")
        }
        return try {
            val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            root.requireExactKeys(
                setOf("format", "version", "createdAtEpochMillis", "payload", "integrity"),
            )
            require(root.getString("format") == FORMAT) { "backup_format" }
            require(root.getInt("version") == VERSION) { "backup_version" }
            val createdAt = root.getLong("createdAtEpochMillis")
            require(createdAt >= 0) { "backup_created_at" }
            val payloadJson = root.getJSONObject("payload")
            val integrity = root.getJSONObject("integrity")
            integrity.requireExactKeys(setOf("algorithm", "payloadSha256"))
            require(integrity.getString("algorithm") == INTEGRITY_ALGORITHM) {
                "backup_integrity_algorithm"
            }
            val expected = integrity.getString("payloadSha256")
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "backup_integrity_format" }
            val actual = sha256(canonicalJson(payloadJson).toByteArray(StandardCharsets.UTF_8))
            require(
                MessageDigest.isEqual(
                    expected.toByteArray(StandardCharsets.US_ASCII),
                    actual.toByteArray(StandardCharsets.US_ASCII),
                ),
            ) { "backup_integrity_mismatch" }
            val payload = payloadJson.payload()
            validatePortablePayload(payload)
            HansBackupDocument(createdAt, payload, actual)
        } catch (failure: HansBackupException) {
            throw failure
        } catch (failure: Exception) {
            val code = (failure.message ?: "")
                .takeIf { it.matches(Regex("backup_[a-z0-9_]+")) }
                ?: "backup_document_invalid"
            throw HansBackupException(code, failure)
        }
    }

    fun payloadFingerprint(payload: HansBackupPayload): String {
        validatePortablePayload(payload)
        return sha256(canonicalJson(payload.json()).toByteArray(StandardCharsets.UTF_8))
    }

    private fun validatePortablePayload(payload: HansBackupPayload) {
        payload.confirmedProfileSummary?.let {
            requirePortableText(it, HansBackupLimits.MAX_PROFILE_CHARS, "backup_profile")
        }
        payload.automations.forEach { definition ->
            requirePortableText(definition.instruction, 32_768, "backup_automation_instruction")
            require(
                RRuleParser.parse(definition.schedule.rrule) is RRuleParseResult.Valid,
            ) { "backup_automation_rrule" }
        }
    }

    private fun HansBackupPayload.json(): JSONObject = JSONObject()
        .put("settings", settings.json())
        .put(
            "confirmedProfileSummary",
            confirmedProfileSummary ?: JSONObject.NULL,
        )
        .put(
            "automations",
            JSONArray().also { array ->
                automations.sortedBy { it.id.value }.forEach { array.put(it.backupJson()) }
            },
        )
        .put(
            "retargetedThreadBoundAutomationIds",
            JSONArray().also { array ->
                retargetedThreadBoundAutomationIds.sorted().forEach(array::put)
            },
        )
        .put(
            "plugins",
            JSONArray().also { array ->
                plugins.sortedBy { it.pluginId }.forEach { array.put(it.json()) }
            },
        )
        .put(
            "skills",
            JSONArray().also { array ->
                skills.sortedBy { it.name }.forEach { array.put(it.json()) }
            },
        )
        .put("workspace", JSONObject().put("kind", workspace.wireValue))

    private fun JSONObject.payload(): HansBackupPayload {
        requireExactKeys(
            setOf(
                "settings",
                "confirmedProfileSummary",
                "automations",
                "retargetedThreadBoundAutomationIds",
                "plugins",
                "skills",
                "workspace",
            ),
        )
        val automationArray = getJSONArray("automations")
        require(automationArray.length() <= HansBackupLimits.MAX_AUTOMATIONS) {
            "backup_automation_count"
        }
        val pluginArray = getJSONArray("plugins")
        require(pluginArray.length() <= HansBackupLimits.MAX_PLUGINS) { "backup_plugin_count" }
        val skillArray = getJSONArray("skills")
        require(skillArray.length() <= HansBackupLimits.MAX_SKILLS) { "backup_skill_count" }
        val retargeted = getJSONArray("retargetedThreadBoundAutomationIds")
        require(retargeted.length() <= HansBackupLimits.MAX_AUTOMATIONS) {
            "backup_retargeted_automation"
        }
        val workspaceJson = getJSONObject("workspace")
        workspaceJson.requireExactKeys(setOf("kind"))
        return HansBackupPayload(
            settings = getJSONObject("settings").settings(),
            confirmedProfileSummary = nullableString("confirmedProfileSummary"),
            automations = buildList(automationArray.length()) {
                repeat(automationArray.length()) { add(automationArray.getJSONObject(it).automation()) }
            },
            retargetedThreadBoundAutomationIds = buildSet(retargeted.length()) {
                repeat(retargeted.length()) { add(retargeted.getString(it)) }
            },
            plugins = buildList(pluginArray.length()) {
                repeat(pluginArray.length()) { add(pluginArray.getJSONObject(it).plugin()) }
            },
            skills = buildList(skillArray.length()) {
                repeat(skillArray.length()) { add(skillArray.getJSONObject(it).skill()) }
            },
            workspace = BackupWorkspaceMetadata.fromWire(workspaceJson.getString("kind"))
                ?: error("backup_workspace_kind"),
        )
    }

    private fun HansSettings.json(): JSONObject = JSONObject()
        .put("model", model)
        .put("reasoningEffort", reasoningEffort)
        .put("serviceTier", serviceTier)
        .put("activeTurnInputMode", activeTurnInputMode.wireValue)
        .put("voice", voice)
        .put("liveVoice", liveVoice)
        .put("codexLiveVoice", codexLiveVoice)
        .put("speechRate", speechRate.toDouble())
        .put("readAloudMode", readAloudMode.wireValue)
        .put("dictationKeyTrigger", dictationKeyTrigger.name)
        .put("cameraHoldToTalkEnabled", cameraHoldToTalkEnabled)

    private fun JSONObject.settings(): HansSettings {
        val legacyKeys = setOf(
                "model",
                "reasoningEffort",
                "activeTurnInputMode",
                "voice",
                "speechRate",
                "readAloudMode",
                "dictationKeyTrigger",
                "cameraHoldToTalkEnabled",
            )
        val optionalKeys = setOf("serviceTier", "liveVoice", "codexLiveVoice").filterTo(mutableSetOf()) { has(it) }
        requireExactKeys(legacyKeys + optionalKeys)
        require(getString("activeTurnInputMode") == ActiveTurnInputMode.STEER.wireValue) {
            "backup_input_mode"
        }
        return HansSettings(
            model = getString("model"),
            reasoningEffort = getString("reasoningEffort"),
            serviceTier = if (has("serviceTier") && !isNull("serviceTier")) {
                getString("serviceTier")
            } else {
                HansSettings.DEFAULT_SERVICE_TIER
            },
            activeTurnInputMode = ActiveTurnInputMode.STEER,
            voice = getString("voice"),
            liveVoice = if (has("liveVoice") && !isNull("liveVoice")) {
                getString("liveVoice")
            } else {
                HansSettings.DEFAULT_LIVE_VOICE
            },
            codexLiveVoice = if (has("codexLiveVoice") && !isNull("codexLiveVoice")) {
                getString("codexLiveVoice")
            } else {
                HansSettings.DEFAULT_CODEX_LIVE_VOICE
            },
            speechRate = getDouble("speechRate").toFloat(),
            readAloudMode = ReadAloudMode.fromWire(getString("readAloudMode"))
                ?: error("backup_read_aloud_mode"),
            dictationKeyTrigger = ActionKeyTrigger.entries.firstOrNull {
                it.name == getString("dictationKeyTrigger")
            } ?: error("backup_dictation_trigger"),
            cameraHoldToTalkEnabled = getBoolean("cameraHoldToTalkEnabled"),
        )
    }

    private fun AutomationDefinition.backupJson(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("revision", revision)
        .put("enabled", enabled)
        .put("start", schedule.dtStartLocal.toString())
        .put("zoneKind", if (schedule.timeZone is AutomationTimeZone.Fixed) "fixed" else "system")
        .put("zoneId", (schedule.timeZone as? AutomationTimeZone.Fixed)?.zoneId ?: JSONObject.NULL)
        .put("rrule", schedule.rrule)
        .put("missedMode", missedRunPolicy.mode.name)
        .put("grace", missedRunPolicy.gracePeriod.toString())
        .put("maximumCatchUp", missedRunPolicy.maximumCatchUpRuns)
        .put("maximumAttempts", retryPolicy.maximumAttempts)
        .put("initialBackoff", retryPolicy.initialBackoff.toString())
        .put("backoffMultiplier", retryPolicy.backoffMultiplier)
        .put("maximumBackoff", retryPolicy.maximumBackoff.toString())
        // Thread identifiers are conversation/session data. Every portable restore starts fresh.
        .put("target", "independent")
        .put("instruction", instruction)
        .put("updatedAt", updatedAt.toString())
        .put(
            "capabilities",
            JSONArray().also { out ->
                requirements.requiredCapabilities.sorted().forEach { out.put(it.value) }
            },
        )
        .put(
            "permissions",
            JSONArray().also { out ->
                requirements.requiredPermissions.sorted().forEach { out.put(it.value) }
            },
        )
        .put("requiresCodexAuthentication", requirements.requiresCodexAuthentication)
        .put("requiresNetwork", requirements.requiresNetwork)
        .put("requiresUnlockedDevice", requirements.requiresUnlockedDevice)
        .put("confirmationPolicy", requirements.confirmationPolicy.name)
        .put("timingPolicy", timingPolicy.name)

    private fun JSONObject.automation(): AutomationDefinition {
        requireExactKeys(AUTOMATION_KEYS)
        require(getString("target") == "independent") { "backup_automation_target" }
        val capabilities = getJSONArray("capabilities")
        val permissions = getJSONArray("permissions")
        require(capabilities.length() <= 64) { "backup_automation_capabilities" }
        require(permissions.length() <= 64) { "backup_automation_permissions" }
        val definition = AutomationDefinition(
            id = AutomationId(getString("id")),
            revision = getLong("revision"),
            enabled = getBoolean("enabled"),
            schedule = AutomationSchedule(
                dtStartLocal = LocalDateTime.parse(getString("start")),
                timeZone = when (getString("zoneKind")) {
                    "fixed" -> AutomationTimeZone.Fixed(getString("zoneId"))
                    "system" -> {
                        require(isNull("zoneId")) { "backup_automation_zone" }
                        AutomationTimeZone.FollowSystem
                    }
                    else -> error("backup_automation_zone")
                },
                rrule = getString("rrule"),
            ),
            missedRunPolicy = MissedRunPolicy(
                mode = MissedRunMode.valueOf(getString("missedMode")),
                gracePeriod = Duration.parse(getString("grace")),
                maximumCatchUpRuns = getInt("maximumCatchUp"),
            ),
            retryPolicy = AutomationRetryPolicy(
                maximumAttempts = getInt("maximumAttempts"),
                initialBackoff = Duration.parse(getString("initialBackoff")),
                backoffMultiplier = getInt("backoffMultiplier"),
                maximumBackoff = Duration.parse(getString("maximumBackoff")),
            ),
            target = CodexAutomationTarget.Independent,
            instruction = getString("instruction"),
            updatedAt = Instant.parse(getString("updatedAt")),
            requirements = AutomationRequirements(
                requiredCapabilities = buildSet(capabilities.length()) {
                    repeat(capabilities.length()) {
                        add(AutomationCapabilityId(capabilities.getString(it)))
                    }
                },
                requiredPermissions = buildSet(permissions.length()) {
                    repeat(permissions.length()) {
                        add(AutomationPermissionId(permissions.getString(it)))
                    }
                },
                requiresCodexAuthentication = getBoolean("requiresCodexAuthentication"),
                requiresNetwork = getBoolean("requiresNetwork"),
                requiresUnlockedDevice = getBoolean("requiresUnlockedDevice"),
                confirmationPolicy = AutomationConfirmationPolicy.valueOf(
                    getString("confirmationPolicy"),
                ),
            ),
            timingPolicy = AutomationTimingPolicy.valueOf(getString("timingPolicy")),
        )
        requirePortableText(definition.instruction, 32_768, "backup_automation_instruction")
        require(RRuleParser.parse(definition.schedule.rrule) is RRuleParseResult.Valid) {
            "backup_automation_rrule"
        }
        return definition
    }

    private fun BackupPluginReference.json(): JSONObject = JSONObject()
        .put("pluginId", pluginId)
        .put("marketplaceName", marketplaceName)
        .put("sourceKind", sourceKind.name)
        .put("enabled", enabled)

    private fun JSONObject.plugin(): BackupPluginReference {
        requireExactKeys(setOf("pluginId", "marketplaceName", "sourceKind", "enabled"))
        return BackupPluginReference(
            pluginId = getString("pluginId"),
            marketplaceName = getString("marketplaceName"),
            sourceKind = PluginSourceKind.valueOf(getString("sourceKind")),
            enabled = getBoolean("enabled"),
        )
    }

    private fun BackupSkillChoice.json(): JSONObject = JSONObject()
        .put("name", name)
        .put("enabled", enabled)

    private fun JSONObject.skill(): BackupSkillChoice {
        requireExactKeys(setOf("name", "enabled"))
        return BackupSkillChoice(getString("name"), getBoolean("enabled"))
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>) {
        val actual = keys().asSequence().toSet()
        require(actual == expected) { "backup_document_fields" }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> error("backup_document_value")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val AUTOMATION_KEYS = setOf(
        "id",
        "revision",
        "enabled",
        "start",
        "zoneKind",
        "zoneId",
        "rrule",
        "missedMode",
        "grace",
        "maximumCatchUp",
        "maximumAttempts",
        "initialBackoff",
        "backoffMultiplier",
        "maximumBackoff",
        "target",
        "instruction",
        "updatedAt",
        "capabilities",
        "permissions",
        "requiresCodexAuthentication",
        "requiresNetwork",
        "requiresUnlockedDevice",
        "confirmationPolicy",
        "timingPolicy",
    )
}
