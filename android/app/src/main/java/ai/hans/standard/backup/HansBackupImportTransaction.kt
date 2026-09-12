package ai.hans.standard.backup

import ai.hans.standard.automations.AutomationBackupRestoreSafety
import ai.hans.standard.automations.AutomationStorage
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.BackupMaintainedAutomationStorage
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.profile.UserProfileStorage
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.HansSettingsStore

internal data class StoredPluginChoices(
    val plugins: List<BackupPluginReference> = emptyList(),
    val skills: List<BackupSkillChoice> = emptyList(),
)

internal interface ImportedPluginChoiceStore {
    fun read(): StoredPluginChoices
    fun write(choices: StoredPluginChoices)
}

internal data class HansBackupRollbackState(
    val settings: HansSettings,
    val profile: UserProfileDocument,
    val automations: AutomationStorageSnapshot,
    val pluginChoices: StoredPluginChoices,
)

internal interface BackupImportJournal {
    fun stage(state: HansBackupRollbackState)
    fun read(): HansBackupRollbackState?
    fun clear()
}

/** Actual Android import/recovery transaction; platform IO lives behind these narrow stores. */
internal class HansBackupImportTransaction(
    private val maintenance: HansBackupMaintenance,
    private val settingsStore: HansSettingsStore,
    private val profileStorage: UserProfileStorage,
    private val automationStorage: AutomationStorage,
    private val pluginChoiceStore: ImportedPluginChoiceStore,
    private val journal: BackupImportJournal,
    private val clock: BackupClock,
) {
    init {
        require(
            automationStorage is BackupMaintainedAutomationStorage &&
                automationStorage.maintenance === maintenance,
        ) { "backup_automation_boundary_mismatch" }
    }

    fun replaceAll(payload: HansBackupPayload) = maintenance.importExclusively {
        require(payload.workspace == BackupWorkspaceMetadata.APP_PRIVATE_DEFAULT)
        val previousJournal = try {
            journal.read()
        } catch (failure: Exception) {
            maintenance.requireRecovery()
            throw HansBackupException("backup_import_rollback_pending", failure)
        }
        if (previousJournal != null) {
            maintenance.requireRecovery()
            throw HansBackupException("backup_import_rollback_pending")
        }
        val before = snapshot()
        val target = try {
            AutomationBackupRestoreSafety.replaceDefinitions(before.automations, payload.automations)
        } catch (failure: IllegalStateException) {
            throw HansBackupException("backup_automation_work_unsettled", failure)
        }
        try {
            journal.stage(before)
        } catch (failure: Exception) {
            // An IO failure does not prove that a before-image was never made durable.
            maintenance.requireRecovery()
            throw HansBackupException("backup_import_rollback_pending", failure)
        }
        var replacementRejected = false
        try {
            if (!automationStorage.compareAndReplaceSnapshotForRestore(before.automations, target)) {
                replacementRejected = true
                throw HansBackupException("backup_state_changed")
            }
            pluginChoiceStore.write(StoredPluginChoices(payload.plugins, payload.skills))
            profileStorage.write(
                UserProfileDocument(
                    revision = Math.addExact(before.profile.revision, 1),
                    confirmedSummary = payload.confirmedProfileSummary,
                    updatedAtMillis = clock.nowMillis(),
                ),
            )
            settingsStore.saveRestoredNonDispatchPreferences(payload.settings)
            journal.clear()
        } catch (failure: Exception) {
            val rollback = runCatching {
                // A rejected CAS made no import changes. Restoring its stale before-image would
                // undo the conflicting writer rather than roll back this transaction.
                if (!replacementRejected) restore(before)
                journal.clear()
            }
            if (rollback.isFailure) {
                maintenance.requireRecovery()
                throw HansBackupException("backup_import_rollback_pending", failure)
            }
            if (failure is HansBackupException) throw failure
            throw HansBackupException("backup_import_failed", failure)
        }
    }

    fun recoverInterruptedImport(): Boolean = maintenance.recoverBeforeRuntime {
        val pending = journal.read() ?: return@recoverBeforeRuntime false
        // Version-one journals may predate maintenance. Never revive their live leases or erase
        // evidence which could have arrived after the old process started scheduling too early.
        restore(pending)
        journal.clear()
        true
    }

    private fun snapshot() = HansBackupRollbackState(
        settings = settingsStore.read(),
        profile = profileStorage.read(),
        automations = automationStorage.snapshot(),
        pluginChoices = pluginChoiceStore.read(),
    )

    private fun restore(state: HansBackupRollbackState) {
        automationStorage.replaceSnapshotForRestore(state.automations)
        settingsStore.saveRestoredNonDispatchPreferences(state.settings)
        profileStorage.write(state.profile)
        pluginChoiceStore.write(state.pluginChoices)
    }
}
