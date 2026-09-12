package ai.hans.standard.backup

/** Retaining portable references is not installing plugins or applying a live skill selection. */
fun backupImportResultMessage(
    result: HansBackupImportResult,
    dispatchSelectionStaged: Boolean,
): String = buildList {
    add("Einstellungen, Profil und Automationsdefinitionen wurden importiert.")
    if (
        result.pluginReferencesRetainedForReconciliation > 0 ||
        result.skillChoicesRetainedForReconciliation > 0
    ) {
        add("Plugin- und Skill-Referenzen wurden nur vorgemerkt, nicht installiert oder aktiviert.")
    }
    if (result.requestedModel != null) {
        add(
            if (dispatchSelectionStaged) {
                "Die gewünschte Modellwahl wird erst nach Bestätigung durch Codex wirksam."
            } else {
                "Das gewünschte Modell ist derzeit nicht verfügbar."
            },
        )
    }
}.joinToString(" ")
