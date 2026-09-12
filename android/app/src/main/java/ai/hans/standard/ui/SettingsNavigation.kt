package ai.hans.standard.ui

/** The existing settings sections, in their sidebar overview order. */
internal enum class SettingsGroup(val id: String, val title: String) {
    PERMISSIONS("permissions", "Berechtigungen"),
    RUNTIME("runtime", "Modell & Antworten"),
    SPEECH("speech", "Stimme & Vorlesen"),
    INPUT("input", "Tasten & Bedienung"),
    PERSONAL("personal", "Einrichtung & Gedächtnis"),
    REMOTE_CONTROL("remote_control", "Fernzugriff durch ChatGPT Desktop"),
    ADVANCED_WORK("advanced_work", "Erweiterte Arbeit"),
    MAINTENANCE("maintenance", "Zugänge & Sicherung"),
}

/** Ephemeral navigation only: each newly opened menu starts at the group overview. */
internal data class SettingsNavigation(val group: SettingsGroup? = null) {
    val title: String
        get() = group?.title ?: "Menü"

    fun open(group: SettingsGroup): SettingsNavigation = copy(group = group)

    fun backToGroups(): SettingsNavigation = SettingsNavigation()
}
