package ai.hans.standard.ui

import ai.hans.standard.automations.AutomationConfirmationPolicy
import ai.hans.standard.automations.AutomationRun
import ai.hans.standard.automations.AutomationRunReceipt
import ai.hans.standard.automations.AutomationRunState
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.AutomationTimeZone
import ai.hans.standard.automations.AutomationTimingPolicy
import ai.hans.standard.automations.MissedRunMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AUTOMATION_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMANY)

/** Pure, bounded projection. Private instructions are shown only in the user's own launcher UI. */
internal fun projectAutomations(
    snapshot: AutomationStorageSnapshot,
    systemZone: ZoneId,
): AutomationsUiState {
    val inboxByAutomation = snapshot.inbox.groupBy { it.key.automationId }
    val runsByAutomation = snapshot.runs.groupBy { it.key.automationId }
    val receiptsByAutomation = snapshot.receipts.groupBy { it.key.automationId }
    val items = snapshot.definitions
        .sortedWith(compareByDescending<ai.hans.standard.automations.AutomationDefinition> { it.enabled }
            .thenByDescending { it.updatedAt })
        .map { definition ->
            val id = definition.id
            val inbox = inboxByAutomation[id].orEmpty()
            val runs = runsByAutomation[id].orEmpty()
            val receipts = receiptsByAutomation[id].orEmpty()
            val historyEvents = buildAutomationHistory(
                runs = runs,
                receipts = receipts,
                systemZone = systemZone,
            )
            val pendingRuns = runs.filter { it.state in PENDING_AUTOMATION_STATES }
            val nextAt = buildList {
                inbox.forEach { add(it.readyAt) }
                pendingRuns.forEach { add(it.availableAt) }
            }.minOrNull()
            val lastRun = runs.maxByOrNull(AutomationRun::updatedAt)
            val lastReceipt = receipts.maxByOrNull(AutomationRunReceipt::completedAt)
            val detailedRunIsLatest = lastRun != null &&
                (lastReceipt == null || lastRun.updatedAt >= lastReceipt.completedAt)
            val lastRunLabel = when {
                lastRun == null && lastReceipt == null -> "Noch kein Lauf"
                detailedRunIsLatest -> checkNotNull(lastRun).let {
                    "${it.state.uiLabel()} · ${formatAutomationInstant(it.updatedAt, systemZone)}"
                }
                else -> checkNotNull(lastReceipt).let {
                    "${it.terminalState.uiLabel()} · ${formatAutomationInstant(it.completedAt, systemZone)}"
                }
            }
            val lastFailureLabel = when {
                detailedRunIsLatest -> checkNotNull(lastRun).lastFailureCode?.automationFailureLabel()
                lastReceipt?.terminalState == AutomationRunState.FAILED_TERMINAL ->
                    "Ausführung nicht abgeschlossen"
                else -> null
            }
            val scheduleZone = definition.schedule.timeZone.resolve(systemZone)
            AutomationUiModel(
                id = id.value,
                revision = definition.revision,
                instructionPreview = definition.instruction
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(220),
                scheduleLabel = buildString {
                    append(humanRrule(definition.schedule.rrule))
                    append(" · ab ")
                    append(definition.schedule.dtStartLocal.format(AUTOMATION_DATE_TIME))
                    append(" · ")
                    append(
                        when (definition.schedule.timeZone) {
                            AutomationTimeZone.FollowSystem -> "Telefon-Zeitzone"
                            is AutomationTimeZone.Fixed -> scheduleZone.id
                        },
                    )
                },
                enabled = definition.enabled,
                unattended = definition.requirements.confirmationPolicy ==
                    AutomationConfirmationPolicy.CAPABILITY_POLICY,
                requiresUnlockedDevice = definition.requirements.requiresUnlockedDevice,
                missedRunMode = when (definition.missedRunPolicy.mode) {
                    MissedRunMode.SKIP -> AutomationMissedRunUiMode.SKIP
                    MissedRunMode.RUN_LATEST -> AutomationMissedRunUiMode.RUN_LATEST
                    MissedRunMode.CATCH_UP -> AutomationMissedRunUiMode.CATCH_UP
                },
                timingLabel = when (definition.timingPolicy) {
                    AutomationTimingPolicy.RELIABLE_INEXACT ->
                        "Zuverlässig, Android darf den Zeitpunkt leicht bündeln"
                    AutomationTimingPolicy.USER_VISIBLE_EXACT ->
                        "Exakter Zeitpunkt, sofern Android die Sonderfreigabe erteilt hat"
                },
                nextRunLabel = nextAt?.let {
                    "Vorgemerkt für ${formatAutomationInstant(it, systemZone)}"
                } ?: if (definition.enabled) {
                    "Nächster Termin wird vom Android-Planer berechnet"
                } else {
                    "Deaktiviert"
                },
                lastRunLabel = lastRunLabel,
                lastFailureLabel = lastFailureLabel,
                pendingCount = inbox.size + pendingRuns.size,
                history = historyEvents.entries,
                historyTruncated = historyEvents.truncated,
            )
        }
    return AutomationsUiState(items = items)
}

private data class AutomationHistoryProjection(
    val entries: List<AutomationRunHistoryUiModel>,
    val truncated: Boolean,
)

