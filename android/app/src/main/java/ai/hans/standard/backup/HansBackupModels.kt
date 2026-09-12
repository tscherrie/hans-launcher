package ai.hans.standard.backup

import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.settings.HansSettings

/** Portable, deliberately non-secret state accepted by the public backup surface. */
data class HansBackupPayload(
    val settings: HansSettings,
    /** Only the user-confirmed summary is portable. Draft answers and nonces never leave the app. */
    val confirmedProfileSummary: String?,
    /** Definitions only. Runs, receipts, confirmations, leases and scheduler cursors are excluded. */
    val automations: List<AutomationDefinition>,
    /** Thread ids are session data, so these definitions restore as independent new-thread jobs. */
    val retargetedThreadBoundAutomationIds: Set<String> = emptySet(),
    val plugins: List<BackupPluginReference>,
    val skills: List<BackupSkillChoice>,
    val workspace: BackupWorkspaceMetadata = BackupWorkspaceMetadata.APP_PRIVATE_DEFAULT,
) {
    init {
        require(automations.size <= HansBackupLimits.MAX_AUTOMATIONS) {
            "backup_automation_count"
        }
        require(automations.map { it.id }.distinct().size == automations.size) {
            "backup_duplicate_automation"
        }
        require(retargetedThreadBoundAutomationIds.size <= automations.size)
        require(retargetedThreadBoundAutomationIds.all { id ->
            automations.any { it.id.value == id }
        }) { "backup_retargeted_automation" }
        require(plugins.size <= HansBackupLimits.MAX_PLUGINS) { "backup_plugin_count" }
        require(plugins.map { it.pluginId }.distinct().size == plugins.size) {
            "backup_duplicate_plugin"
        }
        require(skills.size <= HansBackupLimits.MAX_SKILLS) { "backup_skill_count" }
        require(skills.map { it.name }.distinct().size == skills.size) {
            "backup_duplicate_skill"
        }
        confirmedProfileSummary?.let {
            requirePortableText(it, HansBackupLimits.MAX_PROFILE_CHARS, "backup_profile")
        }
    }
}

data class BackupPluginReference(
    val pluginId: String,
    val marketplaceName: String,
    val sourceKind: PluginSourceKind,
    val enabled: Boolean,
) {
    init {
        requirePortableIdentifier(pluginId, HansBackupLimits.MAX_PLUGIN_ID_CHARS, "backup_plugin_id")
        requirePortableText(
            marketplaceName,
            HansBackupLimits.MAX_MARKETPLACE_NAME_CHARS,
            "backup_marketplace_name",
        )
    }
}

data class BackupSkillChoice(
    val name: String,
    val enabled: Boolean,
) {
    init {
        requirePortableIdentifier(name, HansBackupLimits.MAX_SKILL_NAME_CHARS, "backup_skill_name")
    }
}

enum class BackupWorkspaceMetadata(val wireValue: String) {
    /** A portable selector. The absolute app-private path is intentionally never exported. */
    APP_PRIVATE_DEFAULT("appPrivateDefault");

    companion object {
        fun fromWire(value: String): BackupWorkspaceMetadata? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class HansBackupDocument(
    val createdAtEpochMillis: Long,
    val payload: HansBackupPayload,
    val payloadSha256: String,
) {
    init {
        require(createdAtEpochMillis >= 0) { "backup_created_at" }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "backup_integrity_format" }
    }
}

data class HansBackupImportPreview(
    /** Process-local one-time token binding confirmation to the exact parsed document and baseline. */
    val confirmationToken: String,
    val settingsChanged: Boolean,
    val confirmedProfileWillChange: Boolean,
    val automationsAdded: Int,
    val automationsReplaced: Int,
    val automationsRemoved: Int,
    val pluginReferencesRequested: Int,
    val skillChoicesRequested: Int,
    /** Dispatch is staged separately because model/effort are effective only after App Server proof. */
    val dispatchSelectionWillBeStaged: Boolean,
    val warnings: List<String>,
) {
    init {
        require(confirmationToken.matches(Regex("[A-Za-z0-9_-]{24,128}")))
        require(
            listOf(
                automationsAdded,
                automationsReplaced,
                automationsRemoved,
                pluginReferencesRequested,
                skillChoicesRequested,
            ).all { it >= 0 },
        )
        require(warnings.size <= HansBackupLimits.MAX_PREVIEW_WARNINGS)
    }

    val hasChanges: Boolean
        get() = settingsChanged || confirmedProfileWillChange ||
            automationsAdded > 0 || automationsReplaced > 0 || automationsRemoved > 0 ||
            pluginReferencesRequested > 0 || skillChoicesRequested > 0 ||
            dispatchSelectionWillBeStaged
}

data class HansBackupImportResult(
    val imported: Boolean,
    /** Requested runtime selection to stage through the App Server-confirmed dispatch path. */
    val requestedModel: String? = null,
    val requestedReasoningEffort: String? = null,
    val requestedServiceTier: String? = null,
    val pluginReferencesRetainedForReconciliation: Int = 0,
    val skillChoicesRetainedForReconciliation: Int = 0,
)

class HansBackupException(
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalArgumentException(errorCode, cause)

object HansBackupLimits {
    const val MAX_DOCUMENT_BYTES = 12 * 1024 * 1024
    const val MAX_AUTOMATIONS = 256
    const val MAX_PLUGINS = 256
    const val MAX_SKILLS = 2_048
    const val MAX_PROFILE_CHARS = 32_000
    const val MAX_PLUGIN_ID_CHARS = 256
    const val MAX_MARKETPLACE_NAME_CHARS = 512
    const val MAX_SKILL_NAME_CHARS = 512
    const val MAX_PREVIEW_WARNINGS = 16
}

internal fun requirePortableIdentifier(value: String, maxChars: Int, code: String) {
    requirePortableText(value, maxChars, code)
    require(value.none(Char::isWhitespace)) { code }
}

internal fun requirePortableText(value: String, maxChars: Int, code: String) {
    require(value.isNotBlank()) { code }
    require(value.length <= maxChars) { code }
    require(value.none(Char::isISOControl)) { code }
    require(!BackupSecretPolicy.containsForbiddenMaterial(value)) { "backup_forbidden_material" }
}

/** Conservative fail-closed guard against exporting credentials or device-private paths in text. */
internal object BackupSecretPolicy {
    private val forbidden = listOf(
        Regex("(?i)\\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{16,}"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{16,}"),
        Regex("(?i)\\b(?:access_token|refresh_token|api_key|client_secret)\\b\\s*[:=]"),
        Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        Regex("(?i)\\beyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}"),
        Regex("(?:^|[\\s\"'])(?:/data/(?:user/\\d+|data)/|/private/var/|/Users/|/home/)[^\\s\"']*"),
    )

    fun containsForbiddenMaterial(value: String): Boolean = forbidden.any { it.containsMatchIn(value) }
}
