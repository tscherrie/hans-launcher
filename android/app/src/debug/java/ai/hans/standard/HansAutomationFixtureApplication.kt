package ai.hans.standard

import ai.hans.standard.automations.AndroidAutomationDispatchInterceptor
import ai.hans.standard.automations.AutomationProcessRecoveryFixture
import ai.hans.standard.automations.AutomationRuntimeTrigger
import ai.hans.standard.backup.BackupRecoveryFixture
import android.app.job.JobParameters

/** No fixture descriptor means precisely the normal debug app; shipping builds exclude this. */
class HansAutomationFixtureApplication : HansApplication(), AndroidAutomationDispatchInterceptor {
    override fun onCreate() {
        super.onCreate()
        BackupRecoveryFixture.afterApplicationCreate(this)
    }

    override fun bootstrapIsolatedAutomationFixture(): Boolean =
        BackupRecoveryFixture.bootstrap(this) ?: AutomationProcessRecoveryFixture.bootstrap(this)

    override fun interceptAutomationEnqueue(trigger: AutomationRuntimeTrigger): Boolean? =
        BackupRecoveryFixture.interceptEnqueue(trigger) ?: AutomationProcessRecoveryFixture.interceptEnqueue(trigger)

    override fun interceptAutomationRetry(trigger: AutomationRuntimeTrigger, failedAttempt: Int): Boolean? =
        BackupRecoveryFixture.interceptRetry(trigger, failedAttempt) ?:
            AutomationProcessRecoveryFixture.interceptRetry(trigger, failedAttempt)

    override fun interceptAutomationWakeup(params: JobParameters): Boolean? =
        BackupRecoveryFixture.interceptUnexpectedWork() ?: AutomationProcessRecoveryFixture.receiveWakeup(params)
}