private data class AutomationHistoryEvent(
    val eventAt: Instant,
    val scheduledAt: Instant,
    val state: AutomationRunState,
    val failureLabel: String?,
    val isDetailedRun: Boolean,
)

private fun buildAutomationHistory(
    runs: List<AutomationRun>,
    receipts: List<AutomationRunReceipt>,
    systemZone: ZoneId,
): AutomationHistoryProjection {
    val detailedRunKeys = runs.asSequence().map(AutomationRun::key).toHashSet()
    val events = buildList {
        runs.forEach { run ->
            add(
                AutomationHistoryEvent(
                    eventAt = run.updatedAt,
                    scheduledAt = run.key.scheduledAt,
                    state = run.state,
                    failureLabel = run.lastFailureCode?.automationFailureLabel(),
                    isDetailedRun = true,
                ),
            )
        }
        receipts.forEach { receipt ->
            // A receipt is a compacted tombstone. Keep the richer detailed run when both exist.
            if (receipt.key !in detailedRunKeys) {
                add(
                    AutomationHistoryEvent(
                        eventAt = receipt.completedAt,
                        scheduledAt = receipt.key.scheduledAt,
                        state = receipt.terminalState,
                        failureLabel = if (
                            receipt.terminalState == AutomationRunState.FAILED_TERMINAL
                        ) {
                            "Ausführung nicht abgeschlossen"
                        } else {
                            null
                        },
                        isDetailedRun = false,
                    ),
                )
            }
        }
    }.sortedWith(
        compareByDescending<AutomationHistoryEvent> { it.eventAt }
            .thenByDescending { it.scheduledAt }
            .thenByDescending { it.isDetailedRun },
    )

    return AutomationHistoryProjection(
        entries = events.take(MAX_AUTOMATION_HISTORY).map { event ->
            AutomationRunHistoryUiModel(
                headline = "${event.state.uiLabel()} · " +
                    formatAutomationInstant(event.eventAt, systemZone),
                scheduledLabel = "Termin ${formatAutomationInstant(event.scheduledAt, systemZone)}",
                failureLabel = event.failureLabel,
            )
        },
        truncated = events.size > MAX_AUTOMATION_HISTORY,
    )
}

private fun String.automationFailureLabel(): String = when (this) {
    "network_offline" -> "Keine Internetverbindung"
    "device_unlock_required" -> "Wartet auf das Entsperren des Telefons"
    "exact_alarm_access_required" -> "Freigabe für exakte Alarme erforderlich"
    "authorization_probe_failed" -> "Berechtigungsprüfung fehlgeschlagen; wird erneut versucht"
    "job_execution_stopped" -> "Ausführung unterbrochen; wird erneut versucht"
    "codex_login_required" -> "Codex-Anmeldung erforderlich"
    "confirmation_required", "user_confirmation_required" -> "Bestätigung erforderlich"
    "permission_required" -> "Android-Berechtigung erforderlich"
    "capability_unavailable" -> "Benötigte Fähigkeit nicht verfügbar"
    "codex_runtime_starting", "codex_thread_busy", "codex_independent_busy" ->
        "Codex war noch beschäftigt"
    else -> "Ausführung nicht abgeschlossen"
}

private fun formatAutomationInstant(value: Instant, zone: ZoneId): String =
    AUTOMATION_DATE_TIME.format(value.atZone(zone))

private fun AutomationRunState.uiLabel(): String = when (this) {
    AutomationRunState.PENDING -> "Ausstehend"
    AutomationRunState.LEASED -> "Läuft"
    AutomationRunState.RETRY_WAIT -> "Wird erneut versucht"
    AutomationRunState.SUCCEEDED -> "Erfolgreich"
    AutomationRunState.FAILED_TERMINAL -> "Fehlgeschlagen"
    AutomationRunState.SKIPPED -> "Übersprungen"
}

private fun humanRrule(rrule: String): String {
    val values = rrule.split(';').mapNotNull { field ->
        val separator = field.indexOf('=')
        if (separator <= 0) null else field.substring(0, separator).uppercase(Locale.ROOT) to
            field.substring(separator + 1)
    }.toMap()
    val interval = values["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val base = when (values["FREQ"]?.uppercase(Locale.ROOT)) {
        "MINUTELY" -> if (interval == 1) "Jede Minute" else "Alle $interval Minuten"
        "HOURLY" -> if (interval == 1) "Stündlich" else "Alle $interval Stunden"
        "DAILY" -> if (interval == 1) "Täglich" else "Alle $interval Tage"
        "WEEKLY" -> if (interval == 1) "Wöchentlich" else "Alle $interval Wochen"
        "MONTHLY" -> if (interval == 1) "Monatlich" else "Alle $interval Monate"
        "YEARLY" -> if (interval == 1) "Jährlich" else "Alle $interval Jahre"
        else -> "Wiederkehrend"
    }
    val limit = values["COUNT"]?.let { " · $it Läufe" }
        ?: values["UNTIL"]?.let { " · bis $it" }
        ?: ""
    return base + limit
}

private val PENDING_AUTOMATION_STATES = setOf(
    AutomationRunState.PENDING,
    AutomationRunState.LEASED,
    AutomationRunState.RETRY_WAIT,
)

private const val MAX_AUTOMATION_HISTORY = 20
