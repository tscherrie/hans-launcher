package ai.hans.standard.backup

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

/** Process-local preview metadata only; never serialized into the backup or its fingerprint. */
enum class HansBackupWarningKind {
    RETARGETED_AUTOMATIONS,
    PORTABLE_REFERENCES,
    MISSING_PLUGINS,
    CHANGED_SKILLS,
    SELECTION_STAGED,
}

data class HansBackupPreviewWarning(
    val kind: HansBackupWarningKind,
    val count: Int = 0,
) {
    init {
        when (kind) {
            HansBackupWarningKind.RETARGETED_AUTOMATIONS,
            HansBackupWarningKind.MISSING_PLUGINS,
            HansBackupWarningKind.CHANGED_SKILLS -> require(count > 0)
            HansBackupWarningKind.PORTABLE_REFERENCES,
            HansBackupWarningKind.SELECTION_STAGED -> require(count == 0)
        }
    }

    fun render(text: HansTextResolver): String = when (kind) {
        HansBackupWarningKind.RETARGETED_AUTOMATIONS ->
            text.quantity(R.plurals.presentation_backup_retargeted_automations, count, count)
        HansBackupWarningKind.PORTABLE_REFERENCES ->
            text.text(R.string.presentation_backup_portable_references)
        HansBackupWarningKind.MISSING_PLUGINS ->
            text.quantity(R.plurals.presentation_backup_missing_plugins, count, count)
        HansBackupWarningKind.CHANGED_SKILLS ->
            text.quantity(R.plurals.presentation_backup_changed_skills, count, count)
        HansBackupWarningKind.SELECTION_STAGED ->
            text.text(R.string.presentation_backup_selection_staged)
    }
}
