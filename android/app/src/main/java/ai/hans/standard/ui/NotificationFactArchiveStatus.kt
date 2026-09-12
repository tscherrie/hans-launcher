package ai.hans.standard.ui

import ai.hans.standard.notifications.NotificationTriageQueueHealth
import ai.hans.standard.phone.notifications.facts.NotificationFactArchiveHealth

data class NotificationFactArchiveStatus(
    val loading: Boolean = false,
    val available: Boolean? = null,
    val factCount: Int? = null,
    val usedBytes: Long? = null,
    val pendingFacts: Int? = null,
    val capacityDrops: Long? = null,
    val capacityExceeded: Boolean? = null,
) {
    val summary: String get() = if (loading) "Archivstatus wird gelesen …" else buildString {
        append(when (available) { true -> "Archiv verfügbar"; false -> "Archiv derzeit nicht verfügbar"; null -> "Archivstatus unbekannt" })
        append("\nGespeicherte Fakten: ").append(factCount?.toString() ?: "unbekannt")
        append(" · Belegt: ").append(usedBytes?.let { "$it Bytes" } ?: "unbekannt")
        append("\nAusstehende Übernahmen: ").append(pendingFacts?.toString() ?: "unbekannt")
        append(" · Nicht vorgemerkte Übernahmen bei voller Warteschlange: ").append(capacityDrops?.toString() ?: "unbekannt")
        if (capacityExceeded == true) append("\nKapazitätsgrenze erreicht; bestehende Fakten werden nicht verdrängt.")
    }
    companion object {
        const val DISCLOSURE = "Ausgewählte, später nützliche Angaben aus erlaubten Benachrichtigungen bleiben langfristig lokal gespeichert – auch aus stillen Meldungen: bis zu 100.000 Fakten und 128 MiB Datenbank, ohne automatisches Alterslöschen. Die Angaben bleiben Behauptungen ihrer Quelle, keine bestätigten Profilantworten. Die Roh-Benachrichtigungen bleiben getrennt und standardmäßig nur sieben Tage erhalten. Das Faktenarchiv ist nicht Teil des Hans-Backups und nicht das native Codex-Gedächtnis. Vergessen im Archiv löscht keine früheren Chats oder daraus entstandenen Codex-Erinnerungen."
        internal fun from(health: NotificationFactArchiveHealth?, queue: NotificationTriageQueueHealth?) =
            NotificationFactArchiveStatus(
                available = health?.available, factCount = health?.factCount,
                usedBytes = health?.usedDatabaseBytes,
                pendingFacts = (queue as? NotificationTriageQueueHealth.Available)?.factOutboxCount,
                capacityDrops = (queue as? NotificationTriageQueueHealth.Available)?.factOutboxCapacityDrops,
                capacityExceeded = health?.capacityExceeded,
            )
    }
}

/** Main-thread-owned token: lifecycle exits and newer reads reject delayed IO results. */
internal class NotificationFactStatusGeneration {
    private var current = Any()
    fun begin(): Any = Any().also { current = it }
    fun invalidate() { current = Any() }
    fun accepts(token: Any): Boolean = token === current
}
