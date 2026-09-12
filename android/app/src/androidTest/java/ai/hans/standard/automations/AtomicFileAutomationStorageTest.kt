package ai.hans.standard.automations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicFileAutomationStorageTest {
    private lateinit var context: Context
    private lateinit var fileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "automation-test-${System.nanoTime()}.json"
    }

    @After
    fun tearDown() {
        context.noBackupFilesDir.resolve(fileName).delete()
        context.noBackupFilesDir.resolve("$fileName.bak").delete()
        context.noBackupFilesDir.resolve("$fileName.new").delete()
    }

    @Test
    fun definitionInboxAndCursorSurviveAtomicFileReopen() {
        val persistence = AtomicFileAutomationSnapshotPersistence(context, fileName)
        val storage = PersistentAutomationStorage(persistence)
        val definition = AutomationDefinition(
            id = AutomationId("automation.device_test"),
            revision = 1,
            enabled = true,
            schedule = AutomationSchedule(
                LocalDateTime.of(2026, 8, 24, 9, 0),
                AutomationTimeZone.Fixed("Europe/Oslo"),
                "FREQ=DAILY",
            ),
            missedRunPolicy = MissedRunPolicy(),
            retryPolicy = AutomationRetryPolicy(),
            target = CodexAutomationTarget.Independent,
            instruction = "Create a private daily test summary.",
            updatedAt = Instant.parse("2026-08-23T20:00:00Z"),
        )
        val scheduledAt = Instant.parse("2026-08-24T07:00:00Z")
        storage.upsertDefinition(definition)
        storage.recordDiscovery(
            AutomationDiscoveryBatch(
                automationId = definition.id,
                definitionRevision = definition.revision,
                evaluatedThrough = scheduledAt,
                items = listOf(
                    AutomationInboxItem(
                        key = AutomationRunKey(definition.id, scheduledAt),
                        definitionRevision = definition.revision,
                        discoveredAt = scheduledAt,
                        readyAt = scheduledAt,
                        source = AutomationDiscoverySource.TIMER,
                    ),
                ),
            ),
        )

        val reopened = PersistentAutomationStorage(
            AtomicFileAutomationSnapshotPersistence(context, fileName),
        )

        assertEquals(definition, reopened.definition(definition.id))
        assertEquals(1, reopened.snapshot().inbox.size)
        assertEquals(scheduledAt, reopened.schedulerCursor(definition.id)?.evaluatedThrough)
    }
}
