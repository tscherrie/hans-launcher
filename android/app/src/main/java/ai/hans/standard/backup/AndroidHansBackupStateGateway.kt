package ai.hans.standard.backup

import android.content.Context
import android.util.AtomicFile
import ai.hans.standard.automations.AutomationSnapshotJsonCodec
import ai.hans.standard.automations.AutomationStorage
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.BackupMaintainedAutomationStorage
import ai.hans.standard.automations.CodexAutomationTarget
import ai.hans.standard.plugins.PluginDomainSnapshot
import ai.hans.standard.plugins.PluginCatalogPhase
import ai.hans.standard.profile.AtomicFileUserProfileStorage
import ai.hans.standard.profile.ProfileAnswer
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.profile.UserProfileStorage
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** App-private desired-state record. It never contains marketplace paths or connector credentials. */
internal class AtomicImportedPluginChoiceStore(
    context: Context,
    file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
) : ImportedPluginChoiceStore {
    private val file = AtomicFile(file)

    @Synchronized
    override fun read(): StoredPluginChoices {
        if (!file.baseFile.exists()) return StoredPluginChoices()
        val bytes = file.readFully()
        require(bytes.size <= MAX_BYTES) { "backup_plugin_choices_oversize" }
        val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        require(root.getInt("version") == 1)
        val plugins = root.getJSONArray("plugins")
        val skills = root.getJSONArray("skills")
        require(plugins.length() <= HansBackupLimits.MAX_PLUGINS)
        require(skills.length() <= HansBackupLimits.MAX_SKILLS)
        return StoredPluginChoices(
            plugins = buildList(plugins.length()) {
                repeat(plugins.length()) { index ->
                    val item = plugins.getJSONObject(index)
                    add(
                        BackupPluginReference(
                            pluginId = item.getString("pluginId"),
                            marketplaceName = item.getString("marketplaceName"),
                            sourceKind = ai.hans.standard.plugins.PluginSourceKind.valueOf(
                                item.getString("sourceKind"),
                            ),
                            enabled = item.getBoolean("enabled"),
                        ),
                    )
                }
            },
            skills = buildList(skills.length()) {
                repeat(skills.length()) { index ->
                    val item = skills.getJSONObject(index)
                    add(BackupSkillChoice(item.getString("name"), item.getBoolean("enabled")))
                }
            },
        )
    }

    @Synchronized
    override fun write(choices: StoredPluginChoices) {
        require(choices.plugins.size <= HansBackupLimits.MAX_PLUGINS)
        require(choices.skills.size <= HansBackupLimits.MAX_SKILLS)
        val bytes = JSONObject()
            .put("version", 1)
            .put(
                "plugins",
                JSONArray().also { out ->
                    choices.plugins.sortedBy { it.pluginId }.forEach { plugin ->
                        out.put(
                            JSONObject()
                                .put("pluginId", plugin.pluginId)
                                .put("marketplaceName", plugin.marketplaceName)
                                .put("sourceKind", plugin.sourceKind.name)
                                .put("enabled", plugin.enabled),
                        )
                    }
                },
            )
            .put(
                "skills",
                JSONArray().also { out ->
                    choices.skills.sortedBy { it.name }.forEach { skill ->
                        out.put(
                            JSONObject()
                                .put("name", skill.name)
                                .put("enabled", skill.enabled),
                        )
                    }
                },
            )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private companion object {
        const val FILE_NAME = "hans-imported-plugin-choices-v1.json"
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Android implementation with a private before-image journal. A caught write failure rolls back
 * immediately; Application recovers a process-death journal before any scheduled work starts.
 */
internal class AndroidHansBackupStateGateway(
    context: Context,
    private val settingsStore: SharedPreferencesHansSettingsStore,
    automationStorage: AutomationStorage,
    private val pluginSnapshotProvider: () -> PluginDomainSnapshot?,
    private val clock: BackupClock = BackupClock(System::currentTimeMillis),
    private val profileStorage: UserProfileStorage = AtomicFileUserProfileStorage(context),
    private val pluginChoiceStore: AtomicImportedPluginChoiceStore =
        AtomicImportedPluginChoiceStore(context),
    private val journal: AtomicBackupImportJournal = AtomicBackupImportJournal(context),
    private val maintenance: HansBackupMaintenance = HansBackupProcessState.maintenance,
) : HansBackupStateGateway {
    private val automationStorage = if (automationStorage is BackupMaintainedAutomationStorage) {
        check(automationStorage.maintenance === maintenance) { "backup_automation_boundary_mismatch" }
        automationStorage
    } else {
        BackupMaintainedAutomationStorage(automationStorage, maintenance)
    }
    private val transaction = HansBackupImportTransaction(
        maintenance, settingsStore, profileStorage, this.automationStorage, pluginChoiceStore, journal, clock,
    )

    override fun <T> withStateAccess(block: () -> T): T = maintenance.withStateAccess(block)

    override fun snapshot(): HansBackupPayload = maintenance.withStateAccess {
        val definitions = automationStorage.definitions()
        val retargeted = definitions
            .filter { it.target is CodexAutomationTarget.ThreadBound }
            .mapTo(linkedSetOf()) { it.id.value }
        val portableDefinitions = definitions.map { definition ->
            if (definition.target is CodexAutomationTarget.ThreadBound) {
                definition.copy(target = CodexAutomationTarget.Independent)
            } else {
                definition
            }
        }
        val pluginChoices = currentPluginChoices()
        HansBackupPayload(
            settings = settingsStore.read(),
            confirmedProfileSummary = profileStorage.read().confirmedSummary,
            automations = portableDefinitions,
            retargetedThreadBoundAutomationIds = retargeted,
            plugins = pluginChoices.plugins,
            skills = pluginChoices.skills,
            workspace = BackupWorkspaceMetadata.APP_PRIVATE_DEFAULT,
        )
    }

    override fun replaceAll(payload: HansBackupPayload) = transaction.replaceAll(payload)

    override fun recoverInterruptedImport(): Boolean = transaction.recoverInterruptedImport()

    private fun currentPluginChoices(): StoredPluginChoices {
        val snapshot = pluginSnapshotProvider()
            ?: throw HansBackupException("backup_plugin_catalog_unavailable")
        if (snapshot.phase !in setOf(PluginCatalogPhase.READY, PluginCatalogPhase.STALE)) {
            throw HansBackupException("backup_plugin_catalog_unavailable")
        }
        val retained = pluginChoiceStore.read()
        val livePlugins = snapshot.plugins
            .asSequence()
            .filter { it.installed }
            .map { card ->
                try {
                    BackupPluginReference(
                        pluginId = card.pluginId,
                        marketplaceName = card.marketplaceDisplayName.ifBlank { "Unbekannt" },
                        sourceKind = card.sourceKind,
                        enabled = card.enabled,
                    )
                } catch (failure: Exception) {
                    throw HansBackupException("backup_plugin_reference_invalid", failure)
                }
            }
            .distinctBy { it.pluginId }
            .sortedBy { it.pluginId }
            .toList()
        val plugins = (livePlugins + retained.plugins)
            .distinctBy { it.pluginId }
            .sortedBy { it.pluginId }
        val liveSkills = snapshot.skills
            .asSequence()
            .map { skill ->
                try {
                    BackupSkillChoice(skill.name, skill.enabled)
                } catch (failure: Exception) {
                    throw HansBackupException("backup_skill_reference_invalid", failure)
                }
            }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
        val skills = (liveSkills + retained.skills)
            .distinctBy { it.name }
            .sortedBy { it.name }
        if (plugins.size > HansBackupLimits.MAX_PLUGINS) {
            throw HansBackupException("backup_plugin_count")
        }
        if (skills.size > HansBackupLimits.MAX_SKILLS) {
            throw HansBackupException("backup_skill_count")
        }
        return StoredPluginChoices(plugins, skills)
    }

    internal class AtomicBackupImportJournal(
        context: Context,
        file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
    ) : BackupImportJournal {
        private val file = AtomicFile(file)

        @Synchronized
        override fun stage(state: HansBackupRollbackState) {
            val body = state.json()
            val canonical = body.toString()
            val digest = sha256(canonical.toByteArray(StandardCharsets.UTF_8))
            val bytes = JSONObject()
                .put("version", 1)
                .put("before", canonical)
                .put("sha256", digest)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_JOURNAL_BYTES) { "backup_journal_oversize" }
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }

        @Synchronized
        override fun read(): HansBackupRollbackState? {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                check(!File(file.baseFile.path + ".new").exists()) { "backup_journal_incomplete" }
                return null
            }
            val bytes = file.readFully()
            require(bytes.size <= MAX_JOURNAL_BYTES) { "backup_journal_oversize" }
            val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            require(root.getInt("version") == 1)
            val beforeText = root.getString("before")
            val expected = root.getString("sha256")
            val actual = sha256(beforeText.toByteArray(StandardCharsets.UTF_8))
            require(MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
                "backup_journal_integrity"
            }
            return JSONObject(beforeText).rollbackState()
        }

        @Synchronized
        override fun clear() {
            file.delete()
            check(
                !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                    !File(file.baseFile.path + ".new").exists(),
            ) { "backup_journal_clear_failed" }
        }

        private fun HansBackupRollbackState.json(): JSONObject = JSONObject()
            .put("settings", settings.rollbackJson())
            .put("profile", profile.rollbackJson())
            .put("automations", AutomationSnapshotJsonCodec.encode(automations))
            .put("pluginChoices", pluginChoices.rollbackJson())

        private fun JSONObject.rollbackState(): HansBackupRollbackState = HansBackupRollbackState(
            settings = getJSONObject("settings").rollbackSettings(),
            profile = getJSONObject("profile").rollbackProfile(),
            automations = AutomationSnapshotJsonCodec.decode(getString("automations")),
            pluginChoices = getJSONObject("pluginChoices").rollbackPluginChoices(),
        )

        private fun HansSettings.rollbackJson(): JSONObject = JSONObject()
            .put("model", model)
            .put("reasoningEffort", reasoningEffort)
            .put("serviceTier", serviceTier)
            .put("voice", voice)
            .put("liveVoice", liveVoice)
            .put("speechRate", speechRate.toDouble())
            .put("readAloudMode", readAloudMode.wireValue)
            .put("dictationKeyTrigger", dictationKeyTrigger.name)
            .put("cameraHoldToTalkEnabled", cameraHoldToTalkEnabled)

        private fun JSONObject.rollbackSettings(): HansSettings = HansSettings(
            model = getString("model"),
            reasoningEffort = getString("reasoningEffort"),
            serviceTier = if (has("serviceTier") && !isNull("serviceTier")) {
                getString("serviceTier")
            } else {
                HansSettings.DEFAULT_SERVICE_TIER
            },
            voice = getString("voice"),
            liveVoice = if (has("liveVoice") && !isNull("liveVoice")) {
                getString("liveVoice")
            } else {
                HansSettings.DEFAULT_LIVE_VOICE
            },
            speechRate = getDouble("speechRate").toFloat(),
            readAloudMode = ai.hans.standard.settings.ReadAloudMode.fromWire(
                getString("readAloudMode"),
            ) ?: error("backup_journal_settings"),
            dictationKeyTrigger = ai.hans.standard.phone.keys.ActionKeyTrigger.valueOf(
                getString("dictationKeyTrigger"),
            ),
            cameraHoldToTalkEnabled = getBoolean("cameraHoldToTalkEnabled"),
        )

        private fun UserProfileDocument.rollbackJson(): JSONObject = JSONObject()
            .put("revision", revision)
            .put("interviewActive", interviewActive)
            .put(
                "draftAnswers",
                JSONArray().also { out ->
                    draftAnswers.forEach { answer ->
                        out.put(
                            JSONObject()
                                .put("topic", answer.topic)
                                .put("question", answer.question)
                                .put("answer", answer.answer),
                        )
                    }
                },
            )
            .put("proposedSummary", proposedSummary ?: JSONObject.NULL)
            .put("confirmationNonce", confirmationNonce ?: JSONObject.NULL)
            .put("confirmedSummary", confirmedSummary ?: JSONObject.NULL)
            .put("updatedAtMillis", updatedAtMillis)

        private fun JSONObject.rollbackProfile(): UserProfileDocument {
            val answers = getJSONArray("draftAnswers")
            return UserProfileDocument(
                revision = getLong("revision"),
                interviewActive = getBoolean("interviewActive"),
                draftAnswers = buildList(answers.length()) {
                    repeat(answers.length()) { index ->
                        val item = answers.getJSONObject(index)
                        add(
                            ProfileAnswer(
                                item.getString("topic"),
                                item.getString("question"),
                                item.getString("answer"),
                            ),
                        )
                    }
                },
                proposedSummary = nullableString("proposedSummary"),
                confirmationNonce = nullableString("confirmationNonce"),
                confirmedSummary = nullableString("confirmedSummary"),
                updatedAtMillis = getLong("updatedAtMillis"),
            )
        }

        private fun StoredPluginChoices.rollbackJson(): JSONObject = JSONObject()
            .put(
                "plugins",
                JSONArray().also { out ->
                    plugins.forEach { plugin ->
                        out.put(
                            JSONObject()
                                .put("pluginId", plugin.pluginId)
                                .put("marketplaceName", plugin.marketplaceName)
                                .put("sourceKind", plugin.sourceKind.name)
                                .put("enabled", plugin.enabled),
                        )
                    }
                },
            )
            .put(
                "skills",
                JSONArray().also { out ->
                    skills.forEach { skill ->
                        out.put(
                            JSONObject().put("name", skill.name).put("enabled", skill.enabled),
                        )
                    }
                },
            )

        private fun JSONObject.rollbackPluginChoices(): StoredPluginChoices {
            val plugins = getJSONArray("plugins")
            val skills = getJSONArray("skills")
            return StoredPluginChoices(
                plugins = buildList(plugins.length()) {
                    repeat(plugins.length()) { index ->
                        val item = plugins.getJSONObject(index)
                        add(
                            BackupPluginReference(
                                item.getString("pluginId"),
                                item.getString("marketplaceName"),
                                ai.hans.standard.plugins.PluginSourceKind.valueOf(
                                    item.getString("sourceKind"),
                                ),
                                item.getBoolean("enabled"),
                            ),
                        )
                    }
                },
                skills = buildList(skills.length()) {
                    repeat(skills.length()) { index ->
                        val item = skills.getJSONObject(index)
                        add(BackupSkillChoice(item.getString("name"), item.getBoolean("enabled")))
                    }
                },
            )
        }

        private fun JSONObject.nullableString(name: String): String? =
            if (isNull(name)) null else getString(name)

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private companion object {
            const val FILE_NAME = "hans-backup-import-journal-v1.json"
            const val MAX_JOURNAL_BYTES = 24 * 1024 * 1024
        }
    }
}
