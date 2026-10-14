package ai.hans.standard.fastphone.provisioning;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Executes in the test APK's ordinary UID, not the instrumented Hans UID. */
public final class OrdinaryUidFastPhoneProvisioningProbeActivity extends Activity {
    public static final String LOG_TAG = "HansKeyProvisionProbe";
    public static final String EXTRA_PROBE_TOKEN = "probeToken";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String token = getIntent().getStringExtra(EXTRA_PROBE_TOKEN);
        if (token == null || !token.matches("[a-z0-9-]{1,80}")) { finish(); return; }
        Intent request = new Intent("ai.hans.standard.fastphone.PROVISION_BEGIN")
                .setComponent(new ComponentName("ai.hans.standard",
                        "ai.hans.standard.fastphone.provisioning.FastPhoneKeyProvisioningReceiver"))
                .putExtra("protocol_version", 1);
        try {
            sendOrderedBroadcast(request, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    report(token, getResultCode() == RESULT_OK ? "UNEXPECTED_OK" : "DENIED_RESULT");
                }
            }, null, RESULT_CANCELED, null, null);
        } catch (SecurityException failure) { report(token, "DENIED_SECURITY"); }
    }

    private void report(String token, String result) {
        // Only an explicitly synthetic probe token and constant result, never broadcast data.
        Log.i(LOG_TAG, token + ":" + result);
        finishAndRemoveTask();
    }
}
