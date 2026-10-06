package ai.hans.standard.backup

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

/** Retaining portable references is not installing plugins or applying a live skill selection. */
fun backupImportResultMessage(
    result: HansBackupImportResult,
    dispatchSelectionStaged: Boolean,
    text: HansTextResolver,
): String = buildList {
    add(text.text(R.string.integration_backup_imported))
    if (
        result.pluginReferencesRetainedForReconciliation > 0 ||
        result.skillChoicesRetainedForReconciliation > 0
    ) {
        add(text.text(R.string.integration_backup_references))
    }
    if (result.requestedModel != null) {
        add(
            if (dispatchSelectionStaged) {
                text.text(R.string.integration_backup_model_pending)
            } else {
                text.text(R.string.integration_backup_model_unavailable)
            },
        )
    }
}.joinToString(" ")
