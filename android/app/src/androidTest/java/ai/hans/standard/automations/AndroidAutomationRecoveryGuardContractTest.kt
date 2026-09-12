package ai.hans.standard.automations

import ai.hans.standard.BuildConfig
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android JobInfo/Bundle construction only: no scheduler, alarms, stores or permissions.
 * These contracts exercise the platform builder on API 31–36, including Android 15's constraint
 * validation. An accepted inexact deadline is not proof of timely execution or process recovery.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutomationRecoveryGuardContractTest {
    private lateinit var context: Context

    @Before
    fun requireSupportedDebugBuild() {
        assumeTrue("Requires a debug application", BuildConfig.DEBUG)
        assumeTrue("Contract matrix covers Android 12–16", Build.VERSION.SDK_INT in 31..36)
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun everyWorkKindBuildsAnIndependentPersistedWakeupWithTheRequestedWindow() {
        val schedules = AutomationRuntimeWorkKind.entries.map(::schedule)
        schedules.forEach { expected ->
            val job = AndroidAutomationRecoveryGuard.platformJob(context, expected)

            assertEquals(expected.jobId, job.id)
            assertFalse(job.id == expected.executionJobId)
            assertFalse(job.id == AndroidAutomationDispatch.WAKEUP_JOB_ID)
            assertEquals(
                ComponentName(context, HansAutomationWakeupJobService::class.java),
                job.service,
            )
            assertEquals(120_000L, job.minLatencyMillis)
            assertEquals(150_000L, job.maxExecutionDelayMillis)
            assertTrue(job.isPersisted)
            assertEquals(expected, AndroidAutomationRecoveryGuard.decode(job.id, job.extras))
        }
        assertEquals(schedules.size, schedules.map { it.jobId }.toSet().size)
        assertTrue(
            schedules.map { it.jobId }.toSet()
                .intersect(schedules.map { it.executionJobId }.toSet()).isEmpty(),
        )
    }

    @Test
    fun actualPlatformBuildHasNoFunctionalPeriodicOrExpeditedConstraints() {
        AutomationRuntimeWorkKind.entries.forEach { kind ->
            val job = AndroidAutomationRecoveryGuard.platformJob(context, schedule(kind))

            assertFalse(job.isRequireCharging)
            assertFalse(job.isRequireBatteryNotLow)
            assertFalse(job.isRequireStorageNotLow)
            assertFalse(job.isRequireDeviceIdle)
            assertNull(job.requiredNetwork)
            assertNull(job.triggerContentUris)
            assertNull(job.clipData)
            assertTrue(job.transientExtras.isEmpty)
            assertFalse(job.isPeriodic)
            assertFalse(job.isPrefetch)
            assertFalse(job.isExpedited)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                assertFalse(job.isUserInitiated)
            }
        }
    }

    @Test
    fun extrasContainOnlySchedulingIdentityAndRoundTripEveryWorkKind() {
        AutomationRuntimeWorkKind.entries.forEach { kind ->
            val expected = schedule(kind)
            val encoded = AndroidAutomationRecoveryGuard.extras(expected)

            assertEquals(REQUIRED_KEYS, encoded.keySet())
            assertEquals(1, encoded.getInt(KEY_SCHEMA))
            assertEquals(expected.executionJobId, encoded.getInt(KEY_EXECUTION_ID))
            assertEquals(kind.name, encoded.getString(KEY_KIND))
            assertEquals(expected.generation, encoded.getLong(KEY_GENERATION))
            assertEquals(NONCE, encoded.getString(KEY_NONCE))
            assertEquals(
                expected,
                AndroidAutomationRecoveryGuard.decode(expected.jobId, PersistableBundle(encoded)),
            )
        }
    }

    @Test
    fun decoderRejectsEveryMissingRequiredField() {
        val expected = schedule()
        REQUIRED_KEYS.forEach { key ->
            val extras = AndroidAutomationRecoveryGuard.extras(expected).apply { remove(key) }
            assertNull("Missing $key must be rejected", decode(expected, extras))
        }
        assertNull(decode(expected, PersistableBundle()))
    }

    @Test
    fun decoderRequiresTheSupportedIntegerSchemaVersion() {
        val expected = schedule()
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach { version ->
            assertRejected(expected) { putInt(KEY_SCHEMA, version) }
        }
        assertRejected(expected) { putString(KEY_SCHEMA, "1") }
        assertRejected(expected) { putLong(KEY_SCHEMA, 1L) }
    }

    @Test
    fun decoderRejectsUnknownMalformedOrWronglyTypedWorkKinds() {
        val expected = schedule()
        listOf("", "timer", "TIMER ", "UNKNOWN").forEach { kind ->
            assertRejected(expected) { putString(KEY_KIND, kind) }
        }
        assertRejected(expected) { putString(KEY_KIND, null) }
        assertRejected(expected) { putInt(KEY_KIND, 0) }
    }

    @Test
    fun decoderRequiresAPositiveLongGenerationWithoutNarrowingIt() {
        val expected = schedule()
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { generation ->
            assertRejected(expected) { putLong(KEY_GENERATION, generation) }
        }
        assertRejected(expected) { putInt(KEY_GENERATION, 1) }
        assertRejected(expected) { putString(KEY_GENERATION, "1") }
        listOf(1L, Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE).forEach { generation ->
            val valid = expected.copy(generation = generation)
            assertEquals(valid, decode(valid, AndroidAutomationRecoveryGuard.extras(valid)))
        }
    }

    @Test
    fun decoderRejectsNonpositiveOrCollidingIdsButDoesNotPretendToCheckBindingOwnership() {
        val expected = schedule()
        listOf(Int.MIN_VALUE, -1, 0, expected.executionJobId).forEach { jobId ->
            assertNull(
                AndroidAutomationRecoveryGuard.decode(jobId, AndroidAutomationRecoveryGuard.extras(expected)),
            )
        }
        listOf(Int.MIN_VALUE, -1, 0, expected.jobId).forEach { executionJobId ->
            assertRejected(expected) { putInt(KEY_EXECUTION_ID, executionJobId) }
        }
        assertRejected(expected) { putLong(KEY_EXECUTION_ID, expected.executionJobId.toLong()) }
        assertRejected(expected) { putString(KEY_EXECUTION_ID, expected.executionJobId.toString()) }

        // The decoder validates a descriptor, not a production/test ID allowlist. A receiver must
        // separately match this identity to its own registered guard before it can act on it.
        val otherPositiveIds = expected.copy(jobId = 42, executionJobId = 43)
        assertEquals(
            otherPositiveIds,
            decode(otherPositiveIds, AndroidAutomationRecoveryGuard.extras(otherPositiveIds)),
        )
    }

    @Test
    fun decoderRequiresACanonicalUuidNonce() {
        val expected = schedule()
        listOf("", "not-a-uuid", "1-1-1-1-1", " $NONCE", "$NONCE ", NONCE.uppercase(Locale.ROOT))
            .forEach { nonce -> assertRejected(expected) { putString(KEY_NONCE, nonce) } }
        assertRejected(expected) { putString(KEY_NONCE, null) }
        assertRejected(expected) { putInt(KEY_NONCE, 1) }
        assertEquals(expected, decode(expected, AndroidAutomationRecoveryGuard.extras(expected)))
    }

    private fun schedule(
        kind: AutomationRuntimeWorkKind = AutomationRuntimeWorkKind.TIMER,
    ): AutomationRecoveryGuardSchedule = AutomationRecoveryGuardSchedule(
        jobId = 0x48542000 + kind.ordinal,
        executionJobId = 0x48541000 + kind.ordinal,
        kind = kind,
        generation = kind.ordinal.toLong() + 1L,
        nonce = NONCE,
    )

    private fun decode(
        expected: AutomationRecoveryGuardSchedule,
        extras: PersistableBundle,
    ): AutomationRecoveryGuardSchedule? = AndroidAutomationRecoveryGuard.decode(expected.jobId, extras)

    private fun assertRejected(
        expected: AutomationRecoveryGuardSchedule,
        mutate: PersistableBundle.() -> Unit,
    ) {
        val extras = AndroidAutomationRecoveryGuard.extras(expected).apply(mutate)
        assertNull(decode(expected, extras))
    }

    private companion object {
        const val NONCE = "18c8926e-2a1f-4dc1-8f30-049e7b466e73"
        const val KEY_SCHEMA = "recovery_schema"
        const val KEY_EXECUTION_ID = "recovery_execution_id"
        const val KEY_KIND = "recovery_kind"
        const val KEY_GENERATION = "recovery_generation"
        const val KEY_NONCE = "recovery_nonce"
        val REQUIRED_KEYS = setOf(KEY_SCHEMA, KEY_EXECUTION_ID, KEY_KIND, KEY_GENERATION, KEY_NONCE)
    }
}
