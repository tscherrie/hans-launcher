package ai.hans.standard.setup;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Test-APK component: the ordered handoff originates from an actual ordinary application UID. */
public final class OrdinaryUidHandoffProbeActivity extends Activity {
    public static final String EXTRA_PROBE_TOKEN = "probeToken";
    public static final String LOG_TAG = "HansHandoffProbe";

    private static final String TARGET_PACKAGE = "ai.hans.standard";
    private static final String ACTION_CONTINUE_SETUP =
            "ai.hans.standard.action.CONTINUE_SETUP";
    private static final String EXTRA_PROTOCOL_VERSION = "protocolVersion";
    private static final String EXTRA_HANDOFF_ID = "handoffId";
    private static final String EXTRA_REASON = "reason";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String probeToken = getIntent().getStringExtra(EXTRA_PROBE_TOKEN);
        if (probeToken == null || probeToken.isEmpty()) {
            finish();
            return;
        }
        String handoffId = getIntent().getStringExtra(EXTRA_HANDOFF_ID);
        if (handoffId == null) {
            report(probeToken, "INVALID_PROBE");
            return;
        }
        Intent handoff = new Intent(ACTION_CONTINUE_SETUP)
                .setComponent(new ComponentName(
                        TARGET_PACKAGE,
                        TARGET_PACKAGE + ".setup.HansSetupHandoffReceiver"))
                .putExtra(EXTRA_PROTOCOL_VERSION, 1)
                .putExtra(EXTRA_HANDOFF_ID, handoffId)
                .putExtra(EXTRA_REASON, "install");
        try {
            sendOrderedBroadcast(
                    handoff,
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String status = getResultCode() == RESULT_OK
                                    ? "DELIVERED"
                                    : "DENIED_RESULT_" + getResultCode();
                            report(probeToken, status);
                        }
                    },
                    null,
                    RESULT_CANCELED,
                    null,
                    null);
        } catch (SecurityException failure) {
            report(probeToken, "DENIED_SECURITY");
        }
    }

    private void report(String probeToken, String status) {
        Log.i(LOG_TAG, probeToken + ":" + status);
        finishAndRemoveTask();
    }
}
