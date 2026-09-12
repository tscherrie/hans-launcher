package ai.hans.standard.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Shell-only installation handoff endpoint. The manifest protects this exported receiver with
 * android.permission.DUMP; LauncherActivity deliberately does not accept this action.
 */
class HansSetupHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isOrderedBroadcast) {
            Log.w(TAG, "Rejected non-ordered setup handoff")
            return
        }
        val command = HansSetupHandoffContract.parse(
            intent,
            ComponentName(context, HansSetupHandoffReceiver::class.java),
        )
        if (command == null) {
            returnResult(
                resultCode = Activity.RESULT_CANCELED,
                resultData = RESULT_MALFORMED,
            )
            return
        }

        val result = runCatching {
            HansSetupHandoffCoordinator(
                AtomicFileHansSetupHandoffStorage(context),
            ).register(command)
        }.getOrElse { failure ->
            Log.e(TAG, "Could not persist setup handoff", failure)
            returnAcknowledgement(
                HansSetupHandoffAcknowledgement.create(
                    command,
                    HansSetupHandoffAckStatus.REJECTED_STORAGE,
                ),
                accepted = false,
            )
            return
        }

        val status = when (result.registration) {
            HansSetupHandoffRegistration.ACCEPTED -> HansSetupHandoffAckStatus.ACCEPTED
            HansSetupHandoffRegistration.DUPLICATE -> when (result.record?.phase) {
                HansSetupHandoffPhase.RECOVERY_REQUIRED ->
                    HansSetupHandoffAckStatus.RECOVERY_REQUIRED
                HansSetupHandoffPhase.ALREADY_COMPLETE ->
                    HansSetupHandoffAckStatus.ALREADY_COMPLETE
                else -> HansSetupHandoffAckStatus.DUPLICATE
            }
            HansSetupHandoffRegistration.CONFLICT ->
                HansSetupHandoffAckStatus.REJECTED_CONFLICT
            HansSetupHandoffRegistration.CAPACITY_REACHED ->
                HansSetupHandoffAckStatus.REJECTED_CAPACITY
        }
        val accepted = result.registration in setOf(
            HansSetupHandoffRegistration.ACCEPTED,
            HansSetupHandoffRegistration.DUPLICATE,
        )
        returnAcknowledgement(
            HansSetupHandoffAcknowledgement.create(command, status),
            accepted,
        )
        if (accepted && !signalDeferral.scheduleAfterAcknowledgement()) {
            // The receipt remains durable. A later process/session snapshot safely resumes it.
            Log.e(TAG, "Could not defer setup handoff signal")
        }
    }

    private fun returnAcknowledgement(
        acknowledgement: HansSetupHandoffAcknowledgement,
        accepted: Boolean,
    ) {
        returnResult(
            resultCode = if (accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            resultData = acknowledgement.resultData,
        )
    }

    private fun returnResult(
        resultCode: Int,
        resultData: String,
    ) {
        setResult(resultCode, resultData, null)
    }

    companion object {
        const val RESULT_MALFORMED = "hans.setup-handoff.ack.v1:rejected_malformed"
        private const val TAG = "HansSetupHandoff"
        private val signalDeferral by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val mainHandler = Handler(Looper.getMainLooper())
            HansSetupHandoffSignalDeferral(
                enqueue = { callback -> mainHandler.post(Runnable(callback)) },
                publish = HansSetupHandoffSignalCenter::publish,
            )
        }
    }
}
