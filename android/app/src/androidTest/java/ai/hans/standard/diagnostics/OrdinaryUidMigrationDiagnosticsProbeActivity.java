package ai.hans.standard.diagnostics;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Test-APK component: proves an ordinary application UID cannot invoke the DUMP endpoint. */
public final class OrdinaryUidMigrationDiagnosticsProbeActivity extends Activity {
    public static final String EXTRA_PROBE_TOKEN = "probeToken";
    public static final String LOG_TAG = "HansMigrationProbe";

    private static final String TARGET_PACKAGE = "ai.hans.standard";
    private static final String ACTION =
            "ai.hans.standard.action.MIGRATION_DIAGNOSTICS_V1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String probeToken = getIntent().getStringExtra(EXTRA_PROBE_TOKEN);
        if (probeToken == null || probeToken.isEmpty()) {
            finish();
            return;
        }
        Intent diagnostic = new Intent(ACTION)
                .setComponent(new ComponentName(
                        TARGET_PACKAGE,
                        TARGET_PACKAGE + ".diagnostics.HansMigrationDiagnosticsReceiver"))
                .putExtra("protocolVersion", 1)
                .putExtra("requestId", "cf2cca2a-5f02-41b5-b0be-7a11d37f615c")
                .putExtra("phase", "preflight");
        try {
            sendOrderedBroadcast(
                    diagnostic,
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String status = getResultCode() == RESULT_OK
                                    ? "UNEXPECTED_RESULT_OK"
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
