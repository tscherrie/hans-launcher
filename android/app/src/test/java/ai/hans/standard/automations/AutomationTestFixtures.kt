package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

internal fun testDefinition(
    id: String = "automation.test",
    revision: Long = 1,
    enabled: Boolean = true,
    dtStart: LocalDateTime = LocalDateTime.of(2026, 1, 1, 9, 0),
    timeZone: AutomationTimeZone = AutomationTimeZone.Fixed("UTC"),
    rrule: String = "FREQ=DAILY",
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy(),
    retryPolicy: AutomationRetryPolicy = AutomationRetryPolicy(
        maximumAttempts = 3,
        initialBackoff = Duration.ofSeconds(10),
        backoffMultiplier = 2,
        maximumBackoff = Duration.ofMinutes(1),
    ),
    target: CodexAutomationTarget = CodexAutomationTarget.Independent,
    instruction: String = "Erstelle die tägliche Zusammenfassung.",
): AutomationDefinition = AutomationDefinition(
    id = AutomationId(id),
    revision = revision,
    enabled = enabled,
    schedule = AutomationSchedule(dtStart, timeZone, rrule),
    missedRunPolicy = missedRunPolicy,
    retryPolicy = retryPolicy,
    target = target,
    instruction = instruction,
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

internal fun discoveredItem(
    definition: AutomationDefinition,
    scheduledAt: Instant,
    discoveredAt: Instant = scheduledAt,
    source: AutomationDiscoverySource = AutomationDiscoverySource.TIMER,
): AutomationInboxItem = AutomationInboxItem(
    key = AutomationRunKey(definition.id, scheduledAt),
    definitionRevision = definition.revision,
    discoveredAt = discoveredAt,
    readyAt = maxOf(discoveredAt, scheduledAt),
    source = source,
)

internal fun InMemoryAutomationStorage.insertDueRun(
    definition: AutomationDefinition,
    scheduledAt: Instant,
    now: Instant = scheduledAt,
): AutomationRunKey {
    check(upsertDefinition(definition) in setOf(
        DefinitionWriteResult.INSERTED,
        DefinitionWriteResult.UNCHANGED,
    ))
    val item = discoveredItem(definition, scheduledAt, discoveredAt = now)
    check(
        recordDiscovery(
            AutomationDiscoveryBatch(
                definition.id,
                definition.revision,
                now,
                listOf(item),
            ),
        ).accepted,
    )
    check(materializeDueInbox(now).createdRuns == listOf(item.key))
    return item.key
}
