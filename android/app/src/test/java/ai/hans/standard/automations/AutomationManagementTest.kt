package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationManagementTest {
    @Test
    fun createAndReplaceUseRevisionCompareAndSetAndRejectUnsupportedRules() {
        val clock = ManagementClock(Instant.parse("2026-01-01T00:00:00Z"))
        val storage = InMemoryAutomationStorage()
        val manager = AutomationDefinitionManager(storage, clock)
        val id = AutomationId("automation.managed")
        val draft = AutomationDefinitionDraft(
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 1, 2, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            instruction = "Lag en kort oppsummering.",
        )

        val created = manager.create(id, draft) as AutomationDefinitionMutationResult.Applied
        assertEquals(1, created.definition.revision)
        assertEquals(clock.value, created.definition.updatedAt)
        assertEquals(AutomationDefinitionMutationResult.AlreadyExists, manager.create(id, draft))

        clock.value = clock.value.plusSeconds(60)
        val replacement = draft.copy(instruction = "Lag en svært kort oppsummering.")
        assertEquals(
            AutomationDefinitionMutationResult.RevisionConflict,
            manager.replace(id, expectedRevision = 9, draft = replacement),
        )
        val updated = manager.replace(id, expectedRevision = 1, draft = replacement)
            as AutomationDefinitionMutationResult.Applied
        assertEquals(2, updated.definition.revision)
        assertEquals(replacement.instruction, storage.definition(id)?.instruction)

        val invalid = replacement.copy(
            schedule = replacement.schedule.copy(rrule = "FREQ=SECONDLY"),
        )
        assertEquals(
            AutomationDefinitionMutationResult.InvalidSchedule("unsupported_frequency"),
            manager.replace(id, expectedRevision = 2, draft = invalid),
        )
        assertEquals(2L, storage.definition(id)?.revision)
    }

    @Test
    fun historyIsBoundedPagedAndStatusCountsEveryState() {
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        storage.upsertDefinition(definition)
        val base = Instant.parse("2026-01-01T09:00:00Z")
        repeat(3) { index ->
            val at = base.plusSeconds(index.toLong())
            storage.recordDiscovery(
                AutomationDiscoveryBatch(
                    definition.id,
                    definition.revision,
                    at,
                    listOf(discoveredItem(definition, at)),
                ),
            )
            storage.materializeDueInbox(at)
        }
        val reader = AutomationHistoryReader(storage)

        val first = reader.page(limit = 2)
        assertEquals(2, first.runs.size)
        assertTrue(first.hasMore)
        val second = reader.page(beforeExclusive = first.nextCursor, limit = 2)
        assertEquals(1, second.runs.size)
        assertFalse(second.hasMore)
        assertTrue((first.runs + second.runs).map { it.key }.distinct().size == 3)

        val summary = reader.summary()
        assertEquals(1, summary.enabledDefinitions)
        assertEquals(3, summary.pendingRuns)
        assertEquals(0, summary.succeededRuns)
    }

    @Test
    fun replacingADefinitionAtomicallyRetiresOldPendingAndLeasedWork() {
        val now = Instant.parse("2026-01-01T09:00:00Z")
        val clock = ManagementClock(now.plusSeconds(5))
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition()
        val key = storage.insertDueRun(definition, now)
        val token = AutomationLeaseToken("lease-token-definition-update")
        storage.acquireNextLease(
            AutomationWorkerId("worker-definition-update"),
            token,
            AutomationBootSessionId("boot-0001"),
            now,
            Duration.ofMinutes(2),
        )
        val manager = AutomationDefinitionManager(storage, clock)
        val replacement = AutomationDefinitionDraft(
            schedule = definition.schedule,
            instruction = "A revised instruction.",
        )

        val result = manager.replace(definition.id, definition.revision, replacement)

        assertTrue(result is AutomationDefinitionMutationResult.Applied)
        assertEquals(2L, storage.definition(definition.id)!!.revision)
        assertEquals(AutomationRunState.SKIPPED, storage.snapshot().runs.single().state)
        assertEquals(
            "automation_definition_updated",
            storage.snapshot().runs.single().lastFailureCode,
        )
        assertTrue(storage.snapshot().leases.isEmpty())
        assertEquals(
            AutomationHeartbeatResult.LEASE_NOT_FOUND,
            storage.heartbeat(key, token, clock.value, Duration.ofMinutes(2)),
        )
    }
}

private class ManagementClock(var value: Instant) : AutomationInstantSource {
    override fun now(): Instant = value
}
