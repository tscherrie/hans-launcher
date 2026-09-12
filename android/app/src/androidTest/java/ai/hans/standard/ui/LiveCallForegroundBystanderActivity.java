package ai.hans.standard.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Framework-only Activity running under the test APK's own package and separate process. */
public final class LiveCallForegroundBystanderActivity extends Activity {
    public static final String INITIAL_TEXT = "Live call foreground fixture";
    public static final String CLICKED_TEXT = "Foreground interaction confirmed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView content = new TextView(this);
        content.setText(INITIAL_TEXT);
        content.setTextSize(20);
        content.setTextColor(Color.BLACK);
        content.setBackgroundColor(Color.WHITE);
        content.setGravity(Gravity.CENTER);
        content.setOnClickListener(view -> content.setText(CLICKED_TEXT));
        setContentView(content);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The fixture owns its isolated task; do not leave it behind after returning to Hans.
        if (!isFinishing()) finishAndRemoveTask();
    }
}
