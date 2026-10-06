package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import java.time.format.FormatStyle
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

private fun automationDateTime(text: HansTextResolver): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(text.locale)

/** Pure, bounded projection. Private instructions are shown only in the user's own launcher UI. */
internal fun projectAutomations(
    snapshot: AutomationStorageSnapshot,
    systemZone: ZoneId,
    text: HansTextResolver,
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
                text = text,
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
                lastRun == null && lastReceipt == null -> text.text(R.string.presentation_automation_no_runs)
                detailedRunIsLatest -> checkNotNull(lastRun).let {
                    "${it.state.uiLabel(text)} · ${formatAutomationInstant(it.updatedAt, systemZone, text)}"
                }
                else -> checkNotNull(lastReceipt).let {
                    "${it.terminalState.uiLabel(text)} · ${formatAutomationInstant(it.completedAt, systemZone, text)}"
                }
            }
            val lastFailureLabel = when {
                detailedRunIsLatest -> checkNotNull(lastRun).lastFailureCode?.automationFailureLabel(text)
                lastReceipt?.terminalState == AutomationRunState.FAILED_TERMINAL ->
                    text.text(R.string.presentation_automation_incomplete)
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
                    append(humanRrule(definition.schedule.rrule, text))
                    append(text.text(R.string.presentation_automation_from))
                    append(definition.schedule.dtStartLocal.format(automationDateTime(text)))
                    append(" · ")
                    append(
                        when (definition.schedule.timeZone) {
                            AutomationTimeZone.FollowSystem -> text.text(R.string.presentation_automation_system_zone)
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
                        text.text(R.string.presentation_automation_inexact)
                    AutomationTimingPolicy.USER_VISIBLE_EXACT ->
                        text.text(R.string.presentation_automation_exact)
                },
                nextRunLabel = nextAt?.let {
                    text.text(R.string.presentation_automation_reserved, formatAutomationInstant(it, systemZone, text))
                } ?: if (definition.enabled) {
                    text.text(R.string.presentation_automation_calculating)
                } else {
                    text.text(R.string.presentation_automation_disabled)
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
    text: HansTextResolver,
): AutomationHistoryProjection {
    val detailedRunKeys = runs.asSequence().map(AutomationRun::key).toHashSet()
    val events = buildList {
        runs.forEach { run ->
            add(
                AutomationHistoryEvent(
                    eventAt = run.updatedAt,
                    scheduledAt = run.key.scheduledAt,
                    state = run.state,
                    failureLabel = run.lastFailureCode?.automationFailureLabel(text),
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
                            text.text(R.string.presentation_automation_incomplete)
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
                headline = "${event.state.uiLabel(text)} · " +
                    formatAutomationInstant(event.eventAt, systemZone, text),
                scheduledLabel = text.text(R.string.presentation_automation_scheduled, formatAutomationInstant(event.scheduledAt, systemZone, text)),
                failureLabel = event.failureLabel,
            )
        },
        truncated = events.size > MAX_AUTOMATION_HISTORY,
    )
}

private fun String.automationFailureLabel(text: HansTextResolver): String = when (this) {
    "network_offline" -> text.text(R.string.presentation_automation_network_offline)
    "device_unlock_required" -> text.text(R.string.presentation_automation_unlock_required)
    "exact_alarm_access_required" -> text.text(R.string.presentation_automation_exact_access)
    "authorization_probe_failed" -> text.text(R.string.presentation_automation_probe_retry)
    "job_execution_stopped" -> text.text(R.string.presentation_automation_stopped_retry)
    "codex_login_required" -> text.text(R.string.presentation_automation_login)
    "confirmation_required", "user_confirmation_required" -> text.text(R.string.presentation_automation_confirmation)
    "permission_required" -> text.text(R.string.presentation_automation_permission)
    "capability_unavailable" -> text.text(R.string.presentation_automation_capability)
    "codex_runtime_starting", "codex_thread_busy", "codex_independent_busy" ->
        text.text(R.string.presentation_automation_busy)
    else -> text.text(R.string.presentation_automation_incomplete)
}

private fun formatAutomationInstant(value: Instant, zone: ZoneId, text: HansTextResolver): String =
    automationDateTime(text).format(value.atZone(zone))

private fun AutomationRunState.uiLabel(text: HansTextResolver): String = when (this) {
    AutomationRunState.PENDING -> text.text(R.string.presentation_automation_state_pending)
    AutomationRunState.LEASED -> text.text(R.string.presentation_automation_state_leased)
    AutomationRunState.RETRY_WAIT -> text.text(R.string.presentation_automation_state_retry_wait)
    AutomationRunState.SUCCEEDED -> text.text(R.string.presentation_automation_state_succeeded)
    AutomationRunState.FAILED_TERMINAL -> text.text(R.string.presentation_automation_state_failed)
    AutomationRunState.SKIPPED -> text.text(R.string.presentation_automation_state_skipped)
}

private fun humanRrule(rrule: String, text: HansTextResolver): String {
    val values = rrule.split(';').mapNotNull { field ->
        val separator = field.indexOf('=')
        if (separator <= 0) null else field.substring(0, separator).uppercase(Locale.ROOT) to
            field.substring(separator + 1)
    }.toMap()
    val interval = values["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val base = when (values["FREQ"]?.uppercase(Locale.ROOT)) {
        "MINUTELY" -> if (interval == 1) text.text(R.string.presentation_automation_minutely) else text.text(R.string.presentation_automation_minutes, interval)
        "HOURLY" -> if (interval == 1) text.text(R.string.presentation_automation_hourly) else text.text(R.string.presentation_automation_hours, interval)
        "DAILY" -> if (interval == 1) text.text(R.string.presentation_automation_daily) else text.text(R.string.presentation_automation_days, interval)
        "WEEKLY" -> if (interval == 1) text.text(R.string.presentation_automation_weekly) else text.text(R.string.presentation_automation_weeks, interval)
        "MONTHLY" -> if (interval == 1) text.text(R.string.presentation_automation_monthly) else text.text(R.string.presentation_automation_months, interval)
        "YEARLY" -> if (interval == 1) text.text(R.string.presentation_automation_yearly) else text.text(R.string.presentation_automation_years, interval)
        else -> text.text(R.string.presentation_automation_recurring)
    }
    val limit = values["COUNT"]?.let { text.text(R.string.presentation_automation_run_limit, it) }
        ?: values["UNTIL"]?.let { text.text(R.string.presentation_automation_until, it) }
        ?: ""
    return base + limit
}

private val PENDING_AUTOMATION_STATES = setOf(
    AutomationRunState.PENDING,
    AutomationRunState.LEASED,
    AutomationRunState.RETRY_WAIT,
)

private const val MAX_AUTOMATION_HISTORY = 20
