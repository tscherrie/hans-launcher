package ai.hans.standard.phone.lifecycle.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private immutable PendingIntent action; requests RPC revocation, never claims it already ran. */
class HansActiveWorkRemoteStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_REMOTE) HansActiveWorkOwner.requestRemoteStop()
    }

    internal companion object {
        const val ACTION_STOP_REMOTE = "ai.hans.standard.action.STOP_REMOTE_ACTIVE_WORK_V1"
        fun intent(context: Context): Intent = Intent(context, HansActiveWorkRemoteStopReceiver::class.java)
            .setAction(ACTION_STOP_REMOTE)
    }
}
