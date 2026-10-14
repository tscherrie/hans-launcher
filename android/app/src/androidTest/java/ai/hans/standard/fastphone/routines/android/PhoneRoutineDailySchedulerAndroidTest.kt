package ai.hans.standard.fastphone.routines.android

import ai.hans.standard.backup.HansBackupMaintenance
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only UUID-derived owned fixture jobs are scheduled; never touches the production job ID. */
@RunWith(AndroidJUnit4::class)
class PhoneRoutineDailySchedulerAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val ownedJobIds = mutableSetOf<Int>()

    @After fun cancelOnlyFixtureJobs() { ownedJobIds.forEach(scheduler::cancel) }

    @Test fun jobRequiresBatteryAndValidatedInternetWithoutExcludingVpnOrRequiringExactAlarm() {
        val jobId = fixtureId()
        val job = AndroidPhoneRoutineDailyPlatform.platformJob(context, 120_000L, 123_456L, jobId)
        assertEquals(jobId, job.id)
        assertEquals(ComponentName(context, HansPhoneRoutineDailyLearningJobService::class.java), job.service)
        assertTrue(job.isPersisted)
        assertTrue(job.isRequireBatteryNotLow)
        assertFalse(job.isRequireCharging)
        assertFalse(job.isRequireDeviceIdle)
        assertFalse(job.isPeriodic)
        assertFalse(job.isExpedited)
        assertEquals(120_000L, job.minLatencyMillis)
        val baseline = JobInfo.Builder(fixtureId(), job.service).setMinimumLatency(1L).build()
        assertEquals(baseline.maxExecutionDelayMillis, job.maxExecutionDelayMillis)
        val network = checkNotNull(job.requiredNetwork)
        assertTrue(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
        assertEquals(setOf(AndroidPhoneRoutineDailyPlatform.KEY_WAKE_AT), job.extras.keySet())
        assertEquals(123_456L, job.extras.getLong(AndroidPhoneRoutineDailyPlatform.KEY_WAKE_AT))
    }

    @Test fun platformRetainsPersistedDailyWakeAfterRuntimeObjectReconstruction() {
        val jobId = fixtureId()
        val wake = System.currentTimeMillis() + 86_400_000L
        val job = AndroidPhoneRoutineDailyPlatform.platformJob(context, 86_400_000L, wake, jobId)
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
        val reconstructed = context.getSystemService(JobScheduler::class.java)
        val retained = checkNotNull(reconstructed.getPendingJob(jobId))
        assertTrue(retained.isPersisted)
        assertEquals(wake, retained.extras.getLong(AndroidPhoneRoutineDailyPlatform.KEY_WAKE_AT))
        assertEquals(job.service, retained.service)
    }

    @Test fun disablingRuntimeCancelsOnlyItsFixtureAndRetainsBystanderJob() {
        val owned = fixtureId()
        val bystander = fixtureId()
        val wake = System.currentTimeMillis() + 86_400_000L
        listOf(owned, bystander).forEach { id ->
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(
                AndroidPhoneRoutineDailyPlatform.platformJob(context, 86_400_000L, wake, id)))
        }
        val work = object : PhoneRoutineDailyWork {
            override fun snapshot() = PhoneRoutineDailyWorkState(false, 1, null, true)
            override fun run(cancelled: () -> Boolean) = error("disabled must not run")
            override fun cancelActive() = Unit
        }
        val platform = object : PhoneRoutineDailyPlatform {
            override fun environment() = PhoneRoutineDailyEnvironment(true, true, true, true)
            override fun schedule(wakeAtMillis: Long) = error("disabled must not schedule")
            override fun cancel() { scheduler.cancel(owned) }
        }
        val runtime = AndroidPhoneRoutineDailyLearningRuntime(work, platform, Executor(Runnable::run),
            Executor(Runnable::run), HansBackupMaintenance(), System::currentTimeMillis)
        try {
            runtime.reconcile()
            assertNull(scheduler.getPendingJob(owned))
            assertNotNull(scheduler.getPendingJob(bystander))
            assertEquals(PhoneRoutineDailyScheduleStatus.DISABLED, runtime.snapshot().status)
        } finally { runtime.close() }
    }

    @Test fun defaultAndAssignedNetworkReadinessRemainIndependentAdmissionRequirements() {
        val state = PhoneRoutineDailyWorkState(true, 0, 1L, true)
        val environment = PhoneRoutineDailyEnvironment(true, true, true, true)
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state, environment, false, 2L))
        assertFalse(PhoneRoutineDailyJobPolicy.mayRun(state,
            environment.copy(defaultNetworkValidated = false), true, 2L))
        assertTrue(PhoneRoutineDailyJobPolicy.mayRun(state, environment, true, 2L))
        assertFalse(AndroidPhoneRoutineDailyPlatform.validated(context, null))
    }

    private fun fixtureId(): Int {
        repeat(16) {
            val id = 0x48550000 or (UUID.randomUUID().hashCode() and 0xffff)
            if (id !in ownedJobIds && scheduler.getPendingJob(id) == null) {
                ownedJobIds += id
                return id
            }
        }
        error("daily_learning_fixture_job_id_unavailable")
    }
}
