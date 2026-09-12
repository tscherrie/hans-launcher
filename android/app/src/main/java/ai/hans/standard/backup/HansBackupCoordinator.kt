package ai.hans.standard.backup

import java.util.UUID

/**
 * Transactional state boundary. Implementations must publish all fields or restore the exact
 * pre-import state before throwing; Android additionally keeps a crash-recovery journal.
 */
interface HansBackupStateGateway {
    /** Holds the real shared state boundary across a preview recheck and its import. */
    fun <T> withStateAccess(block: () -> T): T = block()
    fun snapshot(): HansBackupPayload
    fun replaceAll(payload: HansBackupPayload)
    fun recoverInterruptedImport(): Boolean
}

fun interface BackupClock {
    fun nowMillis(): Long
}

fun interface BackupConfirmationTokenSource {
    fun next(): String

    companion object {
        val UUIDS = BackupConfirmationTokenSource {
            UUID.randomUUID().toString().replace("-", "")
        }
    }
}

class HansBackupCoordinator(
    private val gateway: HansBackupStateGateway,
    private val clock: BackupClock = BackupClock(System::currentTimeMillis),
    private val tokens: BackupConfirmationTokenSource = BackupConfirmationTokenSource.UUIDS,
) {
    private data class PendingImport(
        val document: HansBackupDocument,
        val baselineFingerprint: String,
        val preview: HansBackupImportPreview,
    )

    private var pending: PendingImport? = null

    @Synchronized
    fun recoverInterruptedImport(): Boolean = gateway.recoverInterruptedImport()

    @Synchronized
    fun exportDocument(): ByteArray {
        pending = null
        val payload = gateway.snapshot()
        return HansBackupDocumentCodec.encode(payload, clock.nowMillis())
    }

    @Synchronized
    fun prepareImport(bytes: ByteArray): HansBackupImportPreview {
        pending = null
        val document = HansBackupDocumentCodec.decode(bytes)
        val current = gateway.snapshot()
        val imported = document.payload
        val currentIds = current.automations.associateBy { it.id }
        val importedIds = imported.automations.associateBy { it.id }
        val currentPlugins = current.plugins.associateBy { it.pluginId }
        val currentSkills = current.skills.associateBy { it.name }
        val warnings = buildList {
            if (imported.retargetedThreadBoundAutomationIds.isNotEmpty()) {
                add(
                    "${imported.retargetedThreadBoundAutomationIds.size} threadgebundene Automation(en) starten nach dem Import bewusst in einem neuen Thread.",
                )
            }
            if (imported.plugins.isNotEmpty() || imported.skills.isNotEmpty()) {
                add(
                    "Plugin- und Skill-Auswahlen werden als portable Referenzen vorgemerkt. Der aktuelle Codex-Katalog und neue Connector-Anmeldungen bleiben maßgeblich.",
                )
            }
            val missingPlugins = imported.plugins.count { it.pluginId !in currentPlugins }
            if (missingPlugins > 0) {
                add(
                    "$missingPlugins Plugin-Referenz(en) sind im aktuellen Katalog nicht installiert und bleiben bis zur Katalog-Abstimmung vorgemerkt.",
                )
            }
            val changedSkills = imported.skills.count { currentSkills[it.name] != it }
            if (changedSkills > 0) {
                add("$changedSkills Skill-Auswahl(en) unterscheiden sich vom aktuellen Katalog.")
            }
            if (
                imported.settings.model != current.settings.model ||
                imported.settings.reasoningEffort != current.settings.reasoningEffort ||
                imported.settings.serviceTier != current.settings.serviceTier
            ) {
                add(
                    "Modell, Denkaufwand und Fast-Modus werden vorgemerkt und erst nach Bestätigung durch den Codex App Server wirksam.",
                )
            }
        }.take(HansBackupLimits.MAX_PREVIEW_WARNINGS)
        val preview = HansBackupImportPreview(
            confirmationToken = tokens.next(),
            settingsChanged = imported.settings != current.settings,
            confirmedProfileWillChange =
                imported.confirmedProfileSummary != current.confirmedProfileSummary,
            automationsAdded = importedIds.keys.count { it !in currentIds },
            automationsReplaced = importedIds.keys.count { id ->
                id in currentIds && currentIds[id] != importedIds[id]
            },
            automationsRemoved = currentIds.keys.count { it !in importedIds },
            pluginReferencesRequested = imported.plugins.size,
            skillChoicesRequested = imported.skills.size,
            dispatchSelectionWillBeStaged =
                imported.settings.model != current.settings.model ||
                    imported.settings.reasoningEffort != current.settings.reasoningEffort ||
                    imported.settings.serviceTier != current.settings.serviceTier,
            warnings = warnings,
        )
        pending = PendingImport(
            document = document,
            baselineFingerprint = HansBackupDocumentCodec.payloadFingerprint(current),
            preview = preview,
        )
        return preview
    }

    @Synchronized
    fun confirmImport(
        confirmationToken: String,
        explicitUserConfirmation: Boolean,
    ): HansBackupImportResult {
        if (!explicitUserConfirmation) throw HansBackupException("backup_confirmation_required")
        val request = pending ?: throw HansBackupException("backup_import_not_prepared")
        pending = null
        if (
            !constantTimeEquals(
                request.preview.confirmationToken,
                confirmationToken,
            )
        ) {
            throw HansBackupException("backup_confirmation_stale")
        }
        return gateway.withStateAccess {
            val current = gateway.snapshot()
            if (
                HansBackupDocumentCodec.payloadFingerprint(current) != request.baselineFingerprint
            ) {
                throw HansBackupException("backup_state_changed")
            }
            val imported = request.document.payload
            // A restored dispatch preference must not masquerade as an effective runtime choice.
            // Keep the proven selection and return the desired selection to the normal staging path.
            val localTarget = imported.copy(
                settings = imported.settings.copy(
                    model = current.settings.model,
                    reasoningEffort = current.settings.reasoningEffort,
                    serviceTier = current.settings.serviceTier,
                ),
            )
            try {
                gateway.replaceAll(localTarget)
            } catch (failure: HansBackupException) {
                throw failure
            } catch (failure: Exception) {
                throw HansBackupException("backup_import_failed", failure)
            }
            val dispatchSelectionChanged = imported.settings.model != current.settings.model ||
                imported.settings.reasoningEffort != current.settings.reasoningEffort ||
                imported.settings.serviceTier != current.settings.serviceTier
            HansBackupImportResult(
                imported = true,
                requestedModel = imported.settings.model.takeIf { dispatchSelectionChanged },
                requestedReasoningEffort = imported.settings.reasoningEffort
                    .takeIf { dispatchSelectionChanged },
                requestedServiceTier = imported.settings.serviceTier,
                pluginReferencesRetainedForReconciliation = imported.plugins.size,
                skillChoicesRetainedForReconciliation = imported.skills.size,
            )
        }
    }

    @Synchronized
    fun cancelImport() {
        pending = null
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        java.security.MessageDigest.isEqual(
            first.toByteArray(Charsets.UTF_8),
            second.toByteArray(Charsets.UTF_8),
        )
}
