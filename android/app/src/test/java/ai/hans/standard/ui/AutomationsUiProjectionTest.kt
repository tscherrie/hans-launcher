package ai.hans.standard.ui

import ai.hans.standard.automations.AutomationConfirmationPolicy
import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.automations.AutomationId
import ai.hans.standard.automations.AutomationInboxItem
import ai.hans.standard.automations.AutomationRequirements
import ai.hans.standard.automations.AutomationRun
import ai.hans.standard.automations.AutomationRunKey
import ai.hans.standard.automations.AutomationRunReceipt
import ai.hans.standard.automations.AutomationRunState
import ai.hans.standard.automations.AutomationSchedule
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.automations.AutomationTimeZone
import ai.hans.standard.automations.CodexAutomationTarget
import ai.hans.standard.automations.MissedRunMode
import ai.hans.standard.automations.MissedRunPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationsUiProjectionTest {
    @Test
    fun projectsPersistedUnattendedPolicyAndCatchUpStateWithoutInventingRuntimeState() {
        val id = AutomationId("morning_briefing")
        val scheduledAt = Instant.parse("2026-08-30T06:00:00Z")
        val definition = definition(
            id = id,
            confirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
            missedRunMode = MissedRunMode.RUN_LATEST,
        )
        val snapshot = AutomationStorageSnapshot(
            definitions = listOf(definition),
            inbox = listOf(
                AutomationInboxItem(
                    key = AutomationRunKey(id, scheduledAt),
                    definitionRevision = 3,
                    discoveredAt = scheduledAt.plusSeconds(30),
                    readyAt = scheduledAt.plusSeconds(30),
                    source = ai.hans.standard.automations.AutomationDiscoverySource.OFFLINE_RECOVERY,
                ),
            ),
            runs = listOf(
                AutomationRun(
                    key = AutomationRunKey(id, scheduledAt.minusSeconds(86_400)),
                    definitionRevision = 3,
                    state = AutomationRunState.SUCCEEDED,
                    attemptCount = 1,
                    availableAt = scheduledAt.minusSeconds(86_400),
                    createdAt = scheduledAt.minusSeconds(86_400),
                    updatedAt = scheduledAt.minusSeconds(86_300),
                ),
            ),
        )

        val state = projectAutomations(snapshot, ZoneId.of("Europe/Sofia"))

        val item = state.items.single()
        assertEquals("morning_briefing", item.id)
        assertTrue(item.unattended)
        assertEquals(AutomationMissedRunUiMode.RUN_LATEST, item.missedRunMode)
        assertEquals(1, item.pendingCount)
        assertTrue(item.nextRunLabel.contains("Vorgemerkt"))
        assertTrue(item.lastRunLabel.startsWith("Erfolgreich"))
        assertEquals(1, item.history.size)
        assertTrue(item.history.single().headline.startsWith("Erfolgreich · "))
        assertTrue(item.history.single().scheduledLabel.startsWith("Termin "))
        assertNull(item.history.single().failureLabel)
        assertFalse(item.historyTruncated)
        assertFalse(state.loading)
    }

    @Test
    fun confirmationRequiredAndDisabledRemainVisibleAsEffectivePersistedState() {
        val definition = definition(
            id = AutomationId("weekly_check"),
            confirmationPolicy = AutomationConfirmationPolicy.EVERY_RUN,
            missedRunMode = MissedRunMode.SKIP,
        ).copy(enabled = false)

        val item = projectAutomations(
            AutomationStorageSnapshot(definitions = listOf(definition)),
            ZoneId.of("UTC"),
        ).items.single()

        assertFalse(item.enabled)
        assertFalse(item.unattended)
        assertEquals(AutomationMissedRunUiMode.SKIP, item.missedRunMode)
        assertEquals("Deaktiviert", item.nextRunLabel)
        assertEquals("Noch kein Lauf", item.lastRunLabel)
    }

    @Test
    fun latestRetryFailureIsRenderedAsUserFacingState() {
        val id = AutomationId("offline_retry")
        val now = Instant.parse("2026-08-30T09:00:00Z")
        val item = projectAutomations(
            AutomationStorageSnapshot(
                definitions = listOf(
                    definition(id, AutomationConfirmationPolicy.CAPABILITY_POLICY, MissedRunMode.RUN_LATEST),
                ),
                runs = listOf(
                    AutomationRun(
                        key = AutomationRunKey(id, now.minusSeconds(60)),
                        definitionRevision = 3,
                        state = AutomationRunState.RETRY_WAIT,
                        attemptCount = 1,
                        availableAt = now.plusSeconds(60),
                        createdAt = now.minusSeconds(60),
                        updatedAt = now,
                        lastFailureCode = "network_offline",
                    ),
                ),
            ),
            ZoneId.of("UTC"),
        ).items.single()

        assertEquals("Keine Internetverbindung", item.lastFailureLabel)
        assertEquals(1, item.pendingCount)
    }

    @Test
    fun deferredAndInterruptedRunsExplainWhatHansIsWaitingFor() {
        val now = Instant.parse("2026-08-30T09:00:00Z")
        val expectedLabels = linkedMapOf(
            "device_unlock_required" to "Wartet auf das Entsperren des Telefons",
            "exact_alarm_access_required" to "Freigabe für exakte Alarme erforderlich",
            "authorization_probe_failed" to
                "Berechtigungsprüfung fehlgeschlagen; wird erneut versucht",
            "job_execution_stopped" to "Ausführung unterbrochen; wird erneut versucht",
        )

        expectedLabels.forEach { (failureCode, expectedLabel) ->
            val id = AutomationId("status_${failureCode}")
            val item = projectAutomations(
                AutomationStorageSnapshot(
                    definitions = listOf(
                        definition(
                            id,
                            AutomationConfirmationPolicy.CAPABILITY_POLICY,
                            MissedRunMode.RUN_LATEST,
                        ),
                    ),
                    runs = listOf(
                        AutomationRun(
                            key = AutomationRunKey(id, now.minusSeconds(60)),
                            definitionRevision = 3,
                            state = AutomationRunState.RETRY_WAIT,
                            attemptCount = 1,
                            availableAt = now.plusSeconds(60),
                            createdAt = now.minusSeconds(60),
                            updatedAt = now,
                            lastFailureCode = failureCode,
                        ),
                    ),
                ),
                ZoneId.of("UTC"),
            ).items.single()

            assertEquals(expectedLabel, item.lastFailureLabel)
            assertEquals(expectedLabel, item.history.single().failureLabel)
        }
    }

    @Test
    fun newerSuccessReceiptDoesNotLeakOlderDetailedFailure() {
        val id = AutomationId("recovered_after_network")
        val olderRunAt = Instant.parse("2026-08-30T08:00:00Z")
        val newerReceiptAt = Instant.parse("2026-08-30T09:00:00Z")
        val item = projectAutomations(
            AutomationStorageSnapshot(
                definitions = listOf(
                    definition(id, AutomationConfirmationPolicy.CAPABILITY_POLICY, MissedRunMode.RUN_LATEST),
                ),
                runs = listOf(
                    AutomationRun(
                        key = AutomationRunKey(id, olderRunAt.minusSeconds(60)),
                        definitionRevision = 3,
                        state = AutomationRunState.RETRY_WAIT,
                        attemptCount = 1,
                        availableAt = olderRunAt.plusSeconds(60),
                        createdAt = olderRunAt.minusSeconds(60),
                        updatedAt = olderRunAt,
                        lastFailureCode = "network_offline",
                    ),
                ),
                receipts = listOf(
                    AutomationRunReceipt(
                        key = AutomationRunKey(id, newerReceiptAt.minusSeconds(60)),
                        terminalState = AutomationRunState.SUCCEEDED,
                        completedAt = newerReceiptAt,
                    ),
                ),
            ),
            ZoneId.of("UTC"),
        ).items.single()

        assertTrue(item.lastRunLabel.startsWith("Erfolgreich"))
        assertNull(item.lastFailureLabel)
    }

    @Test
    fun newerFailedReceiptUsesGenericReceiptFailureInsteadOfOlderDetailedFailure() {
        val id = AutomationId("failed_after_network")
        val olderRunAt = Instant.parse("2026-08-30T08:00:00Z")
        val newerReceiptAt = Instant.parse("2026-08-30T09:00:00Z")
        val item = projectAutomations(
            AutomationStorageSnapshot(
                definitions = listOf(
                    definition(id, AutomationConfirmationPolicy.CAPABILITY_POLICY, MissedRunMode.RUN_LATEST),
                ),
                runs = listOf(
                    AutomationRun(
                        key = AutomationRunKey(id, olderRunAt.minusSeconds(60)),
                        definitionRevision = 3,
                        state = AutomationRunState.RETRY_WAIT,
                        attemptCount = 1,
                        availableAt = olderRunAt.plusSeconds(60),
                        createdAt = olderRunAt.minusSeconds(60),
                        updatedAt = olderRunAt,
                        lastFailureCode = "network_offline",
                    ),
                ),
                receipts = listOf(
                    AutomationRunReceipt(
                        key = AutomationRunKey(id, newerReceiptAt.minusSeconds(60)),
                        terminalState = AutomationRunState.FAILED_TERMINAL,
                        completedAt = newerReceiptAt,
                    ),
                ),
            ),
            ZoneId.of("UTC"),
        ).items.single()

        assertTrue(item.lastRunLabel.startsWith("Fehlgeschlagen"))
        assertEquals("Ausführung nicht abgeschlossen", item.lastFailureLabel)
    }

    @Test
    fun historyDeduplicatesReceiptAgainstDetailedRunAndSortsNewestFirst() {
        val id = AutomationId("ordered_history")
        val firstScheduledAt = Instant.parse("2026-08-30T06:00:00Z")
        val secondScheduledAt = Instant.parse("2026-08-30T07:00:00Z")
        val detailedKey = AutomationRunKey(id, firstScheduledAt)
        val receiptOnlyKey = AutomationRunKey(id, secondScheduledAt)
        val item = projectAutomations(
            AutomationStorageSnapshot(
                definitions = listOf(
                    definition(id, AutomationConfirmationPolicy.CAPABILITY_POLICY, MissedRunMode.RUN_LATEST),
                ),
                runs = listOf(
                    AutomationRun(
                        key = detailedKey,
                        definitionRevision = 3,
                        state = AutomationRunState.RETRY_WAIT,
                        attemptCount = 1,
                        availableAt = firstScheduledAt.plusSeconds(600),
                        createdAt = firstScheduledAt,
                        updatedAt = firstScheduledAt.plusSeconds(300),
                        lastFailureCode = "network_offline",
                    ),
                ),
                receipts = listOf(
                    AutomationRunReceipt(
                        key = detailedKey,
                        terminalState = AutomationRunState.FAILED_TERMINAL,
                        completedAt = firstScheduledAt.plusSeconds(900),
                    ),
                    AutomationRunReceipt(
                        key = receiptOnlyKey,
                        terminalState = AutomationRunState.SUCCEEDED,
                        completedAt = secondScheduledAt.plusSeconds(300),
                    ),
                ),
            ),
            ZoneId.of("UTC"),
        ).items.single()

        assertEquals(2, item.history.size)
        assertTrue(item.history[0].headline.startsWith("Erfolgreich · 30.08.2026, 07:05"))
        assertEquals("Termin 30.08.2026, 07:00", item.history[0].scheduledLabel)
        assertTrue(item.history[1].headline.startsWith("Wird erneut versucht · 30.08.2026, 06:05"))
        assertEquals("Keine Internetverbindung", item.history[1].failureLabel)
        assertFalse(item.history.any { it.headline.startsWith("Fehlgeschlagen") })
    }

    @Test
    fun historyIsBoundedToTwentyNewestPersistedEventsAndMarksTruncation() {
        val id = AutomationId("bounded_history")
        val start = Instant.parse("2026-08-01T08:00:00Z")
        val receipts = (0 until 23).map { index ->
            val scheduledAt = start.plusSeconds(index * 3_600L)
            AutomationRunReceipt(
                key = AutomationRunKey(id, scheduledAt),
                terminalState = if (index == 22) {
                    AutomationRunState.FAILED_TERMINAL
                } else {
                    AutomationRunState.SUCCEEDED
                },
                completedAt = scheduledAt.plusSeconds(60),
            )
        }
        val item = projectAutomations(
            AutomationStorageSnapshot(
                definitions = listOf(
                    definition(id, AutomationConfirmationPolicy.CAPABILITY_POLICY, MissedRunMode.CATCH_UP),
                ),
                receipts = receipts,
            ),
            ZoneId.of("UTC"),
        ).items.single()

        assertEquals(20, item.history.size)
        assertTrue(item.historyTruncated)
        assertTrue(item.history.first().headline.startsWith("Fehlgeschlagen · 02.08.2026, 06:01"))
        assertEquals("Termin 02.08.2026, 06:00", item.history.first().scheduledLabel)
        assertEquals("Ausführung nicht abgeschlossen", item.history.first().failureLabel)
        assertEquals("Termin 01.08.2026, 11:00", item.history.last().scheduledLabel)
    }

    private fun definition(
        id: AutomationId,
        confirmationPolicy: AutomationConfirmationPolicy,
        missedRunMode: MissedRunMode,
    ) = AutomationDefinition(
        id = id,
        revision = 3,
        enabled = true,
        schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.of(2026, 8, 30, 9, 0),
            timeZone = AutomationTimeZone.FollowSystem,
            rrule = "FREQ=DAILY;INTERVAL=1",
        ),
        missedRunPolicy = MissedRunPolicy(mode = missedRunMode),
        retryPolicy = ai.hans.standard.automations.AutomationRetryPolicy(),
        target = CodexAutomationTarget.Independent,
        instruction = "Fasse die wichtigsten Aufgaben zusammen.",
        updatedAt = Instant.parse("2026-08-29T20:00:00Z"),
        requirements = AutomationRequirements(confirmationPolicy = confirmationPolicy),
    )
}
